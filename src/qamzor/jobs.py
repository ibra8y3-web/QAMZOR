"""Persistent job centre for cancellable and pausable archive creation."""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from typing import Callable

from .format import QamzorError, create_archive


class JobCancelledError(QamzorError):
    """Raised when a user cancels an archive job at a safe checkpoint."""


class JobControl:
    def __init__(self, on_progress: Callable[[dict], None] | None = None,
                 on_state: Callable[[str], None] | None = None):
        self._pause_requested = threading.Event()
        self._resume_event = threading.Event()
        self._resume_event.set()
        self._cancel_requested = threading.Event()
        self._on_progress = on_progress
        self._on_state = on_state

    def pause(self) -> None:
        self._pause_requested.set()

    def resume(self) -> None:
        self._pause_requested.clear()
        self._resume_event.set()

    def cancel(self) -> None:
        self._cancel_requested.set()
        self._resume_event.set()

    def checkpoint(self, progress: dict) -> None:
        if self._cancel_requested.is_set():
            raise JobCancelledError("Archive job was cancelled")
        if self._on_progress:
            self._on_progress(dict(progress))
        if self._pause_requested.is_set():
            self._resume_event.clear()
            if self._on_state:
                self._on_state("paused")
            while self._pause_requested.is_set() and not self._cancel_requested.is_set():
                self._resume_event.wait(0.2)
            if self._cancel_requested.is_set():
                raise JobCancelledError("Archive job was cancelled while paused")
            if self._on_state:
                self._on_state("running")


class JobCenter:
    """Thread-backed archive job service with durable, secret-free job records.

    Jobs pause/cancel at archive chunk boundaries. After a process restart, an
    unfinished job is marked interrupted; source files are never silently
    resumed because their contents may have changed while the app was closed.
    """
    def __init__(self, state_directory: os.PathLike | str, *, workers: int = 2):
        if not isinstance(workers, int) or isinstance(workers, bool) or not 1 <= workers <= 8:
            raise QamzorError("Job worker count must be between 1 and 8")
        self.root = Path(state_directory).expanduser().absolute()
        self.root.mkdir(parents=True, exist_ok=True)
        if self.root.is_symlink():
            raise QamzorError("Job state directory cannot be a symbolic link")
        try:
            os.chmod(self.root, 0o700)
        except OSError:
            pass
        self._lock = threading.RLock()
        self._executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="qamzor-job")
        self._controls: dict[str, JobControl] = {}
        self._futures: dict[str, Future] = {}
        self._records: dict[str, dict] = {}
        for path in self.root.glob("*.json"):
            if path.is_symlink() or not path.is_file():
                continue
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(record, dict) and isinstance(record.get("id"), str):
                    if record.get("status") in {"queued", "running", "paused", "pause_requested"}:
                        record["status"] = "interrupted"
                        record["error"] = "Process ended before the job completed; inspect sources and submit again"
                        self._save(record)
                    self._records[record["id"]] = record
            except (OSError, ValueError, json.JSONDecodeError):
                continue

    def _save(self, record: dict) -> None:
        path = self.root / f"{record['id']}.json"
        temp = self.root / f".{record['id']}.{uuid.uuid4().hex}.tmp"
        data = (json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp, path)
        finally:
            try:
                temp.unlink()
            except FileNotFoundError:
                pass

    def _update(self, job_id: str, **changes) -> None:
        with self._lock:
            record = self._records[job_id]
            record.update(changes)
            record["updated_at"] = time.time()
            self._save(record)

    def submit_create_archive(self, archive_path, sources, **options) -> str:
        if "job_control" in options:
            raise QamzorError("job_control is managed by JobCenter")
        sources = list(sources)
        if not sources:
            raise QamzorError("At least one source is required for a create job")
        job_id = uuid.uuid4().hex
        secret_markers = ("password", "secret", "token", "credential")
        safe_options = {key: value for key, value in options.items()
                        if not any(marker in key.casefold() for marker in secret_markers)}
        record = {
            "id": job_id, "kind": "create_archive", "status": "queued",
            "archive": str(Path(archive_path).expanduser().absolute()),
            "sources": [str(Path(item).expanduser().absolute()) for item in sources],
            "options": safe_options, "progress": {"phase": "queued", "completed_bytes": 0, "total_bytes": 0},
            "created_at": time.time(), "updated_at": time.time(), "error": None, "result": None,
        }
        with self._lock:
            self._records[job_id] = record
            self._save(record)
            control = JobControl(
                on_progress=lambda value: self._update(job_id, progress=value),
                on_state=lambda value: self._update(job_id, status=value),
            )
            self._controls[job_id] = control
            future = self._executor.submit(self._run_create, job_id, control, archive_path, sources, options)
            self._futures[job_id] = future
        return job_id

    def _run_create(self, job_id, control, archive_path, sources, options):
        self._update(job_id, status="running")
        try:
            result = create_archive(archive_path, sources, job_control=control, **options)
            self._update(job_id, status="completed", result=result, error=None)
        except JobCancelledError as exc:
            self._update(job_id, status="cancelled", error=str(exc))
        except Exception as exc:
            self._update(job_id, status="failed", error=f"{type(exc).__name__}: {exc}")
        finally:
            with self._lock:
                self._controls.pop(job_id, None)

    def get(self, job_id: str) -> dict:
        with self._lock:
            if job_id not in self._records:
                raise QamzorError(f"Unknown QAMZOR job: {job_id}")
            return json.loads(json.dumps(self._records[job_id]))

    def list(self) -> list[dict]:
        with self._lock:
            records = [json.loads(json.dumps(record)) for record in self._records.values()]
        return sorted(records, key=lambda item: item["created_at"], reverse=True)

    def pause(self, job_id: str) -> dict:
        with self._lock:
            control = self._controls.get(job_id)
            if control is None or self._records.get(job_id, {}).get("status") not in {"queued", "running", "pause_requested", "paused"}:
                raise QamzorError("Job is not currently running")
            control.pause()
            self._update(job_id, status="pause_requested")
        return self.get(job_id)

    def resume(self, job_id: str) -> dict:
        with self._lock:
            control = self._controls.get(job_id)
            if control is None or self._records.get(job_id, {}).get("status") not in {"queued", "running", "pause_requested", "paused"}:
                raise QamzorError("Job is not currently running")
            control.resume()
            self._update(job_id, status="running")
        return self.get(job_id)

    def cancel(self, job_id: str) -> dict:
        with self._lock:
            control = self._controls.get(job_id)
            if control is None or self._records.get(job_id, {}).get("status") not in {"queued", "running", "pause_requested", "paused"}:
                raise QamzorError("Job is not currently running")
            control.cancel()
        return self.get(job_id)

    def wait(self, job_id: str, timeout: float | None = None) -> dict:
        future = self._futures.get(job_id)
        if future is None:
            return self.get(job_id)
        future.result(timeout=timeout)
        return self.get(job_id)

    def shutdown(self, *, wait: bool = True) -> None:
        with self._lock:
            for control in self._controls.values():
                control.cancel()
        self._executor.shutdown(wait=wait, cancel_futures=False)
