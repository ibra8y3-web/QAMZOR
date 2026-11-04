# Kotlin clients — development status

The repository contains four Gradle modules: `:core` (shared Kotlin/JVM engine), `:androidApp` (native Android/SAF client), `:desktopApp` (JVM Swing client), and `:sdk` (pre-1.0 public facade).

## QZRA compatibility

The Kotlin Core source now reads and writes QZRA 1.0–1.3. The default writer remains QZRA 1.1. A non-empty archive password writes authenticated AES-256-GCM QZRA 1.2; `encryptMetadata=true` writes QZRA 1.3 with encrypted manifest metadata. The QZRA 1.3 writer/reader includes the format-defined scrypt parameters, AES-256-GCM manifest envelope, HMAC-SHA-256 purpose-separated keys, scope-bound chunk AAD, independent folder-password scopes and scope-aware deduplication (maximum 32 protected folders).

The Core APIs accept folder passwords on `list`, `browse`, `preview`, `verify`, `extractSelected`, `extract` and `compareContent`. The SDK facade exposes QZRA 1.3 creation/verification/extraction and separate folder-password maps. Python-generated QZRA 1.3 golden-fixture tests check manifest confidentiality, master-password failure, independent folder unlock, and payload verification. Kotlin-writer tests check the hidden entry count, encrypted paths, folder lock behavior and extraction.

**Validation boundary:** Kotlin source and JUnit tests are authored but have not been compiled or run in this workspace. The fixture tests must pass in GitHub Actions before cross-language interoperability is certified. QZRA 1.3 is still a development format and has not received an independent cryptographic audit.

## QZRA split sets

`ArchiveVolumes.split` and `ArchiveVolumes.join` implement the version-2 `QZRA-PART-SET` descriptor also used by the Python `qamzor.volumes` module. Each immutable part carries a SHA-256 digest; the descriptor carries each part size/digest and the complete archive length/digest. Reassembly validates descriptor bounds, part names/order, size and digest, then verifies the joined QZRA signature and publishes the output only after validation. Android exposes these operations from the phone file browser; opening a `.qzset` offers a verified reassembly flow. Original archives and part files are retained. The split-set checksum detects corruption but is not an authenticity signature against an attacker who can replace both parts and descriptor.

## Android integration

The Android UI exposes QZRA 1.3 metadata hiding and up to 32 independent protected-folder scopes. It connects scoped passwords to create, restore, verify and compare flows. A password vault and WebDAV account passwords are encrypted with AES-GCM using Android Keystore. The signing-key manager stores Ed25519 private material in the encrypted secret store and exports detached signatures/public-key companions for verification.

WorkManager foreground jobs now back archive seal/restore/verify, local snapshot creation/verification/restore and WebDAV push/pull/sync. The Operations page observes live job state, phase, progress, byte counts, measured speed, ETA and file counters when available. Pause/resume is cooperative at safe engine progress checkpoints; cancel stops at a safe checkpoint. WorkManager persists queued/scheduled work over Activity closure and device reboot. If the app process is killed during an in-flight task, it can be replayed from the transaction start; exact byte-offset continuation is not implemented.

Local Android snapshots are full QZRA archives with scheduled retention/history. Content-addressed block reuse and deduplicated snapshot storage remain features of the Python Repository, not the Android snapshot store. Android cloud account support is HTTPS WebDAV only; Drive/Dropbox/OneDrive/S3 OAuth flows are Python-only. USB/SD linking uses Storage Access Framework and post-copy SHA-256 verification.

## Desktop scope

The Kotlin Desktop UI supports basic create, inspect/verify, compare and extract. Its create workflow currently writes default QZRA 1.1; encrypted QZRA 1.2/1.3 and folder-scope operations are available through Core/SDK APIs but are not fully surfaced in desktop dialogs.

## GitHub Actions build

`.github/workflows/platform-builds.yml` installs JDK 17, Gradle 8.9 and Android API 35 on GitHub-hosted runners. It runs `:core:test`, `:sdk:test`, creates the desktop distribution on Windows/Linux/macOS, and builds/tests the Android debug APK. Local absence of Gradle/Android SDK is not a source blocker; **the remote workflow result is the acceptance gate**. Until Actions completes, Kotlin compilation, JUnit results, APK generation and desktop packaging are not claimed as passed.

## Local commands (optional)

```sh
gradle :core:test :sdk:test :desktopApp:installDist
gradle :androidApp:assembleDebug :androidApp:testDebugUnitTest
```
