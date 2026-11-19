"""Local HTTP API and static web interface."""

from __future__ import annotations

import json
import logging
import re
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlsplit

from .errors import (
    BusyError,
    FFmpegNotFoundError,
    FileAccessError,
    ProbeError,
    SelectionError,
    ShooCutError,
)
from .files import check_output_directory, list_directory, suggest_output_dir
from .jobs import JobManager
from .probe import probe_video
from .selection import select_segments
from .system import detect_tools, system_info
from .util import resolve_ffmpeg

LOGGER = logging.getLogger("shoo_cut.web")
STATIC_DIR = Path(__file__).with_name("static")
MAX_BODY_BYTES = 1_000_000
SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'self'; "
        "frame-ancestors 'none'"
    ),
}
STATIC_FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "application/javascript; charset=utf-8"),
    "/styles.css": ("styles.css", "text/css; charset=utf-8"),
    "/favicon.svg": ("favicon.svg", "image/svg+xml"),
}


class ApiError(Exception):
    def __init__(self, status: int, message: str, hint: str | None = None) -> None:
        super().__init__(message)
        self.status = int(status)
        self.message = message
        self.hint = hint


class ShooCutHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        handler_class: type[BaseHTTPRequestHandler],
        *,
        manager: JobManager,
    ) -> None:
        self.manager = manager
        super().__init__(server_address, handler_class)


class RequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ShooAutoCut/1.0"
    sys_version = ""

    @property
    def app(self) -> ShooCutHTTPServer:
        return self.server  # type: ignore[return-value]

    def log_message(self, fmt: str, *args: Any) -> None:
        LOGGER.debug("%s - %s", self.address_string(), fmt % args)

    # -- plumbing --------------------------------------------------------
    def _common_headers(self) -> dict[str, str]:
        headers = dict(SECURITY_HEADERS)
        headers["Cache-Control"] = "no-store"
        return headers

    def _send_bytes(
        self,
        status: int,
        body: bytes,
        content_type: str,
        *,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in self._common_headers().items():
            self.send_header(key, value)
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self._send_bytes(status, body, "application/json; charset=utf-8")

    def _error_payload(self, message: str, hint: str | None = None) -> dict[str, Any]:
        payload: dict[str, Any] = {"error": message}
        if hint:
            payload["hint"] = hint
        return payload

    def _read_json(self) -> dict[str, Any]:
        content_type = (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise ApiError(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "Expected an application/json request body.")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ApiError(HTTPStatus.BAD_REQUEST, "Invalid Content-Length header.") from None
        if length <= 0:
            raise ApiError(HTTPStatus.BAD_REQUEST, "A JSON request body is required.")
        if length > MAX_BODY_BYTES:
            raise ApiError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "Request body is too large.")
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise ApiError(HTTPStatus.BAD_REQUEST, "The request body is not valid JSON.") from None
        if not isinstance(data, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "The JSON request body must be an object.")
        return data

    @staticmethod
    def _require_string(data: dict[str, Any], key: str) -> str:
        value = data.get(key)
        if not isinstance(value, str) or not value.strip():
            raise ApiError(HTTPStatus.BAD_REQUEST, f"'{key}' is required and must be a non-empty string.")
        return value.strip()

    @staticmethod
    def _require_number(data: dict[str, Any], key: str) -> float:
        if key not in data:
            raise ApiError(HTTPStatus.BAD_REQUEST, f"'{key}' is required.")
        value = data[key]
        if isinstance(value, bool) or not isinstance(value, (int, float, str)):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"'{key}' must be a number.")
        try:
            return float(value)
        except (TypeError, ValueError):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"'{key}' must be a number.") from None

    # -- HTTP methods ----------------------------------------------------
    def do_GET(self) -> None:
        try:
            parsed = urlsplit(self.path)
            path = unquote(parsed.path)
            query = parse_qs(parsed.query, keep_blank_values=True)
            if path.startswith("/api/"):
                self._handle_api_get(path, query)
            else:
                self._handle_static(path)
        except ApiError as exc:
            self._send_json(exc.status, self._error_payload(exc.message, exc.hint))
        except ShooCutError as exc:
            self._send_json(self._status_for_error(exc), self._error_payload(str(exc)))
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception:
            LOGGER.exception("Unhandled GET error for %s", self.path)
            self._send_json(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                self._error_payload("Unexpected server error. Check the server logs."),
            )

    def do_POST(self) -> None:
        try:
            parsed = urlsplit(self.path)
            path = unquote(parsed.path)
            body = self._read_json()
            self._handle_api_post(path, body)
        except ApiError as exc:
            self._send_json(exc.status, self._error_payload(exc.message, exc.hint))
        except ShooCutError as exc:
            self._send_json(self._status_for_error(exc), self._error_payload(str(exc)))
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception:
            LOGGER.exception("Unhandled POST error for %s", self.path)
            self._send_json(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                self._error_payload("Unexpected server error. Check the server logs."),
            )

    @staticmethod
    def _status_for_error(exc: ShooCutError) -> int:
        if isinstance(exc, BusyError):
            return HTTPStatus.CONFLICT
        if isinstance(exc, FFmpegNotFoundError):
            return HTTPStatus.SERVICE_UNAVAILABLE
        if isinstance(exc, (ProbeError, FileAccessError, SelectionError)):
            return HTTPStatus.BAD_REQUEST
        return HTTPStatus.BAD_REQUEST

    # -- API -------------------------------------------------------------
    def _handle_api_get(self, path: str, query: dict[str, list[str]]) -> None:
        if path == "/api/health":
            self._send_json(
                HTTPStatus.OK,
                {"status": "ok", "service": "shoo-cut", "version": __import__("shoo_cut").__version__},
            )
            return
        if path == "/api/system":
            self._send_json(HTTPStatus.OK, system_info())
            return
        if path == "/api/fs":
            folder = (query.get("path") or [None])[0]
            show_hidden = (query.get("show_hidden") or ["0"])[0].lower() in {"1", "true", "yes"}
            self._send_json(HTTPStatus.OK, list_directory(folder, show_hidden=show_hidden))
            return
        if path == "/api/jobs":
            self._send_json(HTTPStatus.OK, {"jobs": self.app.manager.list_jobs(include_log=False)})
            return
        match = re.fullmatch(r"/api/jobs/([A-Za-z0-9_\-]+)", path)
        if match:
            job = self.app.manager.get_job(match.group(1))
            if job is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "Export job not found.")
            self._send_json(HTTPStatus.OK, {"job": job})
            return
        raise ApiError(HTTPStatus.NOT_FOUND, "Unknown API endpoint.")

    def _handle_api_post(self, path: str, body: dict[str, Any]) -> None:
        if path == "/api/probe":
            requested = self._require_string(body, "path")
            metadata = probe_video(requested)
            self._send_json(
                HTTPStatus.OK,
                {
                    "metadata": metadata.to_dict(),
                    "suggested_output_dir": str(suggest_output_dir(metadata.path)),
                },
            )
            return

        if path == "/api/plan":
            duration = self._require_number(body, "duration")
            # Support new cut_percent parameter (portion to remove, 0-100)
            # or legacy retention_percent parameter (portion to keep, 0-100)
            cut = body.get("cut_percent")
            retention = body.get("retention_percent")
            if cut is not None and retention is not None:
                raise Exception("Provide either cut_percent or retention_percent, not both.")
            if cut is not None:
                plan = select_segments(duration, cut_percent=cut)
            elif retention is not None:
                plan = select_segments(duration, retention_percent=retention)
            else:
                # Default to 50% cut (the application default)
                plan = select_segments(duration, cut_percent=50.0)
            self._send_json(
                HTTPStatus.OK,
                {
                    "plan": plan.to_dict(),
                    "note": (
                        "Deterministic evenly distributed selection; this is not "
                        "content-aware highlight detection."
                    ),
                },
            )
            return

        if path == "/api/jobs":
            input_path = self._require_string(body, "input_path")
            retention = self._require_number(body, "retention_percent")
            output_dir_value = body.get("output_dir")
            if output_dir_value is not None and (
                not isinstance(output_dir_value, str) or not output_dir_value.strip()
            ):
                raise ApiError(HTTPStatus.BAD_REQUEST, "'output_dir' must be a non-empty string.")
            tools = detect_tools()
            if not tools.get("ffmpeg_available") or not tools.get("ffprobe_available"):
                raise ApiError(
                    HTTPStatus.SERVICE_UNAVAILABLE,
                    tools.get("error") or "FFmpeg/FFprobe are not available.",
                    "Install FFmpeg and restart the application.",
                )
            if not tools.get("encoders_ok"):
                raise ApiError(
                    HTTPStatus.SERVICE_UNAVAILABLE,
                    tools.get("error") or "FFmpeg lacks the required libx264/AAC encoders.",
                )
            metadata = probe_video(input_path)
            output_dir = output_dir_value.strip() if isinstance(output_dir_value, str) else str(
                suggest_output_dir(metadata.path)
            )
            output_dir = str(check_output_directory(output_dir))
            job = self.app.manager.create_job(
                metadata.path,
                output_dir,
                retention,
                metadata=metadata,
            )
            self._send_json(HTTPStatus.CREATED, {"job": job})
            return

        match = re.fullmatch(r"/api/jobs/([A-Za-z0-9_\-]+)/cancel", path)
        if match:
            job = self.app.manager.cancel_job(match.group(1))
            if job is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "Export job not found.")
            self._send_json(HTTPStatus.OK, {"job": job})
            return

        raise ApiError(HTTPStatus.NOT_FOUND, "Unknown API endpoint.")

    # -- static files ----------------------------------------------------
    def _handle_static(self, path: str) -> None:
        mapping = STATIC_FILES.get(path)
        if mapping is None:
            body = b"Not found. The Shoo Auto Cut interface is available at /.\n"
            self._send_bytes(HTTPStatus.NOT_FOUND, body, "text/plain; charset=utf-8")
            return
        filename, content_type = mapping
        file_path = STATIC_DIR / filename
        try:
            body = file_path.read_bytes()
        except OSError:
            LOGGER.exception("Static file missing: %s", file_path)
            self._send_bytes(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                b"Static interface file is missing from the installation.\n",
                "text/plain; charset=utf-8",
            )
            return
        self._send_bytes(HTTPStatus.OK, body, content_type)


def make_server(
    host: str = "127.0.0.1",
    port: int = 8765,
    *,
    manager: JobManager | None = None,
) -> ShooCutHTTPServer:
    if not Path(STATIC_DIR / "index.html").is_file():
        raise ShooCutError(f"Web interface files are missing from {STATIC_DIR}")
    # Fail early with a clear error if FFmpeg is unavailable. The server can
    # still be started by tests after installing the tools.
    try:
        resolve_ffmpeg()
    except FFmpegNotFoundError:
        # Do not prevent the UI from loading diagnostics; jobs return 503.
        pass
    job_manager = manager or JobManager()
    return ShooCutHTTPServer((host, port), RequestHandler, manager=job_manager)
