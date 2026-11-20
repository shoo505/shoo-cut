package com.shoo.cut

import android.content.Context
import android.util.Log
import com.github.pao11.libffmpeg.FFmpeg
import com.github.pao11.libffmpeg.FFmpegExecuteResponseHandler
import com.github.pao11.libffmpeg.exceptions.FFmpegNotSupportedException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
 * Video processor that uses FFmpeg to trim multiple segments, concatenate,
 * upscale to 720p with bicubic scaling, and encode H.264/AAC MP4 output.
 *
 * Pipeline: Probe -> Calculate segments -> Trim each segment -> Concat -> Bicubic scale -> Encode -> Export MP4
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
     * Copies a content URI to a temporary file using streaming I/O.
     */
    private fun copyUriToTempFile(uri: android.net.Uri): File {
        val tempFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    /**
     * Processes a video: trim multiple segments, concat, bicubic upscale to 720p,
     * encode H.264/AAC MP4.
     */
    fun processVideo(
        inputUri: android.net.Uri,
        outputUri: android.net.Uri,
        cutPlan: CutPlan,
        listener: ProcessingListener
    ) {
        if (isRunning.get()) {
            listener.onError("Another processing job is already running.")
            return
        }

        isRunning.set(true)
        cancelRequested.set(false)
        this.listener = listener

        // Copy input URI to temp file for FFmpeg
        val tempInputFile: File
        try {
            listener.onProgress(5.0f, "Preparing input file...")
            tempInputFile = copyUriToTempFile(inputUri)
            Log.d(TAG, "Copied input to temp file: ${tempInputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy input URI", e)
            isRunning.set(false)
            listener.onError("Failed to prepare input file: ${e.message}")
            return
        }

        val tempOutputFile = File(context.cacheDir, "output_${System.currentTimeMillis()}.mp4")

        try {
            listener.onProgress(10.0f, "Initializing FFmpeg...")

            // Ensure FFmpeg is available
            if (!isAvailable()) {
                listener.onError(context.getString(R.string.error_ffmpeg_init))
                return
            }

            listener.onProgress(15.0f, "Building FFmpeg command...")

            val command = buildFfmpegCommand(
                inputPath = tempInputFile.absolutePath,
                outputPath = tempOutputFile.absolutePath,
                cutPlan = cutPlan
            )

            listener.onProgress(15.0f, "Processing video (${cutPlan.segments.size} segments)...")

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
                        listener.onProgress(90.0f, "Copying output to destination...")
                        copyOutputToDestination(tempOutputFile, outputUri, listener)
                    }
                }

                override fun onFailure(message: String?) {
                    Log.e(TAG, "FFmpeg failure: $message")
                    cancelRequested.set(false)
                    isRunning.set(false)
                    cleanupTempFiles(tempInputFile, tempOutputFile)
                    listener.onError(message ?: context.getString(R.string.error_ffmpeg_encode))
                }

                override fun onProgress(message: String?) {
                    Log.d(TAG, "FFmpeg progress: $message")
                    val progress = parseProgress(message ?: "", cutPlan.actualRetainedDuration)
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
            cleanupTempFiles(tempInputFile, tempOutputFile)
            listener.onError(context.getString(R.string.error_ffmpeg_init))
        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            isRunning.set(false)
            cleanupTempFiles(tempInputFile, tempOutputFile)
            listener.onError(e.message ?: context.getString(R.string.error_unknown))
        }
    }

    private fun copyOutputToDestination(source: File, destinationUri: android.net.Uri, listener: ProcessingListener) {
        try {
            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            listener.onProgress(100.0f, "Completed!")
            listener.onCompleted(destinationUri.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy output", e)
            listener.onError("Failed to save output: ${e.message}")
        } finally {
            cleanupTempFiles()
        }
    }

    private fun cleanupTempFiles(vararg files: File?) {
        for (file in files) {
            try {
                file?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp file: $file", e)
            }
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
     * Builds the FFmpeg command for trimming multiple segments, concatenating,
     * bicubic scaling to 720p, and encoding H.264/AAC MP4.
     *
     * Pipeline (single pass):
     *   For each segment: -ss <start> -t <duration> -i <input>
     *   filter_complex: [0:v]trim=...[v0]; [1:v]trim=...[v1]; ... [v0][v1]...concat=n=N:v=1:a=0[vc]; [vc]scale=1280:720:flags=bicubic:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1[vout]
     *   -map [vout]
     *   -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p
     *   -c:a aac -b:a 160k -ar 48000 -ac 2
     *   -f mp4 <output>
     */
    private fun buildFfmpegCommand(
        inputPath: String,
        outputPath: String,
        cutPlan: CutPlan
    ): Array<String> {
        val segments = cutPlan.segments
        val hasAudio = true // We'll check with -map 0:a? in the command

        val cmd = mutableListOf<String>()

        cmd.add("-y")
        cmd.add("-hide_banner")
        cmd.add("-nostdin")
        cmd.add("-loglevel")
        cmd.add("warning")
        cmd.add("-stats_period")
        cmd.add("0.5")

        // Add input for each segment with fast seeking
        for (segment in segments) {
            val duration = segment.end - segment.start
            val durationStr = String.format("%.6f", duration.coerceAtLeast(0.0))
            val startStr = String.format("%.6f", segment.start.coerceAtLeast(0.0))

            cmd.add("-ss")
            cmd.add(startStr)
            cmd.add("-t")
            cmd.add(durationStr)
            cmd.add("-i")
            cmd.add(inputPath)
        }

        // Build filter_complex for concatenation and scaling
        val filterParts = mutableListOf<String>()

        // Trim each input and label video/audio streams
        for (i in segments.indices) {
            val duration = segments[i].end - segments[i].start
            val durationStr = String.format("%.6f", duration.coerceAtLeast(0.0))

            // Video: trim and setpts
            filterParts.add("[${i}:v]trim=duration=$durationStr,setpts=PTS-STARTPTS[v$i]")

            // Audio: atrim and asetpts (if audio exists)
            filterParts.add("[${i}:a]atrim=duration=$durationStr,asetpts=PTS-STARTPTS[a$i]")
        }

        // Concatenate all video and audio streams
        val videoConcat = segments.indices.joinToString("") { "[v$it]" } + "concat=n=${segments.size}:v=1:a=0[vc]"
        val audioConcat = segments.indices.joinToString("") { "[a$it]" } + "concat=n=${segments.size}:v=0:a=1[ac]"
        filterParts.add(videoConcat)
        filterParts.add(audioConcat)

        // Scale and pad to 720p
        filterParts.add("[vc]scale=1280:720:flags=bicubic:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1[vout]")

        // Resample audio
        filterParts.add("[ac]aresample=48000:async=1[aout]")

        cmd.add("-filter_complex")
        cmd.add(filterParts.joinToString(";"))

        // Map output streams
        cmd.add("-map")
        cmd.add("[vout]")
        cmd.add("-map")
        cmd.add("[aout]")

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

        // Audio codec: AAC
        cmd.add("-c:a")
        cmd.add("aac")
        cmd.add("-b:a")
        cmd.add("160k")
        cmd.add("-ar")
        cmd.add("48000")
        cmd.add("-ac")
        cmd.add("2")

        // MP4 output options
        cmd.add("-movflags")
        cmd.add("+faststart")
        cmd.add("-avoid_negative_ts")
        cmd.add("make_zero")
        cmd.add("-f")
        cmd.add("mp4")
        cmd.add(outputPath)

        Log.d("VideoProcessor", "FFmpeg command: ${cmd.joinToString(" ")}")
        return cmd.toTypedArray()
    }

    /**
     * Parses FFmpeg progress percentage from log message.
     */
    private fun parseProgress(message: String, totalDuration: Double): Float {
        // FFmpeg progress messages may contain time=HH:MM:SS.mmm
        val timeRegex = Regex("time=(\\d+):(\\d+):(\\d+\\.\\d+)")
        val match = timeRegex.find(message)
        if (match != null) {
            val h = match.groupValues[1].toInt()
            val m = match.groupValues[2].toInt()
            val s = match.groupValues[3].toDouble()
            val currentTime = h * 3600 + m * 60 + s
            // Return progress percentage (0-95%)
            val percent = (currentTime / totalDuration * 95.0).coerceIn(0.0, 95.0)
            return percent.toFloat()
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