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
    fun segmentCommandStreamCopiesVideoAndAudioWithoutEncoding() {
        val args = FfmpegCommandBuilder.buildSegmentCommand(
            inputPath = "/tmp/in.mp4",
            outputPath = "/tmp/out.mp4",
            startSeconds = 10.0,
            durationSeconds = 20.0,
            hasAudio = true
        )

        // First video stream only, plus optional audio; no data/subtitle streams
        // that MP4 cannot hold and that could make stream copy fail.
        val mapValues = args.withIndex().filter { it.value == "-map" }.map { args[it.index + 1] }
        assertEquals(listOf("0:v:0", "0:a?"), mapValues)

        assertEquals(1, args.count { it == "-c:v" })
        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertEquals(1, args.count { it == "-c:a" })
        assertEquals("copy", args[args.indexOf("-c:a") + 1])

        // No encoder may appear anywhere: re-encoding is forbidden.
        val forbidden = listOf(
            "libx264", "libx265", "libopenh264", "h264", "hevc", "mpeg4",
            "aac", "libmp3lame", "opus"
        )
        for (name in forbidden) {
            assertFalse("Unexpected encoder argument: $name", args.contains(name))
        }
        assertFalse(args.contains("-vf"))
        assertFalse(args.contains("-filter_complex"))
    }

    @Test
    fun segmentCommandWithoutAudioMapsOnlyVideo() {
        val args = FfmpegCommandBuilder.buildSegmentCommand(
            inputPath = "/tmp/in.mp4",
            outputPath = "/tmp/out.mp4",
            startSeconds = 0.0,
            durationSeconds = 20.0,
            hasAudio = false
        )

        val mapValues = args.withIndex().filter { it.value == "-map" }.map { args[it.index + 1] }
        assertEquals(listOf("0:v:0"), mapValues)
        assertFalse(args.contains("-c:a"))
        assertFalse(args.contains("0:a?"))
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