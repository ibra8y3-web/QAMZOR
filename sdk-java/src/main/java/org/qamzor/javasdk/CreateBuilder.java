package org.qamzor.javasdk;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qamzor.core.ArchiveEngine;

/** Fluent builder for creating a {@code .qzra} archive. Not thread-safe. */
public final class CreateBuilder {
    private final File archive;
    private final List<File> sources;
    private boolean overwrite;
    private String password;
    private boolean encryptMetadata;
    private final Map<String, String> folderPasswords = new LinkedHashMap<>();

    CreateBuilder(File archive, List<File> sources) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.sources = new ArrayList<>(Objects.requireNonNull(sources, "sources"));
    }

    /** Replace an existing destination file (default: refuse). */
    public CreateBuilder overwrite(boolean value) {
        this.overwrite = value;
        return this;
    }

    /** Archive master password: writes authenticated AES-256-GCM (QZRA 1.2, or 1.3 when needed). */
    public CreateBuilder password(String value) {
        this.password = value;
        return this;
    }

    /** Hide file names, sizes and the entry count (QZRA 1.3). Requires {@link #password(String)}. */
    public CreateBuilder encryptMetadata(boolean value) {
        this.encryptMetadata = value;
        return this;
    }

    /** Protect an archive-relative folder with its own password (QZRA 1.3, at most 32 folders). */
    public CreateBuilder folderPassword(String folderPath, String folderPassword) {
        folderPasswords.put(Objects.requireNonNull(folderPath, "folderPath"), Objects.requireNonNull(folderPassword, "folderPassword"));
        return this;
    }

    /** Creates the archive. @throws QamzorException on any failure */
    public CreateResult execute() {
        try {
            ArchiveEngine.CreateResult r = ArchiveEngine.INSTANCE.create(
                    archive, sources, overwrite, password, encryptMetadata, new LinkedHashMap<>(folderPasswords));
            return new CreateResult(r.getArchive(), r.getEntries(), r.getFiles(), r.getOriginalBytes(), r.getStoredBytes());
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }
}
