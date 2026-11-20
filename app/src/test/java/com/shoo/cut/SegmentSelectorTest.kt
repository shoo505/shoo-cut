package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentSelectorTest {

    @Test
    fun defaultRetentionKeepsHalf() {
        val plan = SegmentSelector.select(600.0)
        assertEquals(50.0, plan.effectiveRetentionPercent, 0.01)
        assertEquals(300.0, plan.actualRetainedSeconds, 1e-6)
        assertTrue(plan.segments.isNotEmpty())
    }

    @Test
    fun segmentsSpanTheWholeTimeline() {
        val duration = 3600.0
        val plan = SegmentSelector.select(duration)

        assertTrue("first segment should start early", plan.segments.first().startSeconds < duration * 0.15)
        assertTrue("last segment should end late", plan.segments.last().endSeconds > duration * 0.85)
        assertTrue("must use multiple segments", plan.segments.size >= 2)
    }

    @Test
    fun segmentsAreChronologicalAndDoNotOverlap() {
        val durations = listOf(1.0, 5.0, 60.0, 600.0, 3600.0, 39600.0)
        for (duration in durations) {
            val plan = SegmentSelector.select(duration)
            var previousEnd = -1.0
            for (segment in plan.segments) {
                assertTrue(
                    "segment ${segment.index} starts before the previous one ends",
                    segment.startSeconds >= previousEnd - 1e-9
                )
                assertTrue("segment must have positive duration", segment.durationSeconds > 0.0)
                assertTrue(
                    "segment must stay inside the source",
                    segment.endSeconds <= duration + 1e-9
                )
                previousEnd = segment.endSeconds
            }
        }
    }

    @Test
    fun retainedDurationIsApproximatelyHalf() {
        val durations = listOf(10.0, 120.0, 3599.0, 7200.0, 39600.0)
        for (duration in durations) {
            val plan = SegmentSelector.select(duration)
            assertEquals(duration * 0.5, plan.actualRetainedSeconds, duration * 0.01)
            assertEquals(50.0, plan.effectiveRetentionPercent, 1.0)
        }
    }

    @Test
    fun segmentCountIsCappedAtMaximum() {
        val plan = SegmentSelector.select(10.0 * 3600.0)
        assertTrue(plan.segments.size <= SegmentSelector.MAX_SEGMENTS)
    }

    @Test
    fun shortVideoStillKeepsHalf() {
        val plan = SegmentSelector.select(1.0)
        assertEquals(0.5, plan.actualRetainedSeconds, 1e-6)
        assertEquals(1, plan.segments.size)
        assertTrue(plan.segments[0].endSeconds <= 1.0 + 1e-9)
    }

    @Test
    fun tinyVideoUsesSingleSegment() {
        val plan = SegmentSelector.select(0.3)
        assertEquals(0.15, plan.actualRetainedSeconds, 1e-6)
        assertEquals(1, plan.segments.size)
    }

    @Test
    fun minimumDurationIsAccepted() {
        val plan = SegmentSelector.select(SegmentSelector.MIN_SOURCE_SECONDS)
        assertTrue(plan.actualRetainedSeconds > 0.0)
        assertEquals(1, plan.segments.size)
    }

    @Test
    fun invalidDurationsAreRejected() {
        val badDurations = listOf(0.0, -1.0, -0.001, Double.NaN, Double.POSITIVE_INFINITY)
        for (bad in badDurations) {
            assertThrows(IllegalArgumentException::class.java) {
                SegmentSelector.select(bad)
            }
        }
    }

    @Test
    fun durationsBelowMinimumAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SegmentSelector.select(SegmentSelector.MIN_SOURCE_SECONDS / 2.0)
        }
    }

    @Test
    fun invalidRetentionIsRejected() {
        val badRetentions = listOf(0.0, -5.0, 100.1, Double.NaN)
        for (bad in badRetentions) {
            assertThrows(IllegalArgumentException::class.java) {
                SegmentSelector.select(60.0, bad)
            }
        }
    }

    @Test
    fun fullRetentionReturnsWholeVideo() {
        val plan = SegmentSelector.select(120.0, 100.0)
        assertEquals(120.0, plan.actualRetainedSeconds, 1e-6)
        assertEquals(1, plan.segments.size)
        assertEquals(120.0, plan.segments[0].endSeconds, 1e-6)
    }

    @Test
    fun outputDurationMatchesSegmentSum() {
        val plan = SegmentSelector.select(4321.0)
        val sum = plan.segments.sumOf { it.durationSeconds }
        assertEquals(sum, plan.actualRetainedSeconds, 1e-6)
    }
}
