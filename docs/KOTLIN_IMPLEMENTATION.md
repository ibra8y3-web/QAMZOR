# Kotlin clients — development status

The repository now contains four Gradle modules:

- `:core`: Kotlin/JVM implementation shared by Android and desktop.
- `:androidApp`: native Android application using the Storage Access Framework for file selection and destination access.
- `:desktopApp`: Java Swing desktop application that runs on the JVM (Windows, Linux and macOS).
- `:sdk`: pre-1.0 public Kotlin/JVM facade.

## QZRA compatibility in the current Kotlin client

The Kotlin core writes interoperable, unencrypted QZRA 1.1 chunked archives using the deterministic gear chunking constants, zlib-or-store selection, SHA-256 chunk/file checksums and within-archive duplicate chunk references. It reads QZRA 1.0 and 1.1 and authenticated/encrypted QZRA 1.2. QZRA 1.2 uses the format-defined scrypt parameters (N=32768, r=8, p=1), AES-256-GCM manifest authentication and per-chunk authenticated encryption. The `:core` module uses Bouncy Castle's audited SCrypt implementation and the platform AES/GCM provider; it does not introduce a custom cryptographic construction.

The core exposes `browse(archive, directoryPath, password)` for immediate virtual-directory children, `preview(archive, path, maxBytes, password)` for bounded in-memory previews that still verify the complete file, `extractSelected(...)` for a file or directory subtree, and `verify(...)` for the full archive. `extractSelected` enforces file-count/byte quotas, path validation, symlink guards, temporary-file writes and atomic publication. The preview API reads the payload block-by-block; it does not materialize the selected file or the archive tree on disk.

The Android UI provides interactive folder navigation, file action menus, text/image/PDF first-page preview, selected file extraction, selected folder extraction and complete archive extraction. Text preview is bounded at 2 MiB; image/PDF preview is bounded at 16 MiB. PDF rendering uses a short-lived private-cache file because Android `PdfRenderer` requires a file descriptor. Files are fully integrity-checked even when the returned preview bytes are truncated. The archive container itself is staged in private cache to support random access from Android document providers.

The Kotlin client does not yet write encrypted archives: Android/desktop creation remains unencrypted QZRA 1.1. Detached signatures, recovery sidecars and snapshot repositories remain Python-reference features. QZRA 1.0 legacy blocks larger than 16 MiB are rejected in the Kotlin reader. These clients are not production-certified; Android device/emulator, low-storage, lifecycle, large-file and independent security tests remain outstanding.

Golden tests include Python-generated QZRA 1.0, 1.1 and encrypted 1.2 fixtures. Kotlin unit tests cover navigation, bounded verified preview, selected file/directory extraction and encrypted interoperability/wrong-password behavior. Gradle/JUnit results remain pending until CI runs.

## CI

`.github/workflows/platform-builds.yml` installs JDK 17, Gradle 8.9 and Android API 35, runs Kotlin core/SDK tests, builds a desktop distribution and assembles a debug APK. The workflow is configured but has not been observed running on GitHub until this repository is pushed and Actions executes successfully.

## Local commands

With JDK 17, Gradle 8.9 and Android SDK API 35 installed:

```sh
gradle :core:test :sdk:test :desktopApp:installDist
gradle :androidApp:assembleDebug :androidApp:testDebugUnitTest
```
