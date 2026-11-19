package org.qamzor.javasdk;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unchecked exception thrown by every failing SDK operation. */
public final class QamzorException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final Pattern LOCKED = Pattern.compile(
            "Folder is locked; unlock it with its independent password: (.+)", Pattern.CASE_INSENSITIVE);

    private final ErrorCategory category;

    public QamzorException(ErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category");
    }

    /** The classified failure category. */
    public ErrorCategory category() {
        return category;
    }

    /**
     * For {@link ErrorCategory#FOLDER_LOCKED}: the archive-relative path of the folder that
     * must be unlocked, or {@code null} when unknown.
     */
    public String lockedFolderPath() {
        if (category != ErrorCategory.FOLDER_LOCKED || getMessage() == null) {
            return null;
        }
        Matcher matcher = LOCKED.matcher(getMessage());
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
