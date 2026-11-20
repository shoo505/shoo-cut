package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

    @Test
    fun segmentCommandBasic() {
        val args = FfmpegCommandBuilder.buildSegmentCommand(
            inputPath = "/tmp/in.mp4",
            outputPath = "/tmp/out_1.mp4",
            startSeconds = 0.0,
            durationSeconds = 150.0,
            hasAudio = false
        )

        assertEquals(1, args.count { it == "-i" })
        assertEquals(1, args.count { it == "-ss" })
        assertEquals(1, args.count { it == "-t" })
        assertEquals("0.000", args[args.indexOf("-ss") + 1])
        assertEquals("150.000", args[args.indexOf("-t") + 1])
        // Order: -ss 0.000 -t 150.000 -i /tmp/in.mp4
        val ssIndex = args.indexOf("-ss")
        val tIndex = args.indexOf("-t")
        val iIndex = args.indexOf("-i")
        assertTrue("Order: -ss before -t", ssIndex < tIndex)
        assertTrue("Order: -t before -i", tIndex < iIndex)
        assertEquals("/tmp/in.mp4", args[iIndex + 1])
        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertFalse(args.contains("-c:a"))
        assertFalse(args.contains("libopenh264"))
        assertFalse(args.contains("scale="))
        assertFalse(args.contains("pad="))
        assertFalse(args.contains("filter_complex"))
        assertEquals("/tmp/out_1.mp4", args.last())
        assertTrue(args.contains("+faststart"))
    }

    @Test
    fun segmentCommandWithAudio() {
        val args = FfmpegCommandBuilder.buildSegmentCommand(
            inputPath = "/tmp/in.mp4",
            outputPath = "/tmp/out_1.mp4",
            startSeconds = 150.0,
            durationSeconds = 150.0,
            hasAudio = true
        )

        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertEquals("copy", args[args.indexOf("-c:a") + 1])
        assertEquals("150.000", args[args.indexOf("-ss") + 1])
        assertEquals("150.000", args[args.indexOf("-t") + 1])
    }

    @Test
    fun segmentCommandQuotesPathsWithSpaces() {
        val args = FfmpegCommandBuilder.buildSegmentCommand(
            inputPath = "/tmp/my video.mp4",
            outputPath = "/tmp/out video.mp4",
            startSeconds = 0.0,
            durationSeconds = 10.0,
            hasAudio = false
        )
        val command = args.joinToString(" ") { FfmpegCommandBuilder.quote(it) }
        assertTrue(command.contains("-i \"/tmp/my video.mp4\""))
        assertTrue(command.endsWith("\"/tmp/out video.mp4\""))
        assertFalse(command.contains("\n"))
    }

    @Test
    fun segmentCommandRejectsNegativeStart() {
        assertThrows(IllegalArgumentException::class.java) {
            FfmpegCommandBuilder.buildSegmentCommand(
                inputPath = "/tmp/in.mp4",
                outputPath = "/tmp/out.mp4",
                startSeconds = -1.0,
                durationSeconds = 10.0,
                hasAudio = false
            )
        }
    }

    @Test
    fun segmentCommandRejectsZeroDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            FfmpegCommandBuilder.buildSegmentCommand(
                inputPath = "/tmp/in.mp4",
                outputPath = "/tmp/out.mp4",
                startSeconds = 0.0,
                durationSeconds = 0.0,
                hasAudio = false
            )
        }
    }

    @Test
    fun quoteRejectsEmbeddedDoubleQuotes() {
        assertThrows(IllegalArgumentException::class.java) {
            FfmpegCommandBuilder.quote("/tmp/a\"b.mp4")
        }
        assertEquals("\"a b\"", FfmpegCommandBuilder.quote("a b"))
        assertEquals("plain", FfmpegCommandBuilder.quote("plain"))
    }
}