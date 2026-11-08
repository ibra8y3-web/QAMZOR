# QAMZOR — Folder import and archive password report

Date: 2026-11-03

## Android folder import correction

The prior Android folder copier used `DocumentFile.isFile` as a required branch. Some Storage Access Framework (SAF) providers return ordinary child documents without a MIME type; in that case `isFile` may be false even though `ContentResolver.openInputStream(uri)` can read the content. The old `else` branch rejected that item and aborted the folder import.

The updated importer:

- Opens the selected tree with `DocumentFile.fromTreeUri` and recursively enumerates descendants.
- Detects directories through `DocumentFile.isDirectory` and the provider MIME type.
- Treats every non-directory child as a stream candidate, rather than depending on `isFile`.
- Copies files in 64 KiB buffers into app-private staging.
- Preserves the selected root folder, nested folders and empty folders.
- Uses document IDs to reject repeated/cyclic tree references and caps nesting at 128 levels.
- Deletes a partially copied root if any child cannot be read.
- Reports file count, directory count and copied byte count before archive creation.

The Kotlin archive engine's source walker already emits explicit directory entries and recursively records nested files. Existing core tests cover folder trees and empty directories. Actual SAF behavior still needs testing against the user's Android file provider(s), because provider implementations differ.

## Optional password protection

- Added an Android checkbox to enable password protection for a new QZRA archive.
- Requests the passphrase twice; UI requires at least 10 characters.
- Password is passed only to the archive creation call and is not written to app preferences or logs.
- Kotlin writer keeps unencrypted output as QZRA 1.1; with a password it writes QZRA 1.2 using scrypt (N=32768, r=8, p=1) and AES-256-GCM with authenticated chunks and authenticated manifest metadata.
- The app verifies the completed archive with the supplied password before presenting the save picker.
- Added password entry for verifying encrypted archives and corrected the extraction password flow to continue to destination selection after authentication.
- Added Kotlin Core and SDK round-trip tests for encrypted creation, correct password, wrong password, preview and selected-folder extraction.

### Security scope limitation

The current QZRA 1.2 design authenticates manifest metadata but does not encrypt the manifest itself. File payloads require the password, while paths, names, sizes and directory structure remain visible. The Android option protects the entire archive, not individual folders with separate passwords. Hiding metadata and per-folder keys require a versioned format revision; do not advertise them as supported today. Password recovery is not available.

## Additional feature roadmap

See `docs/ROADMAP_FEATURE_IDEAS.md` for prioritized proposals, including encrypted metadata, per-folder key slots, background job center, include/exclude rules, archive comparison and incremental update, split volumes, recovery assistant, richer previews, signed sharing, cloud backup and accessibility.

## Validation performed in this workspace

- Python reference tests: **60 passed**.
- Python `compileall`: passed.
- Kotlin source parser check using the locally installed Kotlin 1.9 parser: no syntax/"expecting" diagnostics in the edited Core and Android files. This check does not type-check project dependencies.
- Android Gradle build and Kotlin JUnit tests: **not run here**. Gradle/Android SDK/dependency caches are unavailable in this workspace. The added Kotlin tests must execute in GitHub Actions before the Android password writer is considered build-verified.
