package org.qamzor.javasdk;

import java.io.File;
import java.util.List;

/**
 * Entry point of the QAMZOR Java SDK (pre-1.0; the API may change before a stable release).
 *
 * <pre>{@code
 * CreateResult created = Qamzor.create(new File("backup.qzra"), List.of(new File("documents")))
 *         .password("correct horse battery staple")
 *         .encryptMetadata(true)
 *         .execute();
 *
 * ArchiveReader reader = Qamzor.open(created.archive()).password("correct horse battery staple");
 * reader.verify();
 * reader.extractAll(new File("restore"), false);
 * }</pre>
 */
public final class Qamzor {
    /** SDK version (kept in sync with {@code VERSION_NAME} in gradle.properties by the build). */
    public static final String SDK_VERSION = QamzorVersion.VALUE;

    private Qamzor() { }

    /** Starts creating {@code archive} from {@code sources} (files and/or folders). */
    public static CreateBuilder create(File archive, List<File> sources) {
        return new CreateBuilder(archive, sources);
    }

    /** Opens an existing archive for reading. */
    public static ArchiveReader open(File archive) {
        return new ArchiveReader(archive);
    }
}
