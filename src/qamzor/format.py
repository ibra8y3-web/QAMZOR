"""QZRA v1 reference implementation (Python standard library only).

This is the first executable reference implementation, not the final Rust core.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import stat
import secrets
import struct
import tempfile
import unicodedata
import zlib
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Iterable, Iterator, Optional

from .chunking import iter_file_chunks
from .scheduler import bounded_ordered_map

MAGIC = b"QZRA\r\n\x1a\n"
MAJOR_VERSION = 1
MINOR_VERSION = 2
MAX_CHUNK_SIZE = 1024 * 1024
MAX_CHUNK_COMPRESSED_SIZE = MAX_CHUNK_SIZE + 65536
SCRYPT_N = 1 << 15
SCRYPT_R = 8
SCRYPT_P = 1
HEADER = struct.Struct(">8sHHIQII")
HEADER_SIZE = HEADER.size
MAX_MANIFEST_SIZE = 64 * 1024 * 1024
MAX_ENTRIES = 1_000_000
IO_CHUNK_SIZE = 1024 * 1024
SUPPORTED_FLAGS = 0


class QamzorError(Exception):
    """Base error for expected QAMZOR archive failures."""


class FormatError(QamzorError):
    """The archive is malformed or uses an unsupported format feature."""


class IntegrityError(QamzorError):
    """An archive entry does not match its declared size or digest."""


class SafetyError(QamzorError):
    """An operation was rejected because it could be unsafe."""


def _derive_encryption_key(password: str, salt: bytes) -> bytes:
    if not isinstance(password, str) or not password:
        raise QamzorError("Encryption password must be a non-empty string")
    try:
        return hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=SCRYPT_N,
            r=SCRYPT_R,
            p=SCRYPT_P,
            dklen=32,
            maxmem=64 * 1024 * 1024,
        )
    except (ValueError, MemoryError) as exc:
        raise QamzorError("Could not derive the archive encryption key") from exc


def _aesgcm_class():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as exc:
        raise QamzorError("Encryption requires the optional dependency: pip install 'qamzor[crypto]'") from exc
    return AESGCM


def _chunk_aad(size: int, digest: str, compression: str) -> bytes:
    return b"QZRA-1.2-CHUNK\0" + struct.pack(">Q", size) + bytes.fromhex(digest) + compression.encode("ascii")


def _canonical_manifest_bytes(manifest: dict) -> bytes:
    return json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _raise_authentication_error(exc: Exception) -> None:
    try:
        from cryptography.exceptions import InvalidTag
    except ImportError:
        InvalidTag = ()
    if isinstance(exc, InvalidTag):
        raise IntegrityError("Wrong password or encrypted archive authentication failed") from exc
    if isinstance(exc, QamzorError):
        raise exc
    raise IntegrityError("Encrypted archive authentication failed") from exc


@dataclass(frozen=True)
class ArchiveChunk:
    """A physical chunk reference; identical ranges may be shared by files."""

    offset: int
    compressed_size: int
    size: int
    compression: str
    sha256: str
    nonce: Optional[str] = None

    @classmethod
    def from_json(cls, value: object) -> "ArchiveChunk":
        if not isinstance(value, dict):
            raise FormatError("Chunk descriptor must be an object")
        offset = value.get("offset")
        compressed_size = value.get("compressed_size")
        size = value.get("size")
        compression = value.get("compression")
        digest = value.get("sha256")
        for label, number in (("offset", offset), ("compressed size", compressed_size), ("size", size)):
            if not isinstance(number, int) or isinstance(number, bool) or number < 0:
                raise FormatError(f"Chunk {label} must be a non-negative integer")
        if compressed_size == 0 or size == 0:
            raise FormatError("Chunk sizes must be positive")
        if size > MAX_CHUNK_SIZE or compressed_size > MAX_CHUNK_COMPRESSED_SIZE + 16:
            raise FormatError("Chunk exceeds the QZRA chunk-size limit")
        if compression not in {"zlib", "store"}:
            raise FormatError(f"Unsupported chunk compression method: {compression!r}")
        nonce = value.get("nonce")
        if nonce is not None and (not isinstance(nonce, str) or not re.fullmatch(r"[0-9a-f]{24}", nonce)):
            raise FormatError("Chunk AES-GCM nonce must be 12-byte lowercase hexadecimal")
        if compression == "store" and compressed_size != size + (16 if nonce else 0):
            raise FormatError("Stored chunks must match their original size plus any AES-GCM tag")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise FormatError("Chunk SHA-256 digest is missing or malformed")
        return cls(offset, compressed_size, size, compression, digest, nonce)

    def to_json(self) -> dict:
        result = {
            "offset": self.offset,
            "compressed_size": self.compressed_size,
            "size": self.size,
            "compression": self.compression,
            "sha256": self.sha256,
        }
        if self.nonce is not None:
            result["nonce"] = self.nonce
        return result


@dataclass(frozen=True)
class ArchiveEntry:
    path: str
    kind: str
    size: int
    offset: int = 0
    compressed_size: int = 0
    compression: str = "none"
    sha256: Optional[str] = None
    chunks: Optional[tuple[ArchiveChunk, ...]] = None

    @classmethod
    def from_json(cls, value: object) -> "ArchiveEntry":
        if not isinstance(value, dict):
            raise FormatError("Manifest entry must be an object")
        required = {"path", "type", "size"}
        if not required.issubset(value):
            raise FormatError("Manifest entry is missing required fields")
        path = value["path"]
        kind = value["type"]
        size = value["size"]
        if not isinstance(path, str) or not isinstance(kind, str):
            raise FormatError("Manifest path and type must be strings")
        if not isinstance(size, int) or isinstance(size, bool) or size < 0:
            raise FormatError("Manifest size must be a non-negative integer")
        _validate_archive_path(path)
        if kind == "directory":
            if size != 0 or "chunks" in value:
                raise FormatError("Directory entries must have size 0 and no chunks")
            return cls(path=path, kind=kind, size=0)
        if kind != "file":
            raise FormatError(f"Unsupported entry type: {kind}")
        digest = value.get("sha256")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise FormatError("File SHA-256 digest is missing or malformed")
        if "chunks" in value:
            raw_chunks = value["chunks"]
            if not isinstance(raw_chunks, list):
                raise FormatError("File chunks must be an array")
            chunks = tuple(ArchiveChunk.from_json(item) for item in raw_chunks)
            if sum(chunk.size for chunk in chunks) != size:
                raise FormatError("File chunk sizes do not add up to the declared file size")
            if size == 0 and chunks:
                raise FormatError("Empty files cannot contain chunks")
            if size > 0 and not chunks:
                raise FormatError("Non-empty files must contain at least one chunk")
            return cls(path=path, kind=kind, size=size, sha256=digest, chunks=chunks)

        offset = value.get("offset")
        compressed_size = value.get("compressed_size")
        compression = value.get("compression")
        if not isinstance(offset, int) or isinstance(offset, bool) or offset < 0:
            raise FormatError("File offset must be a non-negative integer")
        if not isinstance(compressed_size, int) or isinstance(compressed_size, bool) or compressed_size < 0:
            raise FormatError("Compressed size must be a non-negative integer")
        if compression not in {"zlib", "store"}:
            raise FormatError(f"Unsupported compression method: {compression!r}")
        if compression == "zlib" and compressed_size == 0:
            raise FormatError("Compressed zlib streams cannot be empty")
        if compression == "store" and compressed_size != size:
            raise FormatError("Stored entries must have equal original and stored sizes")
        return cls(path, kind, size, offset, compressed_size, compression, digest)

    def to_json(self) -> dict:
        if self.kind == "directory":
            return {"path": self.path, "type": "directory", "size": 0}
        if self.chunks is not None:
            return {
                "path": self.path,
                "type": "file",
                "size": self.size,
                "sha256": self.sha256,
                "chunks": [chunk.to_json() for chunk in self.chunks],
            }
        return {
            "path": self.path,
            "type": "file",
            "size": self.size,
            "offset": self.offset,
            "compressed_size": self.compressed_size,
            "compression": self.compression,
            "sha256": self.sha256,
        }


def _validate_archive_path(name: str) -> None:
    """Require portable relative POSIX paths; reject traversal and ambiguity."""
    if not name or "\x00" in name or "\\" in name or ":" in name:
        raise SafetyError(f"Unsafe archive path: {name!r}")
    if unicodedata.normalize("NFC", name) != name:
        raise SafetyError(f"Archive path is not Unicode NFC-normalized: {name!r}")
    p = PurePosixPath(name)
    if p.is_absolute() or name.startswith("/"):
        raise SafetyError(f"Absolute archive path is forbidden: {name!r}")
    parts = name.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise SafetyError(f"Unsafe path component in {name!r}")
    # Reject Windows device names so archives remain portable across platforms.
    for part in parts:
        stem = part.split(".", 1)[0].upper()
        if stem in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}:
            raise SafetyError(f"Reserved device name in archive path: {name!r}")


def _validate_entry_set(entries: list[ArchiveEntry]) -> None:
    if len(entries) > MAX_ENTRIES:
        raise FormatError("Archive contains too many entries")
    exact: set[str] = set()
    portable: dict[str, str] = {}
    kinds: dict[str, str] = {}
    for entry in entries:
        _validate_archive_path(entry.path)
        if entry.path in exact:
            raise FormatError(f"Duplicate archive path: {entry.path}")
        exact.add(entry.path)
        key = unicodedata.normalize("NFC", entry.path).casefold()
        if key in portable:
            raise FormatError(f"Paths collide on case-insensitive filesystems: {portable[key]!r}, {entry.path!r}")
        portable[key] = entry.path
        kinds[entry.path] = entry.kind
    for entry in entries:
        parts = entry.path.split("/")
        for i in range(1, len(parts)):
            parent = "/".join(parts[:i])
            if kinds.get(parent) == "file":
                raise FormatError(f"File path is also used as a parent directory: {parent!r}")
        if entry.kind == "file":
            prefix = entry.path + "/"
            if any(other.startswith(prefix) for other in exact):
                raise FormatError(f"File entry has child paths: {entry.path!r}")


def _walk_sources(sources: Iterable[os.PathLike | str], archive_path: Path) -> list[tuple[str, Path, str]]:
    items: list[tuple[str, Path, str]] = []
    excluded = archive_path.resolve(strict=False)
    for source_value in sources:
        source = Path(source_value).expanduser()
        try:
            st = source.lstat()
        except FileNotFoundError as exc:
            raise QamzorError(f"Source does not exist: {source}") from exc
        if stat.S_ISLNK(st.st_mode):
            raise SafetyError(f"Symbolic-link sources are not supported: {source}")
        if not (stat.S_ISREG(st.st_mode) or stat.S_ISDIR(st.st_mode)):
            raise SafetyError(f"Only regular files and directories are supported: {source}")
        source = source.resolve(strict=True)
        if source == excluded:
            continue
        base = source.name
        if not base:
            raise SafetyError(f"Cannot archive filesystem root: {source}")
        if stat.S_ISREG(st.st_mode):
            _validate_archive_path(base)
            items.append((base, source, "file"))
            continue
        _validate_archive_path(base)
        items.append((base, source, "directory"))
        for root, dirs, files in os.walk(source, topdown=True, followlinks=False):
            root_path = Path(root)
            rel_root = root_path.relative_to(source)
            safe_dirs: list[str] = []
            for dirname in sorted(dirs):
                child = root_path / dirname
                child_st = child.lstat()
                if stat.S_ISLNK(child_st.st_mode):
                    raise SafetyError(f"Symbolic links are not supported: {child}")
                if not stat.S_ISDIR(child_st.st_mode):
                    raise SafetyError(f"Unexpected non-directory in directory traversal: {child}")
                safe_dirs.append(dirname)
                rel = PurePosixPath(base, *rel_root.parts, dirname).as_posix()
                _validate_archive_path(rel)
                items.append((rel, child, "directory"))
            dirs[:] = safe_dirs
            for filename in sorted(files):
                child = root_path / filename
                if child.resolve(strict=False) == excluded:
                    continue
                child_st = child.lstat()
                if stat.S_ISLNK(child_st.st_mode):
                    raise SafetyError(f"Symbolic links are not supported: {child}")
                if not stat.S_ISREG(child_st.st_mode):
                    raise SafetyError(f"Only regular files are supported: {child}")
                rel = PurePosixPath(base, *rel_root.parts, filename).as_posix()
                _validate_archive_path(rel)
                items.append((rel, child, "file"))
    items.sort(key=lambda item: item[0])
    entries_for_validation = [ArchiveEntry(path, kind, 0) for path, _, kind in items]
    _validate_entry_set(entries_for_validation)
    return items


def _encode_chunk(data: bytes, compression_level: int) -> tuple[str, bytes]:
    encoded = zlib.compress(data, compression_level)
    if len(encoded) < len(data):
        return "zlib", encoded
    return "store", data


def create_archive(
    archive_path: os.PathLike | str,
    sources: Iterable[os.PathLike | str],
    *,
    compression_level: Optional[int] = None,
    profile: str = "balanced",
    overwrite: bool = False,
    workers: int = 1,
    password: Optional[str] = None,
) -> dict:
    """Create a QZRA 1.1 chunked archive with content-addressed deduplication.

    Content-defined chunks are independently compressed. Equal SHA-256/size
    chunks share one physical payload range, including across source files.
    Compression work is ordered and bounded by the worker scheduler.
    """
    profile_levels = {"speed": 1, "balanced": 6, "maximum": 9}
    if profile not in profile_levels:
        raise QamzorError(f"Unknown compression profile: {profile!r}")
    if compression_level is None:
        compression_level = profile_levels[profile]
    if not isinstance(compression_level, int) or isinstance(compression_level, bool) or not 0 <= compression_level <= 9:
        raise QamzorError("Compression level must be an integer between 0 and 9")
    if not isinstance(workers, int) or isinstance(workers, bool) or not 1 <= workers <= 32:
        raise QamzorError("workers must be an integer between 1 and 32")
    aesgcm = None
    encryption_salt = None
    format_minor = 1
    if password is not None:
        AESGCM = _aesgcm_class()
        encryption_salt = secrets.token_bytes(16)
        encryption_key = _derive_encryption_key(password, encryption_salt)
        aesgcm = AESGCM(encryption_key)
        format_minor = 2
    target = Path(archive_path).expanduser().absolute()
    source_values = list(sources)
    if not source_values:
        raise QamzorError("At least one source file or directory is required")
    if target.exists() and not overwrite:
        raise QamzorError(f"Destination already exists: {target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    items = _walk_sources(source_values, target)
    if not items:
        raise QamzorError("No source entries were found")

    manifest_entries: list[ArchiveEntry] = []
    chunk_index: dict[tuple[str, int], ArchiveChunk] = {}
    data_offset = 0
    total_original = 0
    logical_chunk_bytes = 0
    logical_chunks = 0
    deduplicated_chunks = 0
    spool = tempfile.TemporaryFile(mode="w+b", dir=str(target.parent))
    temp_output: Optional[str] = None
    try:
        for rel, source, kind in items:
            if kind == "directory":
                manifest_entries.append(ArchiveEntry(rel, "directory", 0))
                continue
            before = source.stat()
            digest = hashlib.sha256()
            original_size = 0

            def tracked_chunks():
                nonlocal original_size
                with source.open("rb") as input_file:
                    for raw_chunk in iter_file_chunks(input_file):
                        original_size += len(raw_chunk)
                        digest.update(raw_chunk)
                        yield raw_chunk

            def encode_item(raw_chunk: bytes):
                return raw_chunk, _encode_chunk(raw_chunk, compression_level)

            refs: list[ArchiveChunk] = []
            pending_limit = max(1, workers * 2)
            for raw_chunk, encoded_result in bounded_ordered_map(
                encode_item, tracked_chunks(), workers=workers, max_pending=pending_limit
            ):
                chunk_digest = hashlib.sha256(raw_chunk).hexdigest()
                key = (chunk_digest, len(raw_chunk))
                logical_chunks += 1
                logical_chunk_bytes += len(raw_chunk)
                existing = chunk_index.get(key)
                if existing is not None:
                    refs.append(existing)
                    deduplicated_chunks += 1
                    continue
                method, encoded = encoded_result
                nonce_hex = None
                if aesgcm is not None:
                    nonce = secrets.token_bytes(12)
                    encoded = aesgcm.encrypt(nonce, encoded, _chunk_aad(len(raw_chunk), chunk_digest, method))
                    nonce_hex = nonce.hex()
                ref = ArchiveChunk(
                    offset=data_offset,
                    compressed_size=len(encoded),
                    size=len(raw_chunk),
                    compression=method,
                    sha256=chunk_digest,
                    nonce=nonce_hex,
                )
                spool.write(encoded)
                data_offset += len(encoded)
                chunk_index[key] = ref
                refs.append(ref)

            after = source.stat()
            if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
                raise QamzorError(f"Source changed while being archived: {source}")
            if original_size != before.st_size:
                raise QamzorError(f"Source size changed while being archived: {source}")
            manifest_entries.append(
                ArchiveEntry(rel, "file", original_size, sha256=digest.hexdigest(), chunks=tuple(refs))
            )
            total_original += original_size

        manifest_obj = {
            "format": "QZRA",
            "version": f"{MAJOR_VERSION}.{format_minor}",
            "entries": [entry.to_json() for entry in manifest_entries],
        }
        if encryption_salt is not None:
            manifest_obj["security"] = {
                "encryption": "AES-256-GCM",
                "kdf": {
                    "name": "scrypt",
                    "n": SCRYPT_N,
                    "r": SCRYPT_R,
                    "p": SCRYPT_P,
                    "salt": encryption_salt.hex(),
                },
            }
            manifest_nonce = secrets.token_bytes(12)
            manifest_tag = aesgcm.encrypt(manifest_nonce, b"", _canonical_manifest_bytes(manifest_obj))
            manifest_obj["manifest_auth"] = {
                "nonce": manifest_nonce.hex(),
                "tag": manifest_tag.hex(),
            }
        manifest = _canonical_manifest_bytes(manifest_obj)
        if len(manifest) > MAX_MANIFEST_SIZE:
            raise QamzorError("Generated manifest exceeds the format limit")
        _validate_entry_set(manifest_entries)
        header = HEADER.pack(MAGIC, MAJOR_VERSION, format_minor, SUPPORTED_FLAGS, len(manifest), len(manifest_entries), 0)

        fd, temp_output = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=str(target.parent))
        with os.fdopen(fd, "wb") as output:
            output.write(header)
            output.write(manifest)
            spool.seek(0)
            shutil.copyfileobj(spool, output, length=IO_CHUNK_SIZE)
            output.flush()
            os.fsync(output.fileno())
        if overwrite:
            os.replace(temp_output, target)
            temp_output = None
        else:
            try:
                os.link(temp_output, target)
            except FileExistsError as exc:
                raise QamzorError(f"Destination already exists: {target}") from exc
            os.unlink(temp_output)
            temp_output = None
        return {
            "archive": str(target),
            "format_version": f"{MAJOR_VERSION}.{format_minor}",
            "encrypted": aesgcm is not None,
            "entries": len(manifest_entries),
            "files": sum(entry.kind == "file" for entry in manifest_entries),
            "chunks": logical_chunks,
            "unique_chunks": len(chunk_index),
            "deduplicated_chunks": deduplicated_chunks,
            "deduplication_saved_bytes": logical_chunk_bytes - sum(ref.size for ref in chunk_index.values()),
            "original_bytes": total_original,
            "unique_payload_bytes": data_offset,
            "archive_bytes": target.stat().st_size,
            "workers": workers,
        }
    finally:
        spool.close()
        if temp_output and os.path.exists(temp_output):
            os.unlink(temp_output)


class QZRAReader:
    """Validated, read-only access to QZRA 1.0, 1.1 and encrypted 1.2 archives."""

    def __init__(self, archive_path: os.PathLike | str, *, password: Optional[str] = None):
        self.path = Path(archive_path)
        self._password = password
        self._encryption_key: Optional[bytes] = None
        self._encryption_salt: Optional[bytes] = None
        self._manifest_auth: Optional[dict[str, str]] = None
        self._manifest_auth_data: Optional[bytes] = None
        self.is_encrypted = False
        self._file: BinaryIO = self.path.open("rb")
        try:
            self._load()
            if password is not None:
                self.set_password(password)
        except Exception:
            self._file.close()
            raise

    def _ensure_authenticated(self) -> None:
        if not self.is_encrypted or self._encryption_key is not None:
            return
        if self._password is None:
            raise QamzorError("This archive is encrypted; provide its password to verify or extract data")
        self.set_password(self._password)

    def set_password(self, password: str) -> None:
        if not self.is_encrypted or self._encryption_salt is None or self._manifest_auth is None:
            raise QamzorError("This archive is not encrypted or its authentication metadata is missing")
        key = _derive_encryption_key(password, self._encryption_salt)
        try:
            AESGCM = _aesgcm_class()
            AESGCM(key).decrypt(
                bytes.fromhex(self._manifest_auth["nonce"]),
                bytes.fromhex(self._manifest_auth["tag"]),
                self._manifest_auth_data,
            )
        except Exception as exc:
            _raise_authentication_error(exc)
        self._password = password
        self._encryption_key = key

    def close(self) -> None:
        self._file.close()

    def __enter__(self) -> "QZRAReader":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def _load(self) -> None:
        self._file.seek(0, os.SEEK_END)
        self.file_size = self._file.tell()
        if self.file_size < HEADER_SIZE:
            raise FormatError("File is too small to be a QZRA archive")
        self._file.seek(0)
        raw_header = self._file.read(HEADER_SIZE)
        magic, major, minor, flags, manifest_len, entry_count, reserved = HEADER.unpack(raw_header)
        if magic != MAGIC:
            raise FormatError("Invalid QZRA signature")
        if major != MAJOR_VERSION or minor > MINOR_VERSION:
            raise FormatError(f"Unsupported QZRA version: {major}.{minor}")
        self.version_major = major
        self.version_minor = minor
        if flags & ~SUPPORTED_FLAGS:
            raise FormatError("Archive uses unsupported header flags")
        if reserved != 0:
            raise FormatError("Reserved header field must be zero")
        if manifest_len > MAX_MANIFEST_SIZE:
            raise FormatError("Manifest exceeds the maximum permitted size")
        if entry_count > MAX_ENTRIES:
            raise FormatError("Archive contains too many entries")
        if HEADER_SIZE + manifest_len > self.file_size:
            raise FormatError("Manifest extends beyond end of archive")
        self._file.seek(HEADER_SIZE)
        raw_manifest = self._file.read(manifest_len)
        try:
            manifest = json.loads(raw_manifest.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise FormatError("Manifest is not valid UTF-8 JSON") from exc
        if not isinstance(manifest, dict) or manifest.get("format") != "QZRA":
            raise FormatError("Manifest format marker is invalid")
        if manifest.get("version") != f"{major}.{minor}":
            raise FormatError("Header and manifest versions do not match")
        security = manifest.get("security")
        self.is_encrypted = minor == 2
        if self.is_encrypted:
            if not isinstance(security, dict) or security.get("encryption") != "AES-256-GCM":
                raise FormatError("QZRA 1.2 requires AES-256-GCM security metadata")
            kdf = security.get("kdf")
            if not isinstance(kdf, dict) or (
                kdf.get("name"), kdf.get("n"), kdf.get("r"), kdf.get("p")
            ) != ("scrypt", SCRYPT_N, SCRYPT_R, SCRYPT_P):
                raise FormatError("Unsupported QZRA 1.2 key-derivation parameters")
            salt_hex = kdf.get("salt")
            if not isinstance(salt_hex, str) or not re.fullmatch(r"[0-9a-f]{32}", salt_hex):
                raise FormatError("QZRA 1.2 scrypt salt is missing or malformed")
            self._encryption_salt = bytes.fromhex(salt_hex)
            manifest_auth = manifest.get("manifest_auth")
            if not isinstance(manifest_auth, dict):
                raise FormatError("QZRA 1.2 requires manifest authentication metadata")
            auth_nonce = manifest_auth.get("nonce")
            auth_tag = manifest_auth.get("tag")
            if not isinstance(auth_nonce, str) or not re.fullmatch(r"[0-9a-f]{24}", auth_nonce):
                raise FormatError("Manifest authentication nonce is malformed")
            if not isinstance(auth_tag, str) or not re.fullmatch(r"[0-9a-f]{32}", auth_tag):
                raise FormatError("Manifest authentication tag is malformed")
            self._manifest_auth = {"nonce": auth_nonce, "tag": auth_tag}
            auth_manifest = dict(manifest)
            auth_manifest.pop("manifest_auth", None)
            self._manifest_auth_data = _canonical_manifest_bytes(auth_manifest)
        elif security is not None or manifest.get("manifest_auth") is not None:
            raise FormatError("Unencrypted QZRA versions cannot contain security metadata")
        raw_entries = manifest.get("entries")
        if not isinstance(raw_entries, list) or len(raw_entries) != entry_count:
            raise FormatError("Manifest entry count does not match header")
        self.entries = [ArchiveEntry.from_json(item) for item in raw_entries]
        _validate_entry_set(self.entries)
        if minor == 0 and any(entry.chunks is not None for entry in self.entries):
            raise FormatError("QZRA 1.0 cannot contain chunk references")
        if minor >= 1 and any(entry.kind == "file" and entry.chunks is None for entry in self.entries):
            raise FormatError("QZRA chunked file entries must use chunk references")
        if minor == 2 and any(
            entry.kind == "file" and any(chunk.nonce is None for chunk in (entry.chunks or ()))
            for entry in self.entries
        ):
            raise FormatError("Every QZRA 1.2 chunk must have an AES-GCM nonce")
        if minor < 2 and any(
            entry.kind == "file" and any(chunk.nonce is not None for chunk in (entry.chunks or ()))
            for entry in self.entries
        ):
            raise FormatError("AES-GCM chunk nonces require QZRA 1.2")
        self.payload_offset = HEADER_SIZE + manifest_len
        payload_length = self.file_size - self.payload_offset
        # Equal ranges are legal only when they are exact references to the
        # same content-addressed chunk. Partial overlaps remain forbidden.
        range_descriptors: dict[tuple[int, int], tuple[int, str, str, Optional[str]]] = {}
        for entry in self.entries:
            if entry.kind != "file":
                continue
            if entry.chunks is None:
                blocks = [(entry.offset, entry.compressed_size, entry.size, entry.compression, entry.sha256, None)]
            else:
                blocks = [
                    (chunk.offset, chunk.compressed_size, chunk.size, chunk.compression, chunk.sha256, chunk.nonce)
                    for chunk in entry.chunks
                ]
            for offset, compressed_size, original_size, compression, digest, nonce in blocks:
                end = offset + compressed_size
                if end > payload_length:
                    raise FormatError(f"Entry data extends beyond archive: {entry.path}")
                if compressed_size == 0:
                    # Legacy empty stored files have no physical range.
                    if entry.chunks is not None or original_size != 0:
                        raise FormatError("Only legacy empty files may have zero-length payloads")
                    continue
                key = (offset, end)
                descriptor = (original_size, compression, digest or "", nonce)
                previous = range_descriptors.get(key)
                if previous is not None and previous != descriptor:
                    raise FormatError("Shared payload range has inconsistent chunk metadata")
                range_descriptors[key] = descriptor
        ranges = sorted((start, end) for start, end in range_descriptors)
        previous_end = 0
        for start, end in ranges:
            if start < previous_end:
                raise FormatError("Partially overlapping payload blocks are forbidden")
            if start != previous_end:
                raise FormatError("Unreferenced gap before data block")
            previous_end = end
        if ranges and previous_end != payload_length:
            raise FormatError("Unreferenced trailing bytes exist in the data section")
        if not ranges and payload_length != 0:
            raise FormatError("Archive has data bytes but no file entries")
        self._entry_map = {entry.path: entry for entry in self.entries}

    def list_entries(self) -> list[ArchiveEntry]:
        return list(self.entries)

    def inspect(self) -> dict:
        files = [entry for entry in self.entries if entry.kind == "file"]
        physical: dict[tuple[int, int], ArchiveChunk | tuple] = {}
        logical_chunks = 0
        methods: set[str] = set()
        for entry in files:
            if entry.chunks is None:
                methods.add(entry.compression)
                if entry.compressed_size:
                    physical[(entry.offset, entry.offset + entry.compressed_size)] = (entry.compressed_size,)
            else:
                logical_chunks += len(entry.chunks)
                for chunk in entry.chunks:
                    methods.add(chunk.compression)
                    physical[(chunk.offset, chunk.offset + chunk.compressed_size)] = chunk
        unique_payload = sum(end - start for start, end in physical)
        return {
            "format": "QZRA",
            "version": f"{self.version_major}.{self.version_minor}",
            "archive_bytes": self.file_size,
            "entries": len(self.entries),
            "files": len(files),
            "directories": len(self.entries) - len(files),
            "original_bytes": sum(entry.size for entry in files),
            "unique_payload_bytes": unique_payload,
            "compressed_payload_bytes": unique_payload,
            "logical_chunks": logical_chunks,
            "unique_chunks": len(physical) if self.version_minor >= 1 else None,
            "deduplicated_chunk_references": logical_chunks - len(physical) if self.version_minor >= 1 else 0,
            "compression_methods": sorted(methods),
            "encrypted": self.is_encrypted,
            "integrity": "SHA-256 per file and chunk" + ("; AES-256-GCM authenticated payloads" if self.is_encrypted else ""),
        }

    def _decode_block(
        self,
        offset: int,
        compressed_size: int,
        compression: str,
        expected_size: int,
        expected_digest: str,
        sink: Optional[BinaryIO],
        whole_file_digest,
        nonce: Optional[str] = None,
    ) -> tuple[int, str]:
        source: BinaryIO = self._file
        if self.is_encrypted:
            if nonce is None:
                raise FormatError("Encrypted archive chunk is missing its nonce")
            self._ensure_authenticated()
            self._file.seek(self.payload_offset + offset)
            ciphertext = self._file.read(compressed_size)
            if len(ciphertext) != compressed_size:
                raise FormatError("Truncated encrypted chunk")
            try:
                AESGCM = _aesgcm_class()
                compressed_data = AESGCM(self._encryption_key).decrypt(
                    bytes.fromhex(nonce),
                    ciphertext,
                    _chunk_aad(expected_size, expected_digest, compression),
                )
            except Exception as exc:
                _raise_authentication_error(exc)
            source = io.BytesIO(compressed_data)
            remaining_compressed = len(compressed_data)
        else:
            if nonce is not None:
                raise FormatError("Unexpected encryption nonce in an unencrypted archive")
            self._file.seek(self.payload_offset + offset)
            remaining_compressed = compressed_size
        decompressor = zlib.decompressobj(zlib.MAX_WBITS) if compression == "zlib" else None
        digest = hashlib.sha256()
        output_size = 0

        def consume(data: bytes) -> None:
            nonlocal output_size
            if not data:
                return
            output_size += len(data)
            if output_size > expected_size:
                raise IntegrityError("Expanded data exceeds declared chunk size")
            digest.update(data)
            whole_file_digest.update(data)
            if sink is not None:
                sink.write(data)

        while remaining_compressed:
            compressed_chunk = source.read(min(IO_CHUNK_SIZE, remaining_compressed))
            if not compressed_chunk:
                raise FormatError("Truncated compressed block")
            remaining_compressed -= len(compressed_chunk)
            if decompressor is None:
                consume(compressed_chunk)
                continue
            pending = compressed_chunk
            while pending:
                try:
                    produced = decompressor.decompress(pending, max(1, expected_size - output_size + 1))
                except zlib.error as exc:
                    raise FormatError("Invalid compressed stream") from exc
                consume(produced)
                if decompressor.unused_data:
                    raise FormatError("Trailing bytes in compressed stream")
                pending = decompressor.unconsumed_tail
                if pending and not produced and output_size > expected_size:
                    raise IntegrityError("Expanded data exceeds declared chunk size")
        if decompressor is not None:
            # All compressed input has been supplied with a bounded max_length.
            # Do not call flush() here: flush output is not subject to the
            # decompression output ceiling and could allocate attacker-sized data.
            if not decompressor.eof:
                raise FormatError("Incomplete compressed stream")
            if decompressor.unused_data or decompressor.unconsumed_tail:
                raise FormatError("Invalid compressed stream ending")
        actual_digest = digest.hexdigest()
        if output_size != expected_size:
            raise IntegrityError(f"Chunk size mismatch: expected {expected_size}, got {output_size}")
        if actual_digest != expected_digest:
            raise IntegrityError("Chunk SHA-256 mismatch")
        return output_size, actual_digest

    def _decode_file(self, entry: ArchiveEntry, sink: Optional[BinaryIO] = None) -> tuple[int, str]:
        if entry.kind != "file":
            raise QamzorError("Only file entries contain data")
        whole_digest = hashlib.sha256()
        output_size = 0
        if entry.chunks is None:
            output_size, _ = self._decode_block(
                entry.offset,
                entry.compressed_size,
                entry.compression,
                entry.size,
                entry.sha256 or "",
                sink,
                whole_digest,
            )
        else:
            for chunk in entry.chunks:
                decoded, _ = self._decode_block(
                    chunk.offset,
                    chunk.compressed_size,
                    chunk.compression,
                    chunk.size,
                    chunk.sha256,
                    sink,
                    whole_digest,
                    chunk.nonce,
                )
                output_size += decoded
        actual_digest = whole_digest.hexdigest()
        if output_size != entry.size:
            raise IntegrityError(f"Size mismatch for {entry.path}: expected {entry.size}, got {output_size}")
        if actual_digest != entry.sha256:
            raise IntegrityError(f"SHA-256 mismatch for {entry.path}")
        return output_size, actual_digest

    def find_entries(self, query: str = "", *, kind: Optional[str] = None) -> list[ArchiveEntry]:
        """Search archive paths without extracting content."""
        if kind not in {None, "file", "directory"}:
            raise QamzorError("kind must be file, directory, or None")
        needle = query.casefold()
        return [
            entry for entry in self.entries
            if (kind is None or entry.kind == kind) and needle in entry.path.casefold()
        ]

    def preview(self, path: str, *, max_bytes: int = 65536) -> dict:
        """Return a bounded prefix while still verifying the complete file."""
        if not isinstance(max_bytes, int) or isinstance(max_bytes, bool) or not 0 <= max_bytes <= 16 * 1024 * 1024:
            raise QamzorError("Preview byte limit must be between 0 and 16 MiB")
        _validate_archive_path(path)
        entry = self._entry_map.get(path)
        if entry is None:
            raise QamzorError(f"Archive path does not exist: {path}")
        if entry.kind != "file":
            raise QamzorError("Only regular files can be previewed")

        class PrefixSink:
            def __init__(self, limit: int):
                self.limit = limit
                self.data = bytearray()
                self.total = 0

            def write(self, data: bytes) -> int:
                self.total += len(data)
                remaining = self.limit - len(self.data)
                if remaining > 0:
                    self.data.extend(data[:remaining])
                return len(data)

        self._ensure_authenticated()
        sink = PrefixSink(max_bytes)
        self._decode_file(entry, sink)
        return {
            "path": path,
            "data": bytes(sink.data),
            "bytes_previewed": len(sink.data),
            "bytes_total": sink.total,
            "truncated": sink.total > len(sink.data),
            "verified": True,
        }

    def verify(self) -> dict:
        self._ensure_authenticated()
        checked = 0
        total = 0
        for entry in self.entries:
            if entry.kind == "file":
                size, _ = self._decode_file(entry)
                checked += 1
                total += size
        return {"valid": True, "files_checked": checked, "bytes_checked": total}

    def extract(
        self,
        destination: os.PathLike | str,
        *,
        selected: Optional[Iterable[str]] = None,
        overwrite: bool = False,
        max_total_bytes: Optional[int] = None,
        max_files: Optional[int] = None,
    ) -> list[str]:
        self._ensure_authenticated()
        root = Path(destination).expanduser().absolute()
        if root.exists() and root.is_symlink():
            raise SafetyError("Extraction destination cannot be a symbolic link")
        chosen: set[str]
        if selected is None:
            chosen = set(self._entry_map)
        else:
            requested = list(selected)
            chosen = set()
            for name in requested:
                _validate_archive_path(name)
                if name not in self._entry_map:
                    raise QamzorError(f"Requested archive path does not exist: {name}")
                chosen.add(name)
                item = self._entry_map[name]
                if item.kind == "directory":
                    prefix = name + "/"
                    chosen.update(path for path in self._entry_map if path.startswith(prefix))
        ordered = sorted((self._entry_map[name] for name in chosen), key=lambda e: (e.path.count("/"), e.path))
        selected_files = [entry for entry in ordered if entry.kind == "file"]
        if max_total_bytes is not None:
            if not isinstance(max_total_bytes, int) or isinstance(max_total_bytes, bool) or max_total_bytes < 0:
                raise QamzorError("max_total_bytes must be a non-negative integer")
            selected_bytes = sum(entry.size for entry in selected_files)
            if selected_bytes > max_total_bytes:
                raise SafetyError(f"Extraction exceeds byte limit ({selected_bytes} > {max_total_bytes})")
        if max_files is not None:
            if not isinstance(max_files, int) or isinstance(max_files, bool) or max_files < 0:
                raise QamzorError("max_files must be a non-negative integer")
            if len(selected_files) > max_files:
                raise SafetyError(f"Extraction exceeds file-count limit ({len(selected_files)} > {max_files})")
        # Enforce quotas before creating even the extraction root directory.
        root.mkdir(parents=True, exist_ok=True)
        root = root.resolve(strict=True)
        written: list[str] = []
        for entry in ordered:
            target = _safe_target(root, entry.path, create_parents=True)
            if entry.kind == "directory":
                if target.exists():
                    if target.is_symlink() or not target.is_dir():
                        raise SafetyError(f"Extraction path conflicts with an existing item: {entry.path}")
                else:
                    target.mkdir()
                written.append(entry.path)
                continue
            if target.exists() and not overwrite:
                raise QamzorError(f"Refusing to overwrite existing file: {target}")
            if target.exists() and (target.is_symlink() or not target.is_file()):
                raise SafetyError(f"Refusing to replace a non-regular file: {target}")
            fd, temp_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".qzra-tmp", dir=str(target.parent))
            try:
                with os.fdopen(fd, "wb") as output:
                    self._decode_file(entry, output)
                    output.flush()
                    os.fsync(output.fileno())
                if overwrite:
                    os.replace(temp_name, target)
                else:
                    try:
                        os.link(temp_name, target)
                    except FileExistsError as exc:
                        raise QamzorError(f"Refusing to overwrite existing file: {target}") from exc
                    os.unlink(temp_name)
                written.append(entry.path)
            finally:
                if os.path.exists(temp_name):
                    os.unlink(temp_name)
        return written


def _safe_target(root: Path, relative: str, *, create_parents: bool) -> Path:
    _validate_archive_path(relative)
    current = root
    parts = relative.split("/")
    for part in parts[:-1]:
        current = current / part
        if current.exists() or current.is_symlink():
            if current.is_symlink() or not current.is_dir():
                raise SafetyError(f"Unsafe extraction parent: {current}")
        elif create_parents:
            current.mkdir()
    target = current / parts[-1]
    # Existing symlinks are rejected even when overwrite is enabled.
    if target.is_symlink():
        raise SafetyError(f"Refusing to write through symbolic link: {target}")
    try:
        target.absolute().relative_to(root)
    except ValueError as exc:
        raise SafetyError("Resolved extraction path escapes destination") from exc
    return target


def open_archive(path: os.PathLike | str, *, password: Optional[str] = None) -> QZRAReader:
    return QZRAReader(path, password=password)
