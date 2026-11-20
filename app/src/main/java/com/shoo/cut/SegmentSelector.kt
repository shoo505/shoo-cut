package com.shoo.cut

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Represents a single segment in the timeline.
 */
data class Segment(
    val index: Int,
    val start: Double,
    val end: Double,
    val duration: Double
) {
    override fun toString(): String {
        return "Segment($index: ${formatTimestamp(start)} - ${formatTimestamp(end)} [${formatTimestamp(duration)}])"
    }
}

/**
 * Complete plan for video cutting with multiple segments.
 */
data class CutPlan(
    val sourceDuration: Double,
    val cutPercentage: Double,
    val cutDirection: CutDirection,
    val targetRetainedDuration: Double,
    val actualRetainedDuration: Double,
    val effectiveRetentionPercent: Double,
    val segments: List<Segment>
) {
    override fun toString(): String {
        return "CutPlan(source=${formatTimestamp(sourceDuration)}, cut=${cutPercentage}%, dir=$cutDirection, " +
               "target=${formatTimestamp(targetRetainedDuration)}, actual=${formatTimestamp(actualRetainedDuration)}, " +
               "effective=${String.format("%.2f", effectiveRetentionPercent)}%, segments=${segments.size})"
    }
}

/**
 * The five predefined cut percentages. Each represents the portion of the video
 * that will be REMOVED, not retained.
 */
val CUT_PERCENTAGES = listOf(25.0, 45.0, 50.0, 65.0, 75.0)

/**
 * The default cut percentage (50% removal = 50% retained).
 */
val DEFAULT_CUT_PERCENTAGE = 50.0

// Strategy constants
private const val MAX_SEGMENTS = 8
private const val TARGET_SEGMENT_SECONDS = 120.0
private const val MIN_CLIP_SECONDS = 0.2
private const val STRATEGY_KEY = "even-distribution-v1"
private const val STRATEGY_DESCRIPTION = "Deterministic even distribution: equal-length segments spaced uniformly across the source with equal gaps. No content/highlight analysis."

/**
 * Direction of the cut.
 */
enum class CutDirection {
    REMOVE_BEGINNING,
    REMOVE_ENDING
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
 * Validates the source duration.
 */
private fun validateDuration(duration: Double): Double {
    if (!duration.isFinite() || duration <= 0) {
        throw IllegalArgumentException("Video duration must be a positive finite number.")
    }
    if (duration < 0.1) {
        throw IllegalArgumentException("The video is too short to cut (under 0.1 seconds).")
    }
    return duration
}

/**
 * Chooses the segment count based on target duration.
 * About one segment per two retained minutes, capped at eight.
 */
private fun chooseSegmentCount(targetDuration: Double): Int {
    if (targetDuration <= 0) return 1
    val count = ceil(targetDuration / TARGET_SEGMENT_SECONDS).toInt()
    return max(1, min(MAX_SEGMENTS, count))
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

/**
 * Formats a duration in seconds to human-readable string.
 */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return when {
        hours > 0 -> "$hours h ${String.format("%02d", minutes)} m ${String.format("%02d", secs)} s"
        minutes > 0 -> "$minutes m ${String.format("%02d", secs)} s"
        else -> "$secs s"
    }
}

/**
 * Calculates the Auto Cut plan using the even-distribution strategy.
 * This is a port of the Python even-distribution-v1 strategy.
 *
 * @param sourceDuration The duration of the source video in seconds.
 * @param cutPercentage The percentage of video to REMOVE (0-100).
 * @param cutDirection The direction of the cut (beginning or ending).
 * @return A CutPlan containing all segments to retain.
 */
fun calculateCutPlan(
    sourceDuration: Double,
    cutPercentage: Double,
    cutDirection: CutDirection
): CutPlan {
    val duration = validateDuration(sourceDuration)
    val requested = validateCutPercent(cutPercentage)

    // Calculate retention percentage
    val effectiveRetention = 100.0 - requested

    // Calculate the boundary between removed and retained portions based on direction.
    val (retainedStart, retainedEnd, retainedDuration): Triple<Double, Double, Double> = when (cutDirection) {
        CutDirection.REMOVE_BEGINNING -> {
            // Remove from the beginning: keep from cut_point to end.
            val removedDuration = duration * requested / 100.0
            val start = roundToLong(removedDuration * 1_000_000) / 1_000_000.0
            val end = duration
            val dur = duration - removedDuration
            Triple(start, end, dur)
        }
        CutDirection.REMOVE_ENDING -> {
            // Remove from the ending: keep from start to cut_point.
            val dur = duration * effectiveRetention / 100.0
            val end = roundToLong(dur * 1_000_000) / 1_000_000.0
            Triple(0.0, end, dur)
        }
    }

    // Clamp retained_duration to valid range.
    val clampedRetainedDuration = min(max(retainedDuration, 0.1), duration)
    if (clampedRetainedDuration <= 0 || retainedStart >= retainedEnd) {
        throw IllegalArgumentException("Invalid retained duration after clamping.")
    }

    // If requested >= 100%, retain everything (edge case)
    if (requested >= 100.0) {
        val segment = Segment(0, retainedStart, retainedEnd, clampedRetainedDuration)
        return CutPlan(
            sourceDuration = duration,
            cutPercentage = requested,
            cutDirection = cutDirection,
            targetRetainedDuration = clampedRetainedDuration,
            actualRetainedDuration = clampedRetainedDuration,
            effectiveRetentionPercent = 100.0,
            segments = listOf(segment)
        )
    }

    // When direction is specified, target is relative to the retained portion.
    val target = clampedRetainedDuration * requested / 100.0

    // Avoid sub-frame slivers on extremely short retained portions.
    val clampedTarget = min(max(target, MIN_CLIP_SECONDS), clampedRetainedDuration)

    val count = chooseSegmentCount(clampedTarget)
    // Guard against choosing more segments than can have a useful duration.
    val finalCount = min(count, max(1, (clampedTarget / MIN_CLIP_SECONDS).toInt()))
    val segmentDuration = clampedTarget / finalCount
    // Gap is calculated within the retained portion.
    val gap = (clampedRetainedDuration - clampedTarget) / (finalCount + 1)

    val segments = mutableListOf<Segment>()
    for (index in 0 until finalCount) {
        var start = retainedStart + gap + index * (segmentDuration + gap)
        var end = start + segmentDuration
        if (index == finalCount - 1) {
            // Keep the final boundary conservative against float drift.
            end = min(end, retainedEnd)
        }
        // Clamp start to retained range.
        start = roundToLong(max(retainedStart, start) * 1_000_000) / 1_000_000.0
        end = roundToLong(min(retainedEnd, end) * 1_000_000) / 1_000_000.0
        if (end <= start) {
            throw IllegalArgumentException("Could not create a usable segment for this video.")
        }
        segments.add(Segment(index, start, end, roundToLong((end - start) * 1_000_000) / 1_000_000.0))
    }

    // Micro-second rounding must never introduce overlaps or reorder clips.
    for (i in 1 until segments.size) {
        if (segments[i].start < segments[i - 1].end) {
            throw IllegalArgumentException("Internal selection error: generated overlapping segments.")
        }
    }

    val selectedDuration = segments.sumOf { it.duration }
    val effectivePercent = roundToLong(selectedDuration / duration * 100_000.0) / 1000.0

    return CutPlan(
        sourceDuration = duration,
        cutPercentage = requested,
        cutDirection = cutDirection,
        targetRetainedDuration = clampedTarget,
        actualRetainedDuration = selectedDuration,
        effectiveRetentionPercent = effectivePercent,
        segments = segments
    )
}