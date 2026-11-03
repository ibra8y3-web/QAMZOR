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
| 5 — Android | Initial native Android source added; Gradle build/device validation pending |
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
- Initial Kotlin/JVM core module that writes QZRA 1.1 and reads QZRA 1.0/1.1, with Python-generated QZRA 1.0/1.1 interoperability fixtures and Kotlin unit tests.
- Native Android application source with Storage Access Framework operations for archive creation, verification, listing and extraction.
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

- Kotlin writer: QZRA 1.1 only. Kotlin reader: QZRA 1.0/1.1, with a 16 MiB per-block memory safety ceiling for legacy 1.0 blocks. Encrypted QZRA 1.2 is explicitly rejected by the Kotlin client; use the Python reference implementation for encryption/signatures/recovery/repository features.
- Kotlin interoperability currently has a Python-produced QZRA 1.0 and 1.1 fixture tests authored; that test must run in remote Gradle CI before interoperability is accepted.
- Android currently supports choosing multiple files, creating and saving archives, verifying/listing and extracting to a selected SAF folder. Folder-source selection, QZRA 1.2 crypto, signatures/recovery/repositories, background service integration and device lifecycle testing remain open.
- Desktop currently exposes create, inspect/verify and extract. Advanced workflows and native platform installers remain open.
- Kotlin SDK is `0.1.0-dev`, not a stable API/ABI contract. Enterprise RBAC, identity, audit storage, tenant isolation, fleet admin and remote storage are not implemented.

This delivery is a tested Python development reference plus unverified initial Kotlin client sources, not a production-audited replacement for independent backups.
