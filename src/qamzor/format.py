"""QZRA v1 reference implementation (Python standard library only).

This is the first executable reference implementation, not the final Rust core.
"""
from __future__ import annotations

import hashlib
import fnmatch
import hmac
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
MINOR_VERSION = 3
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
V13_MANIFEST_MAGIC = b"QZ13"
V13_MANIFEST_PREFIX = struct.Struct(">4s16s12s")
MAX_FOLDER_SCOPES = 32


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


def _derive_v13_subkey(master_key: bytes, purpose: bytes) -> bytes:
    """Derive a purpose-separated 256-bit key for each QZRA 1.3 AEAD domain."""
    return hmac.new(master_key, b"QZRA-1.3-SUBKEY\0" + purpose, hashlib.sha256).digest()


def _aesgcm_class():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as exc:
        raise QamzorError("Encryption requires the optional dependency: pip install 'qamzor[crypto]'") from exc
    return AESGCM


def _chunk_aad(size: int, digest: str, compression: str) -> bytes:
    return b"QZRA-1.2-CHUNK\0" + struct.pack(">Q", size) + bytes.fromhex(digest) + compression.encode("ascii")


def _chunk_aad_v13(size: int, digest: str, compression: str, scope_id: Optional[str]) -> bytes:
    scope = (scope_id or "-").encode("ascii")
    return b"QZRA-1.3-CHUNK\0" + struct.pack(">Q", size) + bytes.fromhex(digest) + compression.encode("ascii") + b"\0" + scope


def _manifest_aad_v13(salt: bytes, nonce: bytes, flags: int, reserved: int) -> bytes:
    return b"QZRA-1.3-MANIFEST\0" + struct.pack(">HHII", MAJOR_VERSION, 3, flags, reserved) + salt + nonce


def _scope_check_aad(scope_id: str, path: str) -> bytes:
    return b"QZRA-1.3-SCOPE-CHECK\0" + bytes.fromhex(scope_id) + path.encode("utf-8")


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
    scope_id: Optional[str] = None

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
        scope_id = value.get("scope_id")
        if scope_id is not None and (not isinstance(scope_id, str) or not re.fullmatch(r"[0-9a-f]{32}", scope_id)):
            raise FormatError("Chunk scope_id must be 16-byte lowercase hexadecimal")
        if scope_id is not None and nonce is None:
            raise FormatError("Folder-scoped chunks must be encrypted")
        if compression == "store" and compressed_size != size + (16 if nonce else 0):
            raise FormatError("Stored chunks must match their original size plus any AES-GCM tag")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise FormatError("Chunk SHA-256 digest is missing or malformed")
        return cls(offset, compressed_size, size, compression, digest, nonce, scope_id)

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
        if self.scope_id is not None:
            result["scope_id"] = self.scope_id
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


def _parse_exclude_patterns(patterns: Optional[Iterable[str]]) -> list[str]:
    parsed: list[str] = []
    for raw in patterns or ():
        if not isinstance(raw, str):
            raise QamzorError("Exclude patterns must be strings")
        value = raw.strip().replace("\\", "/")
        if not value or value.startswith("#"):
            continue
        if value.startswith("!"):
            raise QamzorError("Negated exclude patterns are not supported; use a narrower positive pattern")
        if len(value) > 1024 or value in {".", "..", "/"}:
            raise QamzorError(f"Invalid exclude pattern: {raw!r}")
        parsed.append(value.lstrip("/"))
        if len(parsed) > 10_000:
            raise QamzorError("At most 10,000 exclude patterns are allowed")
    return parsed


def _matches_exclusion(relative: str, is_directory: bool, patterns: list[str]) -> bool:
    """Match simple gitignore-style patterns relative to each selected root.

    A pattern containing '/' matches the whole relative path; a pattern without
    '/' matches any path component. A trailing slash excludes a directory and
    everything below it. Negation is deliberately rejected by the parser.
    """
    relative = relative.strip("/")
    if not relative:
        return False
    parts = relative.split("/")
    for raw_pattern in patterns:
        directory_only = raw_pattern.endswith("/")
        pattern = raw_pattern.rstrip("/") if directory_only else raw_pattern
        if not pattern:
            continue
        candidates = [relative] if "/" in pattern else parts
        matched = any(fnmatch.fnmatchcase(candidate, pattern) for candidate in candidates)
        if not matched:
            continue
        if directory_only:
            # Directory patterns can match any ancestor component.
            for index in range(1, len(parts) + 1):
                ancestor = "/".join(parts[:index])
                if fnmatch.fnmatchcase(ancestor, pattern) and (is_directory or index < len(parts)):
                    return True
            continue
        return True
    return False


def _matches_exclusion_contexts(relative: str, is_directory: bool, contexts: list[tuple[str, list[str]]]) -> bool:
    for prefix, patterns in contexts:
        if not patterns:
            continue
        if prefix:
            if relative == prefix:
                local = ""
            elif relative.startswith(prefix + "/"):
                local = relative[len(prefix) + 1:]
            else:
                continue
        else:
            local = relative
        if _matches_exclusion(local, is_directory, patterns):
            return True
    return False


def _read_source_excludes(root: Path) -> list[str]:
    ignore_file = root / ".qzignore"
    try:
        if ignore_file.is_symlink() or not ignore_file.is_file():
            return []
        if ignore_file.stat().st_size > 1024 * 1024:
            raise QamzorError(f".qzignore is larger than 1 MiB: {ignore_file}")
        return _parse_exclude_patterns(ignore_file.read_text(encoding="utf-8").splitlines())
    except UnicodeDecodeError as exc:
        raise QamzorError(f".qzignore must be UTF-8 text: {ignore_file}") from exc


def _walk_sources(
    sources: Iterable[os.PathLike | str], archive_path: Path,
    exclude_patterns: Optional[Iterable[str]] = None,
) -> list[tuple[str, Path, str]]:
    items: list[tuple[str, Path, str]] = []
    excluded = archive_path.resolve(strict=False)
    global_patterns = _parse_exclude_patterns(exclude_patterns)
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
            if not _matches_exclusion(base, False, global_patterns):
                items.append((base, source, "file"))
            continue
        _validate_archive_path(base)
        source_patterns = global_patterns + _read_source_excludes(source)
        items.append((base, source, "directory"))
        context_map: dict[Path, list[tuple[str, list[str]]]] = {source: [("", source_patterns)]}
        for root, dirs, files in os.walk(source, topdown=True, followlinks=False):
            root_path = Path(root)
            rel_root = root_path.relative_to(source)
            source_prefix = rel_root.as_posix() if rel_root.parts else ""
            contexts = list(context_map.get(root_path, [("", source_patterns)]))
            local_ignore = _read_source_excludes(root_path) if rel_root.parts else []
            if local_ignore:
                contexts.append((source_prefix, local_ignore))
            safe_dirs: list[str] = []
            for dirname in sorted(dirs):
                child = root_path / dirname
                child_st = child.lstat()
                if stat.S_ISLNK(child_st.st_mode):
                    raise SafetyError(f"Symbolic links are not supported: {child}")
                if not stat.S_ISDIR(child_st.st_mode):
                    raise SafetyError(f"Unexpected non-directory in directory traversal: {child}")
                relative_child = "/".join(filter(None, (source_prefix, dirname)))
                if _matches_exclusion_contexts(relative_child, True, contexts):
                    continue
                safe_dirs.append(dirname)
                context_map[child] = list(contexts)
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
                relative_file = "/".join(filter(None, (source_prefix, filename)))
                if filename == ".qzignore" or _matches_exclusion_contexts(relative_file, False, contexts):
                    continue
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
    encrypt_metadata: bool = False,
    folder_passwords: Optional[dict[str, str]] = None,
    exclude_patterns: Optional[Iterable[str]] = None,
    job_control=None,
) -> dict:
    """Create a QZRA archive.

    QZRA 1.0 is the legacy single-block format, 1.1 is chunked, 1.2 adds
    authenticated payload encryption, and 1.3 encrypts the complete manifest
    (including all names) and supports independently password-locked folders.

    ``folder_passwords`` keys are exact archive-relative directory paths. The
    archive master password is still required to unlock the encrypted manifest;
    each folder password then independently unlocks the payloads in that folder
    and its descendants, with the deepest configured folder taking precedence.
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

    folder_passwords = dict(folder_passwords or {})
    use_v13 = bool(encrypt_metadata or folder_passwords)
    if use_v13 and (not isinstance(password, str) or not password):
        raise QamzorError("QZRA 1.3 requires a non-empty archive master password")
    if folder_passwords and not use_v13:
        raise QamzorError("Folder passwords require QZRA 1.3 metadata encryption")

    aesgcm_cls = _aesgcm_class() if password is not None else None
    encryption_salt = secrets.token_bytes(16) if password is not None else None
    encryption_key = _derive_encryption_key(password, encryption_salt) if password is not None else None
    format_minor = 3 if use_v13 else (2 if password is not None else 1)
    archive_aesgcm = aesgcm_cls(encryption_key) if encryption_key is not None else None
    manifest_key = _derive_v13_subkey(encryption_key, b"manifest") if use_v13 else None
    root_chunk_key = _derive_v13_subkey(encryption_key, b"root-chunks") if use_v13 else encryption_key

    target = Path(archive_path).expanduser().absolute()
    source_values = list(sources)
    if not source_values:
        raise QamzorError("At least one source file or directory is required")
    if target.exists() and not overwrite:
        raise QamzorError(f"Destination already exists: {target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    items = _walk_sources(source_values, target, exclude_patterns)
    if not items:
        raise QamzorError("No source entries were found")

    total_source_bytes = sum(source.stat().st_size for _, source, kind in items if kind == "file")
    completed_source_bytes = 0

    def checkpoint(current_file_bytes: int = 0, phase: str = "archive") -> None:
        if job_control is not None:
            job_control.checkpoint({
                "phase": phase,
                "completed_bytes": completed_source_bytes + current_file_bytes,
                "total_bytes": total_source_bytes,
                "completed_entries": sum(1 for _, _, kind in items[:current_item_index] if kind == "file"),
                "total_entries": sum(1 for _, _, kind in items if kind == "file"),
            })

    item_kinds = {rel: kind for rel, _, kind in items}
    folder_scopes: dict[str, dict] = {}
    scope_keys: dict[str, bytes] = {}
    if use_v13:
        if len(folder_passwords) > MAX_FOLDER_SCOPES:
            raise QamzorError(f"At most {MAX_FOLDER_SCOPES} independently protected folders are allowed per archive")
        for folder_path, folder_password in sorted(folder_passwords.items()):
            _validate_archive_path(folder_path)
            if item_kinds.get(folder_path) != "directory":
                raise QamzorError(f"Protected folder is not present as a directory in the archive: {folder_path}")
            if not isinstance(folder_password, str) or not folder_password:
                raise QamzorError(f"Folder password must be a non-empty string: {folder_path}")
            scope_id = secrets.token_hex(16)
            scope_salt = secrets.token_bytes(16)
            scope_master_key = _derive_encryption_key(folder_password, scope_salt)
            scope_key = _derive_v13_subkey(scope_master_key, b"folder-chunks")
            scope_check_key = _derive_v13_subkey(scope_master_key, b"folder-check")
            check_nonce = secrets.token_bytes(12)
            check_tag = aesgcm_cls(scope_check_key).encrypt(
                check_nonce, b"", _scope_check_aad(scope_id, folder_path)
            )
            folder_scopes[folder_path] = {
                "id": scope_id,
                "path": folder_path,
                "kdf": {
                    "name": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R,
                    "p": SCRYPT_P, "salt": scope_salt.hex(),
                },
                "key_check": {"nonce": check_nonce.hex(), "tag": check_tag.hex()},
            }
            scope_keys[scope_id] = scope_key

    manifest_entries: list[ArchiveEntry] = []
    # Scope is part of the key: the same plaintext chunk protected by two
    # different folder passwords must never share one ciphertext range.
    chunk_index: dict[tuple[str, int, Optional[str]], ArchiveChunk] = {}
    data_offset = 0
    total_original = 0
    logical_chunk_bytes = 0
    logical_chunks = 0
    deduplicated_chunks = 0
    spool = tempfile.TemporaryFile(mode="w+b", dir=str(target.parent))
    temp_output: Optional[str] = None
    try:
        scope_paths_by_length = sorted(folder_scopes, key=len, reverse=True)
        for current_item_index, (rel, source, kind) in enumerate(items):
            checkpoint(0, "starting-entry")
            if kind == "directory":
                manifest_entries.append(ArchiveEntry(rel, "directory", 0))
                continue
            before = source.stat()
            digest = hashlib.sha256()
            original_size = 0
            scope_path = next(
                (candidate for candidate in scope_paths_by_length if rel.startswith(candidate + "/")),
                None,
            ) if use_v13 else None
            scope_id = folder_scopes[scope_path]["id"] if scope_path else None
            chunk_key = scope_keys.get(scope_id) if scope_id else root_chunk_key
            chunk_aesgcm = aesgcm_cls(chunk_key) if chunk_key is not None else None

            def tracked_chunks():
                nonlocal original_size
                with source.open("rb") as input_file:
                    for raw_chunk in iter_file_chunks(input_file):
                        checkpoint(original_size, "reading-source")
                        original_size += len(raw_chunk)
                        digest.update(raw_chunk)
                        yield raw_chunk
                checkpoint(original_size, "source-read")

            def encode_item(raw_chunk: bytes):
                return raw_chunk, _encode_chunk(raw_chunk, compression_level)

            refs: list[ArchiveChunk] = []
            pending_limit = max(1, workers * 2)
            for raw_chunk, encoded_result in bounded_ordered_map(
                encode_item, tracked_chunks(), workers=workers, max_pending=pending_limit
            ):
                chunk_digest = hashlib.sha256(raw_chunk).hexdigest()
                key = (chunk_digest, len(raw_chunk), scope_id)
                logical_chunks += 1
                logical_chunk_bytes += len(raw_chunk)
                existing = chunk_index.get(key)
                if existing is not None:
                    refs.append(existing)
                    deduplicated_chunks += 1
                    continue
                method, encoded = encoded_result
                nonce_hex = None
                if chunk_aesgcm is not None:
                    nonce = secrets.token_bytes(12)
                    if format_minor == 3:
                        aad = _chunk_aad_v13(len(raw_chunk), chunk_digest, method, scope_id)
                    else:
                        aad = _chunk_aad(len(raw_chunk), chunk_digest, method)
                    encoded = chunk_aesgcm.encrypt(nonce, encoded, aad)
                    nonce_hex = nonce.hex()
                ref = ArchiveChunk(
                    offset=data_offset,
                    compressed_size=len(encoded),
                    size=len(raw_chunk),
                    compression=method,
                    sha256=chunk_digest,
                    nonce=nonce_hex,
                    scope_id=scope_id,
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
            completed_source_bytes += original_size
            checkpoint(0, "entry-complete")

        checkpoint(0, "finalizing")
        manifest_obj = {
            "format": "QZRA",
            "version": f"{MAJOR_VERSION}.{format_minor}",
            "entries": [entry.to_json() for entry in manifest_entries],
        }
        if format_minor == 2:
            manifest_obj["security"] = {
                "encryption": "AES-256-GCM",
                "kdf": {
                    "name": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R,
                    "p": SCRYPT_P, "salt": encryption_salt.hex(),
                },
            }
            manifest_nonce = secrets.token_bytes(12)
            manifest_tag = archive_aesgcm.encrypt(manifest_nonce, b"", _canonical_manifest_bytes(manifest_obj))
            manifest_obj["manifest_auth"] = {"nonce": manifest_nonce.hex(), "tag": manifest_tag.hex()}
            manifest_plain = _canonical_manifest_bytes(manifest_obj)
            manifest = manifest_plain
            header_entry_count = len(manifest_entries)
        elif format_minor == 3:
            manifest_obj["security"] = {
                "encryption": "AES-256-GCM",
                "manifest_encryption": "AES-256-GCM",
                "kdf": {
                    "name": "scrypt", "n": SCRYPT_N, "r": SCRYPT_R,
                    "p": SCRYPT_P, "salt": encryption_salt.hex(),
                },
                "scopes": [folder_scopes[path] for path in sorted(folder_scopes)],
            }
            manifest_plain = _canonical_manifest_bytes(manifest_obj)
            if len(manifest_plain) > MAX_MANIFEST_SIZE:
                raise QamzorError("Generated manifest exceeds the format limit")
            manifest_salt = encryption_salt
            manifest_nonce = secrets.token_bytes(12)
            aad = _manifest_aad_v13(manifest_salt, manifest_nonce, SUPPORTED_FLAGS, 0)
            encrypted_manifest = aesgcm_cls(manifest_key).encrypt(manifest_nonce, manifest_plain, aad)
            manifest = V13_MANIFEST_PREFIX.pack(V13_MANIFEST_MAGIC, manifest_salt, manifest_nonce) + encrypted_manifest
            header_entry_count = 0  # Entry count is deliberately hidden in 1.3.
        else:
            manifest = _canonical_manifest_bytes(manifest_obj)
            header_entry_count = len(manifest_entries)

        if len(manifest) > MAX_MANIFEST_SIZE + V13_MANIFEST_PREFIX.size + 16:
            raise QamzorError("Generated manifest exceeds the format limit")
        _validate_entry_set(manifest_entries)
        header = HEADER.pack(
            MAGIC, MAJOR_VERSION, format_minor, SUPPORTED_FLAGS,
            len(manifest), header_entry_count, 0
        )

        checkpoint(0, "publishing")
        fd, temp_output = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=str(target.parent))
        with os.fdopen(fd, "wb") as output:
            output.write(header)
            output.write(manifest)
            spool.seek(0)
            while True:
                block = spool.read(IO_CHUNK_SIZE)
                if not block:
                    break
                checkpoint(0, "publishing")
                output.write(block)
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
            "encrypted": encryption_key is not None,
            "metadata_encrypted": format_minor == 3,
            "protected_folders": len(folder_scopes),
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
        for key_bytes in scope_keys.values():
            # Best-effort key lifecycle hygiene; Python cannot guarantee that
            # immutable key copies have been removed from interpreter memory.
            del key_bytes
        if encryption_key is not None:
            del encryption_key


class QZRAReader:
    """Validated, read-only access to QZRA 1.0, 1.1, encrypted 1.2 and private-metadata 1.3 archives."""

    def __init__(
        self,
        archive_path: os.PathLike | str,
        *,
        password: Optional[str] = None,
        folder_passwords: Optional[dict[str, str]] = None,
    ):
        self.path = Path(archive_path)
        self._password = password
        self._encryption_key: Optional[bytes] = None
        self._manifest_key: Optional[bytes] = None
        self._encryption_salt: Optional[bytes] = None
        self._manifest_auth: Optional[dict[str, str]] = None
        self._manifest_auth_data: Optional[bytes] = None
        self._folder_scopes: dict[str, dict] = {}
        self._scope_by_id: dict[str, dict] = {}
        self._scope_keys: dict[str, bytes] = {}
        self.is_encrypted = False
        self.metadata_encrypted = False
        self._file: BinaryIO = self.path.open("rb")
        try:
            self._load()
            for folder_path, folder_password in (folder_passwords or {}).items():
                self.set_folder_password(folder_path, folder_password)
            if password is not None and self.version_minor == 2:
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
        if self.version_minor == 3:
            raise QamzorError("QZRA 1.3 requires the master password when the archive is opened")
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

    def set_folder_password(self, folder_path: str, password: str) -> None:
        """Unlock one QZRA 1.3 folder scope using its independent password."""
        if self.version_minor != 3:
            raise QamzorError("Independent folder passwords require QZRA 1.3")
        _validate_archive_path(folder_path)
        scope = self._folder_scopes.get(folder_path)
        if scope is None:
            raise QamzorError(f"No independently protected folder exists at: {folder_path}")
        kdf = scope["kdf"]
        folder_master_key = _derive_encryption_key(password, bytes.fromhex(kdf["salt"]))
        check_key = _derive_v13_subkey(folder_master_key, b"folder-check")
        folder_payload_key = _derive_v13_subkey(folder_master_key, b"folder-chunks")
        check = scope["key_check"]
        try:
            _aesgcm_class()(check_key).decrypt(
                bytes.fromhex(check["nonce"]),
                bytes.fromhex(check["tag"]),
                _scope_check_aad(scope["id"], scope["path"]),
            )
        except Exception as exc:
            _raise_authentication_error(exc)
        self._scope_keys[scope["id"]] = folder_payload_key

    unlock_folder = set_folder_password

    def close(self) -> None:
        if not self._file.closed:
            self._file.close()
        # Best-effort release of derived-key references. Python cannot guarantee
        # erasure of immutable bytes or interpreter/runtime copies in memory.
        self._scope_keys.clear()
        self._encryption_key = None
        self._manifest_key = None
        self._password = None

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
        allowed_manifest_size = MAX_MANIFEST_SIZE + (V13_MANIFEST_PREFIX.size + 16 if minor == 3 else 0)
        if manifest_len > allowed_manifest_size:
            raise FormatError("Manifest exceeds the maximum permitted size")
        if minor == 3:
            if entry_count != 0:
                raise FormatError("QZRA 1.3 header entry count must be zero to avoid metadata leakage")
        elif entry_count > MAX_ENTRIES:
            raise FormatError("Archive contains too many entries")
        if HEADER_SIZE + manifest_len > self.file_size:
            raise FormatError("Manifest extends beyond end of archive")
        self._file.seek(HEADER_SIZE)
        raw_manifest = self._file.read(manifest_len)

        self.is_encrypted = minor in {2, 3}
        self.metadata_encrypted = minor == 3
        if minor == 3:
            if password := self._password:
                if len(raw_manifest) < V13_MANIFEST_PREFIX.size + 16:
                    raise FormatError("QZRA 1.3 encrypted manifest is truncated")
                envelope_magic, manifest_salt, manifest_nonce = V13_MANIFEST_PREFIX.unpack_from(raw_manifest)
                if envelope_magic != V13_MANIFEST_MAGIC:
                    raise FormatError("QZRA 1.3 encrypted manifest envelope is invalid")
                self._encryption_salt = manifest_salt
                archive_master_key = _derive_encryption_key(password, manifest_salt)
                self._manifest_key = _derive_v13_subkey(archive_master_key, b"manifest")
                self._encryption_key = _derive_v13_subkey(archive_master_key, b"root-chunks")
                ciphertext = raw_manifest[V13_MANIFEST_PREFIX.size:]
                try:
                    manifest_plain = _aesgcm_class()(self._manifest_key).decrypt(
                        manifest_nonce,
                        ciphertext,
                        _manifest_aad_v13(manifest_salt, manifest_nonce, flags, reserved),
                    )
                except Exception as exc:
                    _raise_authentication_error(exc)
                if len(manifest_plain) > MAX_MANIFEST_SIZE:
                    raise FormatError("Decrypted manifest exceeds the maximum permitted size")
                raw_manifest_json = manifest_plain
            else:
                raise QamzorError("QZRA 1.3 hides filenames and metadata; provide the archive master password when opening")
        else:
            raw_manifest_json = raw_manifest

        try:
            manifest = json.loads(raw_manifest_json.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise FormatError("Manifest is not valid UTF-8 JSON") from exc
        if not isinstance(manifest, dict) or manifest.get("format") != "QZRA":
            raise FormatError("Manifest format marker is invalid")
        if manifest.get("version") != f"{major}.{minor}":
            raise FormatError("Header and manifest versions do not match")
        security = manifest.get("security")
        if minor == 2:
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
        elif minor == 3:
            if not isinstance(security, dict) or security.get("encryption") != "AES-256-GCM" or security.get("manifest_encryption") != "AES-256-GCM":
                raise FormatError("QZRA 1.3 requires AES-256-GCM payload and manifest encryption")
            kdf = security.get("kdf")
            if not isinstance(kdf, dict) or (
                kdf.get("name"), kdf.get("n"), kdf.get("r"), kdf.get("p"), kdf.get("salt")
            ) != ("scrypt", SCRYPT_N, SCRYPT_R, SCRYPT_P, self._encryption_salt.hex()):
                raise FormatError("QZRA 1.3 key derivation metadata is invalid")
            raw_scopes = security.get("scopes", [])
            if not isinstance(raw_scopes, list) or len(raw_scopes) > MAX_FOLDER_SCOPES:
                raise FormatError("QZRA 1.3 protected-folder scope list is invalid")
            for scope in raw_scopes:
                if not isinstance(scope, dict):
                    raise FormatError("QZRA 1.3 folder scope must be an object")
                scope_id, path, scope_kdf, key_check = (
                    scope.get("id"), scope.get("path"), scope.get("kdf"), scope.get("key_check")
                )
                if not isinstance(scope_id, str) or not re.fullmatch(r"[0-9a-f]{32}", scope_id):
                    raise FormatError("QZRA 1.3 folder scope ID is malformed")
                if not isinstance(path, str):
                    raise FormatError("QZRA 1.3 folder scope path is malformed")
                _validate_archive_path(path)
                if path in self._folder_scopes or scope_id in self._scope_by_id:
                    raise FormatError("Duplicate QZRA 1.3 folder scope")
                if not isinstance(scope_kdf, dict) or (
                    scope_kdf.get("name"), scope_kdf.get("n"), scope_kdf.get("r"), scope_kdf.get("p")
                ) != ("scrypt", SCRYPT_N, SCRYPT_R, SCRYPT_P):
                    raise FormatError("QZRA 1.3 folder key derivation parameters are unsupported")
                scope_salt = scope_kdf.get("salt")
                if not isinstance(scope_salt, str) or not re.fullmatch(r"[0-9a-f]{32}", scope_salt):
                    raise FormatError("QZRA 1.3 folder salt is malformed")
                if not isinstance(key_check, dict):
                    raise FormatError("QZRA 1.3 folder key check is missing")
                check_nonce, check_tag = key_check.get("nonce"), key_check.get("tag")
                if not isinstance(check_nonce, str) or not re.fullmatch(r"[0-9a-f]{24}", check_nonce):
                    raise FormatError("QZRA 1.3 folder key-check nonce is malformed")
                if not isinstance(check_tag, str) or not re.fullmatch(r"[0-9a-f]{32}", check_tag):
                    raise FormatError("QZRA 1.3 folder key-check tag is malformed")
                self._folder_scopes[path] = scope
                self._scope_by_id[scope_id] = scope
        elif security is not None or manifest.get("manifest_auth") is not None:
            raise FormatError("Unencrypted QZRA versions cannot contain security metadata")

        raw_entries = manifest.get("entries")
        if not isinstance(raw_entries, list):
            raise FormatError("Manifest entries must be an array")
        if minor == 3:
            if len(raw_entries) > MAX_ENTRIES:
                raise FormatError("Archive contains too many entries")
        elif len(raw_entries) != entry_count:
            raise FormatError("Manifest entry count does not match header")
        self.entries = [ArchiveEntry.from_json(item) for item in raw_entries]
        _validate_entry_set(self.entries)
        if minor == 0 and any(entry.chunks is not None for entry in self.entries):
            raise FormatError("QZRA 1.0 cannot contain chunk references")
        if minor >= 1 and any(entry.kind == "file" and entry.chunks is None for entry in self.entries):
            raise FormatError("QZRA chunked file entries must use chunk references")
        if minor == 2 and any(
            entry.kind == "file" and any(chunk.nonce is None or chunk.scope_id is not None for chunk in (entry.chunks or ()))
            for entry in self.entries
        ):
            raise FormatError("Every QZRA 1.2 chunk must have an AES-GCM nonce and no folder scope")
        if minor < 2 and any(
            entry.kind == "file" and any(chunk.nonce is not None or chunk.scope_id is not None for chunk in (entry.chunks or ()))
            for entry in self.entries
        ):
            raise FormatError("AES-GCM chunk metadata requires QZRA 1.2 or later")
        if minor == 3:
            directory_names = {entry.path for entry in self.entries if entry.kind == "directory"}
            for scope_path in self._folder_scopes:
                if scope_path not in directory_names:
                    raise FormatError("Protected-folder scope does not refer to an explicit directory entry")
            scope_paths_by_length = sorted(self._folder_scopes, key=len, reverse=True)
            for entry in self.entries:
                if entry.kind != "file":
                    continue
                expected_scope_path = next(
                    (candidate for candidate in scope_paths_by_length if entry.path.startswith(candidate + "/")),
                    None,
                )
                expected_scope_id = self._folder_scopes[expected_scope_path]["id"] if expected_scope_path else None
                for chunk in entry.chunks or ():
                    if chunk.nonce is None or chunk.scope_id != expected_scope_id:
                        raise FormatError(f"QZRA 1.3 chunk folder scope does not match its file path: {entry.path}")
        self.payload_offset = HEADER_SIZE + manifest_len
        payload_length = self.file_size - self.payload_offset
        range_descriptors: dict[tuple[int, int], tuple[int, str, str, Optional[str], Optional[str]]] = {}
        for entry in self.entries:
            if entry.kind != "file":
                continue
            if entry.chunks is None:
                blocks = [(entry.offset, entry.compressed_size, entry.size, entry.compression, entry.sha256, None, None)]
            else:
                blocks = [
                    (chunk.offset, chunk.compressed_size, chunk.size, chunk.compression, chunk.sha256, chunk.nonce, chunk.scope_id)
                    for chunk in entry.chunks
                ]
            for offset, compressed_size, original_size, compression, digest, nonce, scope_id in blocks:
                end = offset + compressed_size
                if end > payload_length:
                    raise FormatError(f"Entry data extends beyond archive: {entry.path}")
                if compressed_size == 0:
                    if entry.chunks is not None or original_size != 0:
                        raise FormatError("Only legacy empty files may have zero-length payloads")
                    continue
                key = (offset, end)
                descriptor = (original_size, compression, digest or "", nonce, scope_id)
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
            "metadata_encrypted": self.metadata_encrypted,
            "protected_folders": len(self._folder_scopes),
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
        scope_id: Optional[str] = None,
    ) -> tuple[int, str]:
        source: BinaryIO = self._file
        if self.is_encrypted:
            if nonce is None:
                raise FormatError("Encrypted archive chunk is missing its nonce")
            self._ensure_authenticated()
            if self.version_minor == 3 and scope_id is not None:
                chunk_key = self._scope_keys.get(scope_id)
                if chunk_key is None:
                    scope = self._scope_by_id.get(scope_id)
                    label = scope.get("path", scope_id) if scope else scope_id
                    raise QamzorError(f"Folder is locked; unlock it with its independent password: {label}")
                aad = _chunk_aad_v13(expected_size, expected_digest, compression, scope_id)
            elif self.version_minor == 3:
                chunk_key = self._encryption_key
                aad = _chunk_aad_v13(expected_size, expected_digest, compression, None)
            else:
                chunk_key = self._encryption_key
                aad = _chunk_aad(expected_size, expected_digest, compression)
            self._file.seek(self.payload_offset + offset)
            ciphertext = self._file.read(compressed_size)
            if len(ciphertext) != compressed_size:
                raise FormatError("Truncated encrypted chunk")
            try:
                AESGCM = _aesgcm_class()
                compressed_data = AESGCM(chunk_key).decrypt(
                    bytes.fromhex(nonce),
                    ciphertext,
                    aad,
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
        if self.version_minor == 3:
            matching_scopes = [path for path in self._folder_scopes if entry.path.startswith(path + "/")]
            if matching_scopes:
                required_path = max(matching_scopes, key=len)
                required_id = self._folder_scopes[required_path]["id"]
                if required_id not in self._scope_keys:
                    raise QamzorError(f"Folder is locked; unlock it with its independent password: {required_path}")
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
                    chunk.scope_id,
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

    def preview(
        self,
        path: str,
        *,
        max_bytes: int = 65536,
        folder_passwords: Optional[dict[str, str]] = None,
    ) -> dict:
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
        for folder_path, folder_password in (folder_passwords or {}).items():
            self.set_folder_password(folder_path, folder_password)
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

    def verify(self, *, folder_passwords: Optional[dict[str, str]] = None) -> dict:
        self._ensure_authenticated()
        for folder_path, folder_password in (folder_passwords or {}).items():
            self.set_folder_password(folder_path, folder_password)
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
        folder_passwords: Optional[dict[str, str]] = None,
    ) -> list[str]:
        self._ensure_authenticated()
        for folder_path, folder_password in (folder_passwords or {}).items():
            self.set_folder_password(folder_path, folder_password)
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


def open_archive(
    path: os.PathLike | str,
    *,
    password: Optional[str] = None,
    folder_passwords: Optional[dict[str, str]] = None,
) -> QZRAReader:
    return QZRAReader(path, password=password, folder_passwords=folder_passwords)
