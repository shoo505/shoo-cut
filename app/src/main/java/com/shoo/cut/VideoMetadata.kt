package com.shoo.cut

/**
 * Data class representing video metadata extracted via FFprobe.
 */
data class VideoMetadata(
    val path: String,
    val filename: String,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    val fps: Double?,
    val videoCodec: String,
    val audioCodec: String?,
    val audioChannels: Int?,
    val audioSampleRate: Int?,
    val container: String,
    val formatName: String,
    val sizeBytes: Long,
    val bitrate: Int?,
    val rotation: Int = 0,
    val hasAudio: Boolean = false
) {
    val durationLabel: String
        get() = formatDuration(durationSeconds)

    val resolutionLabel: String
        get() = "$width x $height"
}

/**
 * Formats duration in seconds to a human-readable string.
 */
fun formatDuration(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return when {
        hours > 0 -> "$hours h ${String.format("%02d", minutes)} m ${String.format("%02d", secs)} s"
        minutes > 0 -> "$minutes m ${String.format("%02d", secs)} s"
        else -> "$secs s"
    }
}