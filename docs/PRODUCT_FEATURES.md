# QAMZOR Product Features — Reference Core Delivery

This document distinguishes working Python reference-core functions from platform UI integration. A feature is not considered part of the Android/desktop user experience until the Kotlin engine, UI and platform tests expose it.

## Existing capabilities retained

| Feature | Current implementation | Scope |
|---|---|---|
| Encrypted metadata | QZRA 1.3 encrypts the complete manifest and hides entry count | Python + Kotlin Core source; Android UI source; Gradle interop CI pending |
| Per-folder security | Independent scrypt-derived AES-256-GCM scope keys; deepest scope wins | Python + Kotlin Core source; Android UI exposes one protected scope; Gradle interop CI pending |
| Recovery | `.qzrec` XOR recovery sidecar; one damaged block per parity group | Python reference core/CLI |
| Duplicate intelligence | Content-defined chunk reuse inside an archive; SHA-256 object reuse across repository snapshots | Python core/repository |
| Secure sharing | Detached Ed25519 signatures | Python reference core/CLI |

## Added in this delivery

### Smart Exclusions

- Repeat `--exclude PATTERN` on `qz create` or `qz convert`.
- Use `--exclude-from FILE` for a UTF-8 rule file.
- Put `.qzignore` at the root of a selected source folder; it can also be used in nested folders.
- Blank lines and `#` comments are ignored.
- A trailing `/` means directory and descendants; patterns with `/` match relative paths, and patterns without `/` match any path component.
- Negated (`!`) patterns are explicitly rejected in this release. This avoids silently skipping a re-included path when its parent directory has been pruned.
- The `.qzignore` files themselves are control files and are not added to the archive.

Example:

```text
# .qzignore
build/
.gradle/
*.tmp
*.cache
```

```bash
qz convert source.qzra ./Project --exclude '*.log' --exclude 'node_modules/'
qz convert clean.qzra ./Project --exclude-from ./archive-excludes.txt
```

### Background Job Center

Python API for queued archive-creation jobs with progress, pause, resume, cancel and persistent status records:

```python
from qamzor import JobCenter

jobs = JobCenter("./.qamzor-jobs", workers=2)
job_id = jobs.submit_create_archive(
    "backup.qzra", ["./Project"], profile="balanced", workers=2
)
print(jobs.get(job_id))
jobs.pause(job_id)   # takes effect at the next archive chunk checkpoint
jobs.resume(job_id)
result = jobs.wait(job_id, timeout=3600)
jobs.shutdown()
```

Job state records are stored with owner-only permissions where supported. Passwords and folder passwords are never written to job records. Jobs can pause/cancel only at safe chunk checkpoints. A process restart marks unfinished jobs `interrupted`; automatic resume is deliberately not attempted because source files may have changed. Android now uses WorkManager foreground workers for archive seal/restore/verify, snapshot backup/verify/restore and WebDAV transfer/sync. The operations UI reports progress and offers cooperative pause/resume/cancel at safe checkpoints. WorkManager persists scheduled work over Activity closure/reboot; after process death an in-flight transaction is retried from the beginning, not from an exact byte offset.

### Split Archives

`qz split ARCHIVE.qzra ./volumes --part-size-mib 100` writes sequential `.part00001` volumes inside a unique generation directory and an integrity descriptor `.qzset`. `qz join ARCHIVE.qzra.qzset ./restored.qzra` validates each volume and the reassembled archive SHA-256 before atomic publication. Minimum volume size is 1 MiB. Keep the descriptor and all parts together. The Kotlin Core implements the same version-2 descriptor and Android exposes split/reassemble actions from the Files tab. A matching checksum detects accidental damage; it is not a substitute for an Ed25519 signature against a maliciously replaced set.

### Archive Compare

`qz compare old.qzra new.qzra` reports added, removed, changed and unchanged paths using entry type, size and SHA-256 metadata. QZRA 1.3 archives require their master passwords via `--left-password-file` / `--right-password-file`. Per-folder passwords are not needed to compare authenticated manifest metadata. Comparison does not read or verify all payload bytes; run `qz verify` separately when data integrity must be checked.

### Advanced Office Preview

`qz preview report.qzra Project/report.docx --office` extracts text from DOCX, XLSX and PPTX without writing the office document into the user's extraction directory. The selected archive entry is fully decoded and SHA-256 verified first. Package parsing has source-size, member-count, expanded-size and compression-ratio limits. This is a text preview, not a full fidelity renderer: formatting, charts, formulas, embedded media, tracked changes and complex layout are not rendered. Macro-enabled Office formats are not supported.

### Cloud Backup — WebDAV

The `qz cloud push` and `qz cloud pull` commands implement a small WebDAV-compatible object backup adapter:

```bash
qz cloud push backup.qzra --url 'https://dav.example.net/remote.php/dav/files/user/QAMZOR/' --username USER
qz cloud pull backup.qzra restored.qzra --url 'https://dav.example.net/remote.php/dav/files/user/QAMZOR/' --username USER
```

The password is prompted interactively unless `--password-file` is supplied. Credentials are not persisted. HTTPS is mandatory except for loopback-only local testing. Each archive is stored with a `.qamzor.json` size/SHA-256 sidecar; downloads are written to a temporary file, verified and atomically published. Native Google Drive API v3, Dropbox API v2, Microsoft Graph OneDrive, and AWS Signature V4 S3-compatible adapters are now available through `qz cloud-provider`; details and credentials are in [`CLOUD_CONNECTORS.md`](CLOUD_CONNECTORS.md). These use runtime access tokens/keys, publish a checksum sidecar, and verify size/SHA-256 on restore. They are Python CLI/Core adapters only; Android currently exposes HTTPS WebDAV account management and safe QZRA-only folder sync, not native OAuth login for these providers. No live external account was used for end-to-end provider qualification. Cloud-side authenticity still requires verifying an Ed25519 detached signature using a trusted public key.

## Platform integration status

The feature matrix is platform-specific. Android source exposes QZRA 1.3 creation/unlock, phone/archive search and sorting, format metadata, split/reassemble, WorkManager operations, local snapshots, password/key vaults, Ed25519 signing, USB/SD SAF and HTTPS WebDAV sync. Android does not yet expose native OAuth UI for Drive/Dropbox/OneDrive/S3, chunk-deduplicated snapshots, exact byte-offset resume, XOR repair, or ZIP/7z/TAR/RAR engines. Gradle/JUnit compilation and APK assembly remain pending GitHub Actions and device validation. Smart Exclusions, Office preview and multi-provider OAuth adapters remain Python Core/CLI features.
