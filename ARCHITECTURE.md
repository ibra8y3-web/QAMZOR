# Architecture

## Current executable reference implementation

```text
qz CLI
  |
  +-- create_archive / QZRAReader
         |
         +-- QZRA 1.0 legacy block reader
         +-- QZRA 1.1 content-defined chunked writer/reader
         +-- QZRA 1.2 AES-256-GCM encrypted chunks + authenticated manifest
         +-- QZRA 1.3 encrypted manifest + independent protected-folder scopes (Python reference only)
         +-- deterministic chunk splitter (64 KiB / 256 KiB / 1 MiB)
         +-- bounded ordered compression scheduler (1–32 workers)
         +-- within-archive content-addressed deduplication
         +-- zlib/store adaptive codec
         +-- SHA-256 chunk/file verification + bounded decompression
         +-- path validation, search, verified preview and extraction quotas
  |
  +-- Ed25519 detached .qzsig signature tools
  +-- XOR parity .qzrec recovery sidecars
  +-- local ZIP / 7z benchmark tooling
  +-- Repository manager
         +-- immutable snapshot manifests
         +-- SHA-256 content-addressed object store
         +-- incremental file-object reuse across snapshots
         +-- verify / restore / retention / reachability-based garbage collection
```

The Python implementation is a tested reference implementation. It is not the final optimized Rust core and has not been independently audited or certified across operating systems. Encrypted archives and signatures require the optional `cryptography` package. Unencrypted QZRA operations use the Python standard library.

## Format versions

- **QZRA 1.0:** legacy one-block file entries; retained for read compatibility.
- **QZRA 1.1:** content-defined chunk descriptors, adaptive per-chunk zlib/store coding, and repeated references to deduplicated payload ranges.
- **QZRA 1.2:** QZRA 1.1 chunk layout plus optional authenticated encryption. AES-GCM nonces are per unique encrypted chunk; the manifest has an authentication tag.
- **QZRP 1:** detached XOR recovery file. It repairs at most one damaged archive block per parity group and writes a separate output.
- **QZRA-SIGNATURE 1:** detached Ed25519 signature over the complete archive byte length and SHA-256 digest.
- **Repository snapshot 1:** immutable full-tree manifest referencing SHA-256 objects shared across snapshots. This yields incremental storage updates, but no portable delta package format yet.

See `docs/QZRA_FORMAT.md` for binary layout and `docs/SECURITY.md` for trust boundaries and limitations.


## Current multi-client source layout

```text
Python CLI + Python SDK surface
          |
   Python QZRA reference engine
          |
 QZRA 1.0 / 1.1 / 1.2 + repository / signatures / recovery

Android SAF UI -----------+
                           |
Desktop Swing UI ----------+---- Kotlin/JVM QZRA client core ---- QZRA 1.1
                           |          |                          QZRA 1.0 reader
Kotlin/JVM SDK ------------+          +-- create/list/verify/extract
```

The Kotlin client is a separately implemented transitional engine: it writes QZRA 1.1 by default and authenticated AES-256-GCM QZRA 1.2 when a password is supplied; it reads QZRA 1.0/1.1/1.2. The Python reference engine additionally writes and reads QZRA 1.3 encrypted manifests and independent folder scopes. Kotlin QZRA 1.3 interoperability is not implemented and remains an explicit cross-platform blocker. The target long-term architecture remains one audited Rust core with stable bindings; until that exists, cross-language golden fixtures are required to prevent format drift.

## Target architecture

```text
Android / Windows / Linux / macOS UI       CLI / SDK / Server API
                  \                          /
                   +------ QAMZOR Core ------+
                              |
             +----------------+----------------+
             |                |                |
        QZRA Container   Task Scheduler   Repository Manager
             |                |                |
        Manifest/Index   Parallel Workers   Snapshot Catalog
             |                |                |
        Codec Plugins    Resource Limits    Dedup Store
             |                |                |
        Integrity/Vault  Progress/Cancel    Delta/Retention
```

The intended production core is platform-neutral Rust with stable C ABI / language bindings. Android, desktop UIs, CLI and SDKs should call the same core rather than reimplementing archive semantics. Platform-specific storage permissions, background jobs, UI and filesystem integration belong in adapters.

## Design constraints

- The format is versioned independently of any UI.
- Readers reject unsupported required features instead of silently ignoring them.
- Archive creation and extracted-file publication use temporary files and atomic publication where supported.
- Path validation, payload bounds and declared extraction quotas are enforced before file publication.
- Cryptography uses established algorithms and libraries, never custom ciphers.
- New format features require compatibility rules, conformance tests and explicit documentation.
- Repository snapshots must be immutable; garbage collection must prove reachability before deleting content.
- The local repository currently uses file-level objects; chunk-level cross-snapshot reuse and portable delta interchange are future work.
- UI progress, cancellation and error reporting must reflect actual core operations, not simulated state.
