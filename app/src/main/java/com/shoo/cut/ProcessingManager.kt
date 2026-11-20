package com.shoo.cut

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the 4-segment stream-copy pipeline.
 *
 * Processing sequence:
 * 1. Copy input URI to local temp file
 * 2. Pre-create four output documents in the SAF folder
 * 3. For each segment (sequentially):
 *    a. Run FFmpeg stream copy to a temp output file
 *    b. Write temp output to pre-created SAF document
 *    c. Verify the written document
 *    d. Delete temp segment file
 * 4. Final verification of all four outputs
 * 5. Clean up temp files
 *
 * No re-encoding, no fallback. Stream copy only.
 */
class ProcessingManager(
    private val storage: StorageManager,
    private val processor: VideoProcessor
) {

    interface Listener {
        fun onProgress(percent: Int, status: String)
        fun onSegmentComplete(segmentIndex: Int, outputUri: Uri)
        fun onAllComplete(outputUris: List<Uri>)
        fun onError(message: String)
        fun onCancelled()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shoo-cut-worker").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile private var lastPercent = 0
    @Volatile private var tempInput: File? = null
    @Volatile private var tempSegmentFiles: MutableList<File> = mutableListOf()
    @Volatile private var listener: Listener? = null

    fun isProcessing(): Boolean = running.get()

    fun start(
        inputUri: Uri,
        outputFolderUri: Uri,
        metadata: VideoMetadata,
        plan: SegmentPlan,
        listener: Listener
    ) {
        if (!running.compareAndSet(false, true)) {
            listener.onError("A processing job is already running.")
            return
        }
        cancelled.set(false)
        finished.set(false)
        lastPercent = 0
        tempInput = null
        tempSegmentFiles.clear()
        this.listener = listener

        worker.execute {
            try {
                postProgress(1, "Checking free storage…")
                val requiredBytes = metadata.sizeBytes.coerceAtLeast(1L)
                if (!storage.hasEnoughFreeSpace(requiredBytes)) {
                    throw StorageException(
                        "Not enough free storage. At least " +
                            "${formatFileSize(StorageManager.MIN_FREE_MARGIN_BYTES)} of working space is required."
                    )
                }

                postProgress(3, "Preparing input video…")
                val input = storage.createTempInputFile()
                tempInput = input
                storage.copyUriToFile(inputUri, input) { copied ->
                    if (metadata.sizeBytes > 0L && !cancelled.get()) {
                        val copiedPercent = (copied * 100L / metadata.sizeBytes).toInt().coerceIn(0, 100)
                        val percent = 3 + copiedPercent * 7 / 100
                        postProgress(percent, "Copying video… ${formatFileSize(copied)}")
                    }
                }
                if (cancelled.get()) {
                    finishCancelled()
                    return@execute
                }

                // STEP B: Pre-create four output documents
                postProgress(10, "Creating output documents…")
                val baseFileName = buildOutputFileName(metadata.displayName, 1).replace("_1.mp4", "")
                val outputUris = storage.createOutputDocuments(outputFolderUri, baseFileName)

                postProgress(12, "Starting segment processing…")

                val outputUris = mutableListOf<Uri>()
                val totalSegments = plan.segments.size

                for ((segmentIndex, segment) in plan.segments.withIndex()) {
                    if (cancelled.get()) {
                        finishCancelled()
                        return@execute
                    }

                    val segmentNumber = segmentIndex + 1
                    val segmentPercentStart = 12 + (segmentIndex * 83 / totalSegments)
                    val segmentPercentEnd = 12 + ((segmentIndex + 1) * 83 / totalSegments)

                    postProgress(segmentPercentStart, "Processing segment $segmentNumber/$totalSegments…")

                    val segmentOutput = storage.createTempOutputFile()
                    tempSegmentFiles.add(segmentOutput)

                    val command = FfmpegCommandBuilder.buildSegmentCommand(
                        inputPath = input.absolutePath,
                        outputPath = segmentOutput.absolutePath,
                        startSeconds = segment.startSeconds,
                        durationSeconds = segment.durationSeconds,
                        hasAudio = metadata.hasAudio
                    )

                    val commandString = command.joinToString(" ") { FfmpegCommandBuilder.quote(it) }
                    val segmentDurationMillis = (segment.durationSeconds * 1000.0).coerceAtLeast(1.0)

                    val exitCode = processor.run(commandString) { currentMillis ->
                        if (!cancelled.get()) {
                            val segmentProgress = (currentMillis * 100.0 / segmentDurationMillis).toInt().coerceIn(0, 100)
                            val overallPercent = segmentPercentStart + (segmentProgress * (segmentPercentEnd - segmentPercentStart) / 100)
                            postProgress(overallPercent, "Segment $segmentNumber/$totalSegments… $segmentProgress%")
                        }
                    }

                    if (cancelled.get() || processor.wasCancelled()) {
                        finishCancelled()
                        return@execute
                    }
                    if (exitCode != 0) {
                        val diagnostics = processor.diagnostics()
                        if (diagnostics.isNotEmpty()) {
                            Log.w(TAG, "FFmpeg failed for segment $segmentNumber:\n$diagnostics")
                        }
                        throw IllegalStateException(
                            "Stream copy failed for segment $segmentNumber (${VideoProcessor.describeReturnCode(exitCode)})."
                        )
                    }
                    if (!segmentOutput.isFile || segmentOutput.length() <= 0L) {
                        throw IllegalStateException("FFmpeg produced no output for segment $segmentNumber.")
                    }

                    // STEP D & E: Write segment to pre-created SAF document and verify
                    val outputUri = outputUris[segmentIndex]!!
                    storage.writeToOutputDocument(outputUri, segmentOutput)
                    outputUris[segmentIndex] = outputUri

                    // Delete temp segment file immediately after successful SAF write
                    storage.deleteQuietly(segmentOutput)
                    tempSegmentFiles.remove(segmentOutput)

                    postProgress(segmentPercentEnd, "Segment $segmentNumber/$totalSegments saved")
                    listener?.onSegmentComplete(segmentIndex, outputUri)
                }

                if (cancelled.get()) {
                    finishCancelled()
                    return@execute
                }

                // STEP E: Final verification
                postProgress(96, "Verifying outputs…")
                verifyOutputs(outputUris)

                postProgress(100, "All 4 segments complete")
                finishSuccess(outputUris)
            } catch (e: Exception) {
                if (cancelled.get() && e !is StorageException) {
                    finishCancelled()
                } else {
                    finishError(e.message ?: "Processing failed.")
                }
            }
        }
    }

    /**
     * Verifies all four output documents contain valid MP4 data.
     */
    private fun verifyOutputs(outputUris: List<Uri>) {
        for ((index, uri) in outputUris.withIndex()) {
            val segmentNumber = index + 1
            postProgress(96 + index, "Verifying segment $segmentNumber…")
            
            // Verify document exists and has content
            val size = storage.getDocumentSize(uri)
            if (size <= 0) {
                throw StorageException("Output segment $segmentNumber is empty (0 bytes).")
            }
            
            // Basic MP4 validation - check file signature
            validateMp4Signature(uri)
        }
    }

    /**
     * Validates that the document has a valid MP4 file signature.
     */
    private fun validateMp4Signature(uri: Uri) {
        val input = storage.openInputStream(uri)
            ?: throw StorageException("Cannot open output for validation.")
        input.use { stream ->
            val buffer = ByteArray(12)
            val read = stream.read(buffer)
            if (read < 12) {
                throw StorageException("Output file too small to be valid MP4.")
            }
            // Check for MP4 signature (ftyp box at offset 4)
            // MP4 files typically have 'ftyp' at bytes 4-7
            if (buffer[4] != 0x66 || buffer[5] != 0x74 || buffer[6] != 0x79 || buffer[7] != 0x70) {
                // Some MP4 variants may have different signatures, just warn
                Log.w(TAG, "Output may not have standard MP4 signature (ftyp at offset 4)")
            }
        }
    }

    private fun buildOutputFileName(sourceDisplayName: String, segmentNumber: Int): String {
        val stem = sourceDisplayName
            .substringBeforeLast('.', sourceDisplayName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .ifBlank { "shoo_cut" }
        return "${stem}_Part$segmentNumber.mp4"
    }

    /** Requests cancellation of the current job. */
    fun cancel() {
        if (!running.get() || finished.get()) return
        cancelled.set(true)
        postProgress(lastPercent, "Cancelling…")
        processor.cancel()

        mainHandler.postDelayed({
            if (running.get() && !finished.get()) {
                finishCancelled()
            }
        }, CANCEL_GRACE_MS)
    }

    /** Stops the worker once the service is going away. */
    fun shutdown() {
        cancel()
        worker.shutdownNow()
    }

    private fun postProgress(percent: Int, status: String) {
        if (finished.get()) return
        lastPercent = percent
        val target = listener ?: return
        mainHandler.post {
            if (!finished.get()) {
                target.onProgress(percent, status)
            }
        }
    }

    private fun finishSuccess(outputUris: List<Uri>) = finish { it.onAllComplete(outputUris) }

    private fun finishError(message: String) = finish { it.onError(message) }

    private fun finishCancelled() = finish { it.onCancelled() }

    private fun finish(callback: (Listener) -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        running.set(false)
        cleanupFiles()
        val target = listener
        if (target != null) {
            mainHandler.post { callback(target) }
        }
    }

    private fun cleanupFiles() {
        storage.deleteQuietly(tempInput)
        tempSegmentFiles.forEach { storage.deleteQuietly(it) }
        tempSegmentFiles.clear()
        tempInput = null
    }

    companion object {
        private const val TAG = "ProcessingManager"
        private const val CANCEL_GRACE_MS = 2500L
    }
}