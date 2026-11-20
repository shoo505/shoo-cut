package com.shoo.cut

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the smart processing pipeline with automatic mode selection:
 *
 * 1. STREAM COPY: When input is already 1280x720 with no rotation.
 *    Uses `-c copy` for fast, lossless processing.
 * 2. BICUBIC RE-ENCODE: When scaling/rotation is needed.
 *    Uses libopenh264 with bicubic scaling.
 * 3. AUTOMATIC FALLBACK: If stream copy fails, automatically retries
 *    with bicubic re-encode.
 *
 * Processes 4 segments sequentially to minimize Android memory pressure.
 * All callbacks are delivered on the main thread.
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

                // Determine initial processing mode
                val (initialMode, command) = FfmpegCommandBuilder.buildArguments(
                    inputPath = input.absolutePath,
                    outputPath = output.absolutePath,
                    plan = plan,
                    sourceWidth = metadata.width,
                    sourceHeight = metadata.height,
                    rotationDegrees = metadata.rotationDegrees,
                    hasAudio = metadata.hasAudio
                )

                // Execute with automatic fallback
                val finalMode = executeWithFallback(
                    command = command,
                    initialMode = initialMode,
                    input = input,
                    output = output,
                    plan = plan,
                    metadata = metadata
                )

                if (cancelled.get() || processor.wasCancelled()) {
                    finishCancelled()
                    return@execute
                }
                if (!output.isFile || output.length() <= 0L) {
                    throw IllegalStateException("FFmpeg produced no output.")
                }

                postProgress(96, "Saving to the selected location…")
                storage.writeFileToUri(output, outputUri)
                finishSuccess(outputUri, finalMode)
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
     * Executes FFmpeg command with automatic fallback from stream copy to re-encode.
     * Returns the mode that actually succeeded.
     */
    private fun executeWithFallback(
        command: List<String>,
        initialMode: FfmpegCommandBuilder.Mode,
        input: File,
        output: File,
        plan: SegmentPlan,
        metadata: VideoMetadata
    ): FfmpegCommandBuilder.Mode {
        var currentMode = initialMode
        var currentCommand = command

        // Try initial mode
        val exitCode = runCommand(currentCommand, plan, currentMode)

        // If stream copy failed and we haven't tried re-encode yet, fall back
        if (exitCode != 0 && currentMode == FfmpegCommandBuilder.Mode.STREAM_COPY) {
            if (!cancelled.get() && !processor.wasCancelled()) {
                val diagnostics = processor.diagnostics()
                if (diagnostics.isNotEmpty()) {
                    Log.w(TAG, "Stream copy failed, falling back to bicubic re-encode:\n$diagnostics")
                }
                postProgress(10, "Stream copy failed, falling back to re-encode…")

                // Clean up failed output
                storage.deleteQuietly(output)
                val newOutput = storage.createTempOutputFile()
                tempOutput = newOutput

                // Build re-encode command
                val (_, reencodeCommand) = FfmpegCommandBuilder.buildArguments(
                    inputPath = input.absolutePath,
                    outputPath = newOutput.absolutePath,
                    plan = plan,
                    sourceWidth = metadata.width,
                    sourceHeight = metadata.height,
                    rotationDegrees = metadata.rotationDegrees,
                    hasAudio = metadata.hasAudio
                )

                currentMode = FfmpegCommandBuilder.Mode.BICUBIC_REENCODE
                currentCommand = reencodeCommand

                // Retry with re-encode
                val retryExitCode = runCommand(currentCommand, plan, currentMode)

                if (retryExitCode != 0) {
                    val retryDiagnostics = processor.diagnostics()
                    if (retryDiagnostics.isNotEmpty()) {
                        Log.w(TAG, "Re-encode fallback also failed:\n$retryDiagnostics")
                    }
                    throw IllegalStateException(
                        "Video processing failed (${VideoProcessor.describeReturnCode(retryExitCode)})."
                    )
                }
            }
        } else if (exitCode != 0) {
            val diagnostics = processor.diagnostics()
            if (diagnostics.isNotEmpty()) {
                Log.w(TAG, "FFmpeg failed:\n$diagnostics")
            }
            throw IllegalStateException(
                "Video processing failed (${VideoProcessor.describeReturnCode(exitCode)})."
            )
        }

        return currentMode
    }

    /**
     * Runs a single FFmpeg command with progress reporting.
     */
    private fun runCommand(
        command: List<String>,
        plan: SegmentPlan,
        mode: FfmpegCommandBuilder.Mode
    ): Int {
        val commandString = command.joinToString(" ") { FfmpegCommandBuilder.quote(it) }
        val modeName = when (mode) {
            FfmpegCommandBuilder.Mode.STREAM_COPY -> "Stream copy"
            FfmpegCommandBuilder.Mode.BICUBIC_REENCODE -> "Bicubic re-encode"
        }
        postProgress(10, "$modeName ${plan.segments.size} segment(s)…")

        val totalMillis = (plan.actualRetainedSeconds * 1000.0).coerceAtLeast(1.0)
        return processor.run(commandString) { currentMillis ->
            if (!cancelled.get()) {
                val percent = (10.0 + 85.0 * currentMillis / totalMillis)
                    .toInt()
                    .coerceIn(10, 95)
                postProgress(percent, "$modeName… $percent%")
            }
        }
    }

    /** Best-effort cancellation; always ends with [Listener.onCancelled]. */
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

    private fun finishSuccess(outputUri: Uri, mode: FfmpegCommandBuilder.Mode) {
        val modeName = when (mode) {
            FfmpegCommandBuilder.Mode.STREAM_COPY -> " (stream copy)"
            FfmpegCommandBuilder.Mode.BICUBIC_REENCODE -> " (bicubic 720p)"
        }
        finish { it.onSuccess(outputUri) }
        mainHandler.post {
            listener?.onProgress(100, "Completed$modeName")
        }
    }

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

    companion object {
        private const val TAG = "ProcessingManager"
        private const val CANCEL_GRACE_MS = 2500L
    }
}