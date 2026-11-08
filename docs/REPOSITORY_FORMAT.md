# QAMZOR Local Repository and Delta Format

This document describes repository version 1 and portable direct-child delta version 1. These are development formats implemented by the Python reference repository manager.

## Repository layout

```text
<repository>/
  catalog.json
  .qamzor-write.lock          # present only while a writer is active
  objects/<first-two-hex>/<sha256>.blob
  snapshots/<snapshot-id>.json
```

`catalog.json` is the ordered snapshot index. Each record includes the snapshot ID, parent ID, creation time, user message, manifest SHA-256 digest, entry/file counts and logical byte count. Snapshot manifests are separate canonical UTF-8 JSON files. Object names are the lowercase SHA-256 digest of the complete original file bytes. Files with the same content are stored once, including across snapshots.

A snapshot manifest is a **complete logical tree**, not a sequence of changes. It records relative POSIX paths, directories, file sizes and object digests. This means any retained snapshot can be restored without replaying its ancestors. The parent ID is retained for history and direct-child delta generation.

## Incremental snapshot behavior

When a new snapshot is created, each input file is streamed and hashed. A new object is published only when its digest is not already present. Existing objects are re-hashed before reuse. Snapshot manifests are published before the catalog is atomically updated. A failed snapshot can leave unreferenced objects; the retention/garbage-collection operation can identify and remove unreachable objects.

The current repository uses **file-level objects**. It does not yet store content-defined chunks across snapshots, use remote object storage, or implement a distributed repository protocol.

## Portable QZDL delta v1

The `.qzd` delta is a binary file with a 32-byte big-endian header followed by a JSON manifest and raw object payloads.

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 8 | Magic | `QZDL\r\n\x1a\n` |
| 8 | 2 | Major | `1` |
| 10 | 2 | Minor | `0` |
| 12 | 4 | Flags | Must be zero |
| 16 | 8 | Manifest length | UTF-8 JSON bytes |
| 24 | 4 | Entry count | Number of entries in the complete target snapshot |
| 28 | 4 | Reserved | Must be zero |

The manifest contains:

- `format: "QAMZOR-DELTA"` and `version: "1.0"`.
- `base_snapshot_id` and `base_manifest_sha256`, which bind the delta to one exact base snapshot.
- `target_manifest`, the complete target snapshot manifest.
- `objects`, a list of unique new file objects with SHA-256, original size, relative payload offset and stored size.

Payload object bytes are currently stored verbatim and streamed. The writer includes only objects referenced by the target but absent from the base snapshot. The importer requires that exact base snapshot, verifies its manifest and referenced objects, validates every payload digest, then publishes the target manifest and catalog record. A delta can only be exported from a snapshot to its direct child. This prevents applying an update to the wrong history point.

## Retention and garbage collection

`qz repo prune` defaults to a dry-run plan. With `--apply`, it updates the catalog to retain the newest requested number of snapshots, then deletes manifests and objects no longer reachable from the retained snapshots. Garbage collection checks reachability from complete manifests rather than relying on parent-chain replay. A crash after catalog publication may leave extra unreferenced files, but should not remove objects referenced by retained snapshots.

## Integrity and trust limitations

Object hashes verify file contents; manifest digests detect accidental manifest changes relative to the local catalog. Neither mechanism authenticates a repository owner against an attacker who can rewrite both the catalog and manifests. A `.qzd` delta is not self-authenticating. For transfer across an untrusted channel, sign the delta file with a trusted Ed25519 key and verify its detached `.qzsig` signature before applying it. Protect the repository directory with operating-system permissions and maintain independent backups.

The lock file prevents cooperating local QAMZOR writers from mutating the repository concurrently. It is an exclusive-create lock, not a distributed lock; after a process crash a stale lock may need manual removal after confirming no writer is active. The current implementation has not undergone crash-injection or multi-process stress qualification.
