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
 * Processes each of the 4 quarters sequentially:
 * 1. Copy input URI to local temp file (if needed)
 * 2. For each segment: run FFmpeg stream copy to a temp output file
 * 3. Write each temp output to the SAF destination folder
 * 3. Clean up temp files
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

                postProgress(10, "Starting segment processing…")

                val outputUris = mutableListOf<Uri>()
                val totalSegments = plan.segments.size

                for ((segmentIndex, segment) in plan.segments.withIndex()) {
                    if (cancelled.get()) {
                        finishCancelled()
                        return@execute
                    }

                    val segmentNumber = segmentIndex + 1
                    val segmentPercentStart = 10 + (segmentIndex * 85 / totalSegments)
                    val segmentPercentEnd = 10 + ((segmentIndex + 1) * 85 / totalSegments)

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

                    // Write segment to SAF folder
                    val outputFileName = buildOutputFileName(metadata.displayName, segmentNumber)
                    val segmentUri = storage.writeFileToFolder(outputFolderUri, segmentOutput, outputFileName)
                    outputUris.add(segmentUri)

                    postProgress(segmentPercentEnd, "Segment $segmentNumber/$totalSegments saved")
                    listener?.onSegmentComplete(segmentIndex, segmentUri)
                }

                if (cancelled.get()) {
                    finishCancelled()
                    return@execute
                }

                postProgress(96, "Finalizing…")
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

    private fun buildOutputFileName(sourceDisplayName: String, segmentNumber: Int): String {
        val stem = sourceDisplayName
            .substringBeforeLast('.', sourceDisplayName)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .ifBlank { "shoo_cut" }
        return "${stem}_$segmentNumber.mp4"
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