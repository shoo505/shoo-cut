package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentSelectorTest {

    @Test
    fun defaultRetentionKeepsFullVideo() {
        val plan = SegmentSelector.select(600.0)
        assertEquals(100.0, plan.effectiveRetentionPercent, 0.01)
        assertEquals(600.0, plan.actualRetainedSeconds, 1e-6)
        assertEquals(4, plan.segments.size)
    }

    @Test
    fun exactlyFourSegmentsCreated() {
        val durations = listOf(1.0, 10.0, 60.0, 120.0, 600.0, 3600.0, 39600.0)
        for (duration in durations) {
            val plan = SegmentSelector.select(duration)
            assertEquals("Duration $duration: should have exactly 4 segments", 4, plan.segments.size)
        }
    }

    @Test
    fun segmentsAreConsecutiveQuarters() {
        val duration = 100.0
        val plan = SegmentSelector.select(duration)

        // Segment 1: 0-25%
        assertEquals(0.0, plan.segments[0].startSeconds, 1e-9)
        assertEquals(25.0, plan.segments[0].endSeconds, 1e-9)

        // Segment 2: 25-50%
        assertEquals(25.0, plan.segments[1].startSeconds, 1e-9)
        assertEquals(50.0, plan.segments[1].endSeconds, 1e-9)

        // Segment 3: 50-75%
        assertEquals(50.0, plan.segments[2].startSeconds, 1e-9)
        assertEquals(75.0, plan.segments[2].endSeconds, 1e-9)

        // Segment 4: 75-100%
        assertEquals(75.0, plan.segments[3].startSeconds, 1e-9)
        assertEquals(100.0, plan.segments[3].endSeconds, 1e-9)
    }

    @Test
    fun segmentsAreChronologicalAndDoNotOverlap() {
        val durations = listOf(1.0, 5.0, 60.0, 600.0, 3600.0, 39600.0, 123.456)
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
    fun retainedDurationEqualsFullVideo() {
        val durations = listOf(10.0, 120.0, 3599.0, 7200.0, 39600.0, 123.456)
        for (duration in durations) {
            val plan = SegmentSelector.select(duration)
            assertEquals(duration, plan.actualRetainedSeconds, 1e-6)
            assertEquals(100.0, plan.effectiveRetentionPercent, 0.01)
        }
    }

    @Test
    fun shortVideoStillDividedIntoFour() {
        val plan = SegmentSelector.select(1.0)
        assertEquals(4, plan.segments.size)
        assertEquals(0.25, plan.segments[0].durationSeconds, 1e-6)
        assertEquals(0.25, plan.segments[1].durationSeconds, 1e-6)
        assertEquals(0.25, plan.segments[2].durationSeconds, 1e-6)
        assertEquals(0.25, plan.segments[3].durationSeconds, 1e-6)
    }

    @Test
    fun tinyVideoUsesFourSegments() {
        val plan = SegmentSelector.select(0.3)
        assertEquals(4, plan.segments.size)
        assertEquals(0.075, plan.segments[0].durationSeconds, 1e-6)
    }

    @Test
    fun minimumDurationIsAccepted() {
        val plan = SegmentSelector.select(SegmentSelector.MIN_SOURCE_SECONDS)
        assertEquals(4, plan.segments.size)
        assertTrue(plan.actualRetainedSeconds > 0.0)
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
    fun fullRetentionReturnsWholeVideoInFourSegments() {
        val plan = SegmentSelector.select(120.0, 100.0)
        assertEquals(120.0, plan.actualRetainedSeconds, 1e-6)
        assertEquals(4, plan.segments.size)
        assertEquals(120.0, plan.segments[3].endSeconds, 1e-6)
    }

    @Test
    fun outputDurationMatchesSegmentSum() {
        val plan = SegmentSelector.select(4321.0)
        val sum = plan.segments.sumOf { it.durationSeconds }
        assertEquals(sum, plan.actualRetainedSeconds, 1e-6)
    }

    @Test
    fun lastSegmentReachesExactEndOfVideo() {
        // Test with a duration that doesn't divide evenly by 4
        val duration = 100.1
        val plan = SegmentSelector.select(duration)
        assertEquals(duration, plan.segments[3].endSeconds, 1e-9)
    }

    @Test
    fun segmentsCoverEntireTimelineWithoutGaps() {
        val duration = 500.0
        val plan = SegmentSelector.select(duration)

        // First segment starts at 0
        assertEquals(0.0, plan.segments.first().startSeconds, 1e-9)

        // Last segment ends at exact duration
        assertEquals(duration, plan.segments.last().endSeconds, 1e-9)

        // Each segment is exactly 25%
        for (segment in plan.segments) {
            assertEquals(duration / 4.0, segment.durationSeconds, 1e-9)
        }
    }
}