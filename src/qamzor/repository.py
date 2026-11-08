"""Local content-addressed snapshot repository for QAMZOR.

Snapshots are immutable full-tree manifests. Unchanged file contents are
reused by SHA-256 object identity across snapshots, so each new snapshot only
writes previously unseen file objects. A snapshot is therefore logically
complete while storage updates are incremental.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import struct
import tempfile
import secrets
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Iterable, Iterator, Optional

from .format import (
    ArchiveEntry,
    FormatError,
    IntegrityError,
    QamzorError,
    SafetyError,
    _validate_archive_path,
    _validate_entry_set,
    _walk_sources,
)

REPOSITORY_FORMAT = "QAMZOR-REPOSITORY"
REPOSITORY_VERSION = 1
SNAPSHOT_FORMAT = "QAMZOR-SNAPSHOT"
SNAPSHOT_VERSION = 1
MAX_CATALOG_SIZE = 32 * 1024 * 1024
MAX_SNAPSHOT_SIZE = 64 * 1024 * 1024
MAX_FILES_PER_SNAPSHOT = 1_000_000
DELTA_MAGIC = b"QZDL\r\n\x1a\n"
DELTA_HEADER = struct.Struct(">8sHHIQII")
DELTA_MAJOR = 1
DELTA_MINOR = 0
IO_SIZE = 1024 * 1024
_DIGEST_RE = re.compile(r"[0-9a-f]{64}")
_SNAPSHOT_ID_RE = re.compile(r"[0-9a-f]{32}")


class RepositoryError(QamzorError):
    """Invalid repository state or operation."""


class RepositoryIntegrityError(IntegrityError):
    """A repository catalog, snapshot or object is corrupt."""


def _json_no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def _encode_json(value: dict) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8") + b"\n"


def _atomic_write(path: Path, data: bytes, *, overwrite: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        if overwrite:
            os.replace(temporary, path)
        else:
            try:
                os.link(temporary, path)
            except FileExistsError as exc:
                raise RepositoryError(f"Repository object already exists: {path.name}") from exc
            os.unlink(temporary)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _hash_path(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as source:
        while True:
            block = source.read(IO_SIZE)
            if not block:
                break
            total += len(block)
            digest.update(block)
    return digest.hexdigest(), total


def _ensure_no_symlink_components(path: Path) -> None:
    current = path
    while True:
        try:
            metadata = current.lstat()
        except FileNotFoundError:
            metadata = None
        if metadata is not None and stat.S_ISLNK(metadata.st_mode):
            raise SafetyError(f"Symbolic-link path component is not allowed: {current}")
        if current.parent == current:
            break
        current = current.parent


class Repository:
    """Manage immutable snapshots and a shared SHA-256 object store."""

    def __init__(self, root: os.PathLike | str, *, create: bool = False):
        self.root = Path(root).expanduser().absolute()
        if self.root.exists() and self.root.is_symlink():
            raise SafetyError("Repository root cannot be a symbolic link")
        if create:
            self.initialize()
        self._require_layout()

    @property
    def catalog_path(self) -> Path:
        return self.root / "catalog.json"

    @property
    def objects_path(self) -> Path:
        return self.root / "objects"

    @property
    def snapshots_path(self) -> Path:
        return self.root / "snapshots"

    def initialize(self) -> dict:
        _ensure_no_symlink_components(self.root)
        self.root.mkdir(parents=True, exist_ok=True)
        _ensure_no_symlink_components(self.root)
        self.objects_path.mkdir(exist_ok=True)
        self.snapshots_path.mkdir(exist_ok=True)
        for directory in (self.objects_path, self.snapshots_path):
            if directory.is_symlink() or not directory.is_dir():
                raise SafetyError(f"Repository component must be a real directory: {directory}")
        if not self.catalog_path.exists():
            catalog = {"format": REPOSITORY_FORMAT, "version": REPOSITORY_VERSION, "snapshots": []}
            _atomic_write(self.catalog_path, _encode_json(catalog), overwrite=False)
        self._read_catalog()
        return {"repository": str(self.root), "initialized": True}

    def _require_layout(self) -> None:
        _ensure_no_symlink_components(self.root)
        if self.root.is_symlink() or not self.root.is_dir():
            raise RepositoryError(f"Repository does not exist or is not a directory: {self.root}")
        for path in (self.objects_path, self.snapshots_path, self.catalog_path):
            if path.is_symlink():
                raise SafetyError(f"Repository component cannot be a symbolic link: {path}")
        if not self.objects_path.is_dir() or not self.snapshots_path.is_dir() or not self.catalog_path.is_file():
            raise RepositoryError("Repository layout is incomplete; run `qz repo init` first")

    @contextmanager
    def _write_lock(self) -> Iterator[None]:
        lock_path = self.root / ".qamzor-write.lock"
        try:
            descriptor = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError as exc:
            raise RepositoryError("Repository is locked by another writer; check for a stale .qamzor-write.lock") from exc
        try:
            with os.fdopen(descriptor, "w", encoding="ascii") as lock:
                lock.write(f"pid={os.getpid()}\n")
                lock.flush()
                os.fsync(lock.fileno())
            yield
        finally:
            try:
                lock_path.unlink()
            except FileNotFoundError:
                pass

    def _read_catalog(self) -> dict:
        if self.catalog_path.is_symlink() or not self.catalog_path.is_file():
            raise RepositoryError("Repository catalog is missing or unsafe")
        if self.catalog_path.stat().st_size > MAX_CATALOG_SIZE:
            raise RepositoryIntegrityError("Repository catalog exceeds its size limit")
        try:
            catalog = json.loads(self.catalog_path.read_text(encoding="utf-8"), object_pairs_hook=_json_no_duplicate_keys)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
            raise RepositoryIntegrityError("Repository catalog is not valid unambiguous JSON") from exc
        if not isinstance(catalog, dict) or catalog.get("format") != REPOSITORY_FORMAT or catalog.get("version") != REPOSITORY_VERSION:
            raise RepositoryIntegrityError("Unsupported repository catalog format")
        records = catalog.get("snapshots")
        if not isinstance(records, list):
            raise RepositoryIntegrityError("Repository snapshot index is invalid")
        seen = set()
        for record in records:
            if not isinstance(record, dict):
                raise RepositoryIntegrityError("Repository snapshot index entry is invalid")
            snapshot_id = record.get("id")
            if not isinstance(snapshot_id, str) or not _SNAPSHOT_ID_RE.fullmatch(snapshot_id) or snapshot_id in seen:
                raise RepositoryIntegrityError("Repository snapshot ID is invalid or duplicated")
            seen.add(snapshot_id)
            if not isinstance(record.get("manifest_sha256"), str) or not _DIGEST_RE.fullmatch(record["manifest_sha256"]):
                raise RepositoryIntegrityError("Repository snapshot manifest digest is invalid")
            for field in ("entries", "files", "total_bytes"):
                value = record.get(field)
                if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                    raise RepositoryIntegrityError(f"Repository snapshot {field} value is invalid")
            if not isinstance(record.get("created_at"), str) or not isinstance(record.get("message"), str):
                raise RepositoryIntegrityError("Repository snapshot metadata is invalid")
            parent = record.get("parent_id")
            if parent is not None and (not isinstance(parent, str) or not _SNAPSHOT_ID_RE.fullmatch(parent)):
                raise RepositoryIntegrityError("Repository parent snapshot ID is invalid")
        return catalog

    def _write_catalog(self, catalog: dict) -> None:
        _atomic_write(self.catalog_path, _encode_json(catalog), overwrite=True)

    def _object_path(self, digest: str) -> Path:
        if not _DIGEST_RE.fullmatch(digest):
            raise RepositoryIntegrityError("Invalid object digest")
        shard = self.objects_path / digest[:2]
        if shard.is_symlink():
            raise SafetyError(f"Object-store shard cannot be a symbolic link: {shard}")
        if shard.exists() and not shard.is_dir():
            raise RepositoryIntegrityError(f"Object-store shard is not a directory: {shard}")
        return shard / f"{digest}.blob"

    def _store_file(self, source_path: Path) -> tuple[str, int, bool]:
        """Copy and hash one regular file; publish it once by content hash."""
        object_dir = self.objects_path
        before_lstat = source_path.lstat()
        if stat.S_ISLNK(before_lstat.st_mode) or not stat.S_ISREG(before_lstat.st_mode):
            raise SafetyError(f"Repository input must be a regular non-symlink file: {source_path}")
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(source_path, flags)
        temporary = None
        try:
            before = os.fstat(descriptor)
            if not stat.S_ISREG(before.st_mode):
                raise SafetyError(f"Repository input changed to a non-regular file: {source_path}")
            fd, temporary = tempfile.mkstemp(prefix=".object.", suffix=".tmp", dir=str(object_dir))
            digest = hashlib.sha256()
            total = 0
            with os.fdopen(descriptor, "rb", closefd=False) as source, os.fdopen(fd, "wb") as target:
                while True:
                    block = source.read(IO_SIZE)
                    if not block:
                        break
                    digest.update(block)
                    total += len(block)
                    target.write(block)
                target.flush()
                os.fsync(target.fileno())
            after = os.fstat(descriptor)
            if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino) or total != before.st_size:
                raise RepositoryError(f"Source file changed while snapshotting: {source_path}")
            hex_digest = digest.hexdigest()
            target_path = self._object_path(hex_digest)
            target_path.parent.mkdir(parents=True, exist_ok=True)
            if target_path.parent.is_symlink() or not target_path.parent.is_dir():
                raise SafetyError(f"Unsafe object-store shard: {target_path.parent}")
            if target_path.is_symlink():
                raise SafetyError(f"Object-store object cannot be a symbolic link: {target_path}")
            if target_path.exists():
                if not target_path.is_file():
                    raise RepositoryIntegrityError(f"Unsafe object store path: {target_path}")
                existing_digest, existing_size = _hash_path(target_path)
                if existing_digest != hex_digest or existing_size != total:
                    raise RepositoryIntegrityError(f"Existing object is corrupt: {hex_digest}")
                os.unlink(temporary)
                temporary = None
                return hex_digest, total, False
            try:
                os.link(temporary, target_path)
            except FileExistsError:
                existing_digest, existing_size = _hash_path(target_path)
                if existing_digest != hex_digest or existing_size != total:
                    raise RepositoryIntegrityError(f"Concurrent object publication produced an invalid object: {hex_digest}")
                return hex_digest, total, False
            return hex_digest, total, True
        finally:
            os.close(descriptor)
            if temporary and os.path.exists(temporary):
                os.unlink(temporary)

    def _load_snapshot(self, snapshot_id: str, catalog: Optional[dict] = None) -> tuple[dict, list[dict]]:
        if not isinstance(snapshot_id, str) or not _SNAPSHOT_ID_RE.fullmatch(snapshot_id):
            raise RepositoryError("Snapshot ID must be a 32-character lowercase hexadecimal ID")
        catalog = catalog or self._read_catalog()
        record = next((item for item in catalog["snapshots"] if item["id"] == snapshot_id), None)
        if record is None:
            raise RepositoryError(f"Snapshot does not exist: {snapshot_id}")
        manifest_path = self.snapshots_path / f"{snapshot_id}.json"
        if manifest_path.is_symlink() or not manifest_path.is_file():
            raise RepositoryIntegrityError(f"Snapshot manifest is missing or unsafe: {snapshot_id}")
        if manifest_path.stat().st_size > MAX_SNAPSHOT_SIZE:
            raise RepositoryIntegrityError("Snapshot manifest exceeds its size limit")
        encoded = manifest_path.read_bytes()
        if hashlib.sha256(encoded).hexdigest() != record["manifest_sha256"]:
            raise RepositoryIntegrityError(f"Snapshot manifest digest mismatch: {snapshot_id}")
        try:
            manifest = json.loads(encoded.decode("utf-8"), object_pairs_hook=_json_no_duplicate_keys)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
            raise RepositoryIntegrityError("Snapshot manifest is not valid unambiguous JSON") from exc
        if not isinstance(manifest, dict) or manifest.get("format") != SNAPSHOT_FORMAT or manifest.get("version") != SNAPSHOT_VERSION or manifest.get("snapshot_id") != snapshot_id:
            raise RepositoryIntegrityError("Snapshot manifest format or identity is invalid")
        entries = manifest.get("entries")
        if not isinstance(entries, list) or len(entries) != record["entries"] or len(entries) > MAX_FILES_PER_SNAPSHOT:
            raise RepositoryIntegrityError("Snapshot entry table is inconsistent")
        validated_entries: list[dict] = []
        archive_entries: list[ArchiveEntry] = []
        file_count = 0
        total_bytes = 0
        for item in entries:
            if not isinstance(item, dict):
                raise RepositoryIntegrityError("Snapshot entry is invalid")
            path = item.get("path")
            kind = item.get("type")
            size = item.get("size")
            if not isinstance(path, str) or not isinstance(kind, str) or not isinstance(size, int) or isinstance(size, bool) or size < 0:
                raise RepositoryIntegrityError("Snapshot path, type or size is invalid")
            _validate_archive_path(path)
            if kind == "directory":
                if size != 0 or set(item) != {"path", "type", "size"}:
                    raise RepositoryIntegrityError("Snapshot directory entry is invalid")
                archive_entries.append(ArchiveEntry(path, "directory", 0))
                validated_entries.append({"path": path, "type": kind, "size": 0})
            elif kind == "file":
                digest = item.get("sha256")
                if not isinstance(digest, str) or not _DIGEST_RE.fullmatch(digest) or set(item) != {"path", "type", "size", "sha256"}:
                    raise RepositoryIntegrityError("Snapshot file entry digest or fields are invalid")
                archive_entries.append(ArchiveEntry(path, "file", size))
                validated_entries.append({"path": path, "type": kind, "size": size, "sha256": digest})
                file_count += 1
                total_bytes += size
            else:
                raise RepositoryIntegrityError("Unsupported snapshot entry type")
        try:
            _validate_entry_set(archive_entries)
        except QamzorError as exc:
            raise RepositoryIntegrityError(f"Snapshot paths are invalid: {exc}") from exc
        if file_count != record["files"] or total_bytes != record["total_bytes"]:
            raise RepositoryIntegrityError("Snapshot totals do not match repository catalog")
        return manifest, validated_entries

    def create_snapshot(self, sources: Iterable[os.PathLike | str], *, message: str = "") -> dict:
        if not isinstance(message, str) or len(message) > 4096:
            raise RepositoryError("Snapshot message must be text of at most 4096 characters")
        source_values = list(sources)
        if not source_values:
            raise RepositoryError("At least one source path is required")
        with self._write_lock():
            catalog = self._read_catalog()
            # Exclude the repository itself even when a parent directory is a source.
            scanned = _walk_sources(source_values, self.root / ".qamzor-repository-exclusion")
            entries: list[dict] = []
            created_objects = 0
            reused_objects = 0
            total_bytes = 0
            file_count = 0
            for archive_path, source_path, kind in scanned:
                try:
                    source_path.resolve(strict=True).relative_to(self.root.resolve(strict=True))
                    inside_repository = True
                except ValueError:
                    inside_repository = False
                except FileNotFoundError:
                    inside_repository = False
                if inside_repository:
                    continue
                if kind == "directory":
                    entries.append({"path": archive_path, "type": "directory", "size": 0})
                else:
                    digest, size, created = self._store_file(source_path)
                    entries.append({"path": archive_path, "type": "file", "size": size, "sha256": digest})
                    total_bytes += size
                    file_count += 1
                    if created:
                        created_objects += 1
                    else:
                        reused_objects += 1
            if not entries:
                raise RepositoryError("No source entries remain after excluding the repository path")
            if len(entries) > MAX_FILES_PER_SNAPSHOT:
                raise RepositoryError("Snapshot contains too many entries")
            archive_entries = [ArchiveEntry(item["path"], item["type"], item["size"]) for item in entries]
            _validate_entry_set(archive_entries)
            snapshot_id = secrets.token_hex(16)
            parent_id = catalog["snapshots"][-1]["id"] if catalog["snapshots"] else None
            created_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
            manifest = {
                "format": SNAPSHOT_FORMAT,
                "version": SNAPSHOT_VERSION,
                "snapshot_id": snapshot_id,
                "parent_snapshot_id": parent_id,
                "created_at": created_at,
                "message": message,
                "entries": entries,
            }
            encoded_manifest = _encode_json(manifest)
            if len(encoded_manifest) > MAX_SNAPSHOT_SIZE:
                raise RepositoryError("Snapshot manifest exceeds its size limit")
            manifest_path = self.snapshots_path / f"{snapshot_id}.json"
            _atomic_write(manifest_path, encoded_manifest, overwrite=False)
            record = {
                "id": snapshot_id,
                "parent_id": parent_id,
                "created_at": created_at,
                "message": message,
                "manifest_sha256": hashlib.sha256(encoded_manifest).hexdigest(),
                "entries": len(entries),
                "files": file_count,
                "total_bytes": total_bytes,
            }
            catalog["snapshots"].append(record)
            self._write_catalog(catalog)
            return {
                **record,
                "new_objects": created_objects,
                "reused_objects": reused_objects,
                "logical_bytes": total_bytes,
            }

    def list_snapshots(self) -> list[dict]:
        catalog = self._read_catalog()
        return [dict(item) for item in catalog["snapshots"]]

    def verify(self, snapshot_id: Optional[str] = None) -> dict:
        catalog = self._read_catalog()
        selected = [snapshot_id] if snapshot_id else [item["id"] for item in catalog["snapshots"]]
        verified_objects: set[str] = set()
        files_checked = 0
        bytes_checked = 0
        for current_id in selected:
            _, entries = self._load_snapshot(current_id, catalog)
            for entry in entries:
                if entry["type"] != "file":
                    continue
                digest = entry["sha256"]
                if digest in verified_objects:
                    continue
                path = self._object_path(digest)
                if path.is_symlink() or not path.is_file():
                    raise RepositoryIntegrityError(f"Repository object is missing or unsafe: {digest}")
                actual_digest, actual_size = _hash_path(path)
                if actual_digest != digest or actual_size != entry["size"]:
                    raise RepositoryIntegrityError(f"Repository object failed integrity verification: {digest}")
                verified_objects.add(digest)
                files_checked += 1
                bytes_checked += actual_size
        return {
            "valid": True,
            "snapshots_checked": len(selected),
            "unique_objects_checked": len(verified_objects),
            "bytes_checked": bytes_checked,
        }

    def restore_snapshot(
        self,
        snapshot_id: str,
        destination: os.PathLike | str,
        *,
        overwrite: bool = False,
        max_total_bytes: Optional[int] = None,
        max_files: Optional[int] = None,
    ) -> dict:
        catalog = self._read_catalog()
        _, entries = self._load_snapshot(snapshot_id, catalog)
        files = [entry for entry in entries if entry["type"] == "file"]
        total_bytes = sum(item["size"] for item in files)
        if max_total_bytes is not None and (not isinstance(max_total_bytes, int) or isinstance(max_total_bytes, bool) or max_total_bytes < 0):
            raise RepositoryError("max_total_bytes must be a non-negative integer")
        if max_files is not None and (not isinstance(max_files, int) or isinstance(max_files, bool) or max_files < 0):
            raise RepositoryError("max_files must be a non-negative integer")
        if max_total_bytes is not None and total_bytes > max_total_bytes:
            raise SafetyError(f"Restore exceeds byte limit ({total_bytes} > {max_total_bytes})")
        if max_files is not None and len(files) > max_files:
            raise SafetyError(f"Restore exceeds file-count limit ({len(files)} > {max_files})")
        # Verify every required object before creating the destination tree.
        for item in files:
            blob = self._object_path(item["sha256"])
            if blob.is_symlink() or not blob.is_file():
                raise RepositoryIntegrityError(f"Repository object is missing or unsafe: {item['sha256']}")
            actual_digest, actual_size = _hash_path(blob)
            if actual_digest != item["sha256"] or actual_size != item["size"]:
                raise RepositoryIntegrityError(f"Repository object failed integrity verification: {item['sha256']}")
        root = Path(destination).expanduser().absolute()
        _ensure_no_symlink_components(root)
        if root.exists() and not root.is_dir():
            raise SafetyError("Restore destination exists and is not a directory")
        # Preflight every destination before creating the output tree.
        for item in entries:
            target = root.joinpath(*PurePosixPath(item["path"]).parts)
            _ensure_no_symlink_components(target)
            if target.exists() or target.is_symlink():
                if target.is_symlink():
                    raise SafetyError(f"Restore target cannot be a symbolic link: {target}")
                expected_directory = item["type"] == "directory"
                if expected_directory and not target.is_dir():
                    raise SafetyError(f"Restore directory collides with a non-directory: {target}")
                if not expected_directory:
                    if not target.is_file():
                        raise SafetyError(f"Restore target is not a regular file: {target}")
                    if not overwrite:
                        raise RepositoryError(f"Restore target already exists: {target}")
        root.mkdir(parents=True, exist_ok=True)
        directories = [item for item in entries if item["type"] == "directory"]
        for item in sorted(directories, key=lambda value: (value["path"].count("/"), value["path"])):
            target_dir = root.joinpath(*PurePosixPath(item["path"]).parts)
            _ensure_no_symlink_components(target_dir)
            if target_dir.exists() and not target_dir.is_dir():
                raise SafetyError(f"Restore directory collides with a non-directory: {target_dir}")
            target_dir.mkdir(parents=True, exist_ok=True)
        restored = []
        for item in files:
            target = root.joinpath(*PurePosixPath(item["path"]).parts)
            _ensure_no_symlink_components(target)
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                if target.is_symlink() or not target.is_file():
                    raise SafetyError(f"Restore target is not a regular file: {target}")
                if not overwrite:
                    raise RepositoryError(f"Restore target already exists: {target}")
            blob = self._object_path(item["sha256"])
            fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=str(target.parent))
            try:
                digest = hashlib.sha256()
                written = 0
                with os.fdopen(fd, "wb") as output, blob.open("rb") as source:
                    while True:
                        block = source.read(IO_SIZE)
                        if not block:
                            break
                        digest.update(block)
                        written += len(block)
                        output.write(block)
                    output.flush()
                    os.fsync(output.fileno())
                if written != item["size"] or digest.hexdigest() != item["sha256"]:
                    raise RepositoryIntegrityError(f"Repository object changed during restore: {item['sha256']}")
                if overwrite:
                    os.replace(temporary, target)
                else:
                    try:
                        os.link(temporary, target)
                    except FileExistsError as exc:
                        raise RepositoryError(f"Restore target already exists: {target}") from exc
                    os.unlink(temporary)
                restored.append(item["path"])
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        return {"snapshot_id": snapshot_id, "restored_files": len(restored), "restored_bytes": total_bytes, "destination": str(root)}

    def prune(self, *, keep_last: int = 10, dry_run: bool = True) -> dict:
        if not isinstance(keep_last, int) or isinstance(keep_last, bool) or keep_last < 1:
            raise RepositoryError("keep_last must be an integer of at least 1")
        with self._write_lock():
            catalog = self._read_catalog()
            records = catalog["snapshots"]
            kept = records[-keep_last:]
            removed = records[:-keep_last]
            referenced: set[str] = set()
            for record in kept:
                _, entries = self._load_snapshot(record["id"], catalog)
                referenced.update(item["sha256"] for item in entries if item["type"] == "file")
            garbage: list[Path] = []
            for shard in self.objects_path.iterdir():
                if shard.is_symlink():
                    raise SafetyError(f"Refusing to traverse a symbolic-link object shard: {shard}")
                if not shard.is_dir():
                    continue
                for object_file in shard.glob("*.blob"):
                    if object_file.is_symlink():
                        raise SafetyError(f"Refusing to traverse a symbolic-link object: {object_file}")
                    digest = object_file.stem
                    if _DIGEST_RE.fullmatch(digest) and digest not in referenced:
                        garbage.append(object_file)
            result = {
                "dry_run": dry_run,
                "kept_snapshots": len(kept),
                "remove_snapshots": [record["id"] for record in removed],
                "unreferenced_objects": len(garbage),
                "reclaimable_bytes": sum(path.stat().st_size for path in garbage if path.is_file() and not path.is_symlink()),
            }
            if dry_run:
                return result
            new_catalog = dict(catalog)
            new_catalog["snapshots"] = kept
            self._write_catalog(new_catalog)
            for record in removed:
                manifest_path = self.snapshots_path / f"{record['id']}.json"
                try:
                    manifest_path.unlink()
                except FileNotFoundError:
                    pass
            for object_file in garbage:
                if object_file.is_symlink():
                    raise SafetyError(f"Refusing to remove a symbolic-link object: {object_file}")
                try:
                    object_file.unlink()
                except FileNotFoundError:
                    pass
            result["removed_snapshots"] = len(removed)
            result["removed_objects"] = len(garbage)
            return result


    def export_delta(
        self,
        base_snapshot_id: str,
        target_snapshot_id: str,
        delta_path: os.PathLike | str,
        *,
        overwrite: bool = False,
    ) -> dict:
        """Export a portable delta from a snapshot to its direct child.

        The delta carries a complete target tree manifest plus only file
        objects absent from the base snapshot. Applying it requires that exact
        base snapshot to already exist in the destination repository.
        """
        output = Path(delta_path).expanduser().absolute()
        _ensure_no_symlink_components(output)
        try:
            output.relative_to(self.root)
            raise RepositoryError("Export delta outside the repository directory")
        except ValueError:
            pass
        if output.exists() and not overwrite:
            raise RepositoryError(f"Delta destination already exists: {output}")
        output.parent.mkdir(parents=True, exist_ok=True)

        with self._write_lock():
            catalog = self._read_catalog()
            base_record = next((item for item in catalog["snapshots"] if item["id"] == base_snapshot_id), None)
            target_record = next((item for item in catalog["snapshots"] if item["id"] == target_snapshot_id), None)
            if base_record is None or target_record is None:
                raise RepositoryError("Both base and target snapshots must exist in the repository")
            base_manifest, base_entries = self._load_snapshot(base_snapshot_id, catalog)
            target_manifest, target_entries = self._load_snapshot(target_snapshot_id, catalog)
            if target_manifest.get("parent_snapshot_id") != base_snapshot_id:
                raise RepositoryError("Portable deltas can only be exported between a snapshot and its direct child")
            base_objects = {item["sha256"] for item in base_entries if item["type"] == "file"}
            target_object_sizes: dict[str, int] = {}
            for item in target_entries:
                if item["type"] == "file":
                    previous = target_object_sizes.setdefault(item["sha256"], item["size"])
                    if previous != item["size"]:
                        raise RepositoryIntegrityError("One object digest is associated with inconsistent file sizes")
            descriptors = []
            offset = 0
            for digest, size in sorted(target_object_sizes.items()):
                if digest in base_objects:
                    continue
                object_path = self._object_path(digest)
                if object_path.is_symlink() or not object_path.is_file():
                    raise RepositoryIntegrityError(f"Delta source object is missing or unsafe: {digest}")
                actual_digest, actual_size = _hash_path(object_path)
                if actual_digest != digest or actual_size != size:
                    raise RepositoryIntegrityError(f"Delta source object failed verification: {digest}")
                descriptors.append({"sha256": digest, "size": size, "offset": offset, "stored_size": size})
                offset += size
            delta_manifest = {
                "format": "QAMZOR-DELTA",
                "version": "1.0",
                "base_snapshot_id": base_snapshot_id,
                "base_manifest_sha256": base_record["manifest_sha256"],
                "target_manifest": target_manifest,
                "objects": descriptors,
            }
            encoded_manifest = _encode_json(delta_manifest)
            if len(encoded_manifest) > MAX_SNAPSHOT_SIZE:
                raise RepositoryError("Delta manifest exceeds its size limit")
            header = DELTA_HEADER.pack(
                DELTA_MAGIC, DELTA_MAJOR, DELTA_MINOR, 0,
                len(encoded_manifest), len(target_entries), 0,
            )
            fd, temporary = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=str(output.parent))
            try:
                with os.fdopen(fd, "wb") as target:
                    target.write(header)
                    target.write(encoded_manifest)
                    for descriptor in descriptors:
                        object_path = self._object_path(descriptor["sha256"])
                        digest = hashlib.sha256()
                        copied = 0
                        with object_path.open("rb") as source:
                            while True:
                                block = source.read(IO_SIZE)
                                if not block:
                                    break
                                target.write(block)
                                digest.update(block)
                                copied += len(block)
                        if copied != descriptor["size"] or digest.hexdigest() != descriptor["sha256"]:
                            raise RepositoryIntegrityError("Object changed while exporting delta")
                    target.flush()
                    os.fsync(target.fileno())
                if overwrite:
                    os.replace(temporary, output)
                else:
                    try:
                        os.link(temporary, output)
                    except FileExistsError as exc:
                        raise RepositoryError(f"Delta destination already exists: {output}") from exc
                    os.unlink(temporary)
                temporary = None
            finally:
                if temporary and os.path.exists(temporary):
                    os.unlink(temporary)
            return {
                "delta": str(output),
                "base_snapshot_id": base_snapshot_id,
                "target_snapshot_id": target_snapshot_id,
                "new_objects": len(descriptors),
                "payload_bytes": offset,
                "delta_bytes": output.stat().st_size,
            }

    def apply_delta(self, delta_path: os.PathLike | str) -> dict:
        """Apply a portable direct-child delta to this repository."""
        delta = Path(delta_path).expanduser().absolute()
        if delta.is_symlink() or not delta.is_file():
            raise RepositoryError("Delta input must be a regular, non-symbolic-link file")
        with self._write_lock():
            catalog = self._read_catalog()
            with delta.open("rb") as source:
                before = os.fstat(source.fileno())
                raw_header = source.read(DELTA_HEADER.size)
                if len(raw_header) != DELTA_HEADER.size:
                    raise RepositoryIntegrityError("Delta header is truncated")
                magic, major, minor, flags, manifest_size, entry_count, reserved = DELTA_HEADER.unpack(raw_header)
                if magic != DELTA_MAGIC or major != DELTA_MAJOR or minor != DELTA_MINOR:
                    raise RepositoryIntegrityError("Unsupported QAMZOR delta format")
                if flags != 0 or reserved != 0:
                    raise RepositoryIntegrityError("Delta header contains unsupported flags or reserved fields")
                if manifest_size > MAX_SNAPSHOT_SIZE or DELTA_HEADER.size + manifest_size > before.st_size:
                    raise RepositoryIntegrityError("Delta manifest size is invalid")
                encoded_manifest = source.read(manifest_size)
                if len(encoded_manifest) != manifest_size:
                    raise RepositoryIntegrityError("Delta manifest is truncated")
                try:
                    delta_manifest = json.loads(encoded_manifest.decode("utf-8"), object_pairs_hook=_json_no_duplicate_keys)
                except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
                    raise RepositoryIntegrityError("Delta manifest is not valid unambiguous JSON") from exc
                if not isinstance(delta_manifest, dict) or delta_manifest.get("format") != "QAMZOR-DELTA" or delta_manifest.get("version") != "1.0":
                    raise RepositoryIntegrityError("Unsupported delta manifest")
                base_id = delta_manifest.get("base_snapshot_id")
                base_digest = delta_manifest.get("base_manifest_sha256")
                target_manifest = delta_manifest.get("target_manifest")
                descriptors = delta_manifest.get("objects")
                if not isinstance(base_id, str) or not _SNAPSHOT_ID_RE.fullmatch(base_id):
                    raise RepositoryIntegrityError("Delta base snapshot ID is invalid")
                if not isinstance(base_digest, str) or not _DIGEST_RE.fullmatch(base_digest):
                    raise RepositoryIntegrityError("Delta base manifest digest is invalid")
                base_record = next((item for item in catalog["snapshots"] if item["id"] == base_id), None)
                if base_record is None or base_record["manifest_sha256"] != base_digest:
                    raise RepositoryError("Required base snapshot is missing or does not match this delta")
                base_manifest, base_entries = self._load_snapshot(base_id, catalog)
                if not self.verify(base_id)["valid"]:
                    raise RepositoryIntegrityError("Base snapshot failed verification")
                if not isinstance(target_manifest, dict):
                    raise RepositoryIntegrityError("Delta target snapshot manifest is missing")
                required_manifest_fields = {
                    "format", "version", "snapshot_id", "parent_snapshot_id",
                    "created_at", "message", "entries",
                }
                if set(target_manifest) != required_manifest_fields:
                    raise RepositoryIntegrityError("Delta target manifest fields are invalid")
                target_id = target_manifest.get("snapshot_id")
                if (
                    target_manifest.get("format") != SNAPSHOT_FORMAT
                    or target_manifest.get("version") != SNAPSHOT_VERSION
                    or not isinstance(target_id, str)
                    or not _SNAPSHOT_ID_RE.fullmatch(target_id)
                    or target_manifest.get("parent_snapshot_id") != base_id
                    or not isinstance(target_manifest.get("created_at"), str)
                    or not isinstance(target_manifest.get("message"), str)
                ):
                    raise RepositoryIntegrityError("Delta target snapshot identity or metadata is invalid")
                if any(item["id"] == target_id for item in catalog["snapshots"]):
                    raise RepositoryError("Target snapshot already exists in this repository")
                if target_id in {record["id"] for record in catalog["snapshots"]}:
                    raise RepositoryError("Target snapshot ID already exists")
                target_entries_raw = target_manifest.get("entries")
                if not isinstance(target_entries_raw, list) or len(target_entries_raw) != entry_count or len(target_entries_raw) > MAX_FILES_PER_SNAPSHOT:
                    raise RepositoryIntegrityError("Delta target entry count is inconsistent")
                target_entries: list[dict] = []
                archive_entries: list[ArchiveEntry] = []
                target_objects: dict[str, int] = {}
                file_count = 0
                total_bytes = 0
                for item in target_entries_raw:
                    if not isinstance(item, dict):
                        raise RepositoryIntegrityError("Delta target entry is invalid")
                    path = item.get("path")
                    kind = item.get("type")
                    size = item.get("size")
                    if not isinstance(path, str) or not isinstance(kind, str) or not isinstance(size, int) or isinstance(size, bool) or size < 0:
                        raise RepositoryIntegrityError("Delta target entry path, type or size is invalid")
                    _validate_archive_path(path)
                    if kind == "directory":
                        if size != 0 or set(item) != {"path", "type", "size"}:
                            raise RepositoryIntegrityError("Delta directory entry is invalid")
                        target_entries.append({"path": path, "type": kind, "size": 0})
                        archive_entries.append(ArchiveEntry(path, "directory", 0))
                    elif kind == "file":
                        digest = item.get("sha256")
                        if not isinstance(digest, str) or not _DIGEST_RE.fullmatch(digest) or set(item) != {"path", "type", "size", "sha256"}:
                            raise RepositoryIntegrityError("Delta file entry digest or fields are invalid")
                        previous = target_objects.setdefault(digest, size)
                        if previous != size:
                            raise RepositoryIntegrityError("Delta target uses one object digest with inconsistent sizes")
                        target_entries.append({"path": path, "type": kind, "size": size, "sha256": digest})
                        archive_entries.append(ArchiveEntry(path, "file", size))
                        file_count += 1
                        total_bytes += size
                    else:
                        raise RepositoryIntegrityError("Delta target contains an unsupported entry type")
                try:
                    _validate_entry_set(archive_entries)
                except QamzorError as exc:
                    raise RepositoryIntegrityError(f"Delta target paths are unsafe: {exc}") from exc
                base_objects = {item["sha256"] for item in base_entries if item["type"] == "file"}
                expected_new = {digest: size for digest, size in target_objects.items() if digest not in base_objects}
                if not isinstance(descriptors, list):
                    raise RepositoryIntegrityError("Delta object table is invalid")
                descriptor_map: dict[str, dict] = {}
                expected_offset = 0
                for descriptor in descriptors:
                    if not isinstance(descriptor, dict) or set(descriptor) != {"sha256", "size", "offset", "stored_size"}:
                        raise RepositoryIntegrityError("Delta object descriptor is invalid")
                    digest = descriptor.get("sha256")
                    size = descriptor.get("size")
                    offset = descriptor.get("offset")
                    stored_size = descriptor.get("stored_size")
                    if not isinstance(digest, str) or not _DIGEST_RE.fullmatch(digest) or digest in descriptor_map:
                        raise RepositoryIntegrityError("Delta object digest is invalid or duplicated")
                    if any(not isinstance(value, int) or isinstance(value, bool) or value < 0 for value in (size, offset, stored_size)):
                        raise RepositoryIntegrityError("Delta object size or offset is invalid")
                    if stored_size != size or offset != expected_offset or expected_new.get(digest) != size:
                        raise RepositoryIntegrityError("Delta object table does not match target snapshot")
                    descriptor_map[digest] = descriptor
                    expected_offset += stored_size
                if descriptor_map.keys() != expected_new.keys():
                    raise RepositoryIntegrityError("Delta omits or adds objects not required by the target snapshot")
                payload_offset = DELTA_HEADER.size + manifest_size
                if before.st_size - payload_offset != expected_offset:
                    raise RepositoryIntegrityError("Delta payload size does not match its object table")

                # Stream every new object into a temporary file, validate it,
                # and atomically publish it to the content-addressed store.
                for digest, descriptor in descriptor_map.items():
                    object_digest = hashlib.sha256()
                    object_size = 0
                    fd, temporary = tempfile.mkstemp(prefix=".delta-object.", suffix=".tmp", dir=str(self.objects_path))
                    try:
                        with os.fdopen(fd, "wb") as output:
                            remaining = descriptor["stored_size"]
                            while remaining:
                                block = source.read(min(IO_SIZE, remaining))
                                if not block:
                                    raise RepositoryIntegrityError("Delta payload is truncated")
                                output.write(block)
                                object_digest.update(block)
                                object_size += len(block)
                                remaining -= len(block)
                            output.flush()
                            os.fsync(output.fileno())
                        if object_size != descriptor["size"] or object_digest.hexdigest() != digest:
                            raise RepositoryIntegrityError(f"Delta object failed SHA-256 verification: {digest}")
                        object_path = self._object_path(digest)
                        object_path.parent.mkdir(parents=True, exist_ok=True)
                        if object_path.parent.is_symlink() or not object_path.parent.is_dir() or object_path.is_symlink():
                            raise SafetyError(f"Unsafe object-store path while applying delta: {object_path}")
                        if object_path.exists():
                            old_digest, old_size = _hash_path(object_path)
                            if old_digest != digest or old_size != descriptor["size"]:
                                raise RepositoryIntegrityError(f"Existing object conflicts with delta object: {digest}")
                        else:
                            try:
                                os.link(temporary, object_path)
                            except FileExistsError:
                                old_digest, old_size = _hash_path(object_path)
                                if old_digest != digest or old_size != descriptor["size"]:
                                    raise RepositoryIntegrityError(f"Concurrent object publication is corrupt: {digest}")
                    finally:
                        if os.path.exists(temporary):
                            os.unlink(temporary)
                after = os.fstat(source.fileno())
                if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
                    raise RepositoryIntegrityError("Delta file changed while it was being applied")

            # Confirm every target object, including content already present in
            # the base repository, before publishing the new snapshot record.
            for digest, size in target_objects.items():
                object_path = self._object_path(digest)
                if object_path.is_symlink() or not object_path.is_file():
                    raise RepositoryIntegrityError(f"Target snapshot object is missing: {digest}")
                actual_digest, actual_size = _hash_path(object_path)
                if actual_digest != digest or actual_size != size:
                    raise RepositoryIntegrityError(f"Target snapshot object failed verification: {digest}")
            encoded_target = _encode_json(target_manifest)
            if len(encoded_target) > MAX_SNAPSHOT_SIZE:
                raise RepositoryIntegrityError("Target snapshot manifest exceeds its size limit")
            manifest_path = self.snapshots_path / f"{target_id}.json"
            _atomic_write(manifest_path, encoded_target, overwrite=False)
            record = {
                "id": target_id,
                "parent_id": base_id,
                "created_at": target_manifest["created_at"],
                "message": target_manifest["message"],
                "manifest_sha256": hashlib.sha256(encoded_target).hexdigest(),
                "entries": len(target_entries),
                "files": file_count,
                "total_bytes": total_bytes,
            }
            catalog["snapshots"].append(record)
            self._write_catalog(catalog)
            return {
                "applied": True,
                "base_snapshot_id": base_id,
                "snapshot_id": target_id,
                "files": file_count,
                "total_bytes": total_bytes,
                "new_objects": len(descriptor_map),
            }
