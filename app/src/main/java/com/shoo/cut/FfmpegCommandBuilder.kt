package com.shoo.cut

import java.util.Locale

/**
 * Builds FFmpeg commands for stream-copy splitting of a video into 4 segments.
 *
 * Each segment uses `-c copy` for fast, lossless splitting.
 * The four segments reconstruct the entire original timeline (0-25%, 25-50%, 50-75%, 75-100%).
 *
 * Targets the FFmpeg 4.4 binary from `com.bihe0832.android:lib-ffmpeg-mobile-aaf:8.5.0`.
 * No re-encoding, no filters, no bicubic scaling.
 */
object FfmpegCommandBuilder {

    /**
     * Builds a single stream-copy command for one segment.
     *
     * Uses `-ss` (input seeking) for accurate start position and `-t` for duration.
     * Stream copy with `-ss` before `-i` is fast but not frame-accurate on non-keyframes.
     * This is the safest practical approach for stream copy on the bundled FFmpeg.
     *
     * @param inputPath Path to the source video file (copied to app cache).
     * @param outputPath Path for the output segment file.
     * @param startSeconds Segment start time in seconds from video beginning.
     * @param durationSeconds Segment duration in seconds.
     * @param hasAudio Whether the source has an audio stream to copy.
     * @return List of command arguments (without the `ffmpeg` binary prefix).
     */
    fun buildSegmentCommand(
        inputPath: String,
        outputPath: String,
        startSeconds: Double,
        durationSeconds: Double,
        hasAudio: Boolean
    ): List<String> {
        require(startSeconds >= 0.0) { "Segment start must be >= 0" }
        require(durationSeconds > 0.0) { "Segment duration must be > 0" }

        val args = ArrayList<String>()
        args += "-y"
        args += "-hide_banner"
        args += "-nostdin"
        args += "-loglevel"
        args += "info"
        args += "-stats"

        // Input seeking before -i is fast but may not be frame-accurate on non-keyframes.
        // This is the standard stream-copy approach for the bundled FFmpeg.
        val start = String.format(Locale.US, "%.3f", startSeconds.coerceAtLeast(0.0))
        val duration = String.format(Locale.US, "%.3f", durationSeconds.coerceAtLeast(0.001))

        args += "-ss"
        args += start
        args += "-t"
        args += duration
        args += "-i"
        args += inputPath

        args += "-map"
        args += "0:v:0"
        if (hasAudio) {
            // Optional audio so a misdetected audio stream cannot fail the copy.
            args += "-map"
            args += "0:a?"
        }

        args += "-c:v"
        args += "copy"
        if (hasAudio) {
            args += "-c:a"
            args += "copy"
        }

        args += "-movflags"
        args += "+faststart"
        args += "-avoid_negative_ts"
        args += "make_zero"
        args += "-f"
        args += "mp4"
        args += outputPath

        return args
    }

    /**
     * Quotes one argument for MobileFFmpeg's command line parser.
     */
    fun quote(argument: String): String {
        if (argument.isEmpty()) return "\"\""
        require('"' !in argument) { "Unsupported quote character in command argument." }
        return if (argument.any { it.isWhitespace() }) "\"$argument\"" else argument
    }
}