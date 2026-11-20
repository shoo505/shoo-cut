package com.shoo.cut

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the 4-part stream-copy pipeline.
 *
 * Processing sequence:
 * 1. Copy the input `content://` URI into a local temp file (FFmpeg needs a path).
 * 2. Resolve the SAF folder and pre-create ShooCut_Part1..4.mp4.
 * 3. For each part, strictly in order:
 *    a. Run FFmpeg stream copy to a local temp segment.
 *    b. Write the complete segment through ContentResolver into its document.
 *    c. Verify size, MP4 header and that Android can probe video/duration.
 *    d. Delete the temp segment only after verification succeeds.
 * 4. Final verification of all four documents.
 *
 * No re-encoding, no filters and no silent fallback.
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

        Log.d(
            TAG,
            "start: input=$inputUri folder=$outputFolderUri duration=${metadata.durationSeconds}s audio=${metadata.hasAudio}"
        )

        worker.execute {
            try {
                postProgress(1, "Checking free storage…")
                // The cache holds a full copy of the source plus at most one
                // finished part (<= 25% of the source) at a time.
                val estimatedInput = metadata.sizeBytes.coerceAtLeast(0L)
                val requiredBytes = estimatedInput + estimatedInput / 4
                if (!storage.hasEnoughFreeSpace(requiredBytes)) {
                    throw StorageException(
                        "Not enough free storage. About ${formatFileSize(requiredBytes)} is needed " +
                            "and ${formatFileSize(storage.freeSpaceBytes())} is available."
                    )
                }

                postProgress(3, "Copying source video…")
                val input = storage.createTempInputFile()
                tempInput = input
                storage.copyUriToFile(inputUri, input) { copied ->
                    if (metadata.sizeBytes > 0L && !cancelled.get()) {
                        val copiedPercent = (copied * 100L / metadata.sizeBytes).toInt().coerceIn(0, 100)
                        val percent = 3 + copiedPercent * 7 / 100
                        postProgress(percent, "Copying source video… ${formatFileSize(copied)}")
                    }
                }
                if (cancelled.get()) {
                    finishCancelled()
                    return@execute
                }

                postProgress(10, "Creating output files…")
                val outputUris = storage.createOutputDocuments(outputFolderUri).toMutableList()
                if (outputUris.size != OutputNaming.PART_COUNT) {
                    throw StorageException(
                        "Expected ${OutputNaming.PART_COUNT} output files but created ${outputUris.size}."
                    )
                }
                val totalParts = plan.segments.size

                for ((segmentIndex, segment) in plan.segments.withIndex()) {
                    if (cancelled.get()) {
                        finishCancelled()
                        return@execute
                    }

                    val partNumber = segmentIndex + 1
                    val partPercentStart = 12 + (segmentIndex * 80 / totalParts)
                    val partPercentEnd = 12 + ((segmentIndex + 1) * 80 / totalParts)

                    postProgress(partPercentStart, "Processing part $partNumber of $totalParts…")

                    val segmentOutput = storage.createTempOutputFile()
                    tempSegmentFiles.add(segmentOutput)

                    val command = FfmpegCommandBuilder.buildSegmentCommand(
                        inputPath = input.absolutePath,
                        outputPath = segmentOutput.absolutePath,
                        startSeconds = segment.startSeconds,
                        durationSeconds = segment.durationSeconds,
                        hasAudio = metadata.hasAudio
                    )

                    Log.d(
                        TAG,
                        "Part $partNumber command: " + command.joinToString(" ") { FfmpegCommandBuilder.quote(it) }
                    )
                    val segmentDurationMillis = (segment.durationSeconds * 1000.0).coerceAtLeast(1.0)

                    val exitCode = processor.run(command.joinToString(" ") { FfmpegCommandBuilder.quote(it) }) { currentMillis ->
                        if (!cancelled.get()) {
                            val segmentProgress =
                                (currentMillis * 100.0 / segmentDurationMillis).toInt().coerceIn(0, 100)
                            val overallPercent =
                                partPercentStart + (segmentProgress * (partPercentEnd - partPercentStart) / 100)
                            postProgress(overallPercent, "Processing part $partNumber of $totalParts… $segmentProgress%")
                        }
                    }

                    if (cancelled.get() || processor.wasCancelled()) {
                        finishCancelled()
                        return@execute
                    }
                    if (exitCode != 0) {
                        val diagnostics = processor.diagnostics()
                        if (diagnostics.isNotEmpty()) {
                            Log.w(TAG, "FFmpeg failed for part $partNumber:\n$diagnostics")
                        }
                        throw IllegalStateException(
                            "Stream copy failed for part $partNumber (${VideoProcessor.describeReturnCode(exitCode)})."
                        )
                    }
                    if (!segmentOutput.isFile || segmentOutput.length() <= 0L) {
                        throw IllegalStateException("FFmpeg produced no output for part $partNumber.")
                    }

                    // Write the finished part into the pre-created SAF document.
                    val outputUri = outputUris[segmentIndex]
                    Log.d(TAG, "Writing part $partNumber to $outputUri")
                    storage.writeToOutputDocument(outputUri, segmentOutput)

                    // Probe the document itself (not the temp file) so a provider
                    // that accepted bytes but produced an unreadable document fails.
                    verifyPart(outputUri, partNumber)

                    // The document is verified: the temp segment is now disposable.
                    storage.deleteQuietly(segmentOutput)
                    tempSegmentFiles.remove(segmentOutput)

                    postProgress(partPercentEnd, "Part $partNumber of $totalParts saved")
                    notifySegmentComplete(segmentIndex, outputUri)
                }

                if (cancelled.get()) {
                    finishCancelled()
                    return@execute
                }

                postProgress(95, "Verifying all saved files…")
                verifyOutputs(outputUris)

                postProgress(100, "All 4 parts complete")
                finishSuccess(outputUris)
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                if (cancelled.get() && e !is StorageException) {
                    finishCancelled()
                } else {
                    finishError(e.message ?: "Processing failed.")
                }
            }
        }
    }

    private fun verifyOutputs(outputUris: List<Uri>) {
        for ((index, uri) in outputUris.withIndex()) {
            verifyPart(uri, index + 1)
        }
    }

    /**
     * Verifies one saved document:
     *  - non-zero size reported/observed through ContentResolver
     *  - valid MP4 `ftyp` header read back through ContentResolver
     *  - Android media stack can probe a video stream with a positive duration
     */
    private fun verifyPart(uri: Uri, partNumber: Int) {
        val size = storage.getDocumentSize(uri)
        if (size <= 0L) {
            throw StorageException("Part $partNumber was saved but is empty.")
        }

        validateMp4Header(uri, partNumber)

        val probe = storage.probeOutput(uri)
        if (!probe.hasVideo) {
            throw StorageException("Part $partNumber has no readable video stream.")
        }
        if (probe.durationMs <= 0L) {
            throw StorageException("Part $partNumber has an invalid duration.")
        }
        Log.d(
            TAG,
            "Part $partNumber verified: $size bytes, ${probe.durationMs}ms, " +
                "${probe.width}x${probe.height}, audio=${probe.hasAudio}"
        )
    }

    private fun validateMp4Header(uri: Uri, partNumber: Int) {
        val input = storage.openInputStream(uri)
            ?: throw StorageException("Cannot open part $partNumber for validation.")
        input.use { stream ->
            val header = ByteArray(Mp4Validator.HEADER_SIZE)
            var read = 0
            while (read < header.size) {
                val count = stream.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            if (read < Mp4Validator.HEADER_SIZE) {
                throw StorageException("Part $partNumber is too small to be an MP4.")
            }
            if (!Mp4Validator.hasFtypBox(header)) {
                throw StorageException("Part $partNumber is not a valid MP4 (missing ftyp box).")
            }
        }
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
        // Keep progress monotonic even when FFmpeg reports per-part time.
        val value = percent.coerceIn(0, 100).coerceAtLeast(lastPercent)
        lastPercent = value
        val target = listener ?: return
        mainHandler.post {
            if (!finished.get()) {
                target.onProgress(value, status)
            }
        }
    }

    private fun notifySegmentComplete(segmentIndex: Int, outputUri: Uri) {
        val target = listener ?: return
        mainHandler.post {
            if (!finished.get()) {
                target.onSegmentComplete(segmentIndex, outputUri)
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

    private fun formatFileSize(bytes: Long): String = com.shoo.cut.formatFileSize(bytes)

    companion object {
        private const val TAG = "ProcessingManager"
        private const val CANCEL_GRACE_MS = 2500L
    }
}
