# QAMZOR CI failure correction report — 2026-11-03

## Failures reported from GitHub Actions

### Android `:androidApp:compileDebugKotlin`

1. `MainActivity.kt:78` — `takeLast` was applied directly to a Kotlin `Sequence`. Kotlin provides `takeLast` for lists, not for that sequence pipeline. The history sequence is now materialized with `toList()` before retaining the last 100 events.
2. `MainActivity.kt:986` — `singleLine` was used as an unresolved synthetic property on `TextView`. Replaced with the explicit Android API call `setSingleLine(true)`.

### Kotlin Core `:core:test`

`ArchiveEngineTest.comparesArchiveContentUsingVerifiedSha256RatherThanOnlyFileSizes` failed because `compareContent` included the shared parent directory `Project` in the `unchanged` list. The test is a file-content comparison and expects payload file paths, not structural directory records. The implementation now filters both manifests to regular file entries after verifying the complete archives. Added/removed/changed/unchanged results therefore describe file payloads. SHA-256 verification remains mandatory and unchanged.

## SDK licenses

The supplied log progressed through Android resource processing and reached Kotlin compilation. Therefore SDK license acceptance was not the cause of these particular failures. The workflow already has an explicit SDK license acceptance/install step; no license workaround is needed to explain these diagnostics.

## Validation in this workspace

- `PYTHONPATH=src python -m pytest -q`: **90 passed, 9 subtests passed**.
- Reviewed both Android source corrections at the exact reported locations.
- Reviewed the file-only content-comparison change and regression assertion.
- Gradle is not installed in this workspace and no Gradle wrapper is present, so the Kotlin JUnit suite and Android APK could not be rebuilt locally. GitHub Actions must be rerun against this corrected source ZIP to confirm those jobs pass.

## Files changed

- `androidApp/src/main/java/org/qamzor/android/MainActivity.kt`
- `core/src/main/kotlin/org/qamzor/core/ArchiveEngine.kt`
- `core/src/test/kotlin/org/qamzor/core/ArchiveEngineTest.kt`
- `CHANGELOG.md`
- `CI_FAILURE_FIX_REPORT.md`
- `VALIDATION_RESULTS.txt`
