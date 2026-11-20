package com.shoo.cut

import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a single-pass FFmpeg command that trims every retained segment,
 * concatenates them, upscales with bicubic interpolation into a 1280x720
 * letterboxed canvas and encodes H.264/AAC MP4.
 *
 * The command is pure data (no Android types), so it can be unit tested on the
 * JVM. It targets the bundled FFmpeg 2.4.x binary shipped by the pao11
 * `ffmpeg-android` AAR, which means:
 *
 *  - `force_divisible_by` and `-stats_period` are NOT available,
 *  - the native AAC encoder needs `-strict -2`,
 *  - scaling uses literal even output dimensions computed in Kotlin.
 */
object FfmpegCommandBuilder {

    const val OUTPUT_WIDTH = 1280
    const val OUTPUT_HEIGHT = 720

    /**
     * Returns `[width, height]` of the scaled picture before padding, keeping the
     * aspect ratio (within one pixel because both values are rounded to even
     * numbers and never exceed 1280x720).
     */
    fun scaledSize(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): IntArray {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return intArrayOf(OUTPUT_WIDTH, OUTPUT_HEIGHT)
        }
        val rotated = ((rotationDegrees % 360) + 360) % 360
        val width = if (rotated == 90 || rotated == 270) sourceHeight else sourceWidth
        val height = if (rotated == 90 || rotated == 270) sourceWidth else sourceHeight

        val factor = min(
            OUTPUT_WIDTH.toDouble() / width,
            OUTPUT_HEIGHT.toDouble() / height
        )
        var scaledWidth = (width * factor).roundToInt()
        var scaledHeight = (height * factor).roundToInt()
        scaledWidth = (scaledWidth / 2) * 2
        scaledHeight = (scaledHeight / 2) * 2
        return intArrayOf(
            scaledWidth.coerceIn(2, OUTPUT_WIDTH),
            scaledHeight.coerceIn(2, OUTPUT_HEIGHT)
        )
    }

    fun build(
        inputPath: String,
        outputPath: String,
        plan: SegmentPlan,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        hasAudio: Boolean
    ): Array<String> {
        require(plan.segments.isNotEmpty()) { "The segment plan is empty." }

        val args = ArrayList<String>()
        args += "-y"
        args += "-hide_banner"
        args += "-nostdin"
        args += "-loglevel"
        args += "info"
        args += "-stats"

        // Fast, accurate input seeking: one input per retained segment.
        for (segment in plan.segments) {
            val start = String.format(Locale.US, "%.3f", segment.startSeconds.coerceAtLeast(0.0))
            val duration = String.format(Locale.US, "%.3f", segment.durationSeconds.coerceAtLeast(0.001))
            args += "-ss"
            args += start
            args += "-t"
            args += duration
            args += "-i"
            args += inputPath
        }

        val filters = ArrayList<String>()
        val rotation = rotationFilter(rotationDegrees)

        for (index in plan.segments.indices) {
            val videoChain = buildString {
                if (rotation != null) {
                    append(rotation)
                    append(',')
                }
                append("setpts=PTS-STARTPTS")
            }
            filters += "[$index:v]$videoChain[v$index]"

            if (hasAudio) {
                filters += "[$index:a]aresample=48000,asetpts=PTS-STARTPTS[a$index]"
            }
        }

        val videoLabels = plan.segments.indices.joinToString(separator = "") { "[v$it]" }
        filters += "${videoLabels}concat=n=${plan.segments.size}:v=1:a=0[vc]"

        val scaled = scaledSize(sourceWidth, sourceHeight, rotationDegrees)
        filters += "[vc]scale=${scaled[0]}:${scaled[1]}:flags=bicubic," +
            "pad=$OUTPUT_WIDTH:$OUTPUT_HEIGHT:(ow-iw)/2:(oh-ih)/2:color=black," +
            "setsar=1[vout]"

        if (hasAudio) {
            val audioLabels = plan.segments.indices.joinToString(separator = "") { "[a$it]" }
            filters += "${audioLabels}concat=n=${plan.segments.size}:v=0:a=1[ac]"
            filters += "[ac]aresample=48000:async=1[aout]"
        }

        args += "-filter_complex"
        args += filters.joinToString(";")

        args += "-map"
        args += "[vout]"
        if (hasAudio) {
            args += "-map"
            args += "[aout]"
        }

        args += "-c:v"
        args += "libx264"
        args += "-preset"
        args += "veryfast"
        args += "-crf"
        args += "23"
        args += "-pix_fmt"
        args += "yuv420p"
        args += "-profile:v"
        args += "high"

        if (hasAudio) {
            args += "-c:a"
            args += "aac"
            args += "-b:a"
            args += "160k"
            args += "-ar"
            args += "48000"
            args += "-ac"
            args += "2"
            // FFmpeg 2.4's native AAC encoder is marked experimental.
            args += "-strict"
            args += "-2"
        }

        args += "-movflags"
        args += "+faststart"
        args += "-avoid_negative_ts"
        args += "make_zero"
        args += "-f"
        args += "mp4"
        args += outputPath

        return args.toTypedArray()
    }

    private fun rotationFilter(degrees: Int): String? {
        return when (((degrees % 360) + 360) % 360) {
            90 -> "transpose=1"
            180 -> "transpose=2,transpose=2"
            270 -> "transpose=2"
            else -> null
        }
    }
}
