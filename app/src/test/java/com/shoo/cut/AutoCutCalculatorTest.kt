package com.shoo.cut

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SegmentSelectorTest {

    @Test
    fun testCutPercentagesAreCorrect() {
        assertEquals(listOf(25.0, 45.0, 50.0, 65.0, 75.0), CUT_PERCENTAGES)
        assertEquals(50.0, DEFAULT_CUT_PERCENTAGE)
    }

    @Test
    fun testRemoveBeginning50Percent() {
        // 11-hour video = 39600 seconds
        val sourceDuration = 11.0 * 3600.0
        val plan = calculateCutPlan(sourceDuration, 50.0, CutDirection.REMOVE_BEGINNING)

        assertEquals(50.0, plan.cutPercentage)
        assertEquals(CutDirection.REMOVE_BEGINNING, plan.cutDirection)
        assertEquals(5.5 * 3600.0, plan.sourceDuration - plan.actualRetainedDuration, 1.0) // ~5h30m removed
        assertEquals(5.5 * 3600.0, plan.actualRetainedDuration, 1.0) // ~5h30m retained
        assertEquals(sourceDuration, plan.segments.last().end, 0.001) // end at 11h
        assertTrue(plan.segments.size >= 1) // Should have at least 1 segment
    }

    @Test
    fun testRemoveEnding50Percent() {
        val sourceDuration = 11.0 * 3600.0
        val plan = calculateCutPlan(sourceDuration, 50.0, CutDirection.REMOVE_ENDING)

        assertEquals(50.0, plan.cutPercentage)
        assertEquals(CutDirection.REMOVE_ENDING, plan.cutDirection)
        assertEquals(5.5 * 3600.0, plan.sourceDuration - plan.actualRetainedDuration, 1.0) // ~5h30m removed
        assertEquals(5.5 * 3600.0, plan.actualRetainedDuration, 1.0) // ~5h30m retained
        assertEquals(0.0, plan.segments.first().start, 0.001) // start at 0
        assertEquals(sourceDuration / 2.0, plan.segments.last().end, 1.0) // end at ~5h30m
        assertTrue(plan.segments.size >= 1)
    }

    @Test
    fun testAllPercentagesRemoveBeginning() {
        val sourceDuration = 11.0 * 3600.0

        val testCases = listOf(
            Pair(25.0, Pair(2.75 * 3600.0, 8.25 * 3600.0)), // 2h45m removed, 8h15m retained
            Pair(45.0, Pair(4.95 * 3600.0, 6.05 * 3600.0)), // 4h57m removed, 6h03m retained
            Pair(50.0, Pair(5.5 * 3600.0, 5.5 * 3600.0)),   // 5h30m removed, 5h30m retained
            Pair(65.0, Pair(7.15 * 3600.0, 3.85 * 3600.0)), // 7h09m removed, 3h51m retained
            Pair(75.0, Pair(8.25 * 3600.0, 2.75 * 3600.0))  // 8h15m removed, 2h45m retained
        )

        for ((pct, expected) in testCases) {
            val plan = calculateCutPlan(sourceDuration, pct, CutDirection.REMOVE_BEGINNING)
            assertEquals(expected.first, plan.sourceDuration - plan.actualRetainedDuration, 1.0, "Removed duration for $pct%")
            assertEquals(expected.second, plan.actualRetainedDuration, 1.0, "Retained duration for $pct%")
            assertTrue(plan.segments.size >= 1, "Should have at least 1 segment for $pct%")
            // Verify chronological order and no overlaps
            for (i in 1 until plan.segments.size) {
                assertTrue(plan.segments[i].start >= plan.segments[i - 1].end, "Segments should not overlap for $pct%")
            }
        }
    }

    @Test
    fun testAllPercentagesRemoveEnding() {
        val sourceDuration = 11.0 * 3600.0

        val testCases = listOf(
            Pair(25.0, Pair(2.75 * 3600.0, 8.25 * 3600.0)), // 2h45m removed, 8h15m retained
            Pair(45.0, Pair(4.95 * 3600.0, 6.05 * 3600.0)), // 4h57m removed, 6h03m retained
            Pair(50.0, Pair(5.5 * 3600.0, 5.5 * 3600.0)),   // 5h30m removed, 5h30m retained
            Pair(65.0, Pair(7.15 * 3600.0, 3.85 * 3600.0)), // 7h09m removed, 3h51m retained
            Pair(75.0, Pair(8.25 * 3600.0, 2.75 * 3600.0))  // 8h15m removed, 2h45m retained
        )

        for ((pct, expected) in testCases) {
            val plan = calculateCutPlan(sourceDuration, pct, CutDirection.REMOVE_ENDING)
            assertEquals(expected.first, plan.sourceDuration - plan.actualRetainedDuration, 1.0, "Removed duration for $pct%")
            assertEquals(expected.second, plan.actualRetainedDuration, 1.0, "Retained duration for $pct%")
            assertTrue(plan.segments.size >= 1, "Should have at least 1 segment for $pct%")
            // Verify chronological order and no overlaps
            for (i in 1 until plan.segments.size) {
                assertTrue(plan.segments[i].start >= plan.segments[i - 1].end, "Segments should not overlap for $pct%")
            }
        }
    }

    @Test
    fun testZeroDurationThrows() {
        assertFailsWith<IllegalArgumentException> {
            calculateCutPlan(0.0, 50.0, CutDirection.REMOVE_BEGINNING)
        }
    }

    @Test
    fun testNegativeDurationThrows() {
        assertFailsWith<IllegalArgumentException> {
            calculateCutPlan(-10.0, 50.0, CutDirection.REMOVE_BEGINNING)
        }
    }

    @Test
    fun testShortVideo() {
        // Very short video: 1 second
        val plan = calculateCutPlan(1.0, 50.0, CutDirection.REMOVE_BEGINNING)
        assertEquals(0.5, plan.sourceDuration - plan.actualRetainedDuration, 0.01)
        assertEquals(0.5, plan.actualRetainedDuration, 0.01)
    }

    @Test
    fun testCutPercentValidation() {
        // Invalid percentages should throw
        assertFailsWith<IllegalArgumentException> {
            validateCutPercent(100.0)
        }
        assertFailsWith<IllegalArgumentException> {
            validateCutPercent(-1.0)
        }

        // Valid percentages should pass
        assertEquals(25.0, validateCutPercent(25.0))
        assertEquals(50.0, validateCutPercent(50.0))
        assertEquals(75.0, validateCutPercent(75.0))
    }

    @Test
    fun testDurationFormatting() {
        assertEquals("1 h 30 m 00 s", formatDuration(5400.0)) // 1.5 hours
        assertEquals("30 m 00 s", formatDuration(1800.0))     // 30 minutes
        assertEquals("45 s", formatDuration(45.0))            // 45 seconds
        assertEquals("0 s", formatDuration(0.0))              // 0 seconds
    }

    @Test
    fun testTimestampFormatting() {
        assertEquals("00:00:30.000", formatTimestamp(30.0))
        assertEquals("01:00:00.000", formatTimestamp(3600.0))
        assertEquals("11:00:00.000", formatTimestamp(39600.0))
    }

    @Test
    fun testOutputFilenameGeneration() {
        val name = StorageHelper.generateOutputFilename("my_video.mp4", 50.0, CutDirection.REMOVE_BEGINNING)
        assertTrue(name.contains("my_video"))
        assertTrue(name.contains("50pct"))
        assertTrue(name.contains("removebeg"))
        assertTrue(name.endsWith(".mp4"))

        val name2 = StorageHelper.generateOutputFilename("my_video.mp4", 75.0, CutDirection.REMOVE_ENDING)
        assertTrue(name2.contains("removeend"))
    }

    @Test
    fun testSegmentChronologicalOrder() {
        val sourceDuration = 1000.0 // 1000 seconds
        val plan = calculateCutPlan(sourceDuration, 50.0, CutDirection.REMOVE_BEGINNING)

        // Verify all segments are in chronological order
        var prevEnd = 0.0
        for (segment in plan.segments) {
            assertTrue(segment.start >= prevEnd, "Segment ${segment.index} starts before previous ends")
            assertTrue(segment.end > segment.start, "Segment ${segment.index} has negative duration")
            prevEnd = segment.end
        }
    }

    @Test
    fun testSegmentDistribution() {
        // Test that segments are distributed across the retained portion
        val sourceDuration = 3600.0 // 1 hour
        val plan = calculateCutPlan(sourceDuration, 50.0, CutDirection.REMOVE_ENDING)

        // With 50% cut and REMOVE_ENDING, retained portion is 0-1800s
        // Should have multiple segments distributed
        assertTrue(plan.segments.size >= 1)
        assertEquals(0.0, plan.segments.first().start, 0.01)
        assertTrue(plan.segments.last().end <= 1800.0)
    }
}