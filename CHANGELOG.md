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


## Unreleased — Android complete-folder workflow

- Reworked Android source selection into a queue supporting multiple files and multiple complete folder trees in one archive.
- Added recursive Storage Access Framework folder import with nested and empty directory preservation.
- Added archive browsing that displays both files and directories after integrity verification.
- Strengthened destination directory creation during full extraction and added collision checks.
- Added a Kotlin core regression test for nested folders and empty directories.
- Bumped Android development version to `0.2.0-dev` (versionCode 2).
- The updated APK is pending a successful Gradle build on GitHub and device validation.


## Unreleased — Virtual Archive Explorer and selective in-archive access

- Added Kotlin core immediate-child virtual directory listing, including synthesized directories for archives with implicit parents.
- Added block-by-block file preview that retains only a bounded prefix while verifying all chunk digests and the whole-file SHA-256.
- Added selected file/directory-subtree extraction with file-count/byte quotas, path validation, symlink checks and temporary-file publication; full extraction now uses the same selective engine.
- Added authenticated QZRA 1.2 reading to the Kotlin core using the format-defined scrypt parameters, AES-256-GCM manifest authentication and per-chunk AAD/nonce handling.
- Added Python-generated encrypted QZRA 1.2 cross-language fixture and regression tests for nested navigation, preview, selective extraction and wrong-password rejection.
- Replaced the Android flat manifest list with an interactive Virtual Archive Explorer; added text, raster-image and first-page PDF preview, file/folder extraction actions and password prompt for encrypted archives.
- Android writer remains unencrypted QZRA 1.1; DOCX/XLSX/ODT rendering and audio/video preview remain unsupported.
- Bumped Android development version to 0.3.0-dev (versionCode 3).
- Gradle/JUnit and device validation are pending because Gradle/Android SDK are not available in this workspace.


## Unreleased — Android Virtual Archive Explorer

- Added interactive in-archive folder navigation and breadcrumbs without extracting archive entries.
- Added bounded, integrity-verified in-archive previews for text, raster images and the first PDF page.
- Added extraction of one file, a selected directory subtree or the entire archive.
- Added Kotlin reader support for encrypted QZRA 1.2 using scrypt and AES-256-GCM, including manifest authentication and password prompts.
- Added a Python-generated encrypted QZRA 1.2 fixture and Kotlin regression tests for navigation, preview, selected extraction and incorrect passwords.
- Fixed encrypted-archive detection in Android's browse/extract flows and prevented explorer dialog stacking while navigating.
- Bumped Android development version to 0.4.0-dev (versionCode 4).
- Gradle tests, APK generation and physical-device acceptance remain pending a GitHub Actions run.


## 2026-11-03 — Native QZRA conversion

- Added an explicit `convertToQzra` operation to the shared Kotlin engine and SDK.
- Added `qz convert` as an explicit CLI alias, with enforcement of the `.qzra` destination extension.
- Updated Android and Desktop clients to label the workflow as conversion to the native QZRA container; the Android flow verifies the resulting archive before offering it for saving.
- Added regression tests for mixed files/folders, nested and empty directories, source preservation, archive verification and CLI extension validation.
- Clarified that QZRA conversion is lossless packaging, not a generic document/image/audio/video codec conversion.


## 2026-11-03 — Android dashboard redesign and CI diagnostics

- Replaced the vertically stacked default Android buttons with a dark navy QAMZOR dashboard, responsive two-column action cards, clearer sections, selection/status panels, RTL layout, and consistent spacing.
- Added a full archive integrity-check action to the Android dashboard; it runs `ArchiveEngine.verify` over every stored file and reports verified file/byte counts.
- Kept existing SAF file/folder selection, QZRA conversion, virtual browsing, preview, and extraction operations connected to their existing handlers.
- Added always-uploaded Kotlin core/SDK JUnit XML and HTML reports to the platform workflow, and enabled Gradle stack traces so macOS test failures expose their exact test names and causes.
- Validation in this workspace: Python suite 60/60 passed. Android/Gradle and macOS Kotlin tests could not be executed in this environment; the two remote macOS test failures are not considered fixed until their JUnit reports are inspected and the matrix passes.
