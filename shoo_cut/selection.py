"""Deterministic, content-agnostic segment selection.

The first-release strategy deliberately picks evenly spaced chunks. It is not
AI highlight detection and does not analyse the picture or audio content. A
different strategy can be supplied later without changing the FFmpeg pipeline.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Protocol

from .errors import SelectionError
from .models import Segment, SegmentPlan

CUT_PERCENTAGES: tuple[float, ...] = (25.0, 45.0, 50.0, 65.0, 75.0)
"""Five predefined cut percentages. Each represents the portion of the video
that will be REMOVED, not retained. Default is 50% removal (50% kept)."""

MAX_SEGMENTS = 8
TARGET_SEGMENT_SECONDS = 120.0
MIN_CLIP_SECONDS = 0.2
STRATEGY_KEY = "even-distribution-v1"
STRATEGY_DESCRIPTION = (
    "Deterministic even distribution: equal-length segments spaced uniformly "
    "across the source with equal gaps. No content/highlight analysis."
)


def validate_cut_percent(percent: float | int | str) -> float:
    """Return a validated cut percentage in [0, 100).

    The cut percentage represents the portion of the video that will be
    REMOVED. A value of 0 means nothing is removed; 100 means everything
    is removed (retention of 0).
    """

    if isinstance(percent, bool):
        raise SelectionError("Cut percentage must be a number between 0 and 100.")
    try:
        value = float(percent)
    except (TypeError, ValueError):
        raise SelectionError("Cut percentage must be a number between 0 and 100.") from None
    if not math.isfinite(value):
        raise SelectionError("Cut percentage must be a finite number between 0 and 100.")
    if value < 0 or value >= 100:
        raise SelectionError("Cut percentage must be >= 0 and < 100.")
    return round(value, 6)


def validate_retention(percent: float | int | str) -> float:
    """Return a validated retention percentage in (0, 100].

    This is maintained for backward compatibility; callers that use the
    new cut-percentage workflow should use validate_cut_percent instead.
    """

    return 100.0 - validate_cut_percent(percent)


def validate_direction(direction: str) -> str:
    """Validate and normalise the cut direction string."""

    d = direction.strip().lower()
    if d in {"beginning", "begin", "remove beginning", "remove_beginning", "a"}:
        return "remove_beginning"
    if d in {"ending", "end", "remove ending", "remove_ending", "b", "remove end"}:
        return "remove_ending"
    if d in {"remove_beginning", "remove_ending"}:
        return d
    raise SelectionError(
        "Cut direction must be one of: 'Remove Beginning' or 'Remove Ending'."
    )


def validate_duration(duration: float | int | str) -> float:
    if isinstance(duration, bool):
        raise SelectionError("Video duration must be a positive number.")
    try:
        value = float(duration)
    except (TypeError, ValueError):
        raise SelectionError("Video duration must be a positive number.") from None
    if not math.isfinite(value) or value <= 0:
        raise SelectionError("Video duration must be a positive finite number.")
    if value < 0.1:
        raise SelectionError("The video is too short to cut (under 0.1 seconds).")
    return value


def choose_segment_count(target_duration: float) -> int:
    """Pick a modest number of reasonably sized segments.

    About one segment per two retained minutes, capped at eight. Short videos
    therefore become one clip instead of a swarm of tiny ones.
    """

    if target_duration <= 0:
        return 1
    count = math.ceil(target_duration / TARGET_SEGMENT_SECONDS)
    return max(1, min(MAX_SEGMENTS, count))


@dataclass(frozen=True)
class EvenDistributionStrategy:
    """Equal-sized chunks, equal gaps, chronological order."""

    key: str = STRATEGY_KEY
    description: str = STRATEGY_DESCRIPTION

    def select(self, duration: float, cut_percent: float | None = None, *, retention_percent: float | None = None, direction: str | None = None) -> SegmentPlan:
        """Build a segment plan.

        Exactly one of ``cut_percent`` or ``retention_percent`` must be provided.
        If ``cut_percent`` is given, it represents the portion of the video
        to REMOVE (0 <= cut_percent < 100). The effective retention is
        100 - cut_percent.

        If ``retention_percent`` is given (backward-compatible), it represents
        the portion to KEEP (0 < retention_percent <= 100).

        The ``direction`` parameter determines which portion of the video is
        retained:

        - ``remove_beginning``: The removed portion is at the start of the video.
          The retained segments span from the cut point to the end.
        - ``remove_ending``: The removed portion is at the end of the video.
          The retained segments span from the start to the cut point.
        - ``unknown`` or ``None``: Segments are distributed evenly across the
          entire source (original backward-compatible behavior).

        When a direction is specified, the ``target_duration`` and segment
        positions are calculated relative to the retained portion only, so that
        the even distribution applies within the kept segment rather than across
        the full source duration.
        """

        duration = validate_duration(duration)

        # Determine retention from cut percent (new workflow) or use provided retention.
        if cut_percent is not None and retention_percent is None:
            requested = validate_cut_percent(cut_percent)
            effective_retention = 100.0 - requested
        elif retention_percent is not None and cut_percent is None:
            requested = validate_retention(retention_percent)
            effective_retention = requested
        else:
            raise SelectionError("Provide exactly one of cut_percent or retention_percent.")

        # Calculate the boundary between removed and retained portions based on direction.
        if direction == "remove_beginning":
            # Remove from the beginning: keep from cut_point to end.
            removed_duration = duration * requested / 100.0
            retained_start = round(removed_duration, 6)
            retained_end = duration
            retained_duration = duration - removed_duration
        elif direction == "remove_ending":
            # Remove from the ending: keep from start to cut_point.
            retained_duration = duration * effective_retention / 100.0
            retained_start = 0.0
            retained_end = round(retained_duration, 6)
        else:
            # Unknown direction: retain the full duration, distribute evenly.
            retained_duration = duration
            retained_start = 0.0
            retained_end = duration

        # Clamp retained_duration to valid range.
        retained_duration = min(max(retained_duration, 0.1), duration)
        if retained_duration <= 0 or retained_start >= retained_end:
            # Fall back to full retention with no direction.
            direction = None
            retained_duration = duration
            retained_start = 0.0
            retained_end = duration

        if requested >= 100.0:
            segment = Segment(index=0, start=retained_start, end=retained_end, duration=round(retained_end - retained_start, 6))
            return SegmentPlan(
                source_duration=round(duration, 6),
                retention_percent=requested,
                target_duration=round(retained_duration, 6),
                selected_duration=round(retained_duration, 6),
                effective_percent=100.0,
                segments=(segment,),
                strategy=self.key,
                strategy_description=self.description,
                direction=direction or "unknown",
                cut_percent=cut_percent,
            )

        # When direction is specified, target is relative to the retained portion.
        if direction is not None:
            target = retained_duration * requested / 100.0
        else:
            target = duration * requested / 100.0

        # Avoid sub-frame slivers on extremely short retained portions.
        if target < MIN_CLIP_SECONDS:
            target = min(retained_duration, MIN_CLIP_SECONDS)
        target = min(target, retained_duration)

        count = choose_segment_count(target)
        # Guard against choosing more segments than can have a useful duration.
        count = min(count, max(1, int(target / MIN_CLIP_SECONDS)))
        segment_duration = target / count
        # Gap is calculated within the retained portion.
        gap = (retained_duration - target) / (count + 1)

        segments: list[Segment] = []
        for index in range(count):
            start = retained_start + gap + index * (segment_duration + gap)
            end = start + segment_duration
            if index == count - 1:
                # Keep the final boundary conservative against float drift.
                end = min(end, retained_end)
            # Clamp start to retained range.
            start = round(max(retained_start, start), 6)
            end = round(min(retained_end, end), 6)
            if end <= start:
                raise SelectionError("Could not create a usable segment for this video.")
            segments.append(
                Segment(
                    index=index,
                    start=start,
                    end=end,
                    duration=round(end - start, 6),
                )
            )

        # Micro-second rounding must never introduce overlaps or reorder clips.
        for previous, current in zip(segments, segments[1:]):
            if current.start < previous.end:
                raise SelectionError(
                    "Internal selection error: generated overlapping segments."
                )

        selected_duration = round(sum(segment.duration for segment in segments), 6)
        effective_percent = round(selected_duration / duration * 100.0, 3)
        return SegmentPlan(
            source_duration=round(duration, 6),
            retention_percent=requested,
            target_duration=round(target, 6),
            selected_duration=selected_duration,
            effective_percent=effective_percent,
            segments=tuple(segments),
            strategy=self.key,
            strategy_description=self.description,
            direction=direction or "unknown",
            cut_percent=cut_percent,
        )


class SelectionStrategy(Protocol):
    """Interface for future content-aware or user-curated selection strategies."""

    key: str
    description: str

    def select(self, duration: float, retention_percent: float) -> SegmentPlan: ...


DEFAULT_STRATEGY: SelectionStrategy = EvenDistributionStrategy()


def select_segments(
    duration: float,
    cut_percent: float | None = None,
    *,
    retention_percent: float | None = None,
    direction: str | None = None,
) -> SegmentPlan:
    """Build a segment plan using the even-distribution strategy by default.

    Provide ``cut_percent`` (portion to remove, 0-100) or ``retention_percent``
    (portion to keep, 0-100), but not both.
    """

    return (
        DEFAULT_STRATEGY.select(
            duration, cut_percent=cut_percent, retention_percent=retention_percent, direction=direction
        )
    )