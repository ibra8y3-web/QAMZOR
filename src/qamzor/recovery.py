"""Sidecar XOR recovery data for bounded, single-block-per-stripe repair.

This is erasure recovery, not a replacement for independent backups. A single
bad or missing block in each group can be reconstructed; two bad blocks in one
group cannot. The original archive is never overwritten by this API.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import struct
import tempfile
import zlib
from pathlib import Path

from .format import QamzorError

RECOVERY_MAGIC = b"QZRP\r\n\x1a\n"
RECOVERY_HEADER = struct.Struct(">8sI")
MAX_MANIFEST_SIZE = 64 * 1024 * 1024
MIN_BLOCK_SIZE = 64 * 1024
MAX_BLOCK_SIZE = 4 * 1024 * 1024
MAX_GROUP_SIZE = 16
MAX_ARCHIVE_BLOCKS = 1_000_000
IO_SIZE = 1024 * 1024


class RecoveryError(QamzorError):
    """Recovery data is invalid or the requested damage is not recoverable."""


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _atomic_publish(temp_path: str, output: Path, overwrite: bool) -> None:
    if overwrite:
        os.replace(temp_path, output)
    else:
        try:
            os.link(temp_path, output)
        except FileExistsError as exc:
            raise QamzorError(f"Destination already exists: {output}") from exc
        os.unlink(temp_path)


def create_recovery_file(
    archive_path: os.PathLike | str,
    recovery_path: os.PathLike | str,
    *,
    block_size: int = 1024 * 1024,
    group_size: int = 8,
    overwrite: bool = False,
) -> dict:
    """Create a sidecar with one XOR parity block for each group of blocks."""
    if not isinstance(block_size, int) or isinstance(block_size, bool) or not MIN_BLOCK_SIZE <= block_size <= MAX_BLOCK_SIZE:
        raise QamzorError(f"block_size must be between {MIN_BLOCK_SIZE} and {MAX_BLOCK_SIZE}")
    if not isinstance(group_size, int) or isinstance(group_size, bool) or not 2 <= group_size <= MAX_GROUP_SIZE:
        raise QamzorError(f"group_size must be between 2 and {MAX_GROUP_SIZE}")
    source = Path(archive_path).expanduser().absolute()
    output = Path(recovery_path).expanduser().absolute()
    if source.is_symlink() or not source.is_file():
        raise QamzorError("Archive must be a regular, non-symbolic-link file")
    if source == output:
        raise QamzorError("Recovery sidecar must not overwrite the archive")
    if output.exists() and not overwrite:
        raise QamzorError(f"Destination already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    before = source.stat()
    all_hash = hashlib.sha256()
    block_hashes: list[dict] = []
    groups: list[dict] = []
    spool = tempfile.TemporaryFile(mode="w+b", dir=str(output.parent))
    temp_output = None
    parity_offset = 0
    archive_size = 0
    try:
        with source.open("rb") as stream:
            while True:
                data_blocks: list[bytes] = []
                for _ in range(group_size):
                    data = stream.read(block_size)
                    if not data:
                        break
                    data_blocks.append(data)
                    archive_size += len(data)
                    all_hash.update(data)
                    block_hashes.append({"size": len(data), "sha256": _sha256(data)})
                if not data_blocks:
                    break
                parity = bytearray(block_size)
                for data in data_blocks:
                    for index, value in enumerate(data):
                        parity[index] ^= value
                parity_bytes = bytes(parity)
                encoded = zlib.compress(parity_bytes, 6)
                method = "zlib" if len(encoded) < len(parity_bytes) else "store"
                if method == "store":
                    encoded = parity_bytes
                groups.append({
                    "start_block": len(block_hashes) - len(data_blocks),
                    "count": len(data_blocks),
                    "offset": parity_offset,
                    "compressed_size": len(encoded),
                    "compression": method,
                    "sha256": _sha256(parity_bytes),
                })
                spool.write(encoded)
                parity_offset += len(encoded)
        after = source.stat()
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
            raise QamzorError("Archive changed while recovery data was being created")
        if archive_size != before.st_size:
            raise QamzorError("Archive size changed while recovery data was being created")
        if len(block_hashes) > MAX_ARCHIVE_BLOCKS:
            raise QamzorError("Archive has too many recovery blocks")
        manifest = {
            "format": "QZRP",
            "version": 1,
            "archive_size": archive_size,
            "archive_sha256": all_hash.hexdigest(),
            "block_size": block_size,
            "group_size": group_size,
            "blocks": block_hashes,
            "groups": groups,
        }
        encoded_manifest = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
        if len(encoded_manifest) > MAX_MANIFEST_SIZE:
            raise QamzorError("Recovery manifest exceeds its size limit")
        fd, temp_output = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=str(output.parent))
        with os.fdopen(fd, "wb") as target:
            target.write(RECOVERY_HEADER.pack(RECOVERY_MAGIC, len(encoded_manifest)))
            target.write(encoded_manifest)
            spool.seek(0)
            shutil.copyfileobj(spool, target, length=IO_SIZE)
            target.flush()
            os.fsync(target.fileno())
        _atomic_publish(temp_output, output, overwrite)
        temp_output = None
        return {
            "recovery_file": str(output),
            "archive_size": archive_size,
            "blocks": len(block_hashes),
            "parity_groups": len(groups),
            "block_size": block_size,
            "group_size": group_size,
            "recovery_overhead_bytes": output.stat().st_size,
        }
    finally:
        spool.close()
        if temp_output and os.path.exists(temp_output):
            os.unlink(temp_output)


def _load_recovery(path: Path):
    with path.open("rb") as stream:
        raw = stream.read(RECOVERY_HEADER.size)
        if len(raw) != RECOVERY_HEADER.size:
            raise RecoveryError("Recovery file header is truncated")
        magic, manifest_size = RECOVERY_HEADER.unpack(raw)
        if magic != RECOVERY_MAGIC:
            raise RecoveryError("Invalid recovery file signature")
        if manifest_size > MAX_MANIFEST_SIZE:
            raise RecoveryError("Recovery manifest exceeds its size limit")
        encoded = stream.read(manifest_size)
        if len(encoded) != manifest_size:
            raise RecoveryError("Recovery manifest is truncated")
        try:
            manifest = json.loads(encoded.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise RecoveryError("Recovery manifest is not valid UTF-8 JSON") from exc
        if not isinstance(manifest, dict) or manifest.get("format") != "QZRP" or manifest.get("version") != 1:
            raise RecoveryError("Unsupported recovery data format")
        payload_offset = RECOVERY_HEADER.size + manifest_size
        payload_length = path.stat().st_size - payload_offset
    _validate_manifest(manifest, payload_length)
    return manifest, payload_offset


def _validate_manifest(manifest: dict, payload_length: int) -> None:
    archive_size = manifest.get("archive_size")
    block_size = manifest.get("block_size")
    group_size = manifest.get("group_size")
    blocks = manifest.get("blocks")
    groups = manifest.get("groups")
    archive_digest = manifest.get("archive_sha256")
    if not isinstance(archive_size, int) or isinstance(archive_size, bool) or archive_size <= 0:
        raise RecoveryError("Recovery archive size is invalid")
    if not isinstance(block_size, int) or isinstance(block_size, bool) or not MIN_BLOCK_SIZE <= block_size <= MAX_BLOCK_SIZE:
        raise RecoveryError("Recovery block size is invalid")
    if not isinstance(group_size, int) or isinstance(group_size, bool) or not 2 <= group_size <= MAX_GROUP_SIZE:
        raise RecoveryError("Recovery group size is invalid")
    if not isinstance(archive_digest, str) or not re.fullmatch(r"[0-9a-f]{64}", archive_digest):
        raise RecoveryError("Recovery archive digest is malformed")
    expected_blocks = (archive_size + block_size - 1) // block_size
    if not isinstance(blocks, list) or len(blocks) != expected_blocks or len(blocks) > MAX_ARCHIVE_BLOCKS:
        raise RecoveryError("Recovery block table is inconsistent")
    for index, block in enumerate(blocks):
        expected_size = min(block_size, archive_size - index * block_size)
        if (
            not isinstance(block, dict)
            or not isinstance(block.get("size"), int)
            or isinstance(block.get("size"), bool)
            or block.get("size") != expected_size
        ):
            raise RecoveryError("Recovery block size entry is invalid")
        if not isinstance(block.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", block["sha256"]):
            raise RecoveryError("Recovery block digest is malformed")
    if not isinstance(groups, list) or len(groups) != (expected_blocks + group_size - 1) // group_size:
        raise RecoveryError("Recovery parity group table is inconsistent")
    next_block = 0
    next_offset = 0
    for group in groups:
        if not isinstance(group, dict):
            raise RecoveryError("Recovery parity group is invalid")
        count = group.get("count")
        start = group.get("start_block")
        offset = group.get("offset")
        compressed_size = group.get("compressed_size")
        method = group.get("compression")
        if (
            not isinstance(start, int) or isinstance(start, bool) or start != next_block
            or not isinstance(count, int) or isinstance(count, bool) or not 1 <= count <= group_size
        ):
            raise RecoveryError("Recovery parity groups are not contiguous")
        if not isinstance(offset, int) or isinstance(offset, bool) or offset != next_offset:
            raise RecoveryError("Recovery parity payload offsets are invalid")
        if not isinstance(compressed_size, int) or isinstance(compressed_size, bool) or not 0 < compressed_size <= block_size + 65536:
            raise RecoveryError("Recovery parity block size is invalid")
        if method not in {"zlib", "store"}:
            raise RecoveryError("Unsupported parity compression method")
        if method == "store" and compressed_size != block_size:
            raise RecoveryError("Stored parity block has an invalid size")
        if not isinstance(group.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", group["sha256"]):
            raise RecoveryError("Parity digest is malformed")
        next_block += count
        next_offset += compressed_size
    if next_block != expected_blocks or next_offset != payload_length:
        raise RecoveryError("Recovery parity payload coverage is incomplete")


def _read_parity(recovery_file, payload_offset: int, group: dict, block_size: int) -> bytes:
    recovery_file.seek(payload_offset + group["offset"])
    encoded = recovery_file.read(group["compressed_size"])
    if len(encoded) != group["compressed_size"]:
        raise RecoveryError("Parity payload is truncated")
    if group["compression"] == "store":
        parity = encoded
    else:
        decompressor = zlib.decompressobj()
        try:
            parity = decompressor.decompress(encoded, block_size + 1)
        except zlib.error as exc:
            raise RecoveryError("Parity payload is invalid") from exc
        if len(parity) > block_size or decompressor.unconsumed_tail or decompressor.unused_data or not decompressor.eof:
            raise RecoveryError("Parity payload expands beyond its permitted size")
    if len(parity) != block_size or _sha256(parity) != group["sha256"]:
        raise RecoveryError("Parity block integrity check failed")
    return parity


def repair_archive(
    archive_path: os.PathLike | str,
    recovery_path: os.PathLike | str,
    output_path: os.PathLike | str,
    *,
    overwrite: bool = False,
) -> dict:
    """Repair a copy of an archive, correcting at most one bad block per group."""
    source = Path(archive_path).expanduser().absolute()
    recovery = Path(recovery_path).expanduser().absolute()
    output = Path(output_path).expanduser().absolute()
    if source == output or recovery == output:
        raise QamzorError("Repair output must be different from both input files")
    if source.is_symlink() or not source.is_file():
        raise QamzorError("A regular archive file is required for repair")
    if recovery.is_symlink() or not recovery.is_file():
        raise QamzorError("A regular recovery sidecar is required")
    if output.exists() and not overwrite:
        raise QamzorError(f"Repair output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest, payload_offset = _load_recovery(recovery)
    block_size = manifest["block_size"]
    blocks = manifest["blocks"]
    groups = manifest["groups"]
    temp_output = None
    repaired: list[int] = []
    source_file = source.open("rb")
    recovery_file = recovery.open("rb")
    try:
        fd, temp_output = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=str(output.parent))
        with os.fdopen(fd, "wb") as target:
            for group in groups:
                start = group["start_block"]
                count = group["count"]
                group_blocks: list[bytes | None] = []
                bad_positions: list[int] = []
                for position in range(count):
                    index = start + position
                    expected = blocks[index]
                    source_file.seek(index * block_size)
                    data = source_file.read(expected["size"])
                    if len(data) != expected["size"] or _sha256(data) != expected["sha256"]:
                        group_blocks.append(None)
                        bad_positions.append(position)
                    else:
                        group_blocks.append(data)
                if len(bad_positions) > 1:
                    raise RecoveryError(
                        f"Group starting at block {start} contains {len(bad_positions)} damaged blocks; only one is recoverable"
                    )
                if bad_positions:
                    parity = bytearray(_read_parity(recovery_file, payload_offset, group, block_size))
                    for data in group_blocks:
                        if data is None:
                            continue
                        for index, value in enumerate(data):
                            parity[index] ^= value
                    damaged_position = bad_positions[0]
                    damaged_index = start + damaged_position
                    expected_size = blocks[damaged_index]["size"]
                    recovered = bytes(parity[:expected_size])
                    if _sha256(recovered) != blocks[damaged_index]["sha256"]:
                        raise RecoveryError(f"Recovered block {damaged_index} failed its SHA-256 check")
                    group_blocks[damaged_position] = recovered
                    repaired.append(damaged_index)
                for data in group_blocks:
                    if data is None:
                        raise RecoveryError("Internal recovery failure: unresolved damaged block")
                    target.write(data)
            target.flush()
            os.fsync(target.fileno())
        # Verify the complete reconstructed archive before making it visible.
        full_hash = hashlib.sha256()
        size = 0
        with open(temp_output, "rb") as reconstructed:
            while True:
                data = reconstructed.read(IO_SIZE)
                if not data:
                    break
                full_hash.update(data)
                size += len(data)
        if size != manifest["archive_size"] or full_hash.hexdigest() != manifest["archive_sha256"]:
            raise RecoveryError("Reconstructed archive does not match its recorded whole-file digest")
        _atomic_publish(temp_output, output, overwrite)
        temp_output = None
        return {
            "valid": True,
            "output": str(output),
            "repaired_blocks": repaired,
            "repaired_count": len(repaired),
            "blocks_checked": len(blocks),
            "archive_sha256": full_hash.hexdigest(),
        }
    finally:
        source_file.close()
        recovery_file.close()
        if temp_output and os.path.exists(temp_output):
            os.unlink(temp_output)
