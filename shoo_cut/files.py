"""Local-filesystem browsing for the interface (no uploads, no shell)."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from .errors import FileAccessError
from .util import human_bytes, resolve_ffmpeg

VIDEO_EXTENSIONS = frozenset(
    {
        ".3g2",
        ".3gp",
        ".asf",
        ".avi",
        ".dv",
        ".f4v",
        ".flv",
        ".m2ts",
        ".m2v",
        ".m4v",
        ".mkv",
        ".mov",
        ".mp4",
        ".mpeg",
        ".mpg",
        ".mts",
        ".mxf",
        ".ogv",
        ".ts",
        ".vob",
        ".webm",
        ".wmv",
    }
)
MAX_DIRECTORY_ENTRIES = 3000


def is_video_filename(name: str) -> bool:
    return Path(name).suffix.lower() in VIDEO_EXTENSIONS


def default_output_dir() -> Path:
    videos = Path.home() / "Videos"
    if videos.is_dir():
        return videos / "Shoo Auto Cut"
    return Path.home() / "Shoo Auto Cut"


def suggest_output_dir(input_path: str | Path) -> Path:
    """Prefer an output folder beside the source when that location is writable."""

    source = Path(input_path).expanduser()
    parent = source.parent
    try:
        if parent.is_dir() and os.access(parent, os.W_OK):
            return parent / "Shoo Auto Cut"
    except OSError:
        pass
    return default_output_dir()


def directory_shortcuts() -> list[dict[str, str]]:
    candidates: list[tuple[str, Path]] = [
        ("Home", Path.home()),
        ("Videos", Path.home() / "Videos"),
        ("Desktop", Path.home() / "Desktop"),
        ("Working directory", Path.cwd()),
        ("Filesystem root", Path("/") if os.name != "nt" else Path.home().anchor),
    ]
    seen: set[str] = set()
    shortcuts: list[dict[str, str]] = []
    for label, path in candidates:
        try:
            resolved = path.expanduser().resolve()
        except OSError:
            continue
        if not resolved.is_dir() or str(resolved) in seen:
            continue
        seen.add(str(resolved))
        shortcuts.append({"label": label, "path": str(resolved)})
    return shortcuts


def list_directory(
    path: str | Path | None = None,
    *,
    show_hidden: bool = False,
) -> dict[str, Any]:
    """List one directory for the server-side file/folder picker."""

    if path in (None, ""):
        candidate = Path.home()
    else:
        candidate = Path(str(path)).expanduser()
    try:
        resolved = candidate.resolve(strict=True)
    except FileNotFoundError:
        raise FileAccessError(f"Folder does not exist: {candidate}") from None
    except PermissionError:
        raise FileAccessError(f"Permission denied: {candidate}") from None
    except OSError as exc:
        raise FileAccessError(f"Cannot open folder {candidate}: {exc}") from None
    if not resolved.is_dir():
        raise FileAccessError(f"Not a folder: {resolved}")

    entries: list[dict[str, Any]] = []
    truncated = False
    try:
        with os.scandir(resolved) as iterator:
            for entry in iterator:
                if len(entries) >= MAX_DIRECTORY_ENTRIES:
                    truncated = True
                    break
                if not show_hidden and entry.name.startswith("."):
                    continue
                try:
                    is_dir = entry.is_dir(follow_symlinks=True)
                except OSError:
                    is_dir = False
                if is_dir:
                    entries.append(
                        {
                            "name": entry.name,
                            "path": str(Path(entry.path)),
                            "kind": "directory",
                            "is_video": False,
                        }
                    )
                    continue
                try:
                    if not entry.is_file(follow_symlinks=True):
                        continue
                    stat_result = entry.stat(follow_symlinks=True)
                except OSError:
                    continue
                entry_path = Path(entry.path)
                is_video = is_video_filename(entry.name)
                entries.append(
                    {
                        "name": entry.name,
                        "path": str(entry_path),
                        "kind": "file",
                        "is_video": is_video,
                        "size_bytes": stat_result.st_size,
                        "size_label": human_bytes(stat_result.st_size),
                        "modified": stat_result.st_mtime,
                        "extension": entry_path.suffix.lower(),
                    }
                )
    except PermissionError:
        raise FileAccessError(f"Permission denied: {resolved}") from None
    except OSError as exc:
        raise FileAccessError(f"Cannot list folder {resolved}: {exc}") from None

    entries.sort(key=lambda item: (item["kind"] != "directory", item["name"].casefold()))
    parent = resolved.parent
    return {
        "current": str(resolved),
        "parent": str(parent) if parent != resolved else None,
        "entries": entries,
        "truncated": truncated,
        "shortcuts": directory_shortcuts(),
        "video_extensions": sorted(VIDEO_EXTENSIONS),
    }


def check_output_directory(path: str | Path) -> Path:
    """Resolve and validate an output destination without creating it."""

    candidate = Path(str(path)).expanduser()
    try:
        resolved = candidate.resolve(strict=False)
    except OSError as exc:
        raise FileAccessError(f"Invalid output folder: {candidate} ({exc})") from None
    if resolved.exists() and not resolved.is_dir():
        raise FileAccessError(f"Output path exists but is not a folder: {resolved}")
    if not resolved.exists():
        ancestor = resolved
        while not ancestor.exists() and ancestor != ancestor.parent:
            ancestor = ancestor.parent
        if not ancestor.is_dir():
            raise FileAccessError(f"Cannot create output folder under {ancestor}.")
        if not os.access(ancestor, os.W_OK):
            raise FileAccessError(f"Output folder is not writable: {ancestor}")
    elif not os.access(resolved, os.W_OK):
        raise FileAccessError(f"Output folder is not writable: {resolved}")
    return resolved


def ensure_ffmpeg_ready() -> str:
    return resolve_ffmpeg()
