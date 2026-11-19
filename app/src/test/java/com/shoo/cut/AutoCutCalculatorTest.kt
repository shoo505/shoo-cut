package com.shoo.cut

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoCutCalculatorTest {

    @Test
    fun testCutPercentagesAreCorrect() {
        assertEquals(listOf(25.0, 45.0, 50.0, 65.0, 75.0), CUT_PERCENTAGES)
        assertEquals(50.0, DEFAULT_CUT_PERCENTAGE)
    }

    @Test
    fun testRemoveBeginning50Percent() {
        // 11-hour video = 39600 seconds
        val sourceDuration = 11.0 * 3600.0
        val result = calculateAutoCut(sourceDuration, 50.0, CutDirection.REMOVE_BEGINNING)

        assertEquals(50.0, result.cutPercentage)
        assertEquals(CutDirection.REMOVE_BEGINNING, result.cutDirection)
        assertEquals(5.5 * 3600.0, result.removedDuration, 0.001) // 5h30m removed
        assertEquals(5.5 * 3600.0, result.retainedDuration, 0.001) // 5h30m retained
        assertEquals(5.5 * 3600.0, result.startTime, 0.001) // start at 5h30m
        assertEquals(sourceDuration, result.endTime, 0.001) // end at 11h
    }

    @Test
    fun testRemoveEnding50Percent() {
        val sourceDuration = 11.0 * 3600.0
        val result = calculateAutoCut(sourceDuration, 50.0, CutDirection.REMOVE_ENDING)

        assertEquals(50.0, result.cutPercentage)
        assertEquals(CutDirection.REMOVE_ENDING, result.cutDirection)
        assertEquals(5.5 * 3600.0, result.removedDuration, 0.001) // 5h30m removed
        assertEquals(5.5 * 3600.0, result.retainedDuration, 0.001) // 5h30m retained
        assertEquals(0.0, result.startTime, 0.001) // start at 0
        assertEquals(5.5 * 3600.0, result.endTime, 0.001) // end at 5h30m
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
            val result = calculateAutoCut(sourceDuration, pct, CutDirection.REMOVE_BEGINNING)
            assertEquals(expected.first, result.removedDuration, 0.001, "Removed duration for $pct%")
            assertEquals(expected.second, result.retainedDuration, 0.001, "Retained duration for $pct%")
            assertEquals(expected.first, result.startTime, 0.001, "Start time for $pct%")
            assertEquals(sourceDuration, result.endTime, 0.001, "End time for $pct%")
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
            val result = calculateAutoCut(sourceDuration, pct, CutDirection.REMOVE_ENDING)
            assertEquals(expected.first, result.removedDuration, 0.001, "Removed duration for $pct%")
            assertEquals(expected.second, result.retainedDuration, 0.001, "Retained duration for $pct%")
            assertEquals(0.0, result.startTime, 0.001, "Start time for $pct%")
            assertEquals(expected.second, result.endTime, 0.001, "End time for $pct%")
        }
    }

    @Test
    fun testZeroDurationThrows() {
        assertFailsWith<IllegalArgumentException> {
            calculateAutoCut(0.0, 50.0, CutDirection.REMOVE_BEGINNING)
        }
    }

    @Test
    fun testNegativeDurationThrows() {
        assertFailsWith<IllegalArgumentException> {
            calculateAutoCut(-10.0, 50.0, CutDirection.REMOVE_BEGINNING)
        }
    }

    @Test
    fun testShortVideo() {
        // Very short video: 1 second
        val result = calculateAutoCut(1.0, 50.0, CutDirection.REMOVE_BEGINNING)
        assertEquals(0.5, result.removedDuration, 0.001)
        assertEquals(0.5, result.retainedDuration, 0.001)
        assertEquals(0.5, result.startTime, 0.001)
        assertEquals(1.0, result.endTime, 0.001)
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
}