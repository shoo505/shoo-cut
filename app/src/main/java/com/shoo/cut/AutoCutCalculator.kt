package com.shoo.cut

/**
 * The five predefined cut percentages. Each represents the portion of the video
 * that will be REMOVED, not retained.
 */
val CUT_PERCENTAGES = listOf(25.0, 45.0, 50.0, 65.0, 75.0)

/**
 * The default cut percentage (50% removal = 50% retained).
 */
val DEFAULT_CUT_PERCENTAGE = 50.0

/**
 * Direction of the cut.
 */
enum class CutDirection {
    REMOVE_BEGINNING,
    REMOVE_ENDING
}

/**
 * Result of the Auto Cut calculation.
 */
data class AutoCutResult(
    val sourceDuration: Double,
    val cutPercentage: Double,
    val cutDirection: CutDirection,
    val removedDuration: Double,
    val retainedDuration: Double,
    val startTime: Double,
    val endTime: Double
) {
    val removedDurationLabel: String
        get() = formatDuration(removedDuration)

    val retainedDurationLabel: String
        get() = formatDuration(retainedDuration)

    val startTimeLabel: String
        get() = formatDuration(startTime)

    val endTimeLabel: String
        get() = formatDuration(endTime)
}

/**
 * Validates and normalizes a cut percentage to one of the allowed presets.
 */
fun validateCutPercent(percent: Double): Double {
    if (!percent.isFinite() || percent < 0.0 || percent >= 100.0) {
        throw IllegalArgumentException("Cut percentage must be >= 0 and < 100.")
    }
    // Find the closest preset
    return CUT_PERCENTAGES.minByOrNull { Math.abs(it - percent) } ?: DEFAULT_CUT_PERCENTAGE
}

/**
 * Calculates the Auto Cut result based on source duration, cut percentage, and direction.
 *
 * Formulas:
 *   removedDuration = sourceDuration * cutPercentage / 100
 *   retainedDuration = sourceDuration - removedDuration
 *
 * Remove Beginning:
 *   startTime = removedDuration
 *   endTime = sourceDuration
 *
 * Remove Ending:
 *   startTime = 0
 *   endTime = sourceDuration - removedDuration (= retainedDuration)
 */
fun calculateAutoCut(
    sourceDuration: Double,
    cutPercentage: Double,
    cutDirection: CutDirection
): AutoCutResult {
    if (sourceDuration <= 0.0) {
        throw IllegalArgumentException("Source duration must be positive.")
    }

    val percent = validateCutPercent(cutPercentage)
    val removedDuration = sourceDuration * percent / 100.0
    val retainedDuration = sourceDuration - removedDuration

    val (startTime, endTime) = when (cutDirection) {
        CutDirection.REMOVE_BEGINNING -> {
            // Remove from beginning: keep from removedDuration to end
            startTime = removedDuration
            endTime = sourceDuration
        }
        CutDirection.REMOVE_ENDING -> {
            // Remove from ending: keep from start to retainedDuration
            startTime = 0.0
            endTime = retainedDuration
        }
    }

    return AutoCutResult(
        sourceDuration = sourceDuration,
        cutPercentage = percent,
        cutDirection = cutDirection,
        removedDuration = removedDuration,
        retainedDuration = retainedDuration,
        startTime = startTime.coerceAtLeast(0.0),
        endTime = endTime.coerceAtMost(sourceDuration)
    )
}

/**
 * Formats a timestamp in seconds to HH:MM:SS.mmm format.
 */
fun formatTimestamp(seconds: Double): String {
    val totalMs = seconds.toLong().coerceAtLeast(0)
    val hours = totalMs / 3_600_000
    val minutes = (totalMs % 3_600_000) / 60_000
    val secs = (totalMs % 60_000) / 1000
    val millis = totalMs % 1000
    return String.format("%02d:%02d:%02d.%03d", hours, minutes, secs, millis)
}