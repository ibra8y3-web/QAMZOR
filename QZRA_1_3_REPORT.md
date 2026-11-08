# QZRA 1.3 Implementation Report

Date: 2026-11-03

## Implemented in the Python reference engine

- QZRA 1.3 manifest envelope (`QZ13`) with AES-256-GCM encryption and scrypt (`N=32768`, `r=8`, `p=1`, 16-byte salt).
- Purpose-separated AES-256 subkeys derived from each scrypt root with HMAC-SHA-256 labels: `manifest`, `root-chunks`, `folder-check`, and `folder-chunks`; no KDF root is reused directly across AES-GCM domains.
- Entire canonical manifest encrypted, hiding names, path hierarchy, sizes, SHA-256 digests, chunk metadata, protected-folder records and the actual entry count.
- Header entry count fixed to zero for 1.3; archive and encrypted-manifest lengths remain observable.
- Root archive master password required to open/list/inspect/search a 1.3 archive.
- Up to 32 independently protected folder scopes. Each uses an independent salt, scrypt-derived key and authenticated key-check record.
- The deepest protected directory controls the payload key for its descendants. Files outside protected scopes use the archive master key.
- Chunk AAD binds the chunk to its version and scope ID. Deduplication keys include the scope, preventing ciphertext sharing across different keys.
- Locked-folder checks apply even to zero-byte files with no payload chunks.
- Python APIs: `create_archive(..., encrypt_metadata=True, folder_passwords={...})`, `open_archive(..., password=..., folder_passwords=...)`, `set_folder_password`, `preview`, `verify` and `extract`.
- CLI: `--hide-metadata`, repeatable `--protect-folder PATH`, `--password-file`, and repeatable `--folder-password-file PATH=FILE`.
- Existing QZRA 1.0, 1.1 and 1.2 reading and writing paths retained.

## Security semantics

The archive master password is required to reveal any file or folder names. An independent folder password is additionally required to read, verify, preview or extract payloads stored under that protected folder. The folder password by itself does not reveal the manifest. Nested protected folders use the deepest scope and do not require the parent folder password for that child's data.

The outer archive and encrypted-manifest lengths remain visible. This version has not received an independent cryptographic audit. Losing the archive master password or a protected-folder password makes the associated data unrecoverable.

## Validation performed

- Python unit suite after the final domain-separated key schedule change: **66 tests passed** on the local Python runtime.
- Five QZRA 1.3 reader/writer tests cover: hidden names/zero header entry count; wrong master password; independent folder password gating; nested protected scopes; invalid scope definitions; and empty protected folders.
- CLI integration test covers create with hidden metadata and a protected folder, list with a master password file, and verify with a folder password file.
- Manual CLI/API smoke test: create QZRA 1.3, list entries after master unlock, verify after folder unlock, and inspect returned `metadata_encrypted`/`protected_folders` facts.
- Added a Python-generated golden interoperability fixture at `fixtures/qzra-1.3/python-reference-protected.qzra`, with public test-only credentials and expected behavior. A copy is staged under `core/src/test/resources/qzra-1.3/` for the future Kotlin port; this is not yet a passing Kotlin interop test.
- `python -m compileall -q src tests tools`: passed.
- QZRA 1.0/1.1/1.2 legacy and encrypted tests continue to pass.

## Explicit remaining blockers

- Kotlin/JVM Core, Android and Desktop do not yet read or write QZRA 1.3. They remain compatible through QZRA 1.2 only.
- Android UI does not yet expose separate folder-password assignment/unlock controls for QZRA 1.3.
- No Gradle build, Android APK build, cross-language golden test, device test, independent cryptographic audit or formal verification was run in this environment.

Do not treat QZRA 1.3 support as complete across all QAMZOR platforms until the Kotlin port, cross-language fixtures and Android folder-unlock UI pass CI and device tests.

---

## Superseding implementation update — 2026-11-03 (QAMZOR 0.7.2-dev)

The earlier Python-only limitation above is historical and has now been superseded in source:

- Kotlin Core QZRA 1.3 reader/writer code has been added, including encrypted metadata, separate root/folder keys, independent folder scopes, scope-bound chunk authentication and scope-aware deduplication.
- Kotlin JUnit tests now read the Python-generated protected-folder golden fixture and create/verify an archive from the Kotlin writer.
- Android source exposes QZRA 1.3 metadata hiding, a protected folder path/password option, and password prompts when a protected folder is accessed.
- `:core:test`, `:sdk:test`, `:androidApp:assembleDebug` and Android unit tests are configured in GitHub Actions, but no successful remote run is recorded in this workspace. Cross-language acceptance remains pending those tests.

This source update is not a cryptographic audit or release certification.
