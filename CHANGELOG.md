# Changelog

## 0.1.0-dev — 2026-11-03

- Established the QAMZOR repository structure, Apache-2.0 license, contribution guidance and implementation roadmap.
- Added the QZRA 1.0 reference format and Python writer/reader/CLI for create, list, inspect, verify and extract.
- Added path validation, streamed I/O, adaptive compression, SHA-256 integrity checks and selective extraction.

## Unreleased — Phase 2 compression and storage engine

- Added `speed`, `balanced` and `maximum` compression profiles.
- Added deterministic content-defined chunking with documented boundaries.
- Added per-chunk adaptive zlib/store compression.
- Added content-addressed deduplication within each archive while keeping shared physical chunk ranges verifiable.
- Added bounded ordered parallel compression workers and deterministic output ordering.
- Added QZRA 1.1 and compatibility tests for reading QZRA 1.0.
- Added a reproducible local benchmark harness for QAMZOR, ZIP/DEFLATE and optional 7z comparison.

## Unreleased — Phase 3 browsing, protection and recovery

- Added archive path search and bounded, fully verified file previews without extraction.
- Added extraction byte-count and file-count quotas checked before destination creation.
- Added optional QZRA 1.2 AES-256-GCM encryption with scrypt key derivation, per-chunk nonces and authenticated manifest metadata.
- Added detached Ed25519 signatures and encrypted PKCS#8 private-key generation.
- Added XOR recovery sidecars with non-destructive repair of up to one damaged block per parity group.
- Added wrong-password, encrypted-manifest tampering, payload corruption, signature, recovery and deterministic mutation-smoke tests.
- Added a deterministic 2,000-case byte-mutation fuzz campaign and manifest-tampering regression tests.
- Added a local content-addressed snapshot repository with immutable manifests, incremental file-object reuse, verification, restore, retention and garbage collection.
- Added direct-child portable `.qzd` delta export/import, binding each update to an exact verified base snapshot.
- Added `qz repo init/snapshot/list/verify/restore/prune/export-delta/apply-delta` CLI commands.
- Updated architecture, format, security and execution-status documentation to distinguish implemented features from production/security work still open.


## Unreleased — Initial Android, desktop and Kotlin SDK sources

- Added a shared Kotlin/JVM QZRA core with QZRA 1.1 writing, QZRA 1.0/1.1 reading, path/range validation and bounded chunk verification.
- Added Python-generated QZRA 1.0 and 1.1 interoperability fixtures and Kotlin unit tests for reading them.
- Added a native Android app using system document pickers for archive creation, verification/listing and extraction.
- Added a JVM Swing desktop app for archive creation, inspection/verification and extraction.
- Added a pre-1.0 Kotlin SDK facade and usage documentation.
- Added GitHub Actions configuration for Android debug APK build/tests and three-OS desktop distributions.
- These client projects are initial source implementations; Gradle builds, GitHub CI results and device/OS qualification remain pending.


## Unreleased — GitHub Actions CI repairs

- Fixed Python CI package discovery by installing the local `src/` project in editable mode with the `crypto` optional dependency before running tests.
- Changed CLI validation to call the installed `qz` entry point.
- Changed Android SDK setup to accept licenses explicitly and install pinned SDK packages after setup, avoiding the previous setup-action license failure.
- Updated checkout, setup-python, setup-java, setup-android, setup-gradle and upload-artifact to Node 24-compatible action majors.
- Verified editable installation, 58 Python tests without `PYTHONPATH`, the installed CLI and Python compilation locally; remote GitHub Actions must still be rerun.
