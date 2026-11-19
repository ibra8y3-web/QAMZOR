package org.qamzor.javasdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.EOFException;
import java.io.IOException;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;

/** Messages below are copied verbatim from org.qamzor.core.ArchiveEngine. */
class ErrorClassifierTest {
    private static ErrorCategory iae(String message) {
        return ErrorClassifier.classify(new IllegalArgumentException(message));
    }

    @Test void authentication() {
        assertEquals(ErrorCategory.AUTHENTICATION_FAILED, iae("Wrong password or QZRA authentication failed"));
        assertEquals(ErrorCategory.AUTHENTICATION_FAILED, iae("Wrong password or QZRA manifest authentication failed"));
        assertEquals(ErrorCategory.AUTHENTICATION_FAILED, iae("Wrong password for protected folder: Project/Vault"));
    }

    @Test void passwordRequired() {
        assertEquals(ErrorCategory.PASSWORD_REQUIRED, iae("QZRA 1.3 hides filenames and metadata; enter the archive master password"));
        assertEquals(ErrorCategory.PASSWORD_REQUIRED, iae("This archive is encrypted. Enter its password to browse it."));
        assertEquals(ErrorCategory.PASSWORD_REQUIRED, iae("This QZRA archive is encrypted; password is required"));
    }

    @Test void folderLockedExposesPath() {
        Throwable t = new IllegalStateException("Folder is locked; unlock it with its independent password: Project/Vault");
        assertEquals(ErrorCategory.FOLDER_LOCKED, ErrorClassifier.classify(t));
        assertEquals("Project/Vault", ErrorClassifier.wrap(t).lockedFolderPath());
        assertNull(ErrorClassifier.wrap(new IllegalArgumentException("Wrong password or QZRA authentication failed")).lockedFolderPath());
    }

    @Test void versionsAndPaths() {
        assertEquals(ErrorCategory.UNSUPPORTED_VERSION, iae("Unsupported QZRA version 1.9"));
        assertEquals(ErrorCategory.UNSAFE_PATH, iae("Unsafe archive path: ../escape.txt"));
        assertEquals(ErrorCategory.UNSAFE_PATH, iae("Reserved device path: docs/CON.txt"));
        assertEquals(ErrorCategory.UNSAFE_PATH, iae("Archive path must use Unicode NFC"));
        assertEquals(ErrorCategory.UNSAFE_PATH, iae("Unsafe extraction path: x"));
    }

    @Test void integrity() {
        assertEquals(ErrorCategory.INTEGRITY_FAILED, iae("Chunk integrity check failed"));
        assertEquals(ErrorCategory.INTEGRITY_FAILED, iae("File integrity check failed: a.txt"));
        assertEquals(ErrorCategory.INTEGRITY_FAILED, ErrorClassifier.classify(new ZipException("invalid stored block lengths")));
    }

    @Test void format() {
        assertEquals(ErrorCategory.INVALID_FORMAT, iae("Invalid QZRA signature"));
        assertEquals(ErrorCategory.INVALID_FORMAT, iae("File is too small to be QZRA"));
        assertEquals(ErrorCategory.INVALID_FORMAT, iae("Block extends beyond payload"));
        assertEquals(ErrorCategory.INVALID_FORMAT, iae("Payload contains a gap or overlapping block"));
        assertEquals(ErrorCategory.INVALID_FORMAT, iae("Manifest/header version mismatch"));
        assertEquals(ErrorCategory.INVALID_FORMAT, iae("Invalid QZRA 1.3 encrypted manifest envelope"));
        assertEquals(ErrorCategory.INVALID_FORMAT, ErrorClassifier.classify(new EOFException()));
    }

    @Test void existsAndArguments() {
        assertEquals(ErrorCategory.ALREADY_EXISTS, iae("Refusing to overwrite existing item: a.txt"));
        assertEquals(ErrorCategory.ALREADY_EXISTS, iae("Destination already exists: /tmp/x.qzra"));
        assertEquals(ErrorCategory.INVALID_ARGUMENT, iae("Select at least one source file or folder"));
        assertEquals(ErrorCategory.INVALID_ARGUMENT, ErrorClassifier.classify(new IllegalStateException("No protected folder exists at: a/b")));
    }

    @Test void ioAndUnknown() {
        assertEquals(ErrorCategory.IO_ERROR, ErrorClassifier.classify(new IOException("disk full")));
        assertEquals(ErrorCategory.UNKNOWN, ErrorClassifier.classify(new RuntimeException("boom")));
    }
}
