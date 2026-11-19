"""FFmpeg command and filter-graph construction.

The export is performed in a single FFmpeg invocation:

* each selected segment is opened with fast input seeking (``-ss``/``-t``);
* video and audio are trimmed and timestamps reset;
* all segments are concatenated in the filter graph in chronological order;
* scaling/padding to 1280x720 happens exactly once, after concatenation;
* H.264/AAC encoding happens exactly once.

No intermediate segment files and no full-source pre-upscale are used.
"""

from __future__ import annotations

import math
import os
from pathlib import Path

from .errors import SelectionError
from .models import Segment, VideoMetadata
from .util import resolve_ffmpeg

OUTPUT_WIDTH = 1280
OUTPUT_HEIGHT = 720
OUTPUT_FPS_LIMIT = 60.0
AUDIO_SAMPLE_RATE = 48_000
AUDIO_BITRATE = "160k"

# The required scaling expression is kept verbatim as the prefix. The extra
# options preserve the source aspect ratio and keep dimensions even; padding
# below fills the remaining area instead of stretching the picture.
SCALE_FILTER = (
    f"scale={OUTPUT_WIDTH}:{OUTPUT_HEIGHT}:flags=bicubic"
    ":force_original_aspect_ratio=decrease:force_divisible_by=2"
)
PAD_FILTER = (
    f"pad={OUTPUT_WIDTH}:{OUTPUT_HEIGHT}:(ow-iw)/2:(oh-ih)/2:color=black"
)
VIDEO_FPS_DEFAULT = 30.0
X264_PRESETS = frozenset(
    {
        "ultrafast",
        "superfast",
        "veryfast",
        "faster",
        "fast",
        "medium",
        "slow",
        "slower",
        "veryslow",
    }
)


def _seconds(value: float) -> str:
    return f"{max(0.0, value):.6f}"


def output_fps(metadata: VideoMetadata) -> float:
    """Choose a stable output frame rate without creating huge frame counts."""

    fps = metadata.fps
    if not fps or not math.isfinite(fps) or fps <= 0:
        return VIDEO_FPS_DEFAULT
    fps = min(float(fps), OUTPUT_FPS_LIMIT)
    # Common broadcast rates need 3 decimals; keep enough precision while
    # ensuring the filter argument is locale-independent.
    return round(fps, 3)


def build_filter_graph(segments: tuple[Segment, ...] | list[Segment], has_audio: bool, fps: float) -> str:
    if not segments:
        raise SelectionError("Cannot build an export without selected segments.")
    if not fps or not math.isfinite(fps) or fps <= 0:
        raise SelectionError("Cannot build an export without a valid frame rate.")

    fps_text = f"{fps:.3f}".rstrip("0").rstrip(".")
    parts: list[str] = []
    concat_inputs: list[str] = []

    for position, segment in enumerate(segments):
        duration = _seconds(segment.duration)
        parts.append(
            f"[{position}:v:0]trim=duration={duration},setpts=PTS-STARTPTS,"
            f"fps={fps_text}[v{position}]"
        )
        if has_audio:
            parts.append(
                f"[{position}:a:0]asetpts=PTS-STARTPTS,"
                f"aresample={AUDIO_SAMPLE_RATE}:async=1:first_pts=0,"
                f"apad=whole_dur={duration},atrim=duration={duration},"
                "asetpts=PTS-STARTPTS,"
                f"aformat=sample_fmts=fltp:sample_rates={AUDIO_SAMPLE_RATE}:"
                f"channel_layouts=stereo[a{position}]"
            )
            concat_inputs.append(f"[v{position}][a{position}]")

    if has_audio:
        parts.append(
            "".join(concat_inputs)
            + f"concat=n={len(segments)}:v=1:a=1[vc][ac]"
        )
        parts.append(f"[ac]aresample=async=1:first_pts=0[aout]")
    else:
        parts.append(
            "".join(f"[v{i}]" for i in range(len(segments)))
            + f"concat=n={len(segments)}:v=1:a=0[vc]"
        )

    # Scale once, after the complete retained timeline is assembled.
    parts.append(f"[vc]{SCALE_FILTER},pad={OUTPUT_WIDTH}:{OUTPUT_HEIGHT}:"
                 "(ow-iw)/2:(oh-ih)/2:color=black,setsar=1[vout]")
    return ";".join(parts)


def build_export_command(
    input_path: str | os.PathLike[str],
    output_path: str | os.PathLike[str],
    segments: tuple[Segment, ...] | list[Segment],
    *,
    has_audio: bool,
    fps: float,
    ffmpeg_bin: str | None = None,
    encoder_preset: str = "veryfast",
    crf: int = 23,
    threads: int | None = None,
) -> list[str]:
    """Return a shell-free FFmpeg argument list for the complete export."""

    if not segments:
        raise SelectionError("Cannot build an export without selected segments.")
    if encoder_preset not in X264_PRESETS:
        raise SelectionError(f"Unknown x264 preset: {encoder_preset}")
    if isinstance(crf, bool) or not isinstance(crf, int) or not 0 <= crf <= 51:
        raise SelectionError("CRF must be an integer between 0 and 51.")
    if threads is not None and (isinstance(threads, bool) or int(threads) < 0):
        raise SelectionError("Threads must be zero (automatic) or a positive integer.")

    binary = resolve_ffmpeg(ffmpeg_bin)
    source = str(Path(input_path).expanduser())
    target = str(Path(output_path).expanduser())
    graph = build_filter_graph(segments, has_audio, fps)

    command: list[str] = [
        binary,
        "-hide_banner",
        "-nostdin",
        "-y",
        "-loglevel",
        "warning",
        "-stats_period",
        "0.5",
        "-progress",
        "pipe:1",
        "-nostats",
    ]

    for segment in segments:
        # Fast input seeking decodes only the neighbourhood of each selected
        # segment, not the entire source. -t limits decode to the segment.
        command.extend(
            [
                "-ss",
                _seconds(segment.start),
                "-t",
                _seconds(segment.duration),
                "-i",
                source,
            ]
        )

    command.extend(["-filter_complex", graph, "-map", "[vout]"])
    if has_audio:
        command.extend(["-map", "[aout]"])

    command.extend(
        [
            "-c:v",
            "libx264",
            "-preset",
            encoder_preset,
            "-crf",
            str(crf),
            "-pix_fmt",
            "yuv420p",
            "-profile:v",
            "high",
            "-movflags",
            "+faststart",
            "-avoid_negative_ts",
            "make_zero",
            "-map_metadata",
            "-1",
            "-metadata",
            "title=Shoo Auto Cut",
            "-max_muxing_queue_size",
            "2048",
        ]
    )
    if threads is not None:
        command.extend(["-threads", str(int(threads))])

    if has_audio:
        command.extend(
            [
                "-c:a",
                "aac",
                "-b:a",
                AUDIO_BITRATE,
                "-ar",
                str(AUDIO_SAMPLE_RATE),
                "-ac",
                "2",
            ]
        )

    command.extend(["-f", "mp4", target])
    return command


def build_preview_command(
    input_path: str | os.PathLike[str],
    output_path: str | os.PathLike[str],
    segments: tuple[Segment, ...] | list[Segment],
    *,
    has_audio: bool,
    fps: float,
    ffmpeg_bin: str | None = None,
) -> list[str]:
    """Alias used by dry-run/UI diagnostics."""

    return build_export_command(
        input_path,
        output_path,
        segments,
        has_audio=has_audio,
        fps=fps,
        ffmpeg_bin=ffmpeg_bin,
    )
