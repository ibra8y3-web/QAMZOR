"""Deterministic content-defined chunking primitives for QZRA.

The splitter is deliberately independent from the on-disk format so its
boundary behavior can be tested before a versioned chunk manifest is frozen.
"""
from __future__ import annotations

from collections.abc import Iterable, Iterator

# Fixed constants make boundaries reproducible across platforms and runs.
_MIN_CHUNK = 64 * 1024
_TARGET_CHUNK = 256 * 1024
_MAX_CHUNK = 1024 * 1024
_MASK = _TARGET_CHUNK - 1
_GEAR = tuple(
    ((i * 0x9E3779B1) ^ (i << 16) ^ 0xA5A5A5A5) & 0xFFFFFFFF
    for i in range(256)
)


def split_content_defined(blocks: Iterable[bytes]) -> Iterator[bytes]:
    """Yield deterministic content-defined chunks from an iterable of bytes.

    Memory use is bounded by the maximum chunk size plus the caller's input
    block. Empty input yields no chunks. Input block boundaries do not affect
    output boundaries.
    """
    pending = bytearray()
    rolling = 0
    for block in blocks:
        if not isinstance(block, (bytes, bytearray, memoryview)):
            raise TypeError("chunk input blocks must be bytes-like")
        for value in block:
            pending.append(value)
            rolling = ((rolling << 1) + _GEAR[value]) & 0xFFFFFFFF
            size = len(pending)
            if size >= _MAX_CHUNK or (size >= _MIN_CHUNK and (rolling & _MASK) == 0):
                yield bytes(pending)
                pending.clear()
                rolling = 0
    if pending:
        yield bytes(pending)


def iter_file_chunks(fileobj, read_size: int = 1024 * 1024) -> Iterator[bytes]:
    """Read a binary file-like object and yield content-defined chunks."""
    if not isinstance(read_size, int) or isinstance(read_size, bool) or read_size <= 0:
        raise ValueError("read_size must be a positive integer")

    def blocks():
        while True:
            block = fileobj.read(read_size)
            if not block:
                return
            yield block

    yield from split_content_defined(blocks())
