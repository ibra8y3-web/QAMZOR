# Execution Status

Last updated: 2026-11-03

This file is the source of truth for implementation progress. A feature is marked complete only when the implementation exists and its relevant tests have been run. “Reference-complete” does not mean production-audited or cross-platform certified.

## Phase 0 — Repository foundation

**Status: COMPLETE within this delivered source package.**

- [x] Project layout, Apache-2.0 license, contribution guidance and project identity.
- [x] `.qzra` extension and QZRA format/security documentation.
- [x] Roadmap, status tracking and changelog.
- [x] GitHub Actions workflow file.
- [ ] Remote GitHub repository publication and an observed remote Actions run (not performed).

## Phase 1 — QZRA format and reference core

**Status: TESTED PYTHON REFERENCE; NOT CLOSED AS A PRODUCTION CORE.**

- [x] Fixed 32-byte QZRA header and JSON manifest parser/writer.
- [x] Legacy QZRA 1.0 reader compatibility.
- [x] QZRA 1.1 chunked archives and QZRA 1.2 encrypted chunked archives.
- [x] Create, list, inspect, verify, full/selected extract, search and preview CLI paths.
- [x] SHA-256 integrity validation, path validation, range/overlap checks and bounded decompression.
- [x] Local tests and CLI end-to-end smoke tests (see Evidence).
- [ ] Port the validated core to Rust and run a language-independent conformance suite.
- [ ] Independent format/security review and cross-platform conformance on Windows/macOS/Linux.

## Phase 2 — Chunking, compression, deduplication and scheduling

**Status: FUNCTIONALLY COMPLETE IN THE PYTHON REFERENCE IMPLEMENTATION; PRODUCTION OPTIMIZATION OPEN.**

- [x] `speed`, `balanced` and `maximum` compression profiles.
- [x] Adaptive `zlib` versus `store` selection for each chunk.
- [x] Deterministic content-defined chunking (64 KiB minimum, 256 KiB target, 1 MiB maximum).
- [x] Within-archive content-addressed deduplication using SHA-256 plus original size.
- [x] Bounded parallel compression scheduler (1–32 workers; bounded pending queue; deterministic output order).
- [x] Reader/writer compatibility tests for chunked QZRA 1.1 and legacy QZRA 1.0.
- [x] Local reproducible comparison against ZIP/DEFLATE and libarchive 7z adapter (single fixture; not a general performance claim).
- [ ] Cross-archive repository deduplication and optimized native implementation belong to Phase 4 / Rust core.

## Phase 3 — Virtual browsing, safe extraction, encryption, signatures and recovery

**Status: REFERENCE IMPLEMENTATION ACCEPTANCE COMPLETE; PRODUCTION SECURITY QUALIFICATION REMAINS OPEN.**

- [x] Archive path search without extraction.
- [x] Bounded file preview that verifies the complete file before reporting the preview as verified.
- [x] Optional extraction quotas by total uncompressed bytes and file count, checked before destination creation.
- [x] Optional QZRA 1.2 AES-256-GCM chunk encryption using scrypt-derived keys, per-chunk random nonces and authenticated manifest metadata (`qamzor[crypto]`).
- [x] Detached Ed25519 archive signatures with encrypted PKCS#8 private keys and caller-supplied trusted public keys.
- [x] XOR recovery sidecars that repair at most one damaged block per parity group into a separate output file.
- [x] Wrong-password, payload-tamper, manifest-tamper, signature, recovery and bounded-mutation smoke tests.
- [x] Documented assets, attacker assumptions, trust boundaries and out-of-scope threats.
- [x] Deterministic mutation smoke test plus a 2,000-case local byte-mutation fuzz campaign (not coverage-guided).
- [x] Phase 3 feature acceptance tests pass in the Python reference implementation.
- [ ] Independent cryptographic/security audit and deeper filesystem race hardening remain production-readiness work.
- [ ] Mountable virtual filesystem and stronger multi-error recovery codes remain future enhancements; current features are path search, verified preview and one-erasure-per-XOR-group recovery.

## Phase 4 — Delta updates, history and repository backup management

**Status: REFERENCE IMPLEMENTATION ACCEPTANCE COMPLETE. Production-scale and remote-backend qualification remain open.**

- [x] Local repository layout with immutable snapshot manifests and a SHA-256 content-addressed object store.
- [x] Incremental snapshot creation reuses unchanged file objects across snapshots.
- [x] Snapshot listing, integrity verification and exact restore with byte/file quotas.
- [x] Direct-child portable `.qzd` delta export/import; the exact base snapshot is required and verified before application.
- [x] Retention planning and reachability-based garbage collection.
- [x] Tests for object reuse, parent history, exact restore, delta portability, wrong-base rejection, delta payload corruption, quotas, symlink guards, writer locking and 60-file restore stress.
- [ ] Crash-injection qualification, multi-process stress at scale, chunk-level repository deduplication and remote/object-storage backend.

## Phase 5 — Android application

**Status: INITIAL SOURCE IMPLEMENTATION ADDED; BUILD AND DEVICE VALIDATION PENDING.**

- [x] Native Android Gradle project and launcher activity.
- [x] Storage Access Framework selection for source files, archive input, output archive and extraction destination.
- [x] Real Kotlin-core operations wired to the app: create QZRA 1.1, inspect/list, verify and extract.
- [x] Android CI workflow configured to assemble a debug APK and run unit-test tasks.
- [ ] Remote Gradle/Android build has not yet run; no APK is currently certified.
- [ ] Emulator/device tests, large-file memory profiling, accessibility, lifecycle and encrypted QZRA 1.2 support remain open.

## Phase 6 — Desktop applications

**Status: INITIAL CROSS-PLATFORM JVM DESKTOP SOURCE ADDED; BUILD AND OS VALIDATION PENDING.**

- [x] Swing desktop application with create, inspect/verify and extract operations.
- [x] Gradle application distribution task and a three-OS CI artifact matrix for Windows, Linux and macOS.
- [ ] Windows and macOS native packaging and OS-specific tests.
- [ ] Remote CI build has not yet run; no desktop installers are certified.
- [ ] Advanced repository, signing, recovery, encryption and batch workflows are not yet exposed in the desktop UI.

## Phase 7 — SDK and enterprise capabilities

**Status: INITIAL KOTLIN SDK FACADE ADDED; ENTERPRISE FEATURES NOT IMPLEMENTED.**

- [x] Pre-1.0 Kotlin/JVM SDK facade over the shared core.
- [x] SDK usage and compatibility documentation.
- [ ] Stable API/ABI policy, versioned releases and language-independent conformance suite.
- [ ] Enterprise identity/RBAC, tenant isolation, audit event storage, fleet administration, remote object-storage backends and policy enforcement.
- [ ] Remote SDK/desktop/Android CI results remain pending.

## Evidence from the 2026-11-03 local execution

- `python -m pip install --no-build-isolation -e ".[crypto]"` — editable installation of the local `qamzor` package passed in the available Python 3.13 environment.
- `env -u PYTHONPATH python -m unittest discover -s tests -v` — **58 Python tests passed** after installation, demonstrating that the suite no longer relies on a manually set `PYTHONPATH`; includes format, chunking, scheduling, encryption, signature, recovery, repository and mutation-smoke tests. Kotlin JUnit tests are authored separately and have not yet run via Gradle.
- `qz --help` — installed console entry point passed.
- `PYTHONPATH=src python tools/fuzz_qzra.py --iterations 2000 --output benchmark-results/fuzz-smoke-2026-11-03.json` — **2,000 deterministic mutations; 1,999 rejected; 1 still verified; 0 unexpected exceptions**. This is not coverage-guided fuzzing.
- `python -m compileall -q src tests tools` — passed.
- `PYTHONPATH=src python -m qamzor.cli --help` — passed.
- `python -m pip wheel . --no-deps --no-build-isolation -w /mnt/data/qamzor-wheelhouse` — wheel built successfully. Isolated build first attempted to download build requirements and was blocked by offline network; the preinstalled setuptools build completed without isolation.
- Repository CLI lifecycle smoke test (init, snapshot, verify) — passed; repository restore/retention/corruption paths are covered by unit tests.
- Portable `.qzd` delta export/import between a copied base repository — passed; corrupted delta payload and incorrect/missing base are rejected without publishing a target snapshot.
- CLI smoke suite — encrypted create/verify/preview, path search, keygen/sign/verify-signature, recovery create/repair and extraction — passed.
- Benchmark fixture: 3 files, 6 MiB total (2 MiB repeated, 2 MiB pseudorandom, 2 MiB exact duplicate). QAMZOR archive 2,105,752 bytes; ZIP/DEFLATE 2,110,466 bytes; libarchive 7z 2,126,597 bytes. Observed create times: about 1.109 s, 0.063 s and 0.748 s respectively. Environment: Python 3.13.5, Linux x86_64, zlib 1.3.1, libarchive 3.7.4. This is one local fixture only.
- GitHub Actions has not been run remotely. The Android, desktop and Kotlin SDK modules were added after the local Python evidence above, so they are not included in the 58-test Python count. No Android/desktop Gradle build or device/OS test has yet been run.
- No Rust compiler/cargo or Gradle installation was found in the current environment.

## Completion policy

Do not mark a phase complete from a roadmap entry, placeholder, mock, or unexecuted build. External services, toolchains and devices must be explicitly identified as blockers until their results are available.


## CI repair — 2026-11-03

**Status: WORKFLOW CONFIGURATION PATCHED; REMOTE GITHUB RUN PENDING.**

- [x] Python workflow installs the local `src/` package in editable mode using `python -m pip install -e ".[crypto]"` before `unittest` discovery.
- [x] Python workflow checks the installed console script with `qz --help` rather than relying on a separate `PYTHONPATH` override.
- [x] Android setup skips the setup action's automatic license prompt, then explicitly accepts SDK licenses and installs `platform-tools`, `platforms;android-35` and `build-tools;35.0.0`.
- [x] Updated checkout/setup-python/setup-java/setup-android/setup-gradle/upload-artifact actions to Node 24-compatible major versions to address the deprecation warnings in the diagnostic log.
- [x] Local editable install, full test discovery without `PYTHONPATH`, CLI entry point and Python compile checks pass.
- [ ] GitHub-hosted Python matrix and Android Gradle workflow have not been rerun from this environment; remote success is not yet confirmed.
