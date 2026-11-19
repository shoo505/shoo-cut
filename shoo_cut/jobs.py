"""In-process export jobs, progress state, cancellation and job logs."""

from __future__ import annotations

import threading
import traceback
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .errors import BusyError, ExportCancelled, ShooCutError
from .files import check_output_directory
from .models import SegmentPlan, VideoMetadata
from .pipeline import (
    ExportRequest,
    ExportResult,
    ProgressUpdate,
    run_export,
)
from .probe import probe_video
from .selection import select_segments, validate_retention
from .util import app_state_dir, utc_now_iso

ACTIVE_STATUSES = frozenset(
    {"queued", "probing", "planning", "processing", "validating", "finalizing", "cancelling"}
)
TERMINAL_STATUSES = frozenset({"completed", "failed", "cancelled"})
MAX_JOB_HISTORY = 30


def _now() -> str:
    return utc_now_iso()


def _elapsed(started: float | None, finished: float | None) -> float:
    if started is None:
        return 0.0
    import time

    end = finished if finished is not None else time.monotonic()
    return max(0.0, round(end - started, 2))


@dataclass
class ExportJob:
    id: str
    input_path: str
    output_dir: str
    retention_percent: float
    metadata: VideoMetadata
    plan: SegmentPlan
    log_path: str | None
    status: str = "queued"
    phase: str = "queued"
    progress: float = 0.0
    message: str = "Waiting to start"
    encode_percent: float | None = None
    speed: str | None = None
    frame: int | None = None
    fps: float | None = None
    total_size: int | None = None
    output_path: str | None = None
    error: str | None = None
    validation: dict[str, Any] | None = None
    created_at: str = field(default_factory=_now)
    started_at: str | None = None
    finished_at: str | None = None
    cancel_requested: bool = False
    log_tail: list[str] = field(default_factory=list)
    started_monotonic: float | None = field(default=None, repr=False)
    finished_monotonic: float | None = field(default=None, repr=False)
    cancel_event: threading.Event = field(default_factory=threading.Event, repr=False)
    log_lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    @property
    def active(self) -> bool:
        return self.status in ACTIVE_STATUSES

    def to_dict(self, *, include_log: bool = True) -> dict[str, Any]:
        data: dict[str, Any] = {
            "id": self.id,
            "status": self.status,
            "phase": self.phase,
            "active": self.active,
            "progress": round(self.progress, 2),
            "message": self.message,
            "input_path": self.input_path,
            "output_dir": self.output_dir,
            "retention_percent": self.retention_percent,
            "metadata": self.metadata.to_dict(),
            "plan": self.plan.to_dict(),
            "encode_percent": self.encode_percent,
            "speed": self.speed,
            "frame": self.frame,
            "fps": self.fps,
            "total_size": self.total_size,
            "output_path": self.output_path,
            "error": self.error,
            "validation": self.validation,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "elapsed_seconds": _elapsed(self.started_monotonic, self.finished_monotonic),
            "cancel_requested": self.cancel_requested,
            "log_path": self.log_path,
        }
        if include_log:
            with self.log_lock:
                data["log_tail"] = list(self.log_tail[-120:])
        return data


class JobManager:
    """Run at most one export at a time; keep recent completed job state."""

    def __init__(
        self,
        *,
        log_dir: str | Path | None = None,
        encoder_preset: str = "veryfast",
        crf: int = 23,
        threads: int | None = None,
        deep_validation: bool = False,
        max_history: int = MAX_JOB_HISTORY,
    ) -> None:
        self._lock = threading.RLock()
        self._jobs: dict[str, ExportJob] = {}
        self._threads: dict[str, threading.Thread] = {}
        self._active_id: str | None = None
        self._shutdown = False
        self.encoder_preset = encoder_preset
        self.crf = crf
        self.threads = threads
        self.deep_validation = deep_validation
        self.max_history = max_history
        self.log_dir = Path(log_dir or (app_state_dir() / "logs")).expanduser()
        try:
            self.log_dir.mkdir(parents=True, exist_ok=True)
        except OSError:
            self.log_dir = None  # type: ignore[assignment]

    # -- queries ---------------------------------------------------------
    def get_job(self, job_id: str, *, include_log: bool = True) -> dict[str, Any] | None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            return job.to_dict(include_log=include_log)

    def list_jobs(self, *, include_log: bool = False) -> list[dict[str, Any]]:
        with self._lock:
            jobs = sorted(self._jobs.values(), key=lambda item: item.created_at, reverse=True)
            return [job.to_dict(include_log=include_log) for job in jobs]

    def active_job(self) -> dict[str, Any] | None:
        with self._lock:
            if self._active_id is None:
                return None
            job = self._jobs.get(self._active_id)
            return job.to_dict(include_log=False) if job else None

    # -- commands --------------------------------------------------------
    def create_job(
        self,
        input_path: str,
        output_dir: str,
        retention_percent: float,
        *,
        metadata: VideoMetadata | None = None,
    ) -> dict[str, Any]:
        with self._lock:
            if self._shutdown:
                raise BusyError("The application is shutting down.")
            active = self._active_job_locked()
            if active is not None:
                raise BusyError(
                    f"Another export is already running ({active.id}). "
                    "Wait for it to finish or cancel it first."
                )
            if metadata is None:
                metadata = probe_video(input_path)
            retention = validate_retention(retention_percent)
            plan = select_segments(metadata.duration, retention)
            check_output_directory(output_dir)
            job_id = f"job_{uuid.uuid4().hex[:12]}"
            log_path = self._create_log(job_id, metadata, output_dir, retention)
            job = ExportJob(
                id=job_id,
                input_path=metadata.path,
                output_dir=str(Path(output_dir).expanduser()),
                retention_percent=retention,
                metadata=metadata,
                plan=plan,
                log_path=log_path,
            )
            self._jobs[job_id] = job
            self._active_id = job_id
            self._prune_locked()
            thread = threading.Thread(
                target=self._run_job,
                args=(job,),
                name=f"shoo-cut-{job_id}",
                daemon=True,
            )
            self._threads[job_id] = thread
            job.started_monotonic = __import__("time").monotonic()
            job.started_at = _now()
            try:
                thread.start()
            except RuntimeError as exc:
                job.status = "failed"
                job.error = f"Could not start worker thread: {exc}"
                job.finished_at = _now()
                job.finished_monotonic = __import__("time").monotonic()
                self._active_id = None
                raise ShooCutError(job.error) from exc
            return job.to_dict()

    def cancel_job(self, job_id: str) -> dict[str, Any] | None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            if job.status in TERMINAL_STATUSES:
                return job.to_dict()
            job.cancel_requested = True
            job.status = "cancelling"
            job.phase = "cancelling"
            job.message = "Cancelling – stopping FFmpeg and cleaning up"
            job.cancel_event.set()
            self._log_locked(job, "Cancellation requested by user.")
            return job.to_dict()

    def shutdown(self, *, timeout: float = 15.0) -> None:
        with self._lock:
            self._shutdown = True
            active = self._active_job_locked()
            if active is not None:
                active.cancel_requested = True
                active.cancel_event.set()
            threads = list(self._threads.values())
        for thread in threads:
            if thread.is_alive():
                thread.join(timeout=timeout / max(1, len(threads)))

    # -- internals -------------------------------------------------------
    def _active_job_locked(self) -> ExportJob | None:
        if self._active_id is None:
            return None
        job = self._jobs.get(self._active_id)
        if job is None or not job.active:
            self._active_id = None
            return None
        return job

    def _create_log(
        self,
        job_id: str,
        metadata: VideoMetadata,
        output_dir: str,
        retention: float,
    ) -> str | None:
        if not self.log_dir:
            return None
        path = self.log_dir / f"{job_id}.log"
        try:
            with path.open("w", encoding="utf-8") as handle:
                handle.write(f"Shoo Auto Cut export log\n")
                handle.write(f"Job: {job_id}\n")
                handle.write(f"Started: {_now()}\n")
                handle.write(f"Source: {metadata.path}\n")
                handle.write(f"Output folder: {output_dir}\n")
                handle.write(f"Retention: {retention:g}%\n")
                handle.write("-" * 72 + "\n")
            return str(path)
        except OSError:
            return None

    def _write_log_file(self, job: ExportJob, text: str) -> None:
        if not job.log_path:
            return
        try:
            with open(job.log_path, "a", encoding="utf-8") as handle:
                handle.write(text + "\n")
        except OSError:
            pass

    def _log_locked(self, job: ExportJob, message: str) -> None:
        timestamp = datetime.now(timezone.utc).strftime("%H:%M:%S")
        for line in str(message).splitlines() or [""]:
            entry = f"[{timestamp}] {line}"
            with job.log_lock:
                job.log_tail.append(entry)
                if len(job.log_tail) > 250:
                    del job.log_tail[:100]
            self._write_log_file(job, entry)

    def _log(self, job: ExportJob, message: str) -> None:
        with self._lock:
            self._log_locked(job, message)

    def _on_progress(self, job: ExportJob, update: ProgressUpdate) -> None:
        with self._lock:
            if job.status in TERMINAL_STATUSES:
                return
            job.phase = update.phase
            job.progress = update.percent
            job.message = update.message
            job.encode_percent = update.encode_percent
            job.speed = update.speed
            job.frame = update.frame
            job.fps = update.fps
            job.total_size = update.total_size
            if update.phase == "processing":
                job.status = "processing"
            elif update.phase == "validating":
                job.status = "validating"
            elif update.phase == "finalizing":
                job.status = "finalizing"
            elif update.phase in {"probing", "planning"}:
                job.status = update.phase
            elif update.phase == "completed":
                job.status = "completed"
            if job.cancel_requested and job.status not in TERMINAL_STATUSES:
                job.status = "cancelling"

    def _run_job(self, job: ExportJob) -> None:
        try:
            self._log(job, "Starting export.")
            result: ExportResult = run_export(
                ExportRequest(
                    input_path=job.input_path,
                    output_dir=job.output_dir,
                    retention_percent=job.retention_percent,
                    metadata=job.metadata,
                    encoder_preset=self.encoder_preset,
                    crf=self.crf,
                    threads=self.threads,
                    deep_validation=self.deep_validation,
                ),
                on_progress=lambda update: self._on_progress(job, update),
                on_log=lambda line: self._log(job, line),
                cancel_event=job.cancel_event,
            )
            with self._lock:
                job.status = "completed"
                job.phase = "completed"
                job.progress = 100.0
                job.message = "Export complete"
                job.output_path = result.output_path
                job.validation = result.validation.to_dict()
                job.error = None
                job.encode_percent = 100.0
                job.finished_at = _now()
                job.finished_monotonic = __import__("time").monotonic()
                job.log_tail.append(
                    f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] "
                    f"Saved output: {result.output_path}"
                )
                self._write_log_file(job, f"Saved output: {result.output_path}")
                self._active_id = None
        except ExportCancelled:
            with self._lock:
                job.status = "cancelled"
                job.phase = "cancelled"
                job.message = "Export cancelled; temporary files were removed"
                job.error = None
                job.finished_at = _now()
                job.finished_monotonic = __import__("time").monotonic()
                self._log_locked(job, "Export cancelled.")
                self._active_id = None
        except ShooCutError as exc:
            with self._lock:
                job.status = "failed"
                job.phase = "failed"
                job.message = "Export failed"
                job.error = str(exc)
                job.finished_at = _now()
                job.finished_monotonic = __import__("time").monotonic()
                self._log_locked(job, f"Export failed: {exc}")
                self._active_id = None
        except Exception as exc:  # defensive: keep the server alive
            with self._lock:
                job.status = "failed"
                job.phase = "failed"
                job.message = "Unexpected export failure"
                job.error = f"{type(exc).__name__}: {exc}"
                job.finished_at = _now()
                job.finished_monotonic = __import__("time").monotonic()
                self._log_locked(job, "Unexpected error:\n" + traceback.format_exc())
                self._active_id = None
        finally:
            with self._lock:
                self._threads.pop(job.id, None)

    def _prune_locked(self) -> None:
        if len(self._jobs) <= self.max_history:
            return
        removable = [
            job
            for job in self._jobs.values()
            if job.status in TERMINAL_STATUSES and job.id != self._active_id
        ]
        removable.sort(key=lambda item: item.created_at)
        for job in removable[: max(0, len(self._jobs) - self.max_history)]:
            self._jobs.pop(job.id, None)
