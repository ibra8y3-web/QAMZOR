# QAMZOR Compatibility Suite

Language-neutral conformance tests for anything that reads QZRA archives (an app, a library, a tool).
`manifest.json` lists every vector, the passwords to use, and the exact expected outcome; the files
under `vectors/` are frozen archives (random salts/nonces, so they are never regenerated casually).

## What a conforming reader must do, per vector

1. Check the vector file against its SHA-256 (`SHA256SUMS`).
2. **open** the archive with the vector's `password` (`null` = none), **unlock** every entry of
   `folder_passwords`, **list** entries, **verify** (decrypt, decompress, check every digest), then
   **extract** into an empty directory.
3. `expect.outcome == "ok"`: the sorted entry list (`path`, `type`, `size`) must equal
   `expect.entries`, and every extracted file must have the listed SHA-256.
4. `expect.outcome == "error"`: some step must fail with exactly `expect.category`
   (see `error_categories` and `registry/qzra-registry.json`), and **no file may ever appear
   outside the destination directory**.

Error categories: `AUTHENTICATION_FAILED` (AES-GCM tag failure; wrong password and tampering are
indistinguishable), `INTEGRITY_FAILED` (digest/size/decompression failure on unencrypted data),
`INVALID_FORMAT`, `UNSAFE_PATH`, `UNSUPPORTED_VERSION`.

## Coverage (18 vectors)

Positive: QZRA 1.0, 1.1 (two archives), 1.2 encrypted, 1.3 hidden metadata, 1.3 with nested
folder-password scopes, Arabic NFC file name, empty file, empty directory, deduplicated chunks.
Negative: wrong master password (1.2, 1.3), wrong folder password, tampered payload (1.1),
tampered ciphertext (1.2), tampered encrypted manifest (1.3), truncated archive, bad magic,
unsupported minor version, `../` traversal, absolute path, Windows device name.

All passwords in this suite are public **test-only** values. Never use them for real data.

## Runners in this repository

| Implementation | Runner | Verified |
|---|---|---|
| Python reference engine | `compat/runners/python_runner.py`, `tests/test_registry_and_compat.py` | 18/18 locally |
| Native SDK (C/C++) | `native/tests/compat_runner.c` (`make -C native check`) | 18/18 locally, also under ASan/UBSan |
| Java SDK (over the Kotlin core) | `sdk-java/src/test/java/.../CompatibilitySuiteTest.java` | **Not run yet** - runs in GitHub Actions |

## Adding support in your own program

Implement the procedure above in your language, load `manifest.json` with any JSON parser, and run
all vectors. To add a vector: extend `tools/generate_compat_vectors.py`, regenerate, rerun **every**
runner, and commit `manifest.json`, `vectors/` and `SHA256SUMS` together. Format changes need a new
registry entry, a version note and new vectors before any release.
