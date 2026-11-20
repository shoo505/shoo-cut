package com.shoo.cut

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
 * The plan divides the source video into exactly 4 consecutive segments,
 * each representing 25% of the original duration.
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
 * Deterministic segment selection that divides the video into 4 equal quarters.
 *
 * The video is divided into exactly 4 consecutive segments:
 * - Segment 1: 0–25%
 * - Segment 2: 25–50%
 * - Segment 3: 50–75%
 * - Segment 4: 75–100%
 *
 * This retains 100% of the original video duration, split into 4 quarters
 * for processing.
 */
object SegmentSelector {

    /** Number of segments to divide the video into. */
    const val SEGMENT_COUNT = 4

    /** The application retains the entire video (100%), divided into quarters. */
    const val DEFAULT_RETENTION_PERCENT = 100.0

    /** Anything shorter than this cannot be processed reliably. */
    const val MIN_SOURCE_SECONDS = 0.1

    /**
     * Builds a retain plan that divides the source video into 4 equal quarters.
     *
     * @throws IllegalArgumentException for non-finite, negative or too-short
     * durations.
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

        // The video is divided into exactly 4 consecutive quarters
        val segments = ArrayList<Segment>(SEGMENT_COUNT)
        val quarterDuration = duration / SEGMENT_COUNT

        for (index in 0 until SEGMENT_COUNT) {
            val start = index * quarterDuration
            // For the last segment, ensure it reaches exactly the end of the video
            // to handle any floating-point rounding
            val end = if (index == SEGMENT_COUNT - 1) {
                duration
            } else {
                (index + 1) * quarterDuration
            }

            if (end - start <= 0.0) {
                throw IllegalArgumentException("Could not build a usable segment for this video.")
            }
            segments.add(Segment(index, start, end))
        }

        // Verify segments are continuous and non-overlapping
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