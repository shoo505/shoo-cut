"""Small shared helpers (formatting, binaries, application state paths)."""

from __future__ import annotations

import os
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

from .errors import FFmpegNotFoundError

_SAFE_FILENAME_RE = re.compile(r"[\x00-\x1f\x7f/\\:*?\"<>|]+")


def utc_now_iso() -> str:
    """Return an ISO-8601 UTC timestamp with a trailing Z."""

    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def format_duration(seconds: float | None) -> str:
    """Format seconds as H:MM:SS.mmm (or 0:00.000 for short clips)."""

    if seconds is None:
        return "unknown"
    try:
        value = max(0.0, float(seconds))
    except (TypeError, ValueError):
        return "unknown"
    total_ms = int(round(value * 1000))
    hours, rem = divmod(total_ms, 3_600_000)
    minutes, rem = divmod(rem, 60_000)
    secs, millis = divmod(rem, 1000)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}.{millis:03d}"
    return f"{minutes}:{secs:02d}.{millis:03d}"


def comma_duration(seconds: float | None) -> str:
    """Human-friendly duration such as ``2 h 34 min``."""

    if seconds is None:
        return "unknown"
    value = max(0, int(round(seconds)))
    hours, rem = divmod(value, 3600)
    minutes, secs = divmod(rem, 60)
    if hours >= 24:
        days, hours = divmod(hours, 24)
        return f"{days} d {hours} h"
    if hours:
        return f"{hours} h {minutes} min"
    if minutes:
        return f"{minutes} min {secs} s"
    return f"{secs} s"


def human_bytes(value: int | float | None) -> str:
    if value is None:
        return "unknown"
    size = float(value)
    units = ["B", "KB", "MB", "GB", "TB"]
    for unit in units:
        if size < 1024.0 or unit == units[-1]:
            if unit == "B":
                return f"{int(size)} B"
            return f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size:.1f} TB"


def safe_stem(value: str, max_length: int = 80) -> str:
    """Make a path component safe while keeping unicode letters readable."""

    cleaned = _SAFE_FILENAME_RE.sub("_", value).strip(" .")
    cleaned = re.sub(r"\s+", " ", cleaned)
    if not cleaned:
        cleaned = "video"
    return cleaned[:max_length].rstrip(" .") or "video"


def resolve_ffmpeg(explicit: str | os.PathLike[str] | None = None) -> str:
    """Resolve the FFmpeg executable, honoring ``SHOO_FFMPEG``."""

    candidate = str(explicit) if explicit else os.environ.get("SHOO_FFMPEG", "ffmpeg")
    resolved = shutil.which(candidate)
    if not resolved and Path(candidate).is_file():
        resolved = str(Path(candidate).resolve())
    if not resolved:
        raise FFmpegNotFoundError(
            "FFmpeg was not found. Install it (for example: sudo apt install ffmpeg) "
            "or set SHOO_FFMPEG to the executable path."
        )
    return resolved


def resolve_ffprobe(explicit: str | os.PathLike[str] | None = None) -> str:
    """Resolve FFprobe. Defaults next to FFmpeg when SHOO_FFPROBE is absent."""

    if explicit:
        candidate = str(explicit)
    elif os.environ.get("SHOO_FFPROBE"):
        candidate = os.environ["SHOO_FFPROBE"]
    else:
        candidate = "ffprobe"
    resolved = shutil.which(candidate)
    if not resolved and Path(candidate).is_file():
        resolved = str(Path(candidate).resolve())
    if not resolved:
        raise FFmpegNotFoundError(
            "FFprobe was not found. It is normally installed with FFmpeg. "
            "Set SHOO_FFPROBE to the executable path if it lives elsewhere."
        )
    return resolved


def app_state_dir() -> Path:
    """Return a platform-appropriate directory for logs/state."""

    override = os.environ.get("SHOO_CUT_STATE_DIR")
    if override:
        return Path(override).expanduser()
    if sys.platform.startswith("win"):
        base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        return Path(base) / "ShooAutoCut"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Logs" / "ShooAutoCut"
    base = os.environ.get("XDG_STATE_HOME")
    if base:
        return Path(base).expanduser() / "shoo-cut"
    return Path.home() / ".local" / "state" / "shoo-cut"


def format_percent(value: float) -> str:
    """Trim trailing zeros from a percent value."""

    return f"{value:g}"
