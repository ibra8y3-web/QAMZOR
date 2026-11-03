# QAMZOR cloud-provider connectors (development)

The Python package/CLI includes HTTPS REST adapters for Google Drive API v3, Dropbox API v2, Microsoft Graph OneDrive and S3-compatible object storage. They are usable without vendor SDK packages. Runtime tokens/keys are passed to the process environment; they are not serialized into QZRA archives, sidecars, local configuration, or CLI output.

## CLI

```bash
# Google Drive (OAuth bearer token)
export QAMZOR_GOOGLE_DRIVE_ACCESS_TOKEN='...'
qz cloud-provider push google-drive backup.qzra
qz cloud-provider pull google-drive DRIVE_FILE_ID restored.qzra --metadata-reference DRIVE_SIDECAR_FILE_ID

# Dropbox (OAuth bearer token)
export QAMZOR_DROPBOX_ACCESS_TOKEN='...'
qz cloud-provider push dropbox backup.qzra
qz cloud-provider pull dropbox '/backup.qzra' restored.qzra

# OneDrive (Microsoft Graph delegated OAuth bearer token)
export QAMZOR_ONEDRIVE_ACCESS_TOKEN='...'
qz cloud-provider push onedrive backup.qzra
qz cloud-provider pull onedrive DRIVE_ITEM_ID restored.qzra --metadata-reference SIDECAR_ITEM_ID

# S3 / S3-compatible (AWS Signature Version 4)
export QAMZOR_S3_BUCKET='my-bucket'
export QAMZOR_S3_ACCESS_KEY='...'
export QAMZOR_S3_SECRET_KEY='...'
export QAMZOR_S3_REGION='us-east-1'
# Optional for temporary credentials or compatible endpoints:
export QAMZOR_S3_SESSION_TOKEN='...'
export QAMZOR_S3_ENDPOINT_URL='https://s3-compatible.example'
qz cloud-provider push s3 backup.qzra
qz cloud-provider pull s3 backup.qzra restored.qzra
```

Drive and OneDrive restore require the `metadata_reference` returned by the push command. Dropbox and S3 store the metadata sidecar beside the archive and can derive its name. Use the returned remote references; they are provider IDs or paths, not local filenames.

## Credential and permission setup

- **Google Drive:** create an OAuth client in Google Cloud and obtain an access token with a Drive file-write/read scope suitable for files created by the application (commonly `drive.file`). This adapter accepts an access token; it does not run an interactive OAuth consent/refresh-token flow.
- **Dropbox:** create an app in the Dropbox developer console and grant the `files.content.write` and `files.content.read` scopes. Supply a valid access token.
- **OneDrive:** register an application in Microsoft Entra ID and grant delegated Graph file read/write permissions (for example `Files.ReadWrite`), then supply a valid access token.
- **S3:** create an IAM principal with `s3:PutObject` and `s3:GetObject` for the target bucket/prefix. Add multipart create/upload/complete/abort permissions for archives larger than 5 GiB. Use temporary credentials where possible.

For GitHub Actions, store credentials in repository or environment **Secrets**, then map them to environment variables in the workflow step. Never put tokens in source code, command-line arguments, issue logs or committed `.env` files. Access tokens expire; refresh and issue a valid token outside this CLI before running it.

## Integrity and limits

A successful push stores a `.qamzor.json` sidecar with provider, remote object reference, byte length and SHA-256. Pull downloads to a same-directory temporary file, validates exact size and SHA-256, validates the QZRA header, then publishes the destination atomically (or only when `--overwrite` is set). Run `qz verify` after restoring to authenticate/decode all archive data; encrypted QZRA archives also require their passwords.

S3 uses single-object PUT through 5 GiB and multipart upload above that, with 128 MiB parts and a current QAMZOR 1 TiB safety ceiling. Drive, Dropbox and OneDrive use their resumable/session upload endpoints. The metadata sidecar is a checksum record, not a cryptographic signature: an attacker able to replace both object and sidecar could replace the digest too. For untrusted storage, also sign archives with QAMZOR Ed25519 signatures and verify against a separately trusted public key.

The connectors are Python Core/CLI adapters. They are not yet exposed as Android login screens or background Android sync providers. No live cloud account was available in this source-build environment, so provider APIs were validated with local tests and request construction, not live uploads/restores. They are development connectors pending end-to-end tests against real accounts and vendor-specific error/rate-limit qualification.
