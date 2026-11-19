"""End-to-end export pipeline and FFmpeg process supervision."""

from __future__ import annotations

import os
import shlex
import signal
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .errors import (
    ExportCancelled,
    ExportError,
    FileAccessError,
    SelectionError,
    ValidationError,
)
from .ffmpeg_cmd import build_export_command, output_fps
from .files import check_output_directory
from .models import SegmentPlan, VideoMetadata
from .probe import probe_video
from .progress import FFmpegProgress, ProgressParser
from .selection import select_segments, validate_retention
from .tempfiles import TemporaryOutput, cleanup_stale_temps, unique_output_path
from .util import format_duration, format_percent, utc_now_iso
from .validation import OutputValidation, validate_output

ProgressCallback = Callable[["ProgressUpdate"], None]
LogCallback = Callable[[str], None]


@dataclass(frozen=True)
class ProgressUpdate:
    phase: str
    percent: float
    message: str
    encode_percent: float | None = None
    speed: str | None = None
    frame: int | None = None
    fps: float | None = None
    total_size: int | None = None
    elapsed_seconds: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "phase": self.phase,
            "percent": round(self.percent, 2),
            "message": self.message,
            "encode_percent": self.encode_percent,
            "speed": self.speed,
            "frame": self.frame,
            "fps": self.fps,
            "total_size": self.total_size,
            "elapsed_seconds": round(self.elapsed_seconds, 2),
        }


@dataclass
class ExportRequest:
    input_path: str
    output_dir: str
    retention_percent: float
    metadata: VideoMetadata | None = None
    encoder_preset: str = "veryfast"
    crf: int = 23
    threads: int | None = None
    deep_validation: bool = False


@dataclass
class ExportResult:
    metadata: VideoMetadata
    plan: SegmentPlan
    output_path: str
    validation: OutputValidation
    elapsed_seconds: float
    finish_time: str = field(default_factory=utc_now_iso)

    def to_dict(self) -> dict[str, Any]:
        return {
            "input_metadata": self.metadata.to_dict(),
            "plan": self.plan.to_dict(),
            "output_path": self.output_path,
            "validation": self.validation.to_dict(),
            "elapsed_seconds": round(self.elapsed_seconds, 2),
            "finish_time": self.finish_time,
        }


@dataclass
class _ProcessOutcome:
    returncode: int
    cancelled: bool
    stderr_tail: list[str]


class _Emitter:
    def __init__(
        self,
        started: float,
        on_progress: ProgressCallback | None,
        on_log: LogCallback | None,
    ) -> None:
        self.started = started
        self.on_progress = on_progress
        self.on_log = on_log

    def progress(self, update: ProgressUpdate) -> None:
        if self.on_progress is None:
            return
        try:
            self.on_progress(update)
        except Exception:
            # A UI callback must never break the export itself.
            pass

    def log(self, message: str) -> None:
        if self.on_log is None:
            return
        try:
            self.on_log(message)
        except Exception:
            pass

    def emit(
        self,
        phase: str,
        percent: float,
        message: str,
        *,
        encode_percent: float | None = None,
        stats: FFmpegProgress | None = None,
    ) -> None:
        self.progress(
            ProgressUpdate(
                phase=phase,
                percent=max(0.0, min(100.0, percent)),
                message=message,
                encode_percent=encode_percent,
                speed=stats.speed if stats else None,
                frame=stats.frame if stats else None,
                fps=stats.fps if stats else None,
                total_size=stats.total_size if stats else None,
                elapsed_seconds=round(time.monotonic() - self.started, 2),
            )
        )


def _check_cancel(cancel_event: threading.Event | None) -> None:
    if cancel_event is not None and cancel_event.is_set():
        raise ExportCancelled("Export cancelled by the user.")


def _terminate_process(process: subprocess.Popen[str]) -> None:
    """Terminate the whole FFmpeg process group, escalating to SIGKILL."""

    if process.poll() is not None:
        return
    if os.name == "posix":
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError, OSError):
            try:
                process.terminate()
            except OSError:
                pass
    else:
        try:
            process.terminate()
        except OSError:
            pass
    try:
        process.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    if os.name == "posix":
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError, OSError):
            try:
                process.kill()
            except OSError:
                pass
    else:
        try:
            process.kill()
        except OSError:
            pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def _execute_ffmpeg(
    command: list[str],
    *,
    expected_duration: float,
    cancel_event: threading.Event | None,
    emitter: _Emitter,
) -> _ProcessOutcome:
    _check_cancel(cancel_event)
    emitter.log("Running: " + shlex.join(command))
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            start_new_session=(os.name == "posix"),
        )
    except FileNotFoundError:
        raise ExportError(
            "FFmpeg was not found. Install FFmpeg or set SHOO_FFMPEG."
        ) from None
    except OSError as exc:
        raise ExportError(f"Could not start FFmpeg: {exc}") from None

    stderr_tail: list[str] = []
    stderr_lock = threading.Lock()
    parser = ProgressParser(expected_duration)
    cancelled = False

    def read_stdout() -> None:
        if process.stdout is None:
            return
        try:
            for line in process.stdout:
                snapshot = parser.feed(line)
                if snapshot is None or cancelled:
                    continue
                overall = 5.0 + snapshot.percent * 0.90
                message = (
                    "Encoding finished, preparing validation"
                    if snapshot.done
                    else "Encoding selected segments (single FFmpeg pass)"
                )
                emitter.emit(
                    "processing",
                    min(overall, 95.0),
                    message,
                    encode_percent=snapshot.percent,
                    stats=snapshot,
                )
        except (OSError, ValueError):
            pass

    def read_stderr() -> None:
        if process.stderr is None:
            return
        try:
            for line in process.stderr:
                text = line.rstrip("\n")
                if not text:
                    continue
                with stderr_lock:
                    stderr_tail.append(text)
                    if len(stderr_tail) > 200:
                        del stderr_tail[:100]
                emitter.log(text)
        except (OSError, ValueError):
            pass

    stdout_thread = threading.Thread(target=read_stdout, name="ffmpeg-progress", daemon=True)
    stderr_thread = threading.Thread(target=read_stderr, name="ffmpeg-log", daemon=True)
    stdout_thread.start()
    stderr_thread.start()

    try:
        while process.poll() is None:
            if cancel_event is not None:
                if cancel_event.wait(0.1):
                    cancelled = True
                    emitter.log("Cancellation requested; stopping FFmpeg.")
                    _terminate_process(process)
                    break
            else:
                time.sleep(0.1)
        returncode = process.wait()
        stdout_thread.join(timeout=5)
        stderr_thread.join(timeout=5)
    finally:
        if process.poll() is None:
            _terminate_process(process)
        for stream in (process.stdout, process.stderr):
            try:
                if stream:
                    stream.close()
            except OSError:
                pass

    with stderr_lock:
        tail = list(stderr_tail)
    if cancel_event is not None and cancel_event.is_set() and not cancelled:
        cancelled = True
    return _ProcessOutcome(returncode=returncode, cancelled=cancelled, stderr_tail=tail)


def _format_error_tail(lines: list[str], count: int = 25) -> str:
    interesting = [line for line in lines if line.strip()]
    if not interesting:
        return "No FFmpeg error details were produced."
    return "\n".join(interesting[-count:])


def run_export(
    request: ExportRequest,
    *,
    on_progress: ProgressCallback | None = None,
    on_log: LogCallback | None = None,
    cancel_event: threading.Event | None = None,
) -> ExportResult:
    """Probe, plan, cut/concatenate/scale/encode once, validate, and commit."""

    started = time.monotonic()
    emitter = _Emitter(started, on_progress, on_log)
    retention = validate_retention(request.retention_percent)

    _check_cancel(cancel_event)
    emitter.emit("probing", 1.0, "Inspecting source video")
    if request.metadata is not None:
        metadata = request.metadata
        emitter.log(
            f"Source: {metadata.filename} ({metadata.container}, "
            f"{format_duration(metadata.duration)}, {metadata.resolution_label}, "
            f"{'audio: ' + str(metadata.audio_codec) if metadata.has_audio else 'no audio'})"
        )
    else:
        metadata = probe_video(request.input_path)
        emitter.log(
            f"Source: {metadata.filename} ({metadata.container}, "
            f"{format_duration(metadata.duration)}, {metadata.resolution_label}, "
            f"{'audio: ' + str(metadata.audio_codec) if metadata.has_audio else 'no audio'})"
        )

    _check_cancel(cancel_event)
    emitter.emit("planning", 2.0, "Selecting evenly distributed segments")
    plan = select_segments(metadata.duration, retention)
    for segment in plan.segments:
        emitter.log(
            f"Segment {segment.index + 1}/{len(plan.segments)}: "
            f"{format_duration(segment.start)} -> {format_duration(segment.end)} "
            f"({format_duration(segment.duration)})"
        )
    emitter.log(
        f"Retention: requested {format_percent(retention)}%, "
        f"effective {format_percent(plan.effective_percent)}%, "
        f"selected {format_duration(plan.selected_duration)}."
    )

    try:
        output_dir = check_output_directory(request.output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
    except FileAccessError:
        raise
    except OSError as exc:
        raise FileAccessError(f"Cannot create output folder: {exc}") from None

    removed = cleanup_stale_temps(output_dir)
    if removed:
        emitter.log(f"Removed {removed} stale temporary export(s) from {output_dir}")

    _check_cancel(cancel_event)
    final_path = unique_output_path(output_dir, metadata.filename, retention)
    fps = output_fps(metadata)
    temporary = TemporaryOutput(output_dir)
    try:
        command = build_export_command(
            metadata.path,
            temporary.path,
            plan.segments,
            has_audio=metadata.has_audio,
            fps=fps,
            encoder_preset=request.encoder_preset,
            crf=request.crf,
            threads=request.threads,
        )
        emitter.log(f"Output target: {final_path}")
        emitter.log(
            f"Pipeline: {len(plan.segments)} input segment(s), one concat, one "
            f"1280x720 bicubic scale, one H.264/AAC encode; output fps {fps:g}."
        )

        _check_cancel(cancel_event)
        emitter.emit(
            "processing",
            5.0,
            "Cutting and encoding selected segments",
            encode_percent=0.0,
        )
        outcome = _execute_ffmpeg(
            command,
            expected_duration=plan.selected_duration,
            cancel_event=cancel_event,
            emitter=emitter,
        )
        if outcome.cancelled:
            raise ExportCancelled("Export cancelled by the user.")
        if outcome.returncode != 0:
            raise ExportError(
                "FFmpeg failed with exit code "
                f"{outcome.returncode}.\n{_format_error_tail(outcome.stderr_tail)}"
            )
        _check_cancel(cancel_event)

        emitter.emit("validating", 96.0, "Validating the exported MP4")
        try:
            validation = validate_output(
                temporary.path,
                expected_duration=plan.selected_duration,
                expect_audio=metadata.has_audio,
                deep=request.deep_validation,
                check_playback=True,
            )
        except ValidationError as exc:
            raise ValidationError(f"Export validation failed: {exc}") from exc
        for warning in validation.warnings:
            emitter.log(f"Validation warning: {warning}")
        if validation.metadata is not None:
            emitter.log(
                "Validated: "
                f"{validation.metadata.resolution_label}, "
                f"{validation.metadata.video_codec}, "
                f"{validation.metadata.audio_codec or 'no audio'}, "
                f"{format_duration(validation.metadata.duration)}."
            )

        _check_cancel(cancel_event)
        emitter.emit("finalizing", 99.0, "Saving the final file")
        try:
            temporary.commit(final_path)
        except OSError as exc:
            raise ExportError(f"Could not move the finished export into place: {exc}") from None

        elapsed = time.monotonic() - started
        emitter.emit("completed", 100.0, f"Export complete: {final_path.name}")
        emitter.log(f"Export complete in {elapsed:.1f}s: {final_path}")
        return ExportResult(
            metadata=metadata,
            plan=plan,
            output_path=str(final_path),
            validation=validation,
            elapsed_seconds=elapsed,
        )
    finally:
        temporary.cleanup()


def build_dry_run_command(
    request: ExportRequest,
) -> tuple[VideoMetadata, SegmentPlan, list[str], str]:
    """Inspect and build the command without touching the output folder."""

    retention = validate_retention(request.retention_percent)
    metadata = request.metadata or probe_video(request.input_path)
    plan = select_segments(metadata.duration, retention)
    target = Path(request.output_dir).expanduser() / "preview-output.mp4"
    command = build_export_command(
        metadata.path,
        target,
        plan.segments,
        has_audio=metadata.has_audio,
        fps=output_fps(metadata),
        encoder_preset=request.encoder_preset,
        crf=request.crf,
        threads=request.threads,
    )
    return metadata, plan, command, str(target)


def validate_plan_inputs(percent: float) -> float:
    """Expose consistent retention validation to the API."""

    try:
        return validate_retention(percent)
    except SelectionError:
        raise
