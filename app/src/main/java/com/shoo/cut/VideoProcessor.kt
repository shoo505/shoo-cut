package com.shoo.cut

import android.content.Context
import android.util.Log
import com.github.pao11.libffmpeg.FFmpeg
import com.github.pao11.libffmpeg.FFmpegExecuteResponseHandler
import com.github.pao11.libffmpeg.exceptions.FFmpegNotSupportedException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of video processing.
 */
data class ProcessingResult(
    val success: Boolean,
    val outputPath: String?,
    val errorMessage: String?,
    val durationSeconds: Double
)

/**
 * Listener for processing progress updates.
 */
interface ProcessingListener {
    fun onProgress(percent: Float, message: String)
    fun onCompleted(outputPath: String)
    fun onError(message: String)
    fun onCancelled()
}

/**
 * Video processor that uses FFmpeg to trim, upscale to 720p with bicubic scaling,
 * and encode H.264/AAC MP4 output.
 *
 * Pipeline: Probe -> Calculate timestamps -> Trim -> Bicubic scale -> Encode -> Export MP4
 */
class VideoProcessor(private val context: Context) {

    private val TAG = "VideoProcessor"
    private val ffmpeg: FFmpeg = FFmpeg.getInstance(context)
    private var isRunning = AtomicBoolean(false)
    private var cancelRequested = AtomicBoolean(false)
    private var listener: ProcessingListener? = null

    /**
     * Checks if FFmpeg is available and initialized.
     */
    fun isAvailable(): Boolean {
        try {
            // Try to get library version - throws if not supported
            ffmpeg.getLibraryFFmpegVersion()
            return true
        } catch (e: FFmpegNotSupportedException) {
            Log.e(TAG, "FFmpeg not supported", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg check failed", e)
            return false
        }
    }

    /**
     * Processes a video: trim based on cut percentage/direction, bicubic upscale to 720p,
     * encode H.264/AAC MP4.
     */
    fun processVideo(
        inputPath: String,
        outputPath: String,
        autoCutResult: AutoCutResult,
        listener: ProcessingListener
    ) {
        if (isRunning.get()) {
            listener.onError("Another processing job is already running.")
            return
        }

        isRunning.set(true)
        cancelRequested.set(false)
        this.listener = listener

        try {
            listener.onProgress(5.0f, "Initializing FFmpeg...")

            // Ensure FFmpeg is available
            if (!isAvailable()) {
                listener.onError(context.getString(R.string.error_ffmpeg_init))
                return
            }

            listener.onProgress(10.0f, "Building FFmpeg command...")

            val command = buildFfmpegCommand(
                inputPath = inputPath,
                outputPath = outputPath,
                startTime = autoCutResult.startTime,
                endTime = autoCutResult.endTime,
                sourceDuration = autoCutResult.sourceDuration,
                hasAudio = true
            )

            listener.onProgress(15.0f, "Processing video...")

            ffmpeg.execute(emptyMap(), command, object : FFmpegExecuteResponseHandler() {
                override fun onStart() {
                    Log.d(TAG, "FFmpeg execution started")
                }

                override fun onSuccess(message: String?) {
                    Log.d(TAG, "FFmpeg success: $message")
                    cancelRequested.set(false)
                    isRunning.set(false)
                    if (cancelRequested.get()) {
                        listener.onCancelled()
                    } else {
                        listener.onProgress(95.0f, "Finalizing output...")
                        listener.onCompleted(outputPath)
                    }
                }

                override fun onFailure(message: String?) {
                    Log.e(TAG, "FFmpeg failure: $message")
                    cancelRequested.set(false)
                    isRunning.set(false)
                    listener.onError(message ?: context.getString(R.string.error_ffmpeg_encode))
                }

                override fun onProgress(message: String?) {
                    Log.d(TAG, "FFmpeg progress: $message")
                    val progress = parseProgress(message ?: "")
                    listener.onProgress(progress, message ?: "Processing...")
                }

                override fun onFinish() {
                    Log.d(TAG, "FFmpeg finished")
                    isRunning.set(false)
                }
            })
        } catch (e: FFmpegNotSupportedException) {
            Log.e(TAG, "FFmpeg not supported", e)
            isRunning.set(false)
            listener.onError(context.getString(R.string.error_ffmpeg_init))
        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            isRunning.set(false)
            listener.onError(e.message ?: context.getString(R.string.error_unknown))
        }
    }

    /**
     * Cancels the current processing job.
     */
    fun cancel() {
        cancelRequested.set(true)
        try {
            ffmpeg.killRunningProcesses()
        } catch (e: Exception) {
            Log.w(TAG, "Could not kill FFmpeg process", e)
        }
    }

    /**
     * Builds the FFmpeg command for trimming and bicubic scaling to 720p.
     *
     * Pipeline:
     *   -ss <start> -t <duration> -i <input>  (fast input seeking)
     *   -vf scale=1280:720:flags=bicubic:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1
     *   -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p
     *   -c:a aac -b:a 160k -ar 48000 -ac 2
     *   -f mp4 <output>
     */
    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        startTime: Double,
        endTime: Double,
        sourceDuration: Double,
        hasAudio: Boolean
    ): Array<String> {
        val duration = endTime - startTime
        val durationStr = String.format("%.6f", duration.coerceAtLeast(0.0))
        val startStr = String.format("%.6f", startTime.coerceAtLeast(0.0))

        val cmd = mutableListOf<String>()

        cmd.add("-y")
        cmd.add("-hide_banner")
        cmd.add("-nostdin")
        cmd.add("-loglevel")
        cmd.add("warning")
        cmd.add("-stats_period")
        cmd.add("0.5")

        // Fast input seeking: seek to start time before decoding
        cmd.add("-ss")
        cmd.add(startStr)
        cmd.add("-t")
        cmd.add(durationStr)
        cmd.add("-i")
        cmd.add(inputPath)

        // Video filter: bicubic scale to 720p canvas with padding
        val scaleFilter = "scale=1280:720:flags=bicubic:force_original_aspect_ratio=decrease:force_divisible_by=2"
        val padFilter = "pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black"
        cmd.add("-vf")
        cmd.add("$scaleFilter,$padFilter,setsar=1")

        // Map video stream
        cmd.add("-map")
        cmd.add("0:v:0")

        // Video codec: H.264
        cmd.add("-c:v")
        cmd.add("libx264")
        cmd.add("-preset")
        cmd.add("veryfast")
        cmd.add("-crf")
        cmd.add("23")
        cmd.add("-pix_fmt")
        cmd.add("yuv420p")
        cmd.add("-profile:v")
        cmd.add("high")

        // Handle audio if present
        if (hasAudio) {
            cmd.add("-map")
            cmd.add("0:a:0?")
            cmd.add("-c:a")
            cmd.add("aac")
            cmd.add("-b:a")
            cmd.add("160k")
            cmd.add("-ar")
            cmd.add("48000")
            cmd.add("-ac")
            cmd.add("2")
        }

        // MP4 output options
        cmd.add("-movflags")
        cmd.add("+faststart")
        cmd.add("-avoid_negative_ts")
        cmd.add("make_zero")
        cmd.add("-f")
        cmd.add("mp4")
        cmd.add(outputPath)

        return cmd.toTypedArray()
    }

    /**
     * Parses FFmpeg progress percentage from log message.
     */
    private fun parseProgress(message: String): Float {
        // FFmpeg progress messages may contain time=HH:MM:SS.mmm
        val timeRegex = Regex("time=(\\d+):(\\d+):(\\d+\\.\\d+)")
        val match = timeRegex.find(message)
        if (match != null) {
            val h = match.groupValues[1].toInt()
            val m = match.groupValues[2].toInt()
            val s = match.groupValues[3].toDouble()
            val currentTime = h * 3600 + m * 60 + s
            // Return a rough progress estimate (0-95%)
            return (currentTime * 10.0f).coerceIn(0.0f, 95.0f)
        }
        return 0.0f
    }

    /**
     * Checks if there is sufficient storage space for the output.
     */
    fun hasSufficientStorage(outputPath: String, requiredBytes: Long): Boolean {
        val file = File(outputPath)
        val parentFile = file.parentFile
        if (parentFile == null) return true
        val freeSpace = parentFile.freeSpace
        return freeSpace >= requiredBytes * 2 // Require 2x for temp + output
    }
}