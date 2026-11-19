"""Parse FFmpeg's ``-progress pipe:1`` key/value output."""

from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class FFmpegProgress:
    percent: float
    out_time_seconds: float
    frame: int | None
    fps: float | None
    speed: str | None
    total_size: int | None
    done: bool


class ProgressParser:
    """Accumulate a progress block and emit one snapshot per ``progress=`` line."""

    def __init__(self, total_duration: float) -> None:
        self.total_duration = float(total_duration) if total_duration > 0 else 0.0
        self._values: dict[str, str] = {}
        self._last: FFmpegProgress | None = None

    def feed(self, line: str) -> FFmpegProgress | None:
        text = line.strip()
        if not text or "=" not in text:
            return None
        key, value = text.split("=", 1)
        if key == "progress":
            snapshot = self._snapshot(done=value.strip() == "end")
            # FFmpeg starts a new block after each progress marker. Keep the
            # parser stateless between blocks so stale values cannot linger.
            self._values = {}
            self._last = snapshot
            return snapshot
        self._values[key] = value.strip()
        return None

    def _snapshot(self, *, done: bool) -> FFmpegProgress:
        out_time = self._parse_out_time()
        if done:
            percent = 100.0
        elif self.total_duration > 0:
            percent = max(0.0, min(99.9, out_time / self.total_duration * 100.0))
        else:
            percent = 0.0
        frame = _int_or_none(self._values.get("frame"))
        fps = _float_or_none(self._values.get("fps"))
        speed = self._values.get("speed") or None
        if speed and not speed.endswith("x"):
            speed += "x"
        total_size = _int_or_none(self._values.get("total_size"))
        return FFmpegProgress(
            percent=round(percent, 2),
            out_time_seconds=round(out_time, 3),
            frame=frame,
            fps=fps,
            speed=speed,
            total_size=total_size,
            done=done,
        )

    def _parse_out_time(self) -> float:
        out_time_us = _int_or_none(self._values.get("out_time_us"))
        if out_time_us is not None:
            return max(0.0, out_time_us / 1_000_000.0)
        out_time_ms = _int_or_none(self._values.get("out_time_ms"))
        if out_time_ms is not None:
            # FFmpeg historically labels microseconds as out_time_ms.
            return max(0.0, out_time_ms / 1_000_000.0)
        out_time = self._values.get("out_time")
        if out_time:
            return _parse_timestamp(out_time)
        return 0.0


def _parse_timestamp(value: str) -> float:
    try:
        parts = value.strip().split(":")
        if len(parts) != 3:
            return 0.0
        hours, minutes, seconds = parts
        return float(hours) * 3600 + float(minutes) * 60 + float(seconds)
    except (TypeError, ValueError):
        return 0.0


def _int_or_none(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _float_or_none(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None
