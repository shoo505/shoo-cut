"""Command-line interface: local server, inspection, and headless cutting."""

from __future__ import annotations

import argparse
import json
import logging
import shlex
import signal
import sys
import threading
import time
import webbrowser
from pathlib import Path
from typing import Any, TextIO

from . import APP_NAME, __version__
from .errors import ExportCancelled, ShooCutError
from .files import check_output_directory, default_output_dir, suggest_output_dir
from .jobs import JobManager
from .pipeline import (
    ExportRequest,
    ProgressUpdate,
    build_dry_run_command,
    run_export,
)
from .probe import probe_video
from .system import detect_tools
from .util import comma_duration, format_duration, human_bytes, resolve_ffmpeg
from .web import make_server


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="shoo-cut",
        description=(
            "Shoo Auto Cut - select a local video, keep an evenly distributed "
            "percentage of it, and export a 1280x720 H.264 MP4."
        ),
    )
    parser.add_argument("--version", action="version", version=f"{APP_NAME} {__version__}")
    subparsers = parser.add_subparsers(dest="command", required=False)

    serve = subparsers.add_parser("serve", help="Start the local web interface.")
    serve.add_argument("--host", default="127.0.0.1", help="Bind address (default: 127.0.0.1).")
    serve.add_argument("--port", type=int, default=8765, help="Port; use 0 for an automatic port.")
    serve.add_argument("--open", action="store_true", help="Open the default browser.")
    serve.add_argument("-v", "--verbose", action="store_true", help="Show server debug logging.")
    serve.add_argument(
        "--encoder-preset",
        default="veryfast",
        help="x264 speed preset for exports (default: veryfast).",
    )
    serve.add_argument("--crf", type=int, default=23, help="H.264 quality (CRF 0-51, default 23).")
    serve.add_argument(
        "--threads",
        type=int, default=None,
        help="Limit FFmpeg threads (default: automatic).",
    )

    probe = subparsers.add_parser("probe", help="Inspect a local video without cutting it.")
    probe.add_argument("video", help="Path to the source video.")
    probe.add_argument("--json", action="store_true", help="Print machine-readable JSON.")

    cut = subparsers.add_parser("cut", help="Cut a video without starting the web interface.")
    cut.add_argument("video", help="Path to the source video.")
    cut.add_argument(
        "-o",
        "--output",
        help="Destination folder (default: beside the source in 'Shoo Auto Cut').",
    )
    # Five predefined cut percentages: portion of video to REMOVE
    # (not retained). Default 50% removal = 50% kept.
    _CUT_PERCENT_PRESETS = (25.0, 45.0, 50.0, 65.0, 75.0)
    cut.add_argument(
        "-p",
        "--percent",
        type=float,
        default=50.0,
        choices=_CUT_PERCENT_PRESETS,
        help="Cut percentage (portion to remove, default: 50). "
        "Presets: 25, 45, 50, 65, 75. "
        "The percentage represents the portion REMOVED, not retained.",
    )
    cut.add_argument(
        "--encoder-preset",
        default="veryfast",
        help="x264 speed preset (default: veryfast).",
    )
    cut.add_argument("--crf", type=int, default=23, help="H.264 quality (CRF 0-51, default 23).")
    cut.add_argument("--threads", type=int, default=None, help="Limit FFmpeg threads.")
    cut.add_argument(
        "--deep-validate",
        action="store_true",
        help="Decode the entire output during validation (slower).",
    )
    cut.add_argument(
        "--dry-run",
        action="store_true",
        help="Show the plan and FFmpeg command without writing files.",
    )
    cut.add_argument("--json", action="store_true", help="Print a JSON result.")
    cut.add_argument("-v", "--verbose", action="store_true", help="Show FFmpeg log lines.")
    cut.add_argument("-q", "--quiet", action="store_true", help="Suppress the progress display.")

    return parser


def _metadata_lines(metadata: Any) -> list[str]:
    audio = (
        f"{metadata.audio_codec}, {metadata.audio_channels or '?'} ch"
        if metadata.has_audio
        else "none"
    )
    fps = f"{metadata.fps:g} fps" if metadata.fps else "unknown fps"
    return [
        f"Source:    {metadata.path}",
        f"Duration:  {format_duration(metadata.duration)} ({comma_duration(metadata.duration)})",
        f"Video:     {metadata.width}x{metadata.height}, {fps}, {metadata.video_codec}",
        f"Audio:     {audio}",
        f"Container: {metadata.container}",
        f"Size:      {human_bytes(metadata.size_bytes)}",
    ]


def _plan_lines(plan: Any) -> list[str]:
    lines = [
        f"Retention: {plan.retention_percent:g}% requested, "
        f"{plan.effective_percent:g}% effective, "
        f"{format_duration(plan.selected_duration)} retained in {len(plan.segments)} segment(s)",
        f"Strategy:  {plan.strategy} (even distribution, not AI highlight detection)",
        "Segments:",
    ]
    for segment in plan.segments:
        lines.append(
            f"  {segment.index + 1:>2}. {format_duration(segment.start)} -> "
            f"{format_duration(segment.end)}  ({format_duration(segment.duration)})"
        )
    return lines


class _ProgressPrinter:
    def __init__(self, stream: TextIO, *, quiet: bool = False) -> None:
        self.stream = stream
        self.quiet = quiet
        self.tty = bool(getattr(stream, "isatty", lambda: False)())
        self._last_bucket = -1
        self._last_phase = ""
        self._last_width = 0

    def __call__(self, update: ProgressUpdate) -> None:
        if self.quiet:
            return
        percent = int(update.percent // 5) * 5
        bucket = percent // 5
        if self.tty:
            width = 28
            filled = int(round(width * update.percent / 100.0))
            bar = "#" * filled + "-" * (width - filled)
            message = update.message[:58]
            line = f"\r[{bar}] {update.percent:5.1f}%  {message:<60}"
            line = line[: self._last_width].ljust(self._last_width)
            self.stream.write(line)
            self.stream.flush()
            self._last_width = max(self._last_width, len(line))
        elif bucket != self._last_bucket or update.phase != self._last_phase:
            self.stream.write(f"[{update.percent:5.1f}%] {update.message}\n")
            self.stream.flush()
            self._last_bucket = bucket
            self._last_phase = update.phase

    def finish(self) -> None:
        if self.tty and not self.quiet:
            self.stream.write("\n")
            self.stream.flush()


def _run_probe(args: argparse.Namespace) -> int:
    try:
        metadata = probe_video(args.video)
    except ShooCutError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(metadata.to_dict(), indent=2, ensure_ascii=False))
    else:
        for line in _metadata_lines(metadata):
            print(line)
    return 0


def _run_cut(args: argparse.Namespace) -> int:
    try:
        resolve_ffmpeg()
        metadata = probe_video(args.video)
        output_dir = (
            check_output_directory(args.output)
            if args.output
            else suggest_output_dir(metadata.path)
        )
        # CLI --percent represents cut percentage (portion to remove).
        # Convert to retention percentage for the export request.
        retention = 100.0 - args.percent
        request = ExportRequest(
            input_path=metadata.path,
            output_dir=str(output_dir),
            retention_percent=retention,
            metadata=metadata,
            encoder_preset=args.encoder_preset,
            crf=args.crf,
            threads=args.threads,
            deep_validation=args.deep_validate,
        )
        if args.dry_run:
            _metadata, plan, command, target = build_dry_run_command(request)
            payload = {
                "dry_run": True,
                "input_metadata": metadata.to_dict(),
                "plan": plan.to_dict(),
                "output_dir": str(output_dir),
                "planned_output": target,
                "ffmpeg_command": command,
            }
            if args.json:
                print(json.dumps(payload, indent=2, ensure_ascii=False))
            else:
                for line in _metadata_lines(metadata) + _plan_lines(plan):
                    print(line)
                print(f"Output dir: {output_dir}")
                print("FFmpeg command (no files are written in dry-run mode):")
                print(shlex.join(command))
            return 0

        if not args.json and not args.quiet:
            for line in _metadata_lines(metadata) + _plan_lines(plan):
                print(line, file=sys.stderr)
            print("", file=sys.stderr)

        cancel_event = threading.Event()
        previous_handlers: dict[int, Any] = {}

        def handle_sigint(signum: int, frame: Any) -> None:
            if cancel_event.is_set():
                raise KeyboardInterrupt
            cancel_event.set()
            print("\nCancelling... waiting for FFmpeg to stop.", file=sys.stderr)

        if threading.current_thread() is threading.main_thread():
            for sig in (signal.SIGINT, signal.SIGTERM):
                try:
                    previous_handlers[sig] = signal.signal(sig, handle_sigint)
                except (OSError, ValueError):
                    pass

        printer = _ProgressPrinter(sys.stderr, quiet=args.quiet or args.json)
        logger = None
        if args.verbose and not args.json:
            def logger(message: str) -> None:
                printer.finish()
                print(f"log: {message}", file=sys.stderr)

        started = time.monotonic()
        try:
            result = run_export(
                request,
                on_progress=printer,
                on_log=logger,
                cancel_event=cancel_event,
            )
        finally:
            printer.finish()
            for sig, handler in previous_handlers.items():
                try:
                    signal.signal(sig, handler)
                except (OSError, ValueError):
                    pass

        if args.json:
            print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
        else:
            elapsed = time.monotonic() - started
            print(f"Done in {elapsed:.1f}s.")
            print(f"Output: {result.output_path}")
            output_meta = result.validation.metadata
            if output_meta is not None:
                audio = output_meta.audio_codec or "no audio"
                print(
                    f"Format: {output_meta.resolution_label}, {output_meta.video_codec}, "
                    f"{audio}, {format_duration(output_meta.duration)}"
                )
        return 0
    except ExportCancelled:
        print("Cancelled. No output file was saved.", file=sys.stderr)
        return 130
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130
    except ShooCutError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


def _run_serve(args: argparse.Namespace) -> int:
    tools = detect_tools()
    if not tools.get("ffmpeg_available") or not tools.get("ffprobe_available"):
        print(f"Warning: {tools.get('error') or 'FFmpeg/FFprobe not found.'}", file=sys.stderr)
    elif not tools.get("encoders_ok"):
        print(f"Warning: {tools.get('error')}", file=sys.stderr)

    try:
        manager = JobManager(encoder_preset=args.encoder_preset, crf=args.crf, threads=args.threads)
    except ShooCutError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    try:
        server = make_server(args.host, args.port, manager=manager)
    except (OSError, ShooCutError) as exc:
        print(f"Error: cannot start server on {args.host}:{args.port}: {exc}", file=sys.stderr)
        print("Try another port, for example: shoo-cut serve --port 8766", file=sys.stderr)
        manager.shutdown()
        return 1

    host, port = server.server_address[:2]
    display_host = "127.0.0.1" if host in ("0.0.0.0", "::") else host
    url = f"http://{display_host}:{port}/"
    print(f"{APP_NAME} {__version__}")
    print(f"Web interface: {url}")
    if host not in ("127.0.0.1", "localhost", "::1"):
        print(
            "Warning: the server is bound to a non-loopback address. Anyone who can "
            "reach this port can browse local files and start exports.",
            file=sys.stderr,
        )
    print(f"Logs: {manager.log_dir}")
    print(f"Default output folder: {default_output_dir()}")
    if tools.get("ffmpeg_version"):
        print(f"Engine: {tools['ffmpeg_version']}")
    print("Press Ctrl+C to stop.")
    sys.stdout.flush()

    if args.open:
        try:
            opened = webbrowser.open(url)
            if not opened:
                print("No browser was available to open automatically.", file=sys.stderr)
        except Exception:
            print("Could not open a browser automatically.", file=sys.stderr)

    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("\nStopping server...", file=sys.stderr)
    finally:
        server.server_close()
        manager.shutdown()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    arguments = list(sys.argv[1:] if argv is None else argv)
    if not arguments:
        arguments = ["serve"]
    args = parser.parse_args(arguments)
    if args.command in (None, "serve"):
        logging.basicConfig(
            level=logging.DEBUG if getattr(args, "verbose", False) else logging.WARNING,
            format="%(levelname)s %(name)s: %(message)s",
        )
        return _run_serve(args)
    if args.command == "probe":
        return _run_probe(args)
    if args.command == "cut":
        return _run_cut(args)
    parser.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())