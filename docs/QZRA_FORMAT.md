# QZRA Container Format — Versions 1.0, 1.1 and 1.2

This document specifies the QZRA 1.0, 1.1 and 1.2 formats supported by the current Python reference implementation. QZRA 1.0 remains readable for compatibility; 1.1 introduces content-defined chunks; 1.2 adds optional authenticated encryption. The format is still a development specification and has not yet received an independent security or interoperability audit.

## Byte order and limits

- All fixed-width integers are unsigned big-endian.
- Header size: 32 bytes.
- Maximum manifest size: 64 MiB.
- Maximum entry count: 1,000,000.
- File and chunk payload I/O is streamed; files are not loaded wholly into memory.
- Archive paths are relative POSIX paths, Unicode NFC-normalized, and use `/` separators.
- QZRA 1.1 and 1.2 use content-defined chunking with a 64 KiB minimum, 256 KiB target, and 1 MiB maximum chunk size.

## Header (32 bytes)

| Offset | Size | Field | Value / meaning |
|---:|---:|---|---|
| 0 | 8 | Magic | `QZRA\r\n\x1a\n` |
| 8 | 2 | Major | `1` |
| 10 | 2 | Minor | `0` legacy; `1` chunked; `2` encrypted chunked |
| 12 | 4 | Flags | `0` in current versions; unknown bits are rejected |
| 16 | 8 | Manifest length | UTF-8 JSON byte length |
| 24 | 4 | Entry count | Number of manifest entries |
| 28 | 4 | Reserved | Must be zero |

## Manifest envelope

The header is followed immediately by exactly `manifest_length` bytes of UTF-8 JSON. The top-level object contains:

- `format`: exactly `QZRA`.
- `version`: `1.0`, `1.1`, or `1.2`, matching the header.
- `entries`: array whose length equals the header entry count.

Each directory entry contains `path`, `type: "directory"`, and `size: 0`.

## QZRA 1.0 file entry (legacy)

A version 1.0 file entry contains `path`, `type: "file"`, `size`, `offset`, `compressed_size`, `compression`, and `sha256`. It references one independent `zlib` or `store` payload block.

## QZRA 1.1 and 1.2 file entries

A version 1.1 or 1.2 file entry contains:

- `path`: safe relative path.
- `type`: `file`.
- `size`: total uncompressed file size.
- `sha256`: lowercase SHA-256 digest of the complete file bytes.
- `chunks`: ordered array of chunk descriptors.

Each chunk descriptor contains:

- `offset`: byte offset relative to the start of the payload section.
- `compressed_size`: number of stored bytes; must be positive.
- `size`: original chunk size; must be positive.
- `compression`: `zlib` or `store`.
- `sha256`: lowercase SHA-256 digest of the uncompressed chunk.
- `nonce`: present only in QZRA 1.2; 12-byte lowercase hexadecimal AES-GCM nonce.

The sum of chunk `size` values must equal the file's declared size. An empty file has an empty chunk array and the SHA-256 digest of the empty byte string.

## Content-defined chunk boundaries

The reference splitter is deterministic and independent of input read-block sizes. It uses a 32-bit gear state initialized to zero. For every byte value `b`, the state becomes:

`state = ((state << 1) + GEAR[b]) & 0xffffffff`

where:

`GEAR[b] = ((b * 0x9E3779B1) ^ (b << 16) ^ 0xA5A5A5A5) & 0xffffffff`

A chunk boundary is emitted after the minimum size when `(state & 0x3ffff) == 0`, or unconditionally at the maximum size. The state resets to zero after each emitted chunk. Any final non-empty bytes form the last chunk. These constants and rules are part of QZRA 1.1 compatibility.

## Compression and duplicate references

Each chunk is compressed as an independent zlib-wrapped DEFLATE stream only when the compressed bytes are smaller. Otherwise its original bytes are stored using `store`.

The writer uses `(SHA-256, original size)` as the content-address key. Identical chunks in the same archive reference the same physical payload range, including when they occur in different files. This saves payload storage without changing the reconstructed file bytes. The implementation relies on SHA-256 collision resistance; every chunk and complete file is verified during `verify` and extraction.

## QZRA 1.2 authenticated encryption

QZRA 1.2 encrypts each compressed-or-stored unique chunk using **AES-256-GCM** from the optional `cryptography` package. A 12-byte random nonce is recorded in each chunk descriptor; the 16-byte GCM tag is stored with the ciphertext and included in `compressed_size`. The chunk's original size, SHA-256 digest, and compression identifier are authenticated as associated data.

The manifest contains `security` metadata with the algorithm `AES-256-GCM` and fixed scrypt parameters (`N=32768`, `r=8`, `p=1`) plus a random 16-byte salt. Scrypt derives a 256-bit key from the user's UTF-8 passphrase. A separate manifest authentication tag binds the canonical manifest (including names, sizes, chunk references and security parameters) to that key. Readers reject a wrong password or any changed authenticated manifest/chunk metadata.

Encryption does not hide filenames, directory names, file sizes, chunk counts, or other manifest metadata. Password recovery is not provided. Losing the passphrase means encrypted payloads cannot be read. The optional Python dependency is installed with `pip install 'qamzor[crypto]'`.

A reader may accept multiple references to exactly the same `(offset, end)` range only when the original size, compression method, and digest metadata are identical. Partial overlaps, gaps, unreferenced trailing bytes, and inconsistent aliases are invalid.

## Payload layout

The payload starts immediately after the manifest. Unique physical chunk blocks are written contiguously in first-seen order. Manifest references can repeat a block range, but each unique block is stored once. Chunk offsets are relative to the payload start. Directories and empty files have no payload blocks.

## Integrity and extraction

A reader validates the header, manifest schema, entry count, path safety, path collisions, chunk totals, payload bounds, exact shared references, block overlap, and payload coverage before exposing entries. For encrypted archives, password authentication verifies the manifest tag before any file data is trusted. `verify` decompresses/decrypts each chunk while checking its size and digest, then checks the complete file size and digest. Extraction repeats those checks into a temporary file and publishes the destination only after complete-file verification succeeds.

## Compatibility rules

- Readers must support QZRA 1.0, 1.1 and 1.2 or reject unsupported versions explicitly.
- QZRA 1.0 file entries use one-block fields; QZRA 1.1 and 1.2 file entries must use `chunks`.
- QZRA 1.2 requires AES-256-GCM nonces on all chunks and authenticated manifest metadata.
- Unknown major/minor versions, header flags, reserved fields, entry kinds, compression methods, malformed paths, and invalid block ranges are rejected.
- Future required features must use a versioned compatibility policy; readers must not silently interpret unknown required fields.

## Detached protection and recovery files

An Ed25519 signature is stored separately as a `.qzsig` JSON sidecar. The signature covers a domain-separated SHA-256 digest and exact byte length of the complete archive. Verification also checks the supplied public-key fingerprint; users must obtain that public key through a trusted channel.

An XOR recovery sidecar (`.qzrec`) stores one parity block per group and SHA-256 digests for the original archive blocks. It can repair at most one damaged block per parity group and writes a repaired copy; it is not Reed-Solomon coding and cannot reconstruct a wholly missing archive. The recovery sidecar can itself be signed using the detached signature mechanism.

## Not implemented in the current format versions

Delta chains, repository-wide deduplication, sparse files, symlinks, hard links, ownership, permissions, alternate data streams, and remote range access are not implemented by these format versions.
