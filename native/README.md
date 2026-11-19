# QAMZOR Native SDK (libqzra) - C and C++

Reads, verifies and extracts **QZRA 1.0-1.3** (including encrypted manifests and independent folder
passwords). Cryptography comes from OpenSSL >= 3.0 (`EVP_PBE_scrypt`, AES-256-GCM, HMAC-SHA-256, SHA-256);
decompression from zlib. There is **no custom cryptography**. Version `0.1.0-dev`, pre-1.0 API.

```c
#include <qzra.h>
qzra_archive *a; qzra_error err;
if (qzra_open("backup.qzra", "master password", &a, &err) != QZRA_OK) { /* err.message */ }
qzra_unlock_folder(a, "Project/Vault", "vault password", &err);   /* QZRA 1.3 protected folder */
qzra_verify(a, NULL, &err);
qzra_extract(a, "restore", 0, &err);
qzra_close(a);
```
C++17: `#include <qzra.hpp>` gives RAII `qzra::Archive` and `qzra::Error` (`status()`, `category()`).

Build: `make -C native check` (needs OpenSSL dev headers, zlib; POSIX) or CMake (`native/CMakeLists.txt`).

## Verified here
18/18 Compatibility Suite vectors; the same run under AddressSanitizer + UBSan + LeakSanitizer; 3,128
randomly corrupted archives opened and verified without a crash or sanitizer report; C++ wrapper smoke test.

## Known limits (be aware before relying on it)
- **Read-only**: this release cannot create archives.
- No Unicode NFC check and no non-ASCII case-folding of entry paths (writers produce NFC; collisions are only
  detected for ASCII case differences). Path traversal, absolute paths, device names, backslash and colon are rejected.
- POSIX only (uses `lstat`, `mkdir`, `fseeko`); Windows is not supported yet.
- Manifests are limited to ~4 million JSON nodes; JSON with non-integer numbers is rejected.
- Not independently audited. Linux/macOS CI builds are configured in `.github/workflows/native.yml`; only the Linux x86-64 build
  has been executed so far (OpenSSL 3.0.13 runtime library, compiled with Node.js-bundled OpenSSL 3 headers).
