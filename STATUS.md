# QAMZOR — Current execution status

> **Snapshot: 0.7.2-dev — 2026-11-03.** This status supersedes older phase notes and reports in this repository. Python tests were rerun after the current changes. Kotlin/Android source has been updated, but Gradle/JUnit/APK results are intentionally left pending until GitHub Actions runs.

## Verified in this workspace

- Python QZRA reference engine: QZRA 1.0–1.3 read/write, encrypted manifest, independent folder scopes, chunk integrity, selective extraction, signatures, recovery, repository snapshots, deltas, split/join, compare, exclusions, Office text preview and WebDAV.
- Cloud-provider adapters: Google Drive API v3, Dropbox API v2, Microsoft Graph OneDrive, and AWS Signature V4 S3-compatible transport. They use runtime OAuth tokens/keys, upload resumable/session content (S3 multipart above 5 GiB), publish a SHA-256 sidecar and verify the restored object's size/digest/header before publishing it locally.
- Local cloud tests use an in-memory provider test double plus S3 request/signature construction tests. No live provider account or token was available; real-account upload/restore is not claimed as verified.
- Android source: QZRA 1.3 creation with hidden metadata and one independent folder-password scope, master/folder password prompts during access, and a persisted secret-free operation history.
- Operation history: last 100 events are retained; start/completion/failure events are logged; a previous active task is recorded as interrupted after an app process restart. This is not a WorkManager or foreground-service job engine; Android tasks are not guaranteed to survive process death and there are no Android pause/resume/cancel controls.

## Test evidence

- `PYTHONPATH=src python -m pytest -q` — **90 passed, 9 subtests passed**.
- `python -m py_compile src/qamzor/cloud_providers.py src/qamzor/cli.py src/qamzor/__init__.py tests/test_cloud_providers.py` — passed.
- `python -m compileall -q src tests tools` — see current execution report.
- `PYTHONPATH=src python -m qamzor.cli --help` — passed; includes `cloud-provider push/pull`.
- Package version: `0.7.2.dev0`; Python distribution/wheel is rebuilt for this snapshot.

## Kotlin, Android and desktop acceptance gate

- Kotlin Core source reads/writes QZRA 1.3, including encrypted manifest envelope, HMAC-separated keys, independent folder scopes and scope-bound AES-GCM chunks. Python-produced fixture tests and Kotlin-writer tests are authored.
- Android source exposes QZRA 1.3 and folder-unlock prompts. Android app version is `0.7.2-dev` / versionCode 8.
- `:core:test`, `:sdk:test`, desktop distributions and `:androidApp:assembleDebug :androidApp:testDebugUnitTest` are configured in `.github/workflows/platform-builds.yml`.
- **Not yet verified:** Kotlin compilation, JUnit execution, Android APK generation, desktop OS packaging and device testing. GitHub Actions must run after push/PR; no APK build is claimed locally.

## Remaining boundaries

- Cloud adapters are Python Core/CLI only; no Android OAuth consent/login UI, Android cloud sync or multi-destination scheduler is implemented.
- OAuth adapters accept an already-issued access token; refresh-token lifecycle/interactive consent is not implemented. Tokens and S3 keys must be supplied at runtime and kept in GitHub Secrets or another secret manager.
- S3 multipart uploads use 128 MiB parts with the current 1 TiB QAMZOR safety ceiling. S3 restore/push is not certified against external compatible vendors in this workspace.
- SHA-256 sidecars detect accidental corruption but are not authenticated against a hostile cloud service that can replace both archive and sidecar; use detached Ed25519 signatures with a separately trusted public key for authenticity.
- Kotlin QZRA 1.3 and crypto implementation have not received independent security audit or formal interoperability certification.
- Android operation history does not make Activity-thread operations durable across process termination.
- Smart Exclusions, Split/Join, Office preview and cloud-provider UI remain Python Core/CLI features rather than Android controls.

## GitHub Actions commands

The workflow provisions JDK 17, Gradle 8.9 and Android SDK API 35 on hosted runners, so no local Android SDK or Gradle installation is required for the intended build path. After publishing the source repository, inspect the `Platform builds` workflow artifacts for:

```text
qamzor-android-debug-apk
qamzor-kotlin-test-reports-<os>
qamzor-desktop-<os>
```

A passing workflow is necessary but does not replace physical-device and security testing.
