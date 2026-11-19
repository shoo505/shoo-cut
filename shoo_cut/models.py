"""Data models shared by probing, selection, processing and the API."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class VideoMetadata:
    """Normalized metadata for a source or exported video."""

    path: str
    filename: str
    duration: float
    width: int
    height: int
    fps: float | None
    video_codec: str
    audio_codec: str | None
    audio_channels: int | None
    audio_sample_rate: int | None
    container: str
    format_name: str
    size_bytes: int
    bitrate: int | None
    video_stream_index: int
    audio_stream_index: int | None
    rotation: int = 0
    video_duration: float | None = None
    audio_duration: float | None = None
    start_time: float | None = None
    probe_data: dict[str, Any] = field(default_factory=dict, repr=False, compare=False)

    @property
    def has_audio(self) -> bool:
        return self.audio_stream_index is not None and self.audio_codec is not None

    @property
    def resolution_label(self) -> str:
        return f"{self.width}x{self.height}"

    def to_dict(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "filename": self.filename,
            "duration": round(self.duration, 6),
            "duration_label": _duration_label(self.duration),
            "width": self.width,
            "height": self.height,
            "resolution": self.resolution_label,
            "fps": self.fps,
            "video_codec": self.video_codec,
            "audio_codec": self.audio_codec,
            "audio_channels": self.audio_channels,
            "audio_sample_rate": self.audio_sample_rate,
            "has_audio": self.has_audio,
            "container": self.container,
            "format_name": self.format_name,
            "size_bytes": self.size_bytes,
            "bitrate": self.bitrate,
            "rotation": self.rotation,
            "video_duration": self.video_duration,
            "audio_duration": self.audio_duration,
            "start_time": self.start_time,
        }


@dataclass(frozen=True)
class Segment:
    """A half-open time range (start, end) in seconds on the source timeline."""

    index: int
    start: float
    end: float
    duration: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "start": round(self.start, 6),
            "end": round(self.end, 6),
            "duration": round(self.duration, 6),
            "start_label": _clock_label(self.start),
            "end_label": _clock_label(self.end),
            "duration_label": _duration_label(self.duration),
        }


@dataclass(frozen=True)
class SegmentPlan:
    """Deterministic selection result for one source/retention pair."""

    source_duration: float
    retention_percent: float
    target_duration: float
    selected_duration: float
    effective_percent: float
    segments: tuple[Segment, ...]
    strategy: str
    strategy_description: str
    direction: str = "unknown"
    cut_percent: float | None = None

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {
            "source_duration": round(self.source_duration, 6),
            "source_duration_label": _duration_label(self.source_duration),
            "retention_percent": self.retention_percent,
            "target_duration": round(self.target_duration, 6),
            "selected_duration": round(self.selected_duration, 6),
            "selected_duration_label": _duration_label(self.selected_duration),
            "effective_percent": round(self.effective_percent, 3),
            "segments": [segment.to_dict() for segment in self.segments],
            "segment_count": len(self.segments),
            "strategy": self.strategy,
            "strategy_description": self.strategy_description,
        }
        if self.cut_percent is not None:
            d["cut_percent"] = self.cut_percent
        if self.direction != "unknown":
            d["direction"] = self.direction
        return d


def _duration_label(seconds: float) -> str:
    total = max(0, int(round(seconds)))
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours}h {minutes:02d}m {secs:02d}s"
    if minutes:
        return f"{minutes}m {secs:02d}s"
    return f"{secs}s"


def _clock_label(seconds: float) -> str:
    total_ms = max(0, int(round(seconds * 1000)))
    hours, rem = divmod(total_ms, 3_600_000)
    minutes, rem = divmod(rem, 60_000)
    secs, millis = divmod(rem, 1000)
    if hours:
        return f"{hours:02d}:{minutes:02d}:{secs:02d}.{millis:03d}"
    return f"{minutes:02d}:{secs:02d}.{millis:03d}"


def path_display(path: str | Path) -> str:
    return str(Path(path).expanduser())
