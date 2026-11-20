package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {

    private fun plan(segmentCount: Int = 4): SegmentPlan {
        val segments = (0 until segmentCount).map { index ->
            Segment(index, index * 100.0, (index + 1) * 100.0)
        }
        val actual = segments.sumOf { it.durationSeconds }
        return SegmentPlan(400.0, 100.0, actual, actual, segments)
    }

    private fun args(
        hasAudio: Boolean,
        rotationDegrees: Int = 0,
        width: Int = 1920,
        height: Int = 1080,
        inputPath: String = "/tmp/in.mp4"
    ): Pair<FfmpegCommandBuilder.Mode, List<String>> = FfmpegCommandBuilder.buildArguments(
        inputPath = inputPath,
        outputPath = "/tmp/out.mp4",
        plan = plan(),
        sourceWidth = width,
        sourceHeight = height,
        rotationDegrees = rotationDegrees,
        hasAudio = hasAudio
    )

    private fun filterOf(args: Pair<FfmpegCommandBuilder.Mode, List<String>>): String =
        args.second[args.second.indexOf("-filter_complex") + 1]

    private fun filterOf(args: List<String>): String =
        args[args.indexOf("-filter_complex") + 1]

    @Test
    fun streamCopyWhenAlready720pNoRotation() {
        val (mode, args) = args(hasAudio = false, width = 1280, height = 720)
        assertEquals(FfmpegCommandBuilder.Mode.STREAM_COPY, mode)
        assertTrue(args.contains("-c:v"))
        assertTrue(args.indexOf("-c:v") >= 0)
        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertFalse(args.contains("libopenh264"))
        assertFalse(args.contains("scale="))
        assertFalse(args.contains("pad="))
    }

    @Test
    fun streamCopyWithAudio() {
        val (mode, args) = args(hasAudio = true, width = 1280, height = 720)
        assertEquals(FfmpegCommandBuilder.Mode.STREAM_COPY, mode)
        assertEquals("copy", args[args.indexOf("-c:v") + 1])
        assertEquals("copy", args[args.indexOf("-c:a") + 1])
        assertEquals(1, args.count { it == "-map" })
        assertEquals("0", args[args.indexOf("-map") + 1])
    }

    @Test
    fun reencodeWhenNot720p() {
        val modeAndArgs = args(hasAudio = false, width = 1920, height = 1080)
        assertEquals(FfmpegCommandBuilder.Mode.BICUBIC_REENCODE, modeAndArgs.first)
        assertEquals("libopenh264", modeAndArgs.second[modeAndArgs.second.indexOf("-c:v") + 1])
        val filter = filterOf(modeAndArgs)
        assertTrue(filter.contains("scale="))
        assertTrue(filter.contains("pad="))
    }

    @Test
    fun reencodeWhenRotationPresent() {
        val modeAndArgs = args(hasAudio = false, width = 1280, height = 720, rotationDegrees = 90)
        assertEquals(FfmpegCommandBuilder.Mode.BICUBIC_REENCODE, modeAndArgs.first)
        assertEquals("libopenh264", modeAndArgs.second[modeAndArgs.second.indexOf("-c:v") + 1])
        val filter = filterOf(modeAndArgs)
        assertTrue(filter.contains("transpose="))
    }

    @Test
    fun canUseStreamCopyReturnsTrueFor720p() {
        assertTrue(FfmpegCommandBuilder.canUseStreamCopy(1280, 720, 0))
    }

    @Test
    fun canUseStreamCopyReturnsFalseForNon720p() {
        assertFalse(FfmpegCommandBuilder.canUseStreamCopy(1920, 1080, 0))
        assertFalse(FfmpegCommandBuilder.canUseStreamCopy(640, 360, 0))
        assertFalse(FfmpegCommandBuilder.canUseStreamCopy(1280, 720, 90))
        assertFalse(FfmpegCommandBuilder.canUseStreamCopy(1280, 720, 180))
        assertFalse(FfmpegCommandBuilder.canUseStreamCopy(1280, 720, 270))
    }

    @Test
    fun streamCopyUsesSingleInput() {
        val (_, args) = args(hasAudio = false, width = 1280, height = 720)
        // Stream copy should use single input, not one per segment
        assertEquals(1, args.count { it == "-i" })
        assertEquals(0, args.count { it == "-ss" })
        assertEquals(0, args.count { it == "-t" })
    }

    @Test
    fun streamCopyMapsAllStreams() {
        val (_, args) = args(hasAudio = true, width = 1280, height = 720)
        // Should map all streams from input 0
        assertEquals(1, args.count { it == "-map" })
        assertEquals("0", args[args.indexOf("-map") + 1])
    }

    @Test
    fun reencodeUsesBicubicScale() {
        val modeAndArgs = args(hasAudio = true, width = 1920, height = 1080)
        val filter = filterOf(modeAndArgs)
        assertTrue(filter.contains("scale=1280:720:flags=bicubic"))
        assertTrue(filter.contains("pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black"))
        assertTrue(filter.contains("setsar=1"))
    }

    @Test
    fun reencodeEncodesH264AndAac() {
        val modeAndArgs = args(hasAudio = true)
        assertEquals("libopenh264", modeAndArgs.second[modeAndArgs.second.indexOf("-c:v") + 1])
        assertEquals("4M", modeAndArgs.second[modeAndArgs.second.indexOf("-b:v") + 1])
        assertEquals("yuv420p", modeAndArgs.second[modeAndArgs.second.indexOf("-pix_fmt") + 1])
        assertEquals("aac", modeAndArgs.second[modeAndArgs.second.indexOf("-c:a") + 1])
        assertEquals("2", modeAndArgs.second[modeAndArgs.second.indexOf("-ac") + 1])
        assertEquals(2, modeAndArgs.second.count { it == "-map" })
    }

    @Test
    fun omitsAudioWhenSourceHasNoAudio() {
        val modeAndArgs = args(hasAudio = false, width = 1920, height = 1080)
        assertFalse(modeAndArgs.second.contains("-c:a"))
        assertEquals(1, modeAndArgs.second.count { it == "-map" })
        assertFalse(modeAndArgs.second.contains("[aout]"))
        assertTrue(modeAndArgs.second.contains("[vout]"))
    }

    @Test
    fun writesFastStartMp4ToOutputPath() {
        val modeAndArgs = args(hasAudio = true)
        assertEquals("mp4", modeAndArgs.second[modeAndArgs.second.indexOf("-f") + 1])
        assertEquals("/tmp/out.mp4", modeAndArgs.second.last())
        assertTrue(modeAndArgs.second.contains("+faststart"))
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