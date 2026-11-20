package com.shoo.cut

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the whole background pipeline: copy input, run FFmpeg, stream the
 * result to the SAF destination, clean temp files and report progress.
 *
 * All callbacks are delivered on the main thread, so the Activity can update its
 * views directly. Only one job runs at a time.
 */
class ProcessingManager(
    private val storage: StorageManager,
    private val processor: VideoProcessor
) {

    interface Listener {
        fun onProgress(percent: Int, status: String)
        fun onSuccess(outputUri: Uri)
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
    @Volatile private var tempOutput: File? = null
    @Volatile private var listener: Listener? = null

    fun isProcessing(): Boolean = running.get()

    fun start(
        inputUri: Uri,
        outputUri: Uri,
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
        tempOutput = null
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

                postProgress(3, "Copying video into working storage…")
                val input = storage.createTempInputFile()
                tempInput = input
                storage.copyUriToFile(inputUri, input) { copied ->
                    if (metadata.sizeBytes > 0L && !cancelled.get()) {
                        val copiedPercent = (copied * 100L / metadata.sizeBytes).toInt().coerceIn(0, 100)
                        val percent = 3 + copiedPercent * 5 / 100
                        postProgress(percent, "Copying video… ${formatFileSize(copied)}")
                    }
                }
                if (cancelled.get()) {
                    finishCancelled()
                    return@execute
                }

                postProgress(9, "Preparing FFmpeg…")
                val output = storage.createTempOutputFile()
                tempOutput = output

                val command = FfmpegCommandBuilder.build(
                    inputPath = input.absolutePath,
                    outputPath = output.absolutePath,
                    plan = plan,
                    sourceWidth = metadata.width,
                    sourceHeight = metadata.height,
                    rotationDegrees = metadata.rotationDegrees,
                    hasAudio = metadata.hasAudio
                )

                postProgress(10, "Encoding ${plan.segments.size} segment(s)…")
                val exitCode = processor.run(command) { line ->
                    if (!cancelled.get()) {
                        parsePercent(line, plan.actualRetainedSeconds)?.let { percent ->
                            postProgress(percent, "Encoding… $percent%")
                        }
                    }
                }

                if (cancelled.get() || processor.wasCancelled()) {
                    finishCancelled()
                    return@execute
                }
                if (exitCode != 0) {
                    throw IllegalStateException("FFmpeg exited with code $exitCode.")
                }
                if (!output.isFile || output.length() <= 0L) {
                    throw IllegalStateException("FFmpeg produced no output.")
                }

                postProgress(96, "Saving to the selected location…")
                storage.writeFileToUri(output, outputUri)
                finishSuccess(outputUri)
            } catch (e: Exception) {
                if (cancelled.get() && e !is StorageException) {
                    finishCancelled()
                } else {
                    finishError(e.message ?: "Processing failed.")
                }
            }
        }
    }

    /** Best-effort cancellation; always ends with [Listener.onCancelled]. */
    fun cancel() {
        if (!running.get() || finished.get()) return
        cancelled.set(true)
        postProgress(lastPercent, "Cancelling…")
        processor.cancel()

        // The process normally exits immediately, but guarantee UI completion
        // even if a device is slow to reap it.
        mainHandler.postDelayed({
            if (running.get() && !finished.get()) {
                finishCancelled()
            }
        }, CANCEL_GRACE_MS)
    }

    /** Stops the worker once the Activity is going away. */
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

    private fun finishSuccess(outputUri: Uri) = finish { it.onSuccess(outputUri) }

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
        storage.deleteQuietly(tempOutput)
        tempInput = null
        tempOutput = null
    }

    private fun parsePercent(line: String, totalSeconds: Double): Int? {
        if (totalSeconds <= 0.0) return null
        val match = TIME_PATTERN.find(line) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: return null
        val minutes = match.groupValues[2].toIntOrNull() ?: return null
        val seconds = match.groupValues[3].toDoubleOrNull() ?: return null
        val current = hours * 3600.0 + minutes * 60.0 + seconds
        val percent = (10.0 + 85.0 * (current / totalSeconds)).toInt()
        return percent.coerceIn(10, 95)
    }

    companion object {
        private val TIME_PATTERN = Regex("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
        private const val CANCEL_GRACE_MS = 2500L
    }
}
