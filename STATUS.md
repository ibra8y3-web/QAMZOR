# QAMZOR — Current execution status

> **Source snapshot: 0.9.0 — 2026-11-04.** This package contains new Android WorkManager, backup, WebDAV, secret-vault, signature and USB/SD integrations. Python tests were run in this workspace. Android Gradle compilation, APK generation, Kotlin JUnit execution and physical-device acceptance were not available here and remain release gates.

## Android functionality implemented in source

- QZRA 1.0–1.3 create/read/browse/verify/extract/restore, including up to 32 independently protected folder scopes; scoped passwords are passed separately during create, restore, verify and archive comparison.
- Phone and archive search/sort, hidden-file and directory-first settings, background file listing, archive metadata and split/join with SHA-256-verified `.qzset` volumes.
- WorkManager foreground jobs for archive creation, restore, verification, local snapshot creation/verification/restore, WebDAV push/pull and folder sync. The Operations page observes jobs and reports progress, byte counts, speed, ETA, file counts where provided, errors, results and cloud conflicts. Pause/resume is cooperative at engine progress checkpoints; cancellation stops at the next safe checkpoint.
- WorkManager persists queued/scheduled work across Activity closure and device reboot. If the app process is killed during a running task, WorkManager can rerun the transaction from the beginning; exact byte-offset continuation within archive/cloud streams is not implemented. This must not be described as resumable-at-byte-offset.
- Scheduled local backups: daily, every 3 days, or weekly; manual backup; retention; snapshot list, verify and restore. Android snapshots are full standalone QZRA archives. Python repository snapshots support immutable manifests and content-addressed block reuse/deduplication; that chunk-level incremental storage is not yet the Android snapshot implementation.
- Password vault and WebDAV credentials use AES-GCM encrypted blobs with an AES key held in Android Keystore. WorkManager input data holds references, not cleartext passwords. Android key loss/reinstall may make local secrets unrecoverable; users must keep recovery credentials safely outside the app.
- Ed25519 archive signing and verification with exported detached signature/public-key companions. Users must validate/pin the public-key fingerprint through an independent trusted channel; a public key shipped beside its signature alone does not authenticate identity.
- Android HTTPS WebDAV account management, connection test, verified archive upload, remote listing, verified download and manual two-way sync of top-level `.qzra` files. Conflicts are reported; sync does not overwrite or delete either side.
- USB/SD access via Android Storage Access Framework, including browsing and archive export with post-copy SHA-256 validation. Full shared-storage browsing is subject to Android storage permissions and distribution policy.
- Safe restore staging/rollback, explicit archive-delete failure after successful restore, stale cache cleanup, and stale `.qamzor-restore-*` workspace cleanup after seven days.

## Features that remain platform-limited or incomplete

- Native Android OAuth account UI/refresh-token flows for Google Drive, Dropbox, OneDrive and S3 are not implemented. Those provider adapters remain in Python. Android account integration is WebDAV HTTPS only.
- Android snapshots are full archives, not chunk-incremental/deduplicated snapshots. The Python Repository supports deduplicated snapshot reuse and delta export/apply.
- Exact byte-offset resume after process death is not implemented. WorkManager replays a task from the beginning; in-flight transfer progress is not continued at the last byte.
- Compare and some interactive preview/selection flows remain Activity/executor based rather than all being WorkManager jobs. Long core create/restore/verify/backup/cloud paths are WorkManager-backed.
- Android only exposes QZRA in this app. ZIP, 7z, TAR, GZIP, BZIP2, XZ and RAR are not fully integrated as Android open/create targets in this delivery. Python/desktop support is a separate platform capability. RAR writing requires a licensed compatible engine.
- Android UI does not yet expose XOR archive repair, and USB/SD browsing is constrained by the selected SAF document-tree provider.
- WorkManager, Keystore, WebDAV server compatibility, background execution restrictions, scoped storage, large-file memory/disk behavior and key recovery need device/vendor testing.

## Python validation evidence

- `PYTHONPATH=src pytest -q` — **90 passed, 9 subtests passed**.
- `python -m compileall -q src tests tools` — passed.
- No live OAuth/cloud provider credentials were supplied, so remote Google/Dropbox/OneDrive/S3 account operations were not tested against real services.

## Android/Kotlin validation limits

- `gradle` and Android SDK are not installed in this workspace; no Gradle wrapper is present. Therefore `:core:test`, `:sdk:test`, `:desktopApp:installDist`, `:androidApp:assembleDebug` and `:androidApp:testDebugUnitTest` were not run.
- Kotlin source and JUnit tests have been edited, but no Android compile/APK/JUnit success is claimed. The previous no-classpath `kotlinc` diagnostic is not a valid Android build.
- CI workflows are configured in `.github/workflows/platform-builds.yml`; run them on GitHub before treating the package as install-ready. Follow with physical-device tests for lifecycle/reboot, storage access, USB/SD, WebDAV servers, background restrictions, battery saver, and large files.

## Security status

QZRA and associated cryptographic code are development software and have not received independent security audit or formal format certification. SHA-256 sidecars detect corruption, but authenticity requires Ed25519 verification against a separately trusted fingerprint. Do not make this the only copy of important files before independent review and device acceptance.
