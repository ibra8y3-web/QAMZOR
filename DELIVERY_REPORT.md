# QAMZOR Delivery Report

Date: 2026-11-03

## Verified implementation state

| Phase | State in this delivery |
|---|---|
| 0 — Project foundation | Complete in the source package; remote GitHub CI/publishing not performed |
| 1 — QZRA and core | Tested Python reference implementation; Rust core and production closure remain open |
| 2 — Compression/chunking/dedup/scheduler | Functional acceptance complete in the Python reference implementation |
| 3 — Browsing/extraction/encryption/signatures/recovery | Reference implementation acceptance complete; independent security audit remains open |
| 4 — Snapshots/repository/delta/retention | Reference implementation acceptance complete; large-scale and remote-backend qualification remain open |
| 5 — Android | Virtual Archive Explorer source implemented, including encrypted QZRA 1.2 reader, bounded previews and selected extraction; Gradle build/device validation pending |
| 6 — Windows/Linux/macOS GUI | Initial cross-platform JVM desktop source added; Gradle build/OS validation pending |
| 7 — SDK and enterprise administration | Initial Kotlin SDK facade added; enterprise administration not implemented |

## Implemented capabilities

- QZRA 1.0 legacy reader compatibility, QZRA 1.1 deterministic content-defined chunking, and optional QZRA 1.2 AES-256-GCM encrypted chunks.
- Adaptive zlib/store compression, bounded ordered parallel workers, per-chunk SHA-256 and within-archive content deduplication.
- Archive creation, listing, inspection, verification, selected/full extraction, archive path search, and fully verified bounded file preview.
- Extraction size/file-count quotas and path/symlink/payload-range validation.
- Optional scrypt-derived AES-256-GCM encryption, Ed25519 detached signatures, and XOR recovery sidecars (one damaged block per parity group).
- Local immutable snapshot manifests, SHA-256 file-object reuse across snapshots, snapshot verification, restore, quotas, retention and reachability-based garbage collection.
- Portable `.qzd` direct-child delta export/import, bound to an exact base snapshot and verified before publication.
- CLI command groups for archive, signature, recovery and repository operations.
- Kotlin/JVM core module that writes unencrypted QZRA 1.1 and reads QZRA 1.0/1.1 plus password-authenticated AES-256-GCM QZRA 1.2, with Python-generated compatibility fixtures and Kotlin unit tests.
- Native Android application source with Storage Access Framework operations for adding multiple files and full folder trees (including nested/empty directories), creating archives, interactive virtual browsing, bounded in-archive text/image/PDF previews, selected-file/folder/full extraction, and encrypted QZRA 1.2 password support.
- JVM Swing desktop application source for create, inspect/verify and extract operations; designed to run on Windows, Linux and macOS.
- Pre-1.0 Kotlin/JVM SDK facade over the shared Kotlin core.
- GitHub Actions workflow configured for a three-OS desktop distribution build and Android debug APK build/test.

## Validation evidence

- Existing Python reference suite: **58 tests passed** on Python 3.13.5. The later Kotlin unit tests are authored but have not yet run through Gradle.
- Deterministic byte-mutation campaign: **2,000 inputs**, 1,999 rejected, 1 still verified, zero unexpected exceptions. This is a bounded mutation campaign, not coverage-guided fuzzing.
- `python -m compileall -q src tests tools`: passed.
- Kotlin core, SDK and desktop source syntax check with the locally installed Kotlin 1.9.0 compiler and compile-only serialization stubs: passed; this is not a Gradle build and does not validate Android framework compilation.
- Main CLI and repository CLI help: passed.
- Local wheel build with the preinstalled build backend: passed; isolated target installation, package import and installed CLI help: passed.
- End-to-end CLI smoke suite for encrypted create/verify/preview, path search, Ed25519 key generation/sign/verification, recovery create/repair and extraction: passed.
- Repository CLI lifecycle and portable direct-child delta export/apply: covered by automated tests and passed.
- ZIP/7z benchmark fixture: see `benchmark-results/local-2026-11-03.json`. This is one specific local dataset, not a general performance ranking.

## Explicit blockers and limitations

- No Rust compiler/cargo toolchain is available, so the native Rust core cannot be compiled or certified here.
- No Android SDK, ADB or Gradle installation is available. Android, desktop and SDK source projects have now been added, but no Gradle build, Android APK, emulator test or desktop OS build has been observed yet.
- No remote GitHub repository or Actions run exists in this delivery. The new CI workflow is configured but unexecuted until the repository is pushed to GitHub.
- Windows, macOS and Android device builds/tests have not been run. Desktop distributions are not yet packaged as native MSI/DMG/DEB installers.
- There has been no independent security audit, formal verification or sustained coverage-guided fuzzing.
- Repository objects are file-level (not cross-snapshot content-defined chunks); the local repository is not a remote/distributed service. Portable deltas require the exact base snapshot. Delta and repository metadata are not self-authenticating; sign delta files and verify their detached signature before applying them over an untrusted channel.

## New client source scope and explicit limitations

- Kotlin writer: unencrypted QZRA 1.1 only. Kotlin reader: QZRA 1.0/1.1 and password-authenticated AES-256-GCM QZRA 1.2, with a 16 MiB per-block memory safety ceiling for legacy 1.0 blocks. The Kotlin client does not yet expose signing, recovery sidecars or repository/backup workflows.
- Kotlin interoperability fixtures cover QZRA 1.0, 1.1 and encrypted 1.2; JUnit tests are authored but must pass in remote Gradle CI before interoperability is accepted.
- Android source now includes an interactive Virtual Archive Explorer, bounded verified in-archive text/image/PDF previews, selected file/folder extraction, complete extraction and encrypted QZRA 1.2 password entry. It has not yet been built into a new APK or tested on a device. Office document rendering, audio/video previews, encrypted archive creation, signatures/recovery/repositories, background service integration and lifecycle testing remain open.
- Desktop currently exposes create, inspect/verify and extract. Advanced workflows and native platform installers remain open.
- Kotlin SDK is `0.1.0-dev`, not a stable API/ABI contract. Enterprise RBAC, identity, audit storage, tenant isolation, fleet admin and remote storage are not implemented.

This delivery is a tested Python development reference plus unverified initial Kotlin client sources, not a production-audited replacement for independent backups.


## Android folder workflow patch — 2026-11-03

- Added multi-file plus folder-source queue and recursive SAF folder copying; archive creation now operates on the queued files and directories together.
- Added verified archive contents dialog with file/folder counts and paths.
- Full archive extraction now ensures explicit empty-directory creation and rejects file/directory name conflicts in the target tree.
- Added a Kotlin engine regression test covering nested folders and empty directories.
- Python regression tests rerun after the source changes: **58/58 passed**.
- Android source was parsed by `kotlinc`, with only Android/AndroidX dependency resolution unavailable. No Gradle APK build was possible locally; a fresh GitHub Android workflow run is required to validate the new APK.


## Virtual Archive Explorer source update — 2026-11-03

- Added `ArchiveEngine.browse`, `preview` and `extractSelected` for virtual directory browsing, bounded verified in-archive reads and file/folder/full extraction.
- Added Kotlin parsing, scrypt key derivation and AES-256-GCM authentication/decryption for QZRA 1.2. Python-generated encrypted interoperability fixture added.
- Android source provides folder navigation, text preview (2 MiB retained), raster image preview (16 MiB input limit), first-page PDF preview (16 MiB input limit), password prompt and selected extraction.
- Android version is 0.4.0-dev / versionCode 4.
- The Python reference regression suite remains 58 tests; 10 Kotlin JUnit tests have been authored but not executed with Gradle. No new APK is certified until GitHub Actions builds it and device tests pass.
- Limitations: archive container copied to private cache; DOCX/XLSX/ODT and audio/video are not previewed; Kotlin writer remains unencrypted QZRA 1.1.


## Addendum — Native QZRA conversion

The source tree now includes an explicit conversion operation (`convertToQzra` / `qz convert`) across the core, Android, Desktop and Kotlin SDK. Conversion is defined as lossless packaging inside the native QZRA archive container; it preserves input bytes, filenames and relative paths and does not mutate the source files. Android runs a full archive integrity verification before offering the output `.qzra` file to the Storage Access Framework save dialog. The new Kotlin and Python regression tests are included in the source tree. They remain pending execution in GitHub Actions because Gradle is not installed in this workspace.


## Native conversion addendum — 2026-11-03

- Added explicit lossless file/folder packaging into `.qzra` in the shared Kotlin engine, Android UI, Desktop UI, Kotlin SDK and Python CLI (`qz convert`).
- The Android workflow verifies the new archive before offering it for saving. Inputs remain untouched.
- Added Kotlin regression tests and Python CLI tests. Python suite now passes **60/60**. Installed package smoke test converted a mixed file/folder set, verified the archive, listed entries, extracted them, and compared restored bytes against the originals.
- Kotlin JUnit/Gradle compilation and Android APK/device validation remain pending the GitHub Actions run. This delivery is source code, not a newly built APK.
