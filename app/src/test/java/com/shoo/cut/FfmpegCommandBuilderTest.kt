package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

    private fun plan(segmentCount: Int = 3): SegmentPlan {
        val segments = (0 until segmentCount).map { index ->
            Segment(index, index * 100.0 + 10.0, index * 100.0 + 50.0)
        }
        val actual = segments.sumOf { it.durationSeconds }
        return SegmentPlan(1000.0, 50.0, actual, actual, segments)
    }

    private fun args(
        hasAudio: Boolean,
        rotationDegrees: Int = 0,
        width: Int = 1920,
        height: Int = 1080,
        inputPath: String = "/tmp/in.mp4"
    ): List<String> = FfmpegCommandBuilder.buildArguments(
        inputPath = inputPath,
        outputPath = "/tmp/out.mp4",
        plan = plan(),
        sourceWidth = width,
        sourceHeight = height,
        rotationDegrees = rotationDegrees,
        hasAudio = hasAudio
    )

    private fun filterOf(args: List<String>): String =
        args[args.indexOf("-filter_complex") + 1]

    @Test
    fun buildsOneInputPerSegmentWithSeekAndDuration() {
        val args = args(hasAudio = false)
        assertEquals(3, args.count { it == "-ss" })
        assertEquals(3, args.count { it == "-t" })
        assertEquals(3, args.count { it == "-i" })
        assertEquals(3, args.count { it == "-noautorotate" })
        assertEquals("10.000", args[args.indexOf("-ss") + 1])
    }

    @Test
    fun usesSinglePassFilterComplex() {
        val filter = filterOf(args(hasAudio = true))
        assertTrue(filter.contains("concat=n=3:v=1:a=0"))
        assertTrue(filter.contains("concat=n=3:v=0:a=1"))
        assertTrue(filter.contains("scale=1280:720:flags=bicubic"))
        assertTrue(filter.contains("pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black"))
        assertTrue(filter.contains("setsar=1"))
    }

    @Test
    fun encodesH264AndAacWhenAudioPresent() {
        val args = args(hasAudio = true)
        assertEquals("libopenh264", args[args.indexOf("-c:v") + 1])
        assertEquals("4M", args[args.indexOf("-b:v") + 1])
        assertEquals("yuv420p", args[args.indexOf("-pix_fmt") + 1])
        assertEquals("aac", args[args.indexOf("-c:a") + 1])
        assertEquals("2", args[args.indexOf("-ac") + 1])
        assertEquals(2, args.count { it == "-map" })
        assertTrue(args.contains("[aout]"))
    }

    @Test
    fun omitsAudioWhenSourceHasNoAudio() {
        val args = args(hasAudio = false)
        assertFalse(args.contains("-c:a"))
        assertEquals(1, args.count { it == "-map" })
        assertFalse(args.contains("[aout]"))
        assertTrue(args.contains("[vout]"))
    }

    @Test
    fun writesFastStartMp4ToOutputPath() {
        val args = args(hasAudio = true)
        assertEquals("mp4", args[args.indexOf("-f") + 1])
        assertEquals("/tmp/out.mp4", args.last())
        assertTrue(args.contains("+faststart"))
    }

    @Test
    fun commandLineQuotesPathsWithSpaces() {
        val command = FfmpegCommandBuilder.buildCommand(
            inputPath = "/tmp/my video.mp4",
            outputPath = "/tmp/out video.mp4",
            plan = plan(segmentCount = 1),
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0,
            hasAudio = false
        )
        assertTrue(command.contains("-i \"/tmp/my video.mp4\""))
        assertTrue(command.endsWith("\"/tmp/out video.mp4\""))
        assertFalse(command.contains("\n"))
    }

    @Test
    fun quoteRejectsEmbeddedDoubleQuotes() {
        assertThrows(IllegalArgumentException::class.java) {
            FfmpegCommandBuilder.quote("/tmp/a\"b.mp4")
        }
        assertEquals("\"a b\"", FfmpegCommandBuilder.quote("a b"))
        assertEquals("plain", FfmpegCommandBuilder.quote("plain"))
    }

    @Test
    fun scalesLandscapeToFullFrame() {
        val size = FfmpegCommandBuilder.scaledSize(1920, 1080, 0)
        assertEquals(1280, size[0])
        assertEquals(720, size[1])
    }

    @Test
    fun upscalesSmallLandscape() {
        val size = FfmpegCommandBuilder.scaledSize(640, 360, 0)
        assertEquals(1280, size[0])
        assertEquals(720, size[1])
    }

    @Test
    fun letterboxesPortraitWithoutStretching() {
        val size = FfmpegCommandBuilder.scaledSize(360, 640, 0)
        assertTrue(size[0] < 1280)
        assertEquals(720, size[1])
        assertEquals(0, size[0] % 2)
        assertEquals(0, size[1] % 2)
    }

    @Test
    fun centerPadsSquareSources() {
        val size = FfmpegCommandBuilder.scaledSize(500, 500, 0)
        assertEquals(720, size[0])
        assertEquals(720, size[1])
    }

    @Test
    fun rotationSwapsDimensions() {
        val size = FfmpegCommandBuilder.scaledSize(1920, 1080, 90)
        assertTrue(size[0] < 1280)
        assertEquals(720, size[1])
    }

    @Test
    fun rotationAddsTransposeFilters() {
        assertTrue(filterOf(args(hasAudio = false, rotationDegrees = 90)).contains("transpose=1"))
        assertTrue(filterOf(args(hasAudio = false, rotationDegrees = 270)).contains("transpose=2"))
        assertTrue(
            filterOf(args(hasAudio = false, rotationDegrees = 180)).contains("transpose=2,transpose=2")
        )
        assertFalse(filterOf(args(hasAudio = false, rotationDegrees = 0)).contains("transpose="))
    }

    @Test
    fun rejectsEmptyPlan() {
        val empty = SegmentPlan(100.0, 50.0, 0.0, 0.0, emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            FfmpegCommandBuilder.buildArguments("/in", "/out", empty, 1920, 1080, 0, true)
        }
    }
}
