"""Validate that an exported file is the required, playable MP4."""

from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .errors import ExportError, ValidationError
from .ffmpeg_cmd import OUTPUT_HEIGHT, OUTPUT_WIDTH
from .models import VideoMetadata
from .probe import probe_video
from .util import resolve_ffmpeg


@dataclass
class OutputValidation:
    valid: bool
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    metadata: VideoMetadata | None = None
    playback_checked: bool = False
    deep_decode: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "valid": self.valid,
            "errors": list(self.errors),
            "warnings": list(self.warnings),
            "playback_checked": self.playback_checked,
            "deep_decode": self.deep_decode,
            "output_metadata": self.metadata.to_dict() if self.metadata else None,
        }


def _validate_media(
    metadata: VideoMetadata,
    *,
    expected_duration: float | None,
    expect_audio: bool,
) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []

    if metadata.video_codec != "h264":
        errors.append(f"Video codec is {metadata.video_codec}, expected H.264.")
    if metadata.width != OUTPUT_WIDTH or metadata.height != OUTPUT_HEIGHT:
        errors.append(
            f"Resolution is {metadata.width}x{metadata.height}, "
            f"expected {OUTPUT_WIDTH}x{OUTPUT_HEIGHT}."
        )
    if "mp4" not in metadata.format_name.lower() and "mov" not in metadata.format_name.lower():
        errors.append(f"Container is {metadata.container}, expected MP4.")
    if expect_audio:
        if not metadata.has_audio:
            errors.append("The source has audio, but the export has no audio stream.")
        elif metadata.audio_codec != "aac":
            errors.append(f"Audio codec is {metadata.audio_codec}, expected AAC.")
    elif metadata.has_audio:
        errors.append("The source has no audio, but the export unexpectedly contains audio.")

    if expected_duration is not None and expected_duration > 0:
        tolerance = max(1.0, min(5.0, expected_duration * 0.01))
        difference = abs(metadata.duration - expected_duration)
        if difference > tolerance:
            errors.append(
                f"Output duration {metadata.duration:.3f}s differs from expected "
                f"{expected_duration:.3f}s by more than {tolerance:.1f}s."
            )
        elif difference > max(0.35, tolerance / 3):
            warnings.append(
                f"Output duration differs from the selected timeline by {difference:.2f}s."
            )

    if metadata.has_audio:
        if metadata.audio_duration is not None:
            video_duration = metadata.video_duration or metadata.duration
            av_gap = abs(metadata.audio_duration - video_duration)
            if av_gap > max(1.0, min(5.0, metadata.duration * 0.01)):
                errors.append(
                    "Audio and video stream durations differ by "
                    f"{av_gap:.2f}s, which suggests an audio-sync problem."
                )
            elif av_gap > 0.35:
                warnings.append(
                    f"Audio/video stream durations differ by {av_gap:.2f}s."
                )
        if metadata.start_time is not None and abs(metadata.start_time) > 0.5:
            warnings.append(
                f"Output starts at {metadata.start_time:.2f}s instead of zero."
            )

    return errors, warnings


def _playback_check(path: Path, *, deep: bool, timeout: float = 120) -> tuple[bool, list[str]]:
    """Decode the beginning (or the whole file for deep checks) with FFmpeg."""

    ffmpeg = resolve_ffmpeg()
    command = [
        ffmpeg,
        "-hide_banner",
        "-nostdin",
        "-v",
        "error",
        "-xerror",
        "-i",
        str(path),
        "-map",
        "0:v:0",
        "-map",
        "0:a:0?",
    ]
    if not deep:
        command.extend(["-t", "3"])
    command.extend(["-f", "null", "-"])
    try:
        completed = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return False, ["Playback check timed out."]
    except (FileNotFoundError, OSError) as exc:
        return False, [f"Could not run playback check: {exc}"]
    if completed.returncode != 0:
        detail = (completed.stderr or "").strip().splitlines()
        suffix = f": {detail[-1]}" if detail else ""
        return False, [f"FFmpeg could not decode the exported file{suffix}"]
    return True, []


def validate_output(
    path: str | Path,
    *,
    expected_duration: float | None = None,
    expect_audio: bool | None = None,
    deep: bool = False,
    check_playback: bool = True,
) -> OutputValidation:
    """Probe and optionally decode an export; raise if it is not valid."""

    candidate = Path(path)
    if not candidate.exists():
        raise ValidationError(f"Export file is missing: {candidate}")
    if candidate.stat().st_size == 0:
        raise ValidationError("The exported file is empty.")

    try:
        metadata = probe_video(candidate)
    except Exception as exc:
        raise ValidationError(f"The exported file is not a readable video: {exc}") from exc

    audio_expected = metadata.has_audio if expect_audio is None else bool(expect_audio)
    errors, warnings = _validate_media(
        metadata,
        expected_duration=expected_duration,
        expect_audio=audio_expected,
    )
    playback_checked = False
    if check_playback:
        played, playback_errors = _playback_check(candidate, deep=deep)
        playback_checked = played
        errors.extend(playback_errors)

    report = OutputValidation(
        valid=not errors,
        errors=errors,
        warnings=warnings,
        metadata=metadata,
        playback_checked=playback_checked,
        deep_decode=deep,
    )
    if not report.valid:
        raise ValidationError("; ".join(report.errors))
    return report
