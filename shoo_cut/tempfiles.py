"""Output naming and temporary-file lifecycle.

Exports are written to a hidden temporary MP4 in the destination directory and
atomically renamed only after validation. This avoids a partially written file
appearing under the final name while keeping temporary storage to one output.
"""

from __future__ import annotations

import os
import time
import uuid
from pathlib import Path

from .util import safe_stem

TEMP_PREFIX = ".shoo-cut-"
STALE_TEMP_SECONDS = 24 * 60 * 60


def unique_output_path(output_dir: Path, source_name: str, retention_percent: float) -> Path:
    """Build an unused, human-readable output name in ``output_dir``."""

    stem = safe_stem(Path(source_name).stem)
    percent = f"{retention_percent:g}".replace(".", "_")
    candidate = output_dir / f"{stem}_cut_{percent}pct.mp4"
    counter = 2
    while candidate.exists():
        candidate = output_dir / f"{stem}_cut_{percent}pct_{counter}.mp4"
        counter += 1
    return candidate


def cleanup_stale_temps(output_dir: Path, max_age_seconds: float = STALE_TEMP_SECONDS) -> int:
    """Remove only our own stale temporary exports (never user files)."""

    removed = 0
    cutoff = time.time() - max_age_seconds
    try:
        entries = list(output_dir.iterdir())
    except OSError:
        return 0
    for entry in entries:
        if not entry.name.startswith(TEMP_PREFIX) or entry.suffix.lower() != ".mp4":
            continue
        try:
            if entry.is_file() and entry.stat().st_mtime < cutoff:
                entry.unlink()
                removed += 1
        except OSError:
            continue
    return removed


class TemporaryOutput:
    """A temporary output path that cleans itself unless committed."""

    def __init__(self, output_dir: Path) -> None:
        self.output_dir = output_dir
        self.path = output_dir / f"{TEMP_PREFIX}{uuid.uuid4().hex}.mp4"
        self.committed = False

    def commit(self, final_path: Path) -> Path:
        if self.committed:
            raise RuntimeError("Temporary output has already been committed.")
        final_path.parent.mkdir(parents=True, exist_ok=True)
        os.replace(self.path, final_path)
        self.committed = True
        return final_path

    def cleanup(self) -> None:
        if self.committed:
            return
        try:
            self.path.unlink(missing_ok=True)
        except OSError:
            pass

    def __enter__(self) -> "TemporaryOutput":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.cleanup()
