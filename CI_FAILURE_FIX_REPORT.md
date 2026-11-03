# QAMZOR Kotlin CI failure analysis and test corrections

## Evidence from GitHub Actions
The failing task is `:core:test`. The same two `ArchiveEngineTest` cases fail on Ubuntu, Windows, and macOS, while the Android job succeeds. This points to shared Kotlin core test assertions rather than an OS-specific SDK setup issue.

## Root causes

1. `virtualExplorerBrowsesDirectoriesAndPreviewsFileWithoutExtraction`
   - The fixture creates its source file at `<temp>/Project/README.md`.
   - The old assertion then expected that exact source path not to exist. That expectation contradicts the test setup; the preview API does not delete or move source files.
   - Corrected the assertion to check that no extracted copy was created under a separate `preview-output` destination.

2. `virtualExplorerReadsLegacyArchiveWithoutFullExtraction`
   - The expected preview text `legacy QAMZOR` is 13 UTF-8 bytes.
   - The old test requested a 12-byte preview, then expected all 13 bytes. This is an off-by-one test error.
   - Corrected the preview limit to 13 bytes. The fixture's file is 290 bytes, so `truncated` remains expected to be true.

## Scope
Only the two incorrect test assertions/limits were changed. The archive engine behavior was not weakened, and tests were not skipped.

## Validation limitation
This workspace does not contain `gradlew`, Gradle wrapper files, or a system `gradle` executable, so `:core:test` could not be executed locally here. GitHub Actions must be rerun to establish the Kotlin result on the three CI operating systems.
