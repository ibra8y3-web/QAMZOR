# Kotlin clients — development status

The repository now contains four Gradle modules:

- `:core`: Kotlin/JVM implementation shared by Android and desktop.
- `:androidApp`: native Android application using the Storage Access Framework for file selection and destination access.
- `:desktopApp`: Java Swing desktop application that runs on the JVM (Windows, Linux and macOS).
- `:sdk`: pre-1.0 public Kotlin/JVM facade.

## QZRA compatibility in the current Kotlin client

The Kotlin core writes QZRA 1.1 chunked archives using the same deterministic gear chunking constants, zlib-or-store selection, SHA-256 chunk/file checksums and within-archive duplicate chunk references as the Python reference writer. It reads QZRA 1.0/1.1. Legacy QZRA 1.0 blocks larger than 16 MiB are rejected to maintain a bounded in-memory limit in this initial Kotlin client. QZRA 1.2 encrypted archives are explicitly rejected; encrypted bytes are never silently treated as plain data. The Python reference implementation remains the full-featured implementation for AES-GCM encryption, detached signatures, recovery sidecars and backup repositories.

This is an initial cross-language implementation, not a production-certified engine. The conformance suite must include Python-to-Kotlin and Kotlin-to-Python golden archives before the format compatibility claim is closed. Filesystem race hardening, very-large legacy block streaming, memory profiling and Android device/emulator validation remain required.

## CI

`.github/workflows/platform-builds.yml` installs JDK 17, Gradle 8.9 and Android API 35, runs Kotlin core/SDK tests, builds a desktop distribution and assembles a debug APK. The workflow is configured but has not been observed running on GitHub until this repository is pushed and Actions executes successfully.

## Local commands

With JDK 17, Gradle 8.9 and Android SDK API 35 installed:

```sh
gradle :core:test :sdk:test :desktopApp:installDist
gradle :androidApp:assembleDebug :androidApp:testDebugUnitTest
```
