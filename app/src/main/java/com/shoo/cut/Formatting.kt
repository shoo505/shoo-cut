package com.shoo.cut

import java.util.Locale

/** Formats a duration in seconds as a compact human-readable string. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, secs)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, secs)
        else -> "${secs}s"
    }
}

/** Formats a timestamp in seconds as HH:MM:SS.mmm. */
fun formatTimestamp(seconds: Double): String {
    val totalMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
    val hours = totalMs / 3_600_000
    val minutes = (totalMs % 3_600_000) / 60_000
    val secs = (totalMs % 60_000) / 1000
    val millis = totalMs % 1000
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, secs, millis)
}

/** Formats a human-readable file size. */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

/** Formats a percentage with one decimal place. */
fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

/** Derives a safe `*_cut50.mp4` output name from a source display name. */
fun deriveOutputFileName(sourceDisplayName: String): String {
    val stem = sourceDisplayName
        .substringBeforeLast('.', sourceDisplayName)
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.', '-')
        .ifBlank { "shoo_cut" }
    return "${stem}_cut50.mp4"
}
