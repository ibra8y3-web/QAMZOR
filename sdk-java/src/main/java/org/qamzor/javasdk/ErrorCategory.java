package org.qamzor.javasdk;

/**
 * Failure categories. The first five names are the normative categories of the QAMZOR
 * Compatibility Suite (see {@code registry/qzra-registry.json}); the rest describe
 * conditions that are specific to using an SDK.
 */
public enum ErrorCategory {
    /** An AES-GCM tag failed: wrong password or tampered encrypted data (indistinguishable). */
    AUTHENTICATION_FAILED,
    /** Unencrypted data failed a size, digest or decompression check. */
    INTEGRITY_FAILED,
    /** Malformed header, manifest or payload layout, or a truncated archive. */
    INVALID_FORMAT,
    /** An entry path is absolute, traverses upward, is a device name or is otherwise unsafe. */
    UNSAFE_PATH,
    /** The archive's format version is not implemented. */
    UNSUPPORTED_VERSION,
    /** The archive is encrypted and no password was supplied. */
    PASSWORD_REQUIRED,
    /** A protected folder needs its independent password. */
    FOLDER_LOCKED,
    /** A destination file already exists and overwriting was not requested. */
    ALREADY_EXISTS,
    /** The caller supplied an invalid argument (for example an unknown protected folder). */
    INVALID_ARGUMENT,
    /** An operating-system I/O failure. */
    IO_ERROR,
    /** The failure could not be classified. */
    UNKNOWN
}
