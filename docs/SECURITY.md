# Security Model and Known Limitations

## Threat model

### Assets to protect

- Original file contents and their integrity during archive and repository operations.
- Archive path names and metadata (visible in QZRA 1.2; encrypted in QZRA 1.3).
- Encrypted archive plaintext and the passphrases used to derive its keys.
- Ed25519 signing private keys and the trusted public-key identity used for verification.
- Repository snapshot manifests and content-addressed objects.
- Delta packages transferred between repositories.

### Adversaries and trust boundaries

- An archive or repository may be malformed or deliberately crafted by an untrusted party.
- Stored payloads and repository objects may be corrupted or modified after creation.
- Source trees may contain symlinks, special files or paths that collide across operating systems.
- Restore destinations may contain symlinks or pre-existing files that must not be overwritten without an explicit option.
- The repository root and signing-key storage are assumed to be controlled by the local account operating QAMZOR.

### Assumptions and out-of-scope threats

- The Python runtime, operating system, filesystem, and installed `cryptography` implementation are trusted.
- Users choose strong archive/key passphrases and distribute trusted verification public keys through an authenticated channel.
- A compromised host, malicious administrator, stolen unlocked credentials, and hostile local processes racing filesystem operations are outside the current protection guarantee.
- Repository catalog/manifests are integrity-checked against one another but are not authenticated against an attacker able to rewrite both; `.qzd` deltas must be signed and verified separately when transferred over an untrusted channel.
- QZRA 1.2 authenticates payloads and manifest metadata, but does not conceal the manifest or filenames. QZRA 1.3 encrypts the complete manifest and hides the true entry count.
- QZRA 1.3 folder scopes use independent scrypt salts and AES-256-GCM keys. A folder password is required for payload reads in that scope; the archive master password is still required to decrypt the manifest. The deepest nested protected folder takes precedence.
- QZRA 1.3 exposes total archive length and encrypted-manifest length; size/access-pattern leakage is not eliminated.

## Current protections

- Archive paths are checked for absolute paths, traversal components, backslashes, drive/stream separators, NULs, non-NFC names, reserved Windows device names, duplicate paths, and case-insensitive collisions.
- Source symbolic links and non-regular special files are rejected.
- Extraction rejects symlink destinations and symlink/non-directory parent components.
- Each chunk is decompressed with an output-size ceiling based on its declared length and checked against its own SHA-256 digest; the reconstructed file is checked again against its complete size and digest.
- File, chunk, manifest, entry-count and payload bounds are checked before exposing archive entries.
- Partial payload overlaps are rejected. Shared ranges must match exactly and carry identical content metadata.
- Compression workers have a bounded pending queue. At most `2 * workers` chunks are queued, with a configurable worker limit of 32.
- Optional QZRA 1.2/1.3 encryption uses AES-256-GCM from the `cryptography` package and scrypt password derivation. QZRA 1.3 binds its encrypted manifest to the header and binds chunks to their folder scope. The format has not received an independent cryptographic audit.
- Detached archive signatures use Ed25519 from `cryptography`; verification checks the archive size, SHA-256 digest, signature and caller-supplied public-key fingerprint.
- Detached recovery sidecars use XOR parity and per-block SHA-256 checks. They can recover at most one damaged block per parity group and always write to a separate output path.
- Archive creation and individual extracted files use temporary files before publication.

## Important limitations

- This is a pre-alpha reference implementation, not a security-audited production program.
- SHA-256 detects corruption and supports content addressing, but does not authenticate the archive publisher by itself. Detached Ed25519 signatures are available when the caller supplies a trusted public key.
- Encryption and signatures are optional and have not received an independent cryptographic audit. Encryption protects payloads and authenticates the manifest but leaves paths, sizes and other metadata visible.
- Ed25519 verification is only as trustworthy as the public key supplied by the caller; users must obtain and pin that key through a trusted channel.
- XOR recovery is a single-erasure-per-group mechanism, not Reed-Solomon or multi-block recovery. It does not replace independent backups, and recovery sidecars are not authenticated unless separately signed.
- Password recovery is not supported.
- Local filesystem race attacks involving a hostile process changing destination directories concurrently are not fully eliminated.
- No decompression sandbox or formal verification has been completed. A deterministic 2,000-case byte-mutation campaign is included, but there has been no sustained coverage-guided fuzzing or independent security audit.
- File metadata such as permissions, timestamps, ownership, ACLs and symlinks is not preserved.
- Archive chunk deduplication is scoped to one archive. The local repository adds cross-snapshot file-level object reuse and reachability-based garbage collection, but not chunk-level repository deduplication or a remote object store.
- The Python implementation is a reference implementation. It is not the planned optimized Rust core.

Do not use this prototype as the sole backup of irreplaceable data. Keep independent verified backups.
