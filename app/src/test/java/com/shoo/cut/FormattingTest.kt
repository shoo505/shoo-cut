package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun formatsHoursMinutesAndSeconds() {
        assertEquals("1h 30m 00s", formatDuration(5400.0))
        assertEquals("30m 00s", formatDuration(1800.0))
        assertEquals("45s", formatDuration(45.0))
        assertEquals("0s", formatDuration(0.0))
        assertEquals("0s", formatDuration(-10.0))
    }

    @Test
    fun formatsTimestampsWithMilliseconds() {
        assertEquals("00:00:30.000", formatTimestamp(30.0))
        assertEquals("01:00:00.000", formatTimestamp(3600.0))
        assertEquals("11:00:00.000", formatTimestamp(39600.0))
    }

    @Test
    fun formatsFileSizes() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.0 MB", formatFileSize(1024L * 1024L))
    }

    @Test
    fun formatsPercentages() {
        assertEquals("50.0%", formatPercent(50.0))
        assertEquals("33.3%", formatPercent(100.0 / 3.0))
    }

    @Test
    fun derivesOutputFileName() {
        assertEquals("my_video_cut50.mp4", deriveOutputFileName("my video.mp4"))
        assertEquals("clip_cut50.mp4", deriveOutputFileName("clip"))
        assertEquals("shoo_cut_cut50.mp4", deriveOutputFileName("..."))
    }
}
