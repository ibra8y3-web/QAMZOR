"""Integrity-checked splitting and joining for large QZRA archives."""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import secrets
import tempfile
from pathlib import Path

from .format import MAGIC, QamzorError, FormatError, HEADER_SIZE

_MAX_PARTS = 100_000
_MAX_SET_BYTES = 16 * 1024 * 1024
_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,240}$")


def _json_no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _sha256_file(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
            total += len(block)
    return digest.hexdigest(), total


def _atomic_write(path: Path, data: bytes, *, overwrite: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if overwrite:
            os.replace(temp_name, path)
        else:
            os.link(temp_name, path)
            os.unlink(temp_name)
    finally:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass


def split_archive(
    archive_path: os.PathLike | str,
    output_directory: os.PathLike | str,
    *,
    part_size: int = 100 * 1024 * 1024,
    overwrite: bool = False,
) -> dict:
    original_source = Path(archive_path).expanduser()
    if original_source.is_symlink():
        raise QamzorError("Split source cannot be a symbolic link")
    source = original_source.resolve(strict=True)
    if not source.is_file():
        raise QamzorError("Split source must be a regular archive file")
    if source.stat().st_size < HEADER_SIZE:
        raise FormatError("Input is too small to be a QZRA archive")
    with source.open("rb") as stream:
        if stream.read(len(MAGIC)) != MAGIC:
            raise FormatError("Split input is not a QZRA archive")
    if not isinstance(part_size, int) or isinstance(part_size, bool) or part_size < 1024 * 1024:
        raise QamzorError("Part size must be at least 1 MiB")
    requested_output = Path(output_directory).expanduser().absolute()
    current = requested_output
    while True:
        if current.is_symlink():
            raise QamzorError(f"Split output path cannot contain a symbolic-link component: {current}")
        if current.parent == current:
            break
        current = current.parent
    requested_output.mkdir(parents=True, exist_ok=True)
    output = requested_output.resolve(strict=True)
    archive_name = source.name
    if not _NAME_RE.fullmatch(archive_name):
        raise QamzorError("Archive name contains characters unsupported by portable split sets")
    set_path = output / f"{archive_name}.qzset"
    if set_path.is_symlink():
        raise QamzorError("Split-set descriptor cannot be a symbolic link")
    if set_path.exists() and not overwrite:
        raise QamzorError(f"Split-set descriptor already exists: {set_path}")
    old_parts_directory = None
    if set_path.is_file():
        try:
            old_descriptor = json.loads(set_path.read_text(encoding="utf-8"), object_pairs_hook=_json_no_duplicate_keys)
            old_parts_name = old_descriptor.get("parts_directory") if isinstance(old_descriptor, dict) else None
            if (old_descriptor.get("format") == "QZRA-PART-SET" and old_descriptor.get("version") == 2
                    and isinstance(old_parts_name, str)
                    and re.fullmatch(re.escape(archive_name + ".parts-") + r"[0-9a-f]{12}", old_parts_name)):
                old_parts_directory = output / old_parts_name
        except (OSError, ValueError, UnicodeError):
            old_parts_directory = None
    archive_digest, archive_size = _sha256_file(source)
    part_count = (archive_size + part_size - 1) // part_size
    if part_count > _MAX_PARTS:
        raise QamzorError("Archive requires too many split parts")

    # Each split generation owns a unique immutable parts directory. Replacing
    # the descriptor is the single publication point, so an older set remains
    # usable if a new split fails before descriptor publication.
    parts_directory = f"{archive_name}.parts-{secrets.token_hex(6)}"
    parts_directory_path = output / parts_directory
    parts_directory_path.mkdir(mode=0o700)
    parts: list[dict] = []
    stream_digest = hashlib.sha256()
    stream_total = 0
    try:
        with source.open("rb") as input_stream:
            for index in range(part_count):
                name = f"{archive_name}.part{index + 1:05d}"
                target = parts_directory_path / name
                fd, temp_name = tempfile.mkstemp(prefix=f".{name}.", suffix=".tmp", dir=parts_directory_path)
                digest = hashlib.sha256()
                size = 0
                try:
                    with os.fdopen(fd, "wb") as part_stream:
                        remaining = min(part_size, archive_size - index * part_size)
                        while remaining:
                            block = input_stream.read(min(1024 * 1024, remaining))
                            if not block:
                                raise QamzorError("Source archive changed or was truncated while splitting")
                            part_stream.write(block)
                            digest.update(block)
                            stream_digest.update(block)
                            size += len(block)
                            stream_total += len(block)
                            remaining -= len(block)
                        part_stream.flush()
                        os.fsync(part_stream.fileno())
                    os.link(temp_name, target)
                finally:
                    try:
                        os.unlink(temp_name)
                    except FileNotFoundError:
                        pass
                parts.append({"name": name, "size": size, "sha256": digest.hexdigest()})
        if stream_total != archive_size or stream_digest.hexdigest() != archive_digest:
            raise QamzorError("Source archive changed while it was being split; partial volumes were discarded")
        descriptor = {
            "format": "QZRA-PART-SET",
            "version": 2,
            "archive_name": archive_name,
            "archive_size": archive_size,
            "archive_sha256": archive_digest,
            "part_size": part_size,
            "parts_directory": parts_directory,
            "parts": parts,
        }
        _atomic_write(set_path, (json.dumps(descriptor, sort_keys=True, separators=(",", ":")) + "\n").encode(), overwrite=overwrite)
        if old_parts_directory and old_parts_directory != parts_directory_path and old_parts_directory.is_dir() and not old_parts_directory.is_symlink():
            shutil.rmtree(old_parts_directory, ignore_errors=True)
        return {"set": str(set_path), "archive": archive_name, "parts_directory": str(parts_directory_path), "parts": len(parts), "archive_size": archive_size, "archive_sha256": archive_digest}
    except Exception:
        shutil.rmtree(parts_directory_path, ignore_errors=True)
        raise


def join_archive(
    set_path: os.PathLike | str,
    output_path: os.PathLike | str,
    *,
    overwrite: bool = False,
) -> dict:
    original_descriptor = Path(set_path).expanduser()
    if original_descriptor.is_symlink():
        raise QamzorError("Split-set descriptor cannot be a symbolic link")
    descriptor_path = original_descriptor.resolve(strict=True)
    if descriptor_path.stat().st_size > _MAX_SET_BYTES:
        raise FormatError("Split-set descriptor exceeds its size limit")
    try:
        descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"), object_pairs_hook=_json_no_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise FormatError("Split-set descriptor is not valid JSON") from exc
    if not isinstance(descriptor, dict) or descriptor.get("format") != "QZRA-PART-SET" or type(descriptor.get("version")) is not int or descriptor.get("version") not in {1, 2}:
        raise FormatError("Unsupported split-set descriptor")
    archive_name = descriptor.get("archive_name")
    archive_size = descriptor.get("archive_size")
    archive_digest = descriptor.get("archive_sha256")
    parts = descriptor.get("parts")
    part_size = descriptor.get("part_size")
    if not isinstance(archive_name, str) or not _NAME_RE.fullmatch(archive_name):
        raise FormatError("Invalid archive name in split-set descriptor")
    if not isinstance(archive_size, int) or isinstance(archive_size, bool) or archive_size < HEADER_SIZE:
        raise FormatError("Invalid archive size in split-set descriptor")
    if not isinstance(part_size, int) or isinstance(part_size, bool) or part_size < 1024 * 1024:
        raise FormatError("Invalid part size in split-set descriptor")
    if not isinstance(archive_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", archive_digest):
        raise FormatError("Invalid archive digest in split-set descriptor")
    expected_count = (archive_size + part_size - 1) // part_size
    if not isinstance(parts, list) or not 1 <= len(parts) <= _MAX_PARTS or len(parts) != expected_count:
        raise FormatError("Split-set part list is incomplete or excessive")
    if descriptor.get("version") == 2:
        parts_directory = descriptor.get("parts_directory")
        expected_prefix = archive_name + ".parts-"
        if not isinstance(parts_directory, str) or not re.fullmatch(re.escape(expected_prefix) + r"[0-9a-f]{12}", parts_directory):
            raise FormatError("Split-set parts directory is invalid")
        parts_root = descriptor_path.parent / parts_directory
        if parts_root.is_symlink() or not parts_root.is_dir():
            raise QamzorError("Split-set parts directory is missing or unsafe")
    else:
        parts_root = descriptor_path.parent
    output = Path(output_path).expanduser().absolute()
    current = output.parent
    while True:
        if current.is_symlink():
            raise QamzorError(f"Destination parent cannot be a symbolic link: {current}")
        if current.parent == current:
            break
        current = current.parent
    if output.exists() and not overwrite:
        raise QamzorError(f"Destination already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    digest = hashlib.sha256()
    total = 0
    try:
        with os.fdopen(fd, "wb") as merged:
            for index, part in enumerate(parts, 1):
                expected_name = f"{archive_name}.part{index:05d}"
                if not isinstance(part, dict) or part.get("name") != expected_name:
                    raise FormatError("Split-set part names are invalid or out of order")
                part_digest = part.get("sha256")
                part_size_expected = part.get("size")
                expected_size = min(part_size, archive_size - (index - 1) * part_size)
                if not isinstance(part_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", part_digest):
                    raise FormatError("Split part digest is malformed")
                if part_size_expected != expected_size:
                    raise FormatError("Split part size is inconsistent")
                part_path = parts_root / expected_name
                if part_path.is_symlink() or not part_path.is_file():
                    raise QamzorError(f"Split part is missing or unsafe: {expected_name}")
                part_hash = hashlib.sha256()
                part_total = 0
                with part_path.open("rb") as stream:
                    while True:
                        block = stream.read(1024 * 1024)
                        if not block:
                            break
                        part_hash.update(block)
                        digest.update(block)
                        merged.write(block)
                        part_total += len(block)
                        total += len(block)
                if part_total != expected_size or part_hash.hexdigest() != part_digest:
                    raise FormatError(f"Split part integrity check failed: {expected_name}")
            merged.flush()
            os.fsync(merged.fileno())
        if total != archive_size or digest.hexdigest() != archive_digest:
            raise FormatError("Reassembled archive size or SHA-256 does not match the descriptor")
        with open(temp_name, "rb") as merged:
            if merged.read(len(MAGIC)) != MAGIC:
                raise FormatError("Reassembled file is not a QZRA archive")
        if overwrite:
            os.replace(temp_name, output)
        else:
            os.link(temp_name, output)
            os.unlink(temp_name)
        return {"archive": str(output), "bytes": total, "sha256": digest.hexdigest(), "valid": True}
    finally:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
