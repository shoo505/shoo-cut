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
 * JVM. It targets the FFmpeg 4.4 binary shipped by the verified
 * `com.bihe0832.android:lib-ffmpeg-mobile-aaf:8.5.0` AAR (MobileFFmpeg build):
 *
 *  - H.264 comes from `libopenh264`, the only H.264 encoder in that build,
 *  - AAC is the native FFmpeg encoder,
 *  - `bicubic`, `pad`, `transpose`, `concat` and `setsar` are available,
 *  - the bundled binary does **not** autorotate reliably across versions, so
 *    rotation is applied explicitly and autorotation is disabled.
 */
object FfmpegCommandBuilder {

    const val OUTPUT_WIDTH = 1280
    const val OUTPUT_HEIGHT = 720

    /** Target video bitrate for the 720p H.264 stream. */
    const val VIDEO_BITRATE = "4M"

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
        val scaledWidth = ((width * factor).roundToInt() / 2) * 2
        val scaledHeight = ((height * factor).roundToInt() / 2) * 2
        return intArrayOf(
            scaledWidth.coerceIn(2, OUTPUT_WIDTH),
            scaledHeight.coerceIn(2, OUTPUT_HEIGHT)
        )
    }

    /**
     * Builds the command line for `FFmpeg.execute`.
     *
     * Paths come from the app's private cache, but they are quoted when they
     * contain whitespace so a relocated cache directory can never break the
     * command.
     */
    fun buildCommand(
        inputPath: String,
        outputPath: String,
        plan: SegmentPlan,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        hasAudio: Boolean
    ): String = buildArguments(
        inputPath,
        outputPath,
        plan,
        sourceWidth,
        sourceHeight,
        rotationDegrees,
        hasAudio
    ).joinToString(" ") { quote(it) }

    /** Same as [buildCommand] but as an argument list, which is easier to test. */
    fun buildArguments(
        inputPath: String,
        outputPath: String,
        plan: SegmentPlan,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
        hasAudio: Boolean
    ): List<String> {
        require(plan.segments.isNotEmpty()) { "The segment plan is empty." }

        val args = ArrayList<String>()
        args += "-y"
        args += "-hide_banner"
        args += "-nostdin"
        args += "-loglevel"
        args += "info"
        args += "-stats"

        // Fast, accurate input seeking: one input per retained segment.
        // Autorotation is disabled because the filter graph applies it
        // deterministically from the platform rotation value.
        for (segment in plan.segments) {
            val start = String.format(Locale.US, "%.3f", segment.startSeconds.coerceAtLeast(0.0))
            val duration = String.format(
                Locale.US,
                "%.3f",
                segment.durationSeconds.coerceAtLeast(0.001)
            )
            args += "-noautorotate"
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
        args += "libopenh264"
        args += "-b:v"
        args += VIDEO_BITRATE
        args += "-pix_fmt"
        args += "yuv420p"

        if (hasAudio) {
            args += "-c:a"
            args += "aac"
            args += "-b:a"
            args += "160k"
            args += "-ar"
            args += "48000"
            args += "-ac"
            args += "2"
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
     * Quotes one argument for MobileFFmpeg's command line parser. MobileFFmpeg
     * splits on unquoted spaces and understands both single and double quotes.
     */
    fun quote(argument: String): String {
        if (argument.isEmpty()) return "\"\""
        require('"' !in argument) { "Unsupported quote character in command argument." }
        return if (argument.any { it.isWhitespace() }) "\"$argument\"" else argument
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
