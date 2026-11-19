package org.qamzor.javasdk;

import java.io.EOFException;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.ZipException;

/**
 * Maps failures thrown by the Kotlin core to {@link ErrorCategory}.
 *
 * <p>The core reports failures as {@link IllegalArgumentException}, {@link IllegalStateException}
 * and I/O exceptions with descriptive English messages rather than typed exceptions, so this
 * class classifies by exception type first and by well-known message fragments second. The
 * Compatibility Suite exercises every category end to end; messages that match no rule are
 * reported as {@link ErrorCategory#INVALID_ARGUMENT} (argument problems) or
 * {@link ErrorCategory#UNKNOWN} (everything else) instead of being guessed.
 */
public final class ErrorClassifier {
    private ErrorClassifier() { }

    public static ErrorCategory classify(Throwable error) {
        Throwable t = error;
        String message = "";
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t.getMessage() != null && message.isEmpty()) {
                message = t.getMessage();
            }
            if (t instanceof ZipException || t instanceof DataFormatException) {
                return ErrorCategory.INTEGRITY_FAILED;
            }
            if (t instanceof EOFException) {
                return ErrorCategory.INVALID_FORMAT;
            }
            if (t.getClass().getName().endsWith("SerializationException")) {
                return ErrorCategory.INVALID_FORMAT;
            }
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("folder is locked")) {
            return ErrorCategory.FOLDER_LOCKED;
        }
        if (m.contains("enter the archive master password") || m.contains("enter its password") || m.contains("password is required")) {
            return ErrorCategory.PASSWORD_REQUIRED;
        }
        if (m.contains("wrong password") || m.contains("authentication failed")) {
            return ErrorCategory.AUTHENTICATION_FAILED;
        }
        if (m.contains("unsupported qzra version")) {
            return ErrorCategory.UNSUPPORTED_VERSION;
        }
        if (m.contains("unsafe archive path") || m.contains("unsafe extraction path") || m.contains("reserved device path")
                || m.contains("must use unicode nfc") || m.contains("symbolic link")) {
            return ErrorCategory.UNSAFE_PATH;
        }
        if (m.contains("refusing to overwrite") || m.contains("destination already exists") || m.contains("already exists")) {
            return ErrorCategory.ALREADY_EXISTS;
        }
        if (m.contains("integrity check failed") || m.contains("exceeds declared size") || m.contains("sha-256 mismatch")) {
            return ErrorCategory.INTEGRITY_FAILED;
        }
        if (m.contains("no protected folder exists") || m.contains("select at least one") || m.contains("password must not be empty")
                || m.contains("does not exist")) {
            return ErrorCategory.INVALID_ARGUMENT;
        }
        if (looksLikeFormatProblem(m)) {
            return ErrorCategory.INVALID_FORMAT;
        }
        if (error instanceof IOException) {
            return ErrorCategory.IO_ERROR;
        }
        if (error instanceof IllegalArgumentException) {
            return ErrorCategory.INVALID_ARGUMENT;
        }
        return ErrorCategory.UNKNOWN;
    }

    private static boolean looksLikeFormatProblem(String m) {
        String[] fragments = {
            "invalid qzra", "too small to be qzra", "invalid manifest", "manifest extends", "manifest/header", "header flags",
            "chunk", "block", "payload", "entry data", "envelope", "key-derivation", "kdf", "scope", "duplicate archive path",
            "case-insensitive path collision", "parent directory", "child paths", "directory entry", "digest", "unsupported entry type",
            "unsupported compression", "unsupported qzra", "malformed", "too many manifest entries", "hide the manifest entry count",
            "security metadata", "encryption metadata", "legacy"
        };
        for (String fragment : fragments) {
            if (m.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Wraps any failure thrown by the core in a {@link QamzorException}. */
    static QamzorException wrap(Throwable error) {
        if (error instanceof QamzorException) {
            return (QamzorException) error;
        }
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return new QamzorException(classify(error), message, error);
    }
}
