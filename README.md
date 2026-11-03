# QAMZOR

**QAMZOR** is an open-source archive and data-management project. Its native container format is **QZRA** (`.qzra`).

> Current delivery: a tested Python reference implementation with QZRA 1.0/1.1/1.2, chunked compression, within-archive deduplication, optional authenticated encryption, Ed25519 signatures, XOR recovery, virtual search/preview, safe extraction and local snapshot/delta repositories. Initial Kotlin source projects are also present for a shared QZRA 1.1 engine, native Android app, cross-platform JVM desktop app and pre-1.0 SDK. Those Kotlin modules and their GitHub CI builds are not yet remotely verified; Rust core, production security review, native desktop installers and enterprise administration remain open. This is a development implementation, not a production-audited archive system.

## Implemented

- Writes and reads QZRA 1.1 archives and optional encrypted QZRA 1.2; reads legacy QZRA 1.0 archives.
- Content-defined chunks with fixed, documented deterministic boundary rules.
- Independent zlib/DEFLATE or verbatim `store` compression for each chunk.
- Deduplicates identical chunks across files inside the same archive by shared payload references.
- Bounded ordered compression workers (`--workers` 1–32); default is one worker for predictable resource use.
- SHA-256 integrity checks for every chunk and complete file.
- Optional AES-256-GCM payload encryption with scrypt key derivation and authenticated manifest metadata (`qamzor[crypto]`).
- Detached Ed25519 signing and verification with password-protected PKCS#8 private keys.
- Detached XOR recovery sidecars that recover one damaged block per parity group into a separate restored archive.
- Local snapshot repository with immutable manifests, a SHA-256 object store, incremental snapshot reuse, verification, restore, retention planning and garbage collection.
- Virtual archive search and verified bounded previews without extracting files.
- Explicit extraction byte-count and file-count quotas.
- Create, list, inspect, verify and extract CLI commands, including full and selected extraction.
- Additional CLI commands: `search`, `preview`, `keygen`, `sign`, `verify-signature`, `recover create/repair`, and `repo init/snapshot/list/verify/restore/prune/export-delta/apply-delta`.
- Rejects symbolic-link sources, unsafe archive paths, case-insensitive path collisions, partial/invalid payload overlaps, malformed manifests, and unsafe extraction targets.
- Uses temporary files and atomic publication for archive creation and extracted files.
- Supports Python 3.10+ and has a GitHub Actions test workflow.

## Requirements

- Python 3.10 or newer.
- No third-party dependency is needed for unencrypted QZRA 1.0/1.1 operations.
- Install the optional cryptography dependency for encrypted QZRA 1.2 and Ed25519: `python -m pip install -e '.[crypto]'`.

## Install and test

```bash
python -m pip install -e .
python -m unittest discover -s tests -v
qz --help
```

Or run directly from the source tree with `PYTHONPATH=src`.

## Commands

```bash
# Create an archive using the balanced profile and two compression workers
qz create project.qzra ./project --profile balanced --workers 2
# Optional encrypted archive (prompts for the passphrase)
qz create private.qzra ./private --encrypt

# List entries / inspect metadata / verify every file
qz list project.qzra
qz inspect project.qzra
qz verify project.qzra
qz search project.qzra README --kind file
qz preview project.qzra project/README.md --max-bytes 65536

# Extract everything or selected content
qz extract project.qzra --output ./restored
qz extract project.qzra --output ./restored --file project/README.md --max-files 1000 --max-total-bytes 1073741824

# Signing keys, detached signatures and verification
qz keygen --private-key signing-private.pem --public-key signing-public.pem
qz sign project.qzra --private-key signing-private.pem
qz verify-signature project.qzra --public-key signing-public.pem --signature project.qzra.qzsig

# Recovery sidecar and non-destructive repair to a new output file
qz recover create project.qzra --output project.qzra.qzrec
qz recover repair damaged.qzra project.qzra.qzrec --output project.repaired.qzra

# Local backup repository: initialize, snapshot, list, verify and restore
qz repo init ./backup.qamzor
qz repo snapshot ./backup.qamzor ./project --message "before update"
qz repo list ./backup.qamzor
qz repo verify ./backup.qamzor
qz repo restore ./backup.qamzor SNAPSHOT_ID --output ./restored
# Preview retention / garbage-collection plan; add --apply to execute it
qz repo prune ./backup.qamzor --keep-last 10
# Export a direct-child delta and apply it on a repository containing the base snapshot
qz repo export-delta ./backup.qamzor BASE_SNAPSHOT_ID TARGET_SNAPSHOT_ID --output update.qzd
qz repo apply-delta ./restored-backup.qamzor update.qzd
```

Compression profiles are `speed` (zlib level 1), `balanced` (level 6), and `maximum` (level 9). The writer stores a chunk verbatim when compression would not reduce its size. For encrypted `verify`, `extract`, and `preview` commands, the CLI prompts for the password; a protected password file can be supplied with `--password-file`. Install `qamzor[crypto]` for AES-GCM and Ed25519 features. Use `--key-password-file` when signing with an encrypted private key.

## Encryption, signatures and recovery

QZRA 1.2 uses AES-256-GCM with scrypt-derived keys and authenticated manifest metadata. Filenames, file sizes and other manifest metadata remain visible. There is no password recovery. Detached Ed25519 signatures require users to trust and pin the supplied public key through a separate trusted channel. XOR recovery sidecars repair at most one damaged block per parity group; they are not multi-error Reed-Solomon data and do not replace independent backups.

For encrypted archives, `list`, `search` and `inspect` can display the visible manifest without a password; `verify`, `preview` and `extract` require the passphrase. The CLI prompts interactively or reads it from a protected `--password-file`. `qz keygen` prompts for a password and stores the Ed25519 private key as encrypted PKCS#8 PEM.

## Benchmark

Run the reproducible local comparison against Python ZIP/DEFLATE and, when a 7z CLI or buildable libarchive adapter is available, 7z:

```bash
python tools/benchmark.py --size-mib 8 --workers 2 --output benchmark-results/local.json
```

The checked-in `benchmark-results/local-2026-11-03.json` is a single local run on Linux x86_64, Python 3.13.5, zlib 1.3.1 and libarchive 3.7.4. Its dataset contains 6 MiB total: a 2 MiB repeated file, a 2 MiB pseudorandom file and an exact 2 MiB duplicate. QAMZOR verified all data and wrote a 2,105,752-byte archive; ZIP/DEFLATE wrote 2,110,466 bytes; the libarchive 7z writer wrote 2,126,597 bytes. Measured creation times were approximately 1.109 s, 0.063 s and 0.748 s respectively. This fixture demonstrates deduplication behavior, not a general performance ranking; results depend on input data, hardware and implementation.

## Format and security notes

See [`docs/QZRA_FORMAT.md`](docs/QZRA_FORMAT.md), [`docs/REPOSITORY_FORMAT.md`](docs/REPOSITORY_FORMAT.md) and [`docs/SECURITY.md`](docs/SECURITY.md). QZRA 1.0/1.1/1.2 and the repository/delta formats are development specifications and have not had an independent security or interoperability audit. Do not use this prototype as the only copy of important data.

## Project status

See [`STATUS.md`](STATUS.md) for exact completion state and test evidence. The roadmap is in [`ROADMAP.md`](ROADMAP.md). Repository snapshots are full logical tree manifests with file-level content-addressed reuse. Direct-child `.qzd` delta files can be exported and applied when the exact base snapshot is already present in the destination repository. See [`docs/REPOSITORY_FORMAT.md`](docs/REPOSITORY_FORMAT.md) for the repository and delta format details.

## License

Apache-2.0. See [`LICENSE`](LICENSE).


## Android, desktop and Kotlin SDK (initial source implementation)

The repository now includes Gradle modules for a native Android app (`androidApp`), a JVM desktop app (`desktopApp`), a shared Kotlin engine (`core`) and a pre-1.0 Kotlin SDK (`sdk`). These modules provide real create/list/verify/extract flows for QZRA 1.1 in the Kotlin client. The Kotlin implementation rejects encrypted QZRA 1.2 explicitly; use the Python reference CLI for encryption, signatures, recovery and repository workflows.

The Android and desktop projects are source-complete only at an initial integration level. They have not yet been built by Gradle in this execution environment. GitHub Actions is configured in `.github/workflows/platform-builds.yml` to build the debug APK, test the Kotlin core/SDK and package the desktop distribution after the repository is pushed. Do not treat these artifacts as release-ready until those remote jobs pass and emulator/Windows/macOS/Linux tests are recorded.

See [Kotlin implementation status](docs/KOTLIN_IMPLEMENTATION.md), [Kotlin SDK guide](docs/KOTLIN_SDK.md) and [GitHub publishing instructions](GITHUB_SETUP.md).
