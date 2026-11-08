# QZRA Container Format — Versions 1.0 through 1.3

This document specifies the current QZRA reference format. The Python implementation reads QZRA 1.0, 1.1, 1.2 and 1.3. QZRA 1.3 adds an authenticated encrypted manifest (hiding filenames, directory names, sizes and the entry count) plus independently password-protected directory scopes. The format remains a development specification and has not yet received an independent cryptographic or interoperability audit.

## 1. Common byte order, limits and header

All fixed-width integers are unsigned big-endian. The fixed header is 32 bytes, packed as `>8sHHIQII`:

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 8 | Magic | `QZRA\r\n\x1a\n` |
| 8 | 2 | Major | `1` |
| 10 | 2 | Minor | `0`, `1`, `2`, or `3` |
| 12 | 4 | Flags | `0` in current versions |
| 16 | 8 | Manifest length | Number of bytes immediately following the header |
| 24 | 4 | Entry count | Exact count in 1.0–1.2; **must be zero in 1.3** |
| 28 | 4 | Reserved | Must be zero |

Common limits: 64 MiB plaintext manifest, 1,000,000 entries, 1 MiB maximum chunk size. Archive paths are relative POSIX paths, Unicode NFC-normalized, use `/`, and reject traversal, device names, symlinks and portable case-fold collisions.

## 2. QZRA 1.0

A 1.0 manifest is UTF-8 JSON. File entries contain `path`, `type: "file"`, `size`, `offset`, `compressed_size`, `compression`, and `sha256`. Each file uses one independent `zlib` or `store` payload block. Directory entries contain `path`, `type: "directory"`, and `size: 0`.

## 3. QZRA 1.1 and 1.2 chunked entries

A 1.1 or 1.2 file entry contains `path`, `type`, `size`, `sha256`, and an ordered `chunks` array. Each chunk descriptor has `offset`, `compressed_size`, `size`, `compression`, and `sha256`. Version 1.2 adds a 12-byte `nonce` to every non-empty chunk.

Chunk boundaries use the deterministic gear-hash splitter: 64 KiB minimum, 256 KiB target, and 1 MiB maximum. Chunks use `zlib` only when it reduces size; otherwise `store`. The writer deduplicates identical `(SHA-256, original size)` chunks within the archive.

QZRA 1.2 payload encryption uses AES-256-GCM. The archive password is converted to a 256-bit key with scrypt (`N=32768`, `r=8`, `p=1`, random 16-byte salt). Each chunk uses a random 12-byte nonce. Its associated data is:

`QZRA-1.2-CHUNK\0 || uint64_be(original_size) || digest_bytes || compression_ascii`

The 1.2 manifest is readable JSON. It carries the KDF salt and a manifest authentication tag, so names and other manifest metadata are authenticated but **not hidden** in 1.2.

## 4. QZRA 1.3 encrypted manifest envelope

QZRA 1.3 always requires an archive master password. It encrypts the entire canonical JSON manifest, not just an authentication tag. Consequently, filenames, directory names, path hierarchy, file sizes, digests, chunk descriptors, folder-scope definitions and the true entry count are not visible in plaintext.

The 1.3 manifest bytes are a binary envelope followed by AES-GCM ciphertext:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | Envelope magic `QZ13` |
| 4 | 16 | Random archive-manifest scrypt salt |
| 20 | 12 | Random AES-GCM nonce |
| 32 | remainder | Encrypted canonical JSON manifest plus 16-byte GCM tag |

The archive master key is derived with scrypt (`N=32768`, `r=8`, `p=1`, `dkLen=32`) using the envelope salt. That 32-byte KDF output is a key-derivation root only; it is never used directly for AES-GCM. QZRA 1.3 derives separate 256-bit subkeys with HMAC-SHA-256:

`HMAC-SHA256(kdf_root, "QZRA-1.3-SUBKEY\0" || purpose_ascii)`

The registered purpose labels are `manifest`, `root-chunks`, `folder-check`, and `folder-chunks`. This domain separation prevents one AES-GCM use from reusing another use's key. The manifest uses the `manifest` subkey; unprotected file chunks use `root-chunks`; each folder password independently runs scrypt with that scope's salt and derives its own `folder-check` and `folder-chunks` subkeys.

The manifest AES-GCM associated data is:

`QZRA-1.3-MANIFEST\0 || uint16_be(1) || uint16_be(3) || uint32_be(flags) || uint32_be(reserved) || salt || nonce`

The fixed header entry-count field is zero in 1.3; the actual number of entries is read only after successful manifest authentication/decryption. `manifest_length` and total archive length remain visible and may reveal approximate metadata/payload size. QZRA 1.3 does not hide total archive size or access patterns.

After decryption, the canonical JSON manifest contains `format: "QZRA"`, `version: "1.3"`, `entries`, and `security`. `security` contains:

- `encryption: "AES-256-GCM"`
- `manifest_encryption: "AES-256-GCM"`
- archive-master `kdf` parameters and the same salt as the outer envelope
- `scopes`: zero or more independently protected folder records

## 5. Independently password-protected folders

A folder scope record contains:

- `id`: random 16-byte identifier rendered as 32 lowercase hex characters
- `path`: exact archive-relative directory path
- `kdf`: scrypt (`N=32768`, `r=8`, `p=1`) with an independent random 16-byte salt
- `key_check`: AES-GCM nonce and tag that validate the folder password without requiring a non-empty file in that folder

The folder KDF root is derived from that folder's password and its own independent salt. It is split into separate `folder-check` and `folder-chunks` subkeys using the HMAC-SHA-256 domain-separation rule above. The folder key-check authenticates an empty plaintext with associated data:

`QZRA-1.3-SCOPE-CHECK\0 || scope_id_bytes || folder_path_utf8`

For each file, the deepest configured protected ancestor directory selects its folder scope. A nested protected folder uses its own password and **replaces** the parent scope for files below it; passwords are not cumulatively combined. Unprotected files use the archive master key. Protected-folder files use the independent folder key.

Every 1.3 chunk descriptor includes a 12-byte `nonce`; chunks in a protected folder additionally include its `scope_id`. The 1.3 chunk associated data is:

`QZRA-1.3-CHUNK\0 || uint64_be(original_size) || digest_bytes || compression_ascii || 0x00 || scope_id_ascii_or_dash`

A reader must reject a chunk whose scope ID does not match the deepest protected ancestor of its file path. Content deduplication is scoped: identical plaintext chunks may share a payload range only when they use the same scope/key. Cross-scope ciphertext deduplication is deliberately disabled.

The archive master password is required to open the manifest and reveal names. A folder password is then required to preview, verify or extract data in that protected folder. Empty files in a protected folder are also locked by their path scope, even though they have no payload chunks. Up to 32 independent folder scopes are allowed in one archive by this reference implementation.

## 6. Payload layout and validation

The payload begins immediately after the manifest/envelope. Unique physical chunk blocks are contiguous in first-seen order. Shared references must point to exactly the same complete range and must have identical size, compression, digest, nonce and scope metadata. Partial overlaps, gaps, unreferenced trailing bytes, out-of-bounds blocks, invalid paths and inconsistent folder scopes are rejected.

Readers authenticate the 1.3 manifest before exposing any path. Payload chunks are independently authenticated with AES-GCM, decompressed under output limits, and checked against per-chunk and whole-file SHA-256 digests before successful preview, verify or extraction results are returned.

## 7. Compatibility rules

- Readers must support 1.0–1.3 or explicitly reject unsupported versions.
- 1.0 uses one-block file entries.
- 1.1 uses unencrypted chunked entries.
- 1.2 uses visible JSON metadata and encrypted chunks.
- 1.3 uses the `QZ13` encrypted manifest envelope, header entry count zero, encrypted chunks, and optional folder scopes.
- A reader must never interpret an unknown required feature as if it were absent.

## 8. Detached signatures and recovery

Ed25519 signatures remain detached `.qzsig` JSON sidecars covering a domain-separated digest and exact byte length of the full archive. XOR recovery sidecars remain `.qzrec`; they can reconstruct at most one damaged block per parity group. These sidecars do not reveal the encrypted QZRA 1.3 manifest.

## 9. Known limitations / security notes

- Losing the archive master password makes the encrypted manifest and all archive contents unrecoverable.
- Losing a folder password makes that folder's encrypted payload unrecoverable, even if the archive master password remains known.
- Folder passwords protect file payloads; folder paths themselves are hidden at rest by the archive master encrypted manifest, not by the folder password alone.
- QZRA 1.3 metadata and envelope lengths still reveal approximate archive/manifest sizes.
- The reference implementation caps folder scopes at 32 to bound repeated scrypt work.
- No independent security audit, formal verification, side-channel review or broad cross-language interoperability certification has been completed.
- Delta chains, sparse files, symlinks, hard links, ownership, permissions, alternate data streams and remote range access remain unsupported.
