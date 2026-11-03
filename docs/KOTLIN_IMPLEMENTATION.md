# Kotlin clients — development status

The repository contains four Gradle modules: `:core` (shared Kotlin/JVM engine), `:androidApp` (native Android/SAF client), `:desktopApp` (JVM Swing client), and `:sdk` (pre-1.0 public facade).

## QZRA compatibility

The Kotlin Core source now reads and writes QZRA 1.0–1.3. The default writer remains QZRA 1.1. A non-empty archive password writes authenticated AES-256-GCM QZRA 1.2; `encryptMetadata=true` writes QZRA 1.3 with encrypted manifest metadata. The QZRA 1.3 writer/reader includes the format-defined scrypt parameters, AES-256-GCM manifest envelope, HMAC-SHA-256 purpose-separated keys, scope-bound chunk AAD, independent folder-password scopes and scope-aware deduplication (maximum 32 protected folders).

The Core APIs accept folder passwords on `list`, `browse`, `preview`, `verify`, `extractSelected`, `extract` and `compareContent`. The SDK facade exposes QZRA 1.3 creation/verification/extraction and separate folder-password maps. Python-generated QZRA 1.3 golden-fixture tests check manifest confidentiality, master-password failure, independent folder unlock, and payload verification. Kotlin-writer tests check the hidden entry count, encrypted paths, folder lock behavior and extraction.

**Validation boundary:** Kotlin source and JUnit tests are authored but have not been compiled or run in this workspace. The fixture tests must pass in GitHub Actions before cross-language interoperability is certified. QZRA 1.3 is still a development format and has not received an independent cryptographic audit.

## Android integration

The Android UI exposes QZRA 1.3 metadata hiding and one independent protected-folder scope during archive creation. It prompts for the archive master password at open, then requests the matching folder password when the user attempts to preview, verify or extract a protected folder. The UI does not persist any archive/folder password. The operation center persists the last 100 secret-free events, shows the current operation and elapsed time while the Activity is alive, counts successes/failures/interrupted operations, and marks a prior process's active operation as interrupted after an app-process restart.

This operation center is **not** a WorkManager/foreground-service job engine: active work is not guaranteed to survive process death, and pause/resume/cancel controls are not implemented in the Android UI. The persistent history makes interruption visible; it does not resume file operations.

## Desktop scope

The Kotlin Desktop UI supports basic create, inspect/verify, compare and extract. Its create workflow currently writes default QZRA 1.1; encrypted QZRA 1.2/1.3 and folder-scope operations are available through Core/SDK APIs but are not fully surfaced in desktop dialogs.

## GitHub Actions build

`.github/workflows/platform-builds.yml` installs JDK 17, Gradle 8.9 and Android API 35 on GitHub-hosted runners. It runs `:core:test`, `:sdk:test`, creates the desktop distribution on Windows/Linux/macOS, and builds/tests the Android debug APK. Local absence of Gradle/Android SDK is not a source blocker; **the remote workflow result is the acceptance gate**. Until Actions completes, Kotlin compilation, JUnit results, APK generation and desktop packaging are not claimed as passed.

## Local commands (optional)

```sh
gradle :core:test :sdk:test :desktopApp:installDist
gradle :androidApp:assembleDebug :androidApp:testDebugUnitTest
```
