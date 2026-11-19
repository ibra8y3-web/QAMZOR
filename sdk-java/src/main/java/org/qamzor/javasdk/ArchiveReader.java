package org.qamzor.javasdk;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qamzor.core.ArchiveEngine;

/**
 * Reads an existing archive. Configure passwords first, then call an operation. Every
 * operation re-opens the archive file, so one reader can be reused; it is not thread-safe.
 */
public final class ArchiveReader {
    private final File archive;
    private String password;
    private final Map<String, String> folderPasswords = new LinkedHashMap<>();

    ArchiveReader(File archive) {
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    /** Archive master password (required for QZRA 1.2 and 1.3). */
    public ArchiveReader password(String value) {
        this.password = value;
        return this;
    }

    /** Supplies the independent password of a protected folder (QZRA 1.3). */
    public ArchiveReader folderPassword(String folderPath, String folderPassword) {
        folderPasswords.put(Objects.requireNonNull(folderPath, "folderPath"), Objects.requireNonNull(folderPassword, "folderPassword"));
        return this;
    }

    private Map<String, String> folders() {
        return new LinkedHashMap<>(folderPasswords);
    }

    /** Every entry in the archive. */
    public List<EntryInfo> listEntries() {
        try {
            List<EntryInfo> out = new ArrayList<>();
            for (ArchiveEngine.EntryInfo e : ArchiveEngine.INSTANCE.list(archive, password, folders())) {
                out.add(new EntryInfo(e.getPath(), e.getType(), e.getSize()));
            }
            return out;
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }

    /** Archive-relative paths of independently protected folders (QZRA 1.3), sorted. */
    public List<String> protectedFolders() {
        try {
            return new ArrayList<>(ArchiveEngine.INSTANCE.protectedFolders(archive, password));
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }

    /** Immediate children of {@code directoryPath} ("" for the root); no payload is read. */
    public List<BrowseItem> browse(String directoryPath) {
        try {
            List<BrowseItem> out = new ArrayList<>();
            for (ArchiveEngine.BrowseItem b : ArchiveEngine.INSTANCE.browse(archive, directoryPath, password, folders())) {
                out.add(new BrowseItem(b.getPath(), b.getName(), b.getType(), b.getSize()));
            }
            return out;
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }

    /** Decrypts, decompresses and checks every file. Throws if anything is wrong. */
    public VerifyResult verify() {
        try {
            ArchiveEngine.VerifyResult r = ArchiveEngine.INSTANCE.verify(archive, password, folders());
            if (!r.getValid()) {
                throw new QamzorException(ErrorCategory.INTEGRITY_FAILED, "Archive verification failed", null);
            }
            return new VerifyResult(r.getFilesChecked(), r.getBytesChecked(), r.getVersion());
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }

    /** Verified preview of one file, retaining at most {@code maxBytes} (0..16 MiB). */
    public PreviewResult preview(String path, int maxBytes) {
        try {
            ArchiveEngine.PreviewResult p = ArchiveEngine.INSTANCE.preview(archive, path, maxBytes, password, folders());
            return new PreviewResult(p.getPath(), p.getData(), p.getBytesTotal(), p.getTruncated());
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }

    /** Extracts everything below {@code destination}; returns the files written. */
    public List<File> extractAll(File destination, boolean overwrite) {
        try {
            return new ArrayList<>(ArchiveEngine.INSTANCE.extract(archive, destination, overwrite, password, folders()));
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }

    /**
     * Extracts only the given archive-relative files and/or directory subtrees. The default
     * safety limits of the core apply (4 GiB total, 100,000 files). Returns files and directories written.
     */
    public List<File> extract(Set<String> paths, File destination, boolean overwrite) {
        try {
            return new ArrayList<>(ArchiveEngine.INSTANCE.extractSelected(
                    archive, destination, new LinkedHashSet<>(paths), overwrite,
                    4L * 1024 * 1024 * 1024, 100_000, password, folders()));
        } catch (Exception e) {
            throw ErrorClassifier.wrap(e);
        }
    }
}
