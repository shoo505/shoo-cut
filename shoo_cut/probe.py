"""FFprobe integration and source-video validation."""

from __future__ import annotations

import json
import math
import os
import subprocess
from pathlib import Path
from typing import Any

from .errors import FFmpegNotFoundError, ProbeError
from .models import VideoMetadata
from .util import resolve_ffprobe


def _safe_float(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(result):
        return None
    return result


def _safe_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _parse_rate(value: Any) -> float | None:
    """Parse FFprobe rational frame rates such as ``30000/1001``."""

    if value in (None, "", "0/0"):
        return None
    try:
        text = str(value)
        if "/" in text:
            numerator, denominator = text.split("/", 1)
            num = float(numerator)
            den = float(denominator)
            if den == 0:
                return None
            result = num / den
        else:
            result = float(text)
    except (TypeError, ValueError, ZeroDivisionError):
        return None
    if not math.isfinite(result) or result <= 0:
        return None
    return round(result, 3)


def _rotation_of(stream: dict[str, Any]) -> int:
    candidates: list[Any] = []
    tags = stream.get("tags") or {}
    candidates.append(tags.get("rotate"))
    for item in stream.get("side_data_list") or []:
        if isinstance(item, dict):
            candidates.append(item.get("rotation"))
    for candidate in candidates:
        try:
            value = int(float(candidate))
        except (TypeError, ValueError):
            continue
        normalized = value % 360
        if normalized:
            return normalized
    return 0


def _friendly_container(format_name: str, format_long_name: str, path: Path) -> str:
    ext = path.suffix.lower().lstrip(".")
    lowered = f"{format_name} {format_long_name}".lower()
    mapping = [
        (("mp4", "mov", "m4a", "3gp"), "MP4"),
        (("matroska", "webm"), "Matroska / WebM"),
        (("avi",), "AVI"),
        (("mpegts", "m2ts"), "MPEG transport stream"),
        (("mpeg",), "MPEG"),
        (("flv",), "FLV"),
        (("asf", "wmv"), "ASF / WMV"),
        (("ogg",), "Ogg"),
        (("mxf",), "MXF"),
    ]
    for needles, label in mapping:
        if any(needle in lowered for needle in needles):
            return label
    if ext:
        return ext.upper()
    return format_long_name or format_name or "Unknown container"


def probe_json(path: str | os.PathLike[str], *, ffprobe: str | None = None, timeout: float = 120) -> dict[str, Any]:
    """Run FFprobe and return its raw JSON document.

    All subprocess arguments are passed as a list; no shell is involved.
    """

    candidate = Path(path).expanduser()
    try:
        candidate = candidate.resolve(strict=True)
    except FileNotFoundError:
        raise ProbeError(f"File not found: {path}") from None
    except OSError as exc:
        raise ProbeError(f"Cannot access {path}: {exc}") from None

    if not candidate.is_file():
        raise ProbeError(f"Not a regular file: {candidate}")
    try:
        if candidate.stat().st_size == 0:
            raise ProbeError(f"The file is empty: {candidate.name}")
    except OSError as exc:
        raise ProbeError(f"Cannot read {candidate}: {exc}") from None

    binary = resolve_ffprobe(ffprobe)
    command = [
        binary,
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        "-i",
        str(candidate),
    ]
    try:
        completed = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        raise FFmpegNotFoundError(
            "FFprobe was not found. Install FFmpeg or configure SHOO_FFPROBE."
        ) from None
    except subprocess.TimeoutExpired:
        raise ProbeError(
            "FFprobe timed out while reading this file. It may be damaged or on a slow drive."
        ) from None
    except OSError as exc:
        raise ProbeError(f"Could not start FFprobe: {exc}") from None

    if completed.returncode != 0:
        detail = (completed.stderr or "").strip().splitlines()
        suffix = f" ({detail[-1]})" if detail else ""
        raise ProbeError(
            f"FFprobe could not read this file. It may be corrupt or use an unsupported format{suffix}"
        )

    try:
        data = json.loads(completed.stdout or "{}")
    except json.JSONDecodeError:
        raise ProbeError("FFprobe returned an unreadable response for this file.") from None
    if not isinstance(data, dict):
        raise ProbeError("FFprobe returned an unexpected response for this file.")
    return data


def metadata_from_probe(data: dict[str, Any], path: Path) -> VideoMetadata:
    streams = data.get("streams") or []
    if not isinstance(streams, list):
        streams = []
    format_info = data.get("format") or {}

    video_streams = [
        stream
        for stream in streams
        if isinstance(stream, dict)
        and stream.get("codec_type") == "video"
        and not (stream.get("disposition") or {}).get("attached_pic")
    ]
    if not video_streams:
        raise ProbeError(
            "This file does not contain a video stream. Choose a video recording "
            "(the source may be audio-only or a still-image container)."
        )
    video = video_streams[0]

    audio_streams = [
        stream
        for stream in streams
        if isinstance(stream, dict)
        and stream.get("codec_type") == "audio"
        and not (stream.get("disposition") or {}).get("attached_pic")
    ]
    audio = audio_streams[0] if audio_streams else None

    width = _safe_int(video.get("width"))
    height = _safe_int(video.get("height"))
    if not width or not height or width <= 0 or height <= 0:
        raise ProbeError("The video dimensions could not be determined.")

    rotation = _rotation_of(video)
    display_width, display_height = width, height
    if rotation in (90, 270):
        display_width, display_height = height, width

    format_duration = _safe_float(format_info.get("duration"))
    video_duration = _safe_float(video.get("duration"))
    audio_duration = _safe_float(audio.get("duration")) if audio else None
    stream_durations = [
        value
        for value in (video_duration, audio_duration)
        if value is not None and value > 0
    ]
    duration = format_duration if format_duration and format_duration > 0 else None
    if duration is None and stream_durations:
        duration = max(stream_durations)
    if duration is None or duration <= 0 or not math.isfinite(duration):
        raise ProbeError(
            "The video duration could not be determined. The file may be truncated or damaged."
        )
    if duration < 0.1:
        raise ProbeError("The video is too short to process (under 0.1 seconds).")

    fps = _parse_rate(video.get("avg_frame_rate")) or _parse_rate(video.get("r_frame_rate"))
    bitrate = _safe_int(format_info.get("bit_rate"))
    size_bytes = _safe_int(format_info.get("size"))
    if not size_bytes:
        try:
            size_bytes = path.stat().st_size
        except OSError:
            size_bytes = 0
    if not bitrate and size_bytes and duration:
        bitrate = int(round(size_bytes * 8 / duration))

    start_time = _safe_float(format_info.get("start_time"))
    if start_time is None:
        start_time = _safe_float(video.get("start_time"))

    format_name = str(format_info.get("format_name") or path.suffix.lstrip("."))
    container = _friendly_container(
        format_name,
        str(format_info.get("format_long_name") or ""),
        path,
    )

    return VideoMetadata(
        path=str(path),
        filename=path.name,
        duration=round(duration, 6),
        width=display_width,
        height=display_height,
        fps=fps,
        video_codec=str(video.get("codec_name") or "unknown"),
        audio_codec=str(audio.get("codec_name")) if audio and audio.get("codec_name") else None,
        audio_channels=_safe_int(audio.get("channels")) if audio else None,
        audio_sample_rate=_safe_int(audio.get("sample_rate")) if audio else None,
        container=container,
        format_name=format_name,
        size_bytes=size_bytes,
        bitrate=bitrate,
        video_stream_index=_safe_int(video.get("index")) or 0,
        audio_stream_index=_safe_int(audio.get("index")) if audio else None,
        rotation=rotation,
        video_duration=video_duration,
        audio_duration=audio_duration,
        start_time=start_time,
        probe_data=data,
    )


def probe_video(
    path: str | os.PathLike[str],
    *,
    ffprobe: str | None = None,
    timeout: float = 120,
) -> VideoMetadata:
    """Inspect a local video and return normalized metadata."""

    candidate = Path(path).expanduser()
    try:
        resolved = candidate.resolve(strict=True)
    except FileNotFoundError:
        raise ProbeError(f"File not found: {path}") from None
    except OSError as exc:
        raise ProbeError(f"Cannot access {path}: {exc}") from None

    data = probe_json(resolved, ffprobe=ffprobe, timeout=timeout)
    return metadata_from_probe(data, resolved)
