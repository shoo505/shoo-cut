"""Application exception types.

All errors that are safe to show to a user derive from :class:`ShooCutError`.
"""

from __future__ import annotations


class ShooCutError(Exception):
    """Base class for expected, user-facing application errors."""


class ProbeError(ShooCutError):
    """The source file could not be inspected or is not a usable video."""


class SelectionError(ShooCutError):
    """Invalid duration/retention input for segment selection."""


class FFmpegNotFoundError(ShooCutError):
    """FFmpeg or FFprobe is not available."""


class ExportError(ShooCutError):
    """The export failed."""


class ExportCancelled(ShooCutError):
    """The export was cancelled by the user."""


class ValidationError(ShooCutError):
    """The produced file failed export validation."""


class BusyError(ShooCutError):
    """Another export is already running."""


class FileAccessError(ShooCutError):
    """A path could not be read or written."""
