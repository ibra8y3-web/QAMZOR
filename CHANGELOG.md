# Changelog

## 0.9.0 — File search/sort, archive metadata and verified split sets (2026-11-04)

- Android: the first tab is now a file browser for the whole phone (internal storage and SD card) like RAR. Long-press to select; tap a file to select it; tap a folder to enter it.
- Android: **Archive** and **Encrypt** act on the original item. A `.qzra` file is created next to it, fully verified (every chunk and SHA-256), checked against a before/after snapshot of the source, and only then is the original deleted. If anything fails the original is left untouched and the partial archive is removed.
- Android: **Encrypt** asks for a master password (min. 10 characters, confirmed), then optionally asks for independent passwords for up to 32 selected folder scopes. It writes QZRA 1.3 with AES-256-GCM, scrypt and encrypted metadata. Opening prompts for the master password; entering a protected folder prompts for its independent password.
- Android: `.qzra` rows show the QAMZOR icon (and a lock when password-protected). Tapping one offers open/browse, restore original, and integrity check. Restore extracts into a hidden work folder, verifies, then moves into place.
- Android: the in-place vault path works on the selected original file/folder and writes a verified `.qzra` beside it; source deletion is configurable. Direct path opens do not copy the archive, while SAF-provided archive streams may be copied to private cache for random access. Closing an archive never deletes the user's original archive.
- Android: needs "All files access" (MANAGE_EXTERNAL_STORAGE) on Android 11+, runtime storage permission on Android 8–10. Sideloaded builds only.
- Android: added live search by file/folder name and extension in the current phone directory, plus persistent sorting by modified date (newest/oldest), name (ascending/descending), size and file type. Directories remain grouped first.
- Android: added search and relevant sort modes to each opened archive directory. Archive timestamps are intentionally not offered because QZRA entries do not store per-file modification dates.
- Android: opened archive details now display the actual QZRA format version, encryption/metadata privacy state, file and folder counts, and uncompressed size after manifest authentication.
- Kotlin Core: added integrity-checked split/join for large QZRA archives. It writes Python-compatible version-2 `.qzset` descriptors, SHA-256 hashes for every part and the complete archive, and publishes a reassembled archive only after all checks pass.
- Android: added file-browser actions to split a `.qzra` archive into configurable parts and reassemble a `.qzset` file. Original archive/part files are retained.
- Unified package, Android, Desktop, SDK and app version metadata at `0.9.0` (Android versionCode remains monotonic at 10).
- Added Kotlin split-set round-trip, corruption and overwrite-protection tests. Gradle/JUnit/APK execution still requires a Gradle + Android SDK environment.
- Android: «استخراج» inside an opened archive that lives in storage no longer opens a folder picker: the whole archive is restored next to itself (with the option to delete the archive), and a single item is extracted beside the archive.
- Core: new `InPlaceVault` (`seal`, `restore`, `restoreItem`, `isEncrypted`) with JUnit tests in `InPlaceVaultTest`.
- Not built or run in this workspace: Kotlin/Gradle compile, JUnit and APK must be verified by GitHub Actions.

### 0.9.0 maintenance completion — Android safety, Settings and scoped password validation

- Added a dedicated Android Settings page for hidden files, directory-first sorting, keeping/deleting the source after verified archiving, destructive-action confirmations, private temp cleanup of entries untouched for at least 24 hours, and operation-history retention.
- Connected the settings to actual browser, archive and maintenance behavior; added protection against cleaning the active archive session or starting cleanup during a running operation.
- Repaired Android folder-password snapshots and changed independent-password validation to use the authenticated AES-GCM folder key-check record in QZRA 1.3. Listing validates the supplied scope key-check; verify/preview/extract additionally read payload bytes and check SHA-256.
- Completed independent folder-password collection for Android creation, restore, single-item extraction, verification and archive comparison; Desktop now collects separate folder passwords for create, inspect/verify, compare and extract.
- Added rollback of partially moved restore outputs if a later move fails. If archive deletion fails after successful restoration, keep both restored data and the archive and report the deletion failure.
- Moved large phone-file listing/sorting to a dedicated executor; Desktop file tasks use SwingWorker and keep Swing UI work on the EDT.
- Corrected the protected-folder selection label, the file-selection safety summary and the cleanup status message; fixed encryption detection to require a valid QZRA signature.
- Updated Android documentation and the cloud adapter User-Agent to application version 0.9.0.
- Validation boundary: Python regression tests can run locally; Android Gradle/JUnit/APK and real-device validation still require a configured Android SDK/Gradle runner. Android operations are not durable WorkManager jobs.

## 0.8.0-dev — Android UI rework and folder-password UX (2026-11-04)

- Android: new layout with a compact bottom navigation bar (Create / Open / Tools / Operations), smaller icons and one persistent status line; the old dashboard grid and dialog-based explorer are gone.
- Android: archive protection is one switch with inline password fields; hiding file names is on by default (QZRA 1.3).
- Android: tap the lock icon next to any folder in the selection tree to give that folder its own password (up to 32). Creating no longer fails with an uncaught exception when nothing is selected.
- Android: the archive explorer lives in the Open page; protected folders show a lock and ask for their own password when entered. Master password shows names; folder password is needed to read, verify or extract.
- Android: `.qzra` files open directly in QAMZOR from file managers (VIEW intent). Plain-text working copies in the app cache are deleted after saving, on closing an archive, and at next launch.
- Core: `ArchiveEngine.protectedFolders(archive, masterPassword)` lists folders that have an independent password.
- Not built or run in this workspace: Kotlin/Gradle compile, JUnit and APK must be verified by GitHub Actions.


## 0.7.2-dev — CI failure corrections — 2026-11-03

- Fixed Android operation-history restoration by materializing the filtered `Sequence` before applying `takeLast(100)`.
- Replaced the invalid Kotlin synthetic property `singleLine` with the explicit Android `TextView.setSingleLine(true)` call.
- Corrected Kotlin archive content comparison to compare payload files only; parent directory records are structural metadata and are no longer included in added/removed/changed/unchanged file-content lists.
- Kept SHA-256 verification of both archives before comparing file digests; no tests were disabled and no integrity checks were removed.
- Re-ran the Python regression suite after the patch: 90 passed, 9 subtests passed. Gradle/JUnit and Android APK builds still require the GitHub Actions runner.

## 0.7.2-dev — 2026-11-03

- Added QZRA 1.3 reader/writer paths to Kotlin Core: encrypted manifest, hidden entry count, purpose-separated subkeys, independent folder scopes, scope-bound chunk authentication and scope-aware deduplication.
- Added Python-generated QZRA 1.3 golden-fixture tests and Kotlin writer/reader tests for master password, folder password, metadata secrecy and selective extraction. Gradle/JUnit execution is pending GitHub Actions.
- Integrated QZRA 1.3 metadata hiding and independent folder-password entry/unlock flows into the Android source.
- Persisted Android operation history and detect/log tasks interrupted by a prior app-process restart; no durable WorkManager job execution is claimed.
- Added Python cloud-provider adapters for Google Drive API v3, Dropbox API v2, Microsoft Graph OneDrive and AWS Signature V4 S3-compatible endpoints, including resumable uploads, S3 multipart, SHA-256 sidecars and verified atomic restore.
- Added `qz cloud-provider push/pull` and runtime credential environment variables; live external-account integration is not yet qualified.
- Added cloud-connector documentation, expanded provider regression tests, and advanced Python/Android/SDK development versions to 0.7.2-dev.
- Local Python regression suite: 90 passed, 9 subtests passed.

## 0.7.0-dev — 2026-11-03

- Unified the active source tree around the newest QZRA 1.3 Python reference and Android folder-import/password sources.
- Aligned Python, Android, Desktop and Kotlin SDK development version metadata.
- Made Python archive comparison verify all payload bytes and SHA-256 digests by default, with separate QZRA 1.3 folder-password inputs.
- Added verified SHA-256 archive comparison to Kotlin Core and surfaced it through SDK, Android and Desktop.
- Added an in-session Android operation history for running, completed and failed tasks.
- Documented platform boundaries explicitly: Kotlin remains on QZRA 1.2; Office visual rendering and cloud-provider adapters remain outstanding.


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


## 2026-11-03 — Folder import hardening and optional Android archive password

- Hardened Android Storage Access Framework folder import: recursive traversal, stable document-ID cycle detection, unknown-MIME file fallback via `openInputStream`, clear copy failures, cleanup of partial staging trees, and folder/file/byte counts.
- Added an optional Android password-protection switch and password confirmation flow.
- Extended the Kotlin QZRA writer to emit authenticated AES-256-GCM QZRA 1.2 using scrypt, matching the existing reader and Python format constants. Unencrypted archives remain QZRA 1.1.
- Added encrypted writer/reader round-trip and SDK tests. These Gradle tests are authored but require GitHub Actions to execute; no remote pass is claimed.
- Password protection is archive-wide. QZRA 1.2 authenticates but does not hide manifest paths/names. Per-folder password slots and encrypted metadata require a future format revision.

## 2026-11-03 — QZRA 1.3 reference implementation

- Added QZRA 1.3 encrypted-manifest envelope using AES-256-GCM and scrypt.
- Encrypted the complete manifest to hide file names, folder names, paths, per-entry sizes/digests and the true entry count.
- Added up to 32 independent folder password scopes with per-scope salts and authenticated password checks.
- Added scope-bound chunk encryption and disabled cross-scope ciphertext deduplication.
- Added HMAC-SHA-256 purpose separation for manifest, root payload, folder key-check and folder payload encryption keys.
- Added folder unlock support to the Python reader, preview, verify and extraction APIs.
- Added CLI support for `--hide-metadata`, repeated `--protect-folder`, master password files and folder password files.
- Preserved readers for QZRA 1.0, 1.1 and 1.2.
- Added five QZRA 1.3 tests for metadata secrecy, wrong passwords, independent/nested scopes, invalid scopes and empty protected folders.
- Kotlin/Android QZRA 1.3 support is not included in this change and remains a compatibility blocker for opening 1.3 archives directly in the Android app.

## 2026-11-03 — QAMZOR product feature expansion (Python reference core)

- Added Smart Exclusions to archive creation: repeated `--exclude`, `--exclude-from`, and root/nested `.qzignore` rules. Directory exclusions prune traversal; negated rules are explicitly rejected rather than partially honored.
- Added integrity-checked split volumes (`qz split`) with a `.qzset` descriptor and verified reassembly (`qz join`). Every part and the final byte stream are checked with SHA-256 before publication.
- Added archive comparison (`qz compare`) reporting added, removed, changed and unchanged entries from authenticated manifest metadata without extraction. QZRA 1.3 comparison requires both master passwords.
- Added bounded text preview extraction for DOCX, XLSX and PPTX (`qz preview --office`). The selected QZRA file is fully decoded and SHA-256 verified; ZIP member count, expansion size and compression ratio are capped. This is text extraction, not Office layout rendering.
- Added a WebDAV-compatible backup adapter (`qz cloud push/pull`) requiring HTTPS outside loopback. Uploads include a size/SHA-256 sidecar; downloads are staged, verified and atomically published. Credentials are runtime-only; native vendor APIs are not included.
- Added `JobCenter` API with bounded background archive workers, progress persistence, pause/resume/cancel checkpoints, owner-only state files, and password redaction. In-flight jobs are marked interrupted after process restart rather than being resumed blindly.
- Confirmed existing Recovery, Ed25519 Secure Sharing and Duplicate Intelligence remain in the engine; no duplicate placeholder implementations were added.
- Added feature documentation, safety boundaries and regression tests for exclusions, volumes, comparison, Office preview, WebDAV and job control.

## Unreleased — Product feature expansion (2026-11-03)

- Added Smart Exclusions via CLI rules and root/nested `.qzignore` files.
- Added integrity-checked split/join volumes and archive comparison.
- Added bounded DOCX/XLSX/PPTX text preview.
- Added WebDAV HTTPS backup transport with SHA-256 sidecars and verified atomic downloads.
- Added a persistent Python JobCenter API with progress, checkpoint pause/resume/cancel, and secret redaction.
- Added product feature documentation in English and Arabic; Kotlin/Android/Desktop UI integration remains open.

## 0.9.0 maintenance — Android operations, snapshots, secrets and WebDAV

- Added a WorkManager foreground job center for QZRA seal/restore/verify, local snapshot backup/verify/restore and HTTPS WebDAV push/pull/sync. Operations publish phase, byte progress, speed, estimated remaining time and file counters where available; cooperative pause/resume/cancel controls stop at safe engine checkpoints.
- WorkManager persists scheduled work over Activity closure/reboot. After process death, running tasks are retried from the start of the transaction; exact byte-offset resume is not implemented.
- Added manual and periodic local QZRA snapshots (daily / every 3 days / weekly), retention, history, integrity verification and restore. Android snapshots are full archives; Python repository storage retains content-addressed deduplication.
- Added Android-Keystore-backed AES-GCM storage for archive passwords, protected-folder passwords, WebDAV credentials and signing private-key material. Cleartext secrets are not put into WorkManager input data or operation history.
- Added HTTPS WebDAV account testing, verified upload/download and safe two-way root-level QZRA sync. Conflicts are reported without overwriting or deleting either copy. Native Android OAuth flows for Drive/Dropbox/OneDrive/S3 remain out of scope.
- Added Android Ed25519 archive signing/verification and export of `.qzsig` plus public-key companions. Public-key fingerprints must be trusted out of band.
- Added USB/SD Storage Access Framework linking, QZRA browsing/export and post-copy SHA-256 verification.
- Added cleanup of abandoned `.qamzor-restore-*` workspaces older than seven days, while preserving recent workspaces that may belong to an active restore.
- Updated Android guide, README, status and implementation report to describe current platform support and remaining limitations accurately.
- Python validation: 90 tests and 9 subtests passed. Gradle/JUnit/APK/device tests were not run in this environment due missing Gradle/Android SDK.
