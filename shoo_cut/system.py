"""Runtime capability detection for the UI and CLI."""

from __future__ import annotations

import subprocess
from functools import lru_cache
from typing import Any

from . import APP_NAME, __version__
from .errors import FFmpegNotFoundError
from .files import default_output_dir, directory_shortcuts
from .selection import (
    MAX_SEGMENTS,
    CUT_PERCENTAGES,
    STRATEGY_DESCRIPTION,
    STRATEGY_KEY,
    TARGET_SEGMENT_SECONDS,
)
from .util import resolve_ffmpeg, resolve_ffprobe


@lru_cache(maxsize=1)
def detect_tools() -> dict[str, Any]:
    """Probe FFmpeg once and cache version/encoder information."""

    result: dict[str, Any] = {
        "ffmpeg_path": None,
        "ffprobe_path": None,
        "ffmpeg_available": False,
        "ffprobe_available": False,
        "ffmpeg_version": None,
        "encoders_ok": False,
        "libx264": False,
        "aac": False,
        "error": None,
    }
    try:
        ffmpeg = resolve_ffmpeg()
        ffprobe = resolve_ffprobe()
        result["ffmpeg_path"] = ffmpeg
        result["ffprobe_path"] = ffprobe
        result["ffmpeg_available"] = True
        result["ffprobe_available"] = True
    except FFmpegNotFoundError as exc:
        result["error"] = str(exc)
        return result

    try:
        version = subprocess.run(
            [ffmpeg, "-version"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
            check=False,
        )
        first_line = (version.stdout or "").splitlines()
        result["ffmpeg_version"] = first_line[0] if first_line else None
    except (OSError, subprocess.TimeoutExpired):
        result["error"] = "FFmpeg exists but could not be executed."
        return result

    try:
        encoders = subprocess.run(
            [ffmpeg, "-hide_banner", "-encoders"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=15,
            check=False,
        )
        output = encoders.stdout or ""
        result["libx264"] = " libx264 " in output or "libx264 " in output
        result["aac"] = " aac " in output
        result["encoders_ok"] = bool(result["libx264"] and result["aac"])
        if not result["encoders_ok"]:
            result["error"] = (
                "FFmpeg is installed but is missing libx264 or the AAC encoder."
            )
    except (OSError, subprocess.TimeoutExpired):
        result["error"] = "Could not inspect FFmpeg encoders."
    return result


def system_info() -> dict[str, Any]:
    tools = detect_tools()
    output_dir = default_output_dir()
    return {
        "app_name": APP_NAME,
        "version": __version__,
        "python_version": ".".join(map(str, __import__("sys").version_info[:3])),
        "platform": __import__("platform").platform(),
        "home": str(__import__("pathlib").Path.home()),
        "cwd": str(__import__("pathlib").Path.cwd()),
        "default_output_dir": str(output_dir),
        "shortcuts": directory_shortcuts(),
        "selection": {
            "strategy": STRATEGY_KEY,
            "description": STRATEGY_DESCRIPTION,
            "max_segments": MAX_SEGMENTS,
            "target_segment_seconds": TARGET_SEGMENT_SECONDS,
        },
        "retention_presets": list(CUT_PERCENTAGES),
        "output": {
            "width": 1280,
            "height": 720,
            "video_codec": "h264",
            "audio_codec": "aac",
            "scale": "bicubic",
        },
        "tools": tools,
    }
