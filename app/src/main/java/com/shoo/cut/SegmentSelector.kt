package com.shoo.cut

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A single retain-range inside the source timeline.
 *
 * The selector produces retain ranges (the parts that are kept), not the parts
 * that are removed. Ranges are expressed in seconds from the start of the file.
 */
data class Segment(
    val index: Int,
    val startSeconds: Double,
    val endSeconds: Double
) {
    val durationSeconds: Double
        get() = endSeconds - startSeconds
}

/**
 * The complete, deterministic plan for one automatic cut.
 *
 * The plan keeps [actualRetainedSeconds] out of [sourceDurationSeconds], spread
 * over one or more chronological, non-overlapping segments.
 */
data class SegmentPlan(
    val sourceDurationSeconds: Double,
    val retentionPercent: Double,
    val targetRetainedSeconds: Double,
    val actualRetainedSeconds: Double,
    val segments: List<Segment>
) {
    val effectiveRetentionPercent: Double
        get() = if (sourceDurationSeconds > 0.0) {
            actualRetainedSeconds / sourceDurationSeconds * 100.0
        } else {
            0.0
        }
}

/**
 * Deterministic, content-agnostic segment selection.
 *
 * This is a Kotlin port of the earlier even-distribution strategy: the video is
 * divided into [count] equal retained chunks with equal gaps before, between and
 * after them. No picture or audio analysis is performed - this is not "AI
 * highlight detection".
 *
 * For [DEFAULT_RETENTION_PERCENT] the plan retains approximately half of the
 * source duration while covering the whole timeline, instead of keeping only the
 * first or last half.
 */
object SegmentSelector {

    /** The application always keeps about half of the video. */
    const val DEFAULT_RETENTION_PERCENT = 50.0

    /** Upper bound on retained chunks; keeps the FFmpeg filter graph small. */
    const val MAX_SEGMENTS = 8

    /** Aim for chunks around this length before hitting [MAX_SEGMENTS]. */
    const val TARGET_SEGMENT_SECONDS = 120.0

    /** Do not create chunks shorter than this unless the whole target is tiny. */
    const val MIN_SEGMENT_SECONDS = 0.2

    /** Anything shorter than this cannot be processed reliably. */
    const val MIN_SOURCE_SECONDS = 0.1

    /**
     * Builds a retain plan for [sourceDurationSeconds].
     *
     * @throws IllegalArgumentException for non-finite, negative or too-short
     * durations, or for a retention percent outside `(0, 100]`.
     */
    fun select(
        sourceDurationSeconds: Double,
        retentionPercent: Double = DEFAULT_RETENTION_PERCENT
    ): SegmentPlan {
        if (!sourceDurationSeconds.isFinite() || sourceDurationSeconds < MIN_SOURCE_SECONDS) {
            throw IllegalArgumentException(
                "Invalid video duration: $sourceDurationSeconds s " +
                    "(must be at least $MIN_SOURCE_SECONDS s)."
            )
        }
        if (!retentionPercent.isFinite() || retentionPercent <= 0.0 || retentionPercent > 100.0) {
            throw IllegalArgumentException("Retention percent must be greater than 0 and at most 100.")
        }

        val duration = sourceDurationSeconds
        val target = duration * retentionPercent / 100.0

        // Keeping everything is a single full-range segment.
        if (target >= duration - 1e-9) {
            return SegmentPlan(
                sourceDurationSeconds = duration,
                retentionPercent = retentionPercent,
                targetRetainedSeconds = target,
                actualRetainedSeconds = duration,
                segments = listOf(Segment(0, 0.0, duration))
            )
        }

        var count = ceil(target / TARGET_SEGMENT_SECONDS).toInt().coerceIn(1, MAX_SEGMENTS)
        if (target < MIN_SEGMENT_SECONDS) {
            count = 1
        } else {
            count = min(count, max(1, floor(target / MIN_SEGMENT_SECONDS).toInt()))
        }

        val segmentDuration = target / count
        val gap = (duration - target) / (count + 1)

        val segments = ArrayList<Segment>(count)
        for (index in 0 until count) {
            var start = gap + index * (segmentDuration + gap)
            var end = start + segmentDuration
            if (index == count - 1) {
                end = min(end, duration)
            }
            start = start.coerceIn(0.0, duration)
            end = end.coerceIn(0.0, duration)

            if (end - start <= 0.0) {
                throw IllegalArgumentException("Could not build a usable segment for this video.")
            }
            segments.add(Segment(index, start, end))
        }

        // Rounding must never introduce overlaps or reorder clips.
        for (index in 1 until segments.size) {
            require(segments[index].startSeconds >= segments[index - 1].endSeconds - 1e-9) {
                "Internal selection error: generated overlapping segments."
            }
        }

        val actual = segments.sumOf { it.durationSeconds }
        return SegmentPlan(
            sourceDurationSeconds = duration,
            retentionPercent = retentionPercent,
            targetRetainedSeconds = target,
            actualRetainedSeconds = actual,
            segments = segments
        )
    }
}
