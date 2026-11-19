/*
 * QAMZOR Native SDK (libqzra) - C API, pre-1.0.
 *
 * Reads, verifies and extracts QZRA 1.0-1.3 archives. Uses OpenSSL (libcrypto >= 3.0) for
 * AES-256-GCM, scrypt, HMAC-SHA-256 and SHA-256, and zlib for DEFLATE. No custom cryptography.
 * This release does NOT write archives; use the Kotlin/Java SDK or the Python reference
 * implementation to create them.
 *
 * Thread safety: a qzra_archive must not be used from several threads at once.
 * Distinct archives are independent.
 */
#ifndef QZRA_H
#define QZRA_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define QZRA_SDK_VERSION "0.1.0-dev"
#define QZRA_ERROR_MESSAGE_SIZE 256

typedef enum {
    QZRA_OK = 0,
    QZRA_ERR_INVALID_ARGUMENT,
    QZRA_ERR_IO,
    QZRA_ERR_NO_MEMORY,
    QZRA_ERR_INVALID_FORMAT,        /* malformed container, manifest or layout */
    QZRA_ERR_UNSUPPORTED_VERSION,   /* header version this SDK does not implement */
    QZRA_ERR_UNSAFE_PATH,           /* traversal, absolute, device-name or symlink escape */
    QZRA_ERR_AUTHENTICATION_FAILED, /* AEAD tag failure: wrong password OR tampering */
    QZRA_ERR_INTEGRITY_FAILED,      /* digest/size/decompression failure on unencrypted data */
    QZRA_ERR_PASSWORD_REQUIRED,     /* archive is encrypted and no password was supplied */
    QZRA_ERR_FOLDER_LOCKED,         /* a protected folder has not been unlocked */
    QZRA_ERR_LIMIT,                 /* implementation limit exceeded */
    QZRA_ERR_EXISTS                 /* destination file exists and overwrite is off */
} qzra_status;

typedef struct {
    qzra_status status;
    char message[QZRA_ERROR_MESSAGE_SIZE]; /* human readable, English, never contains secrets */
} qzra_error;

typedef struct qzra_archive qzra_archive;

typedef struct {
    const char *path;      /* UTF-8, '/'-separated, valid while the archive is open */
    int is_directory;
    uint64_t size;         /* uncompressed size; 0 for directories */
} qzra_entry;

typedef struct {
    uint64_t files_checked;
    uint64_t bytes_checked;
    int version_minor;     /* QZRA 1.<minor> */
} qzra_verify_result;

const char *qzra_version(void);
/* Stable identifier such as "AUTHENTICATION_FAILED"; matches the Compatibility Suite categories. */
const char *qzra_status_name(qzra_status status);

/* Opens an archive. `password` is the archive master password (UTF-8); pass NULL for QZRA 1.0/1.1.
 * For QZRA 1.2/1.3 a NULL password yields QZRA_ERR_PASSWORD_REQUIRED. `err` may be NULL. */
qzra_status qzra_open(const char *path, const char *password, qzra_archive **out, qzra_error *err);
void qzra_close(qzra_archive *archive);

/* QZRA 1.3 only: unlock an independently protected folder (exact archive-relative path). */
qzra_status qzra_unlock_folder(qzra_archive *archive, const char *folder_path, const char *password, qzra_error *err);

int qzra_version_minor(const qzra_archive *archive);
size_t qzra_entry_count(const qzra_archive *archive);
qzra_status qzra_entry_at(const qzra_archive *archive, size_t index, qzra_entry *out);

/* Decrypts/decompresses every file and checks all digests. Files in locked folders yield
 * QZRA_ERR_FOLDER_LOCKED. `result` may be NULL. */
qzra_status qzra_verify(qzra_archive *archive, qzra_verify_result *result, qzra_error *err);

/* Extracts all entries below `destination` (created if missing). Each file is fully verified
 * before it is moved into place. Refuses to write through symbolic links. An existing file
 * yields QZRA_ERR_EXISTS unless `overwrite` is non-zero. */
qzra_status qzra_extract(qzra_archive *archive, const char *destination, int overwrite, qzra_error *err);

#ifdef __cplusplus
}
#endif
#endif
