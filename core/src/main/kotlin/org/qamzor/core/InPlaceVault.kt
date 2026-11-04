package org.qamzor.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * In-place archiving: turns a file or folder into a single `.qzra` sitting next to it and
 * then removes the original, so the content can only be read through QAMZOR.
 *
 * Safety order (the original is touched only at the very end):
 *  1. check free space and take a snapshot of the source (file count, bytes, newest mtime)
 *  2. write the archive (the engine publishes it atomically only when complete)
 *  3. fully verify the archive with the password (every chunk + SHA-256)
 *  4. check the source did not change while it was being archived
 *  5. only then delete the original
 * Any failure before step 5 leaves the original untouched and removes the partial archive.
 */
object InPlaceVault {
    private const val SPACE_MARGIN = 16L * 1024 * 1024
    private const val MAX_REPORTED_PATHS = 5000
    private const val RESTORE_WORK_TTL_MS = 7L * 24 * 60 * 60 * 1000

    data class SealResult(
        val archive: File,
        val files: Int,
        val originalBytes: Long,
        val storedBytes: Long,
        val encrypted: Boolean,
        /** Absolute paths of removed files (capped), so callers can refresh media indexes. */
        val removedPaths: List<String>
    )

    private data class Snapshot(val files: Int, val directories: Int, val bytes: Long, val newest: Long)
    private class ArchiveDeleteException(message: String) : IllegalStateException(message)

    /** True when the file is a QZRA 1.2/1.3 archive, which always requires a password. */
    @JvmStatic
    fun isEncrypted(archive: File): Boolean {
        return try {
            RandomAccessFile(archive, "r").use { input ->
                if (input.length() < 12) {
                    false
                } else {
                    val signature = ByteArray(8)
                    input.readFully(signature)
                    val expected = byteArrayOf(0x51, 0x5A, 0x52, 0x41, 0x0D, 0x0A, 0x1A, 0x0A)
                    if (!signature.contentEquals(expected)) return@use false
                    val major = input.readUnsignedShort()
                    val minor = input.readUnsignedShort()
                    major == 1 && minor in 2..3
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun isQzra(file: File): Boolean = file.isFile && file.name.endsWith(".qzra", ignoreCase = true)

    /**
     * Archive [source] next to itself as `<name>.qzra`. With a [password] the archive is
     * QZRA 1.3 (AES-256-GCM, scrypt, names/sizes hidden). Without one it is a plain QZRA 1.1
     * container: unreadable by other apps but NOT secret.
     */
    @JvmStatic
    @JvmOverloads
    fun seal(
        source: File,
        password: String? = null,
        deleteOriginal: Boolean = true,
        folderPasswords: Map<String, String> = emptyMap(),
        onProgress: ((ArchiveEngine.OperationProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): SealResult {
        require(password == null || password.isNotEmpty()) { "Password must not be empty" }
        require(folderPasswords.isEmpty() || !password.isNullOrEmpty()) {
            "A master archive password is required for independently protected folders"
        }
        require(folderPasswords.size <= 32) { "At most 32 independently protected folders are supported" }
        val src = source.absoluteFile
        require(Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Source does not exist: ${src.path}" }
        require(!Files.isSymbolicLink(src.toPath())) { "Symbolic links are not supported" }
        val parent = src.parentFile ?: error("Cannot archive the filesystem root")
        require(parent.canWrite()) { "No write access to ${parent.path}" }
        if (src.isFile) require(!src.name.startsWith(".qamzor-")) { "Refusing to archive a QAMZOR temporary file" }

        val before = snapshot(src)
        // Worst case (incompressible data): payload spool + final archive coexist with the original.
        val needed = before.bytes * 2 + SPACE_MARGIN
        require(parent.usableSpace >= needed) {
            "Not enough free space: need about ${needed / (1024 * 1024)} MB free next to the item"
        }

        val target = uniqueFile(parent, src.name, ".qzra")
        val created = try {
            ArchiveEngine.convertToQzra(
                target, listOf(src), password = password,
                encryptMetadata = password != null || folderPasswords.isNotEmpty(),
                folderPasswords = folderPasswords,
                onProgress = onProgress,
                isCancelled = isCancelled
            )
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        try {
            val verified = ArchiveEngine.verify(target, password, folderPasswords,
                onProgress = { value -> onProgress?.invoke(value.copy(phase = "verifying")) },
                isCancelled = isCancelled)
            require(verified.valid && verified.filesChecked == created.files) { "Archive verification failed" }
            require(created.files == before.files && created.originalBytes == before.bytes) {
                "The item changed while it was being archived; nothing was removed"
            }
            val after = snapshot(src)
            require(after == before) { "The item changed while it was being archived; nothing was removed" }
            if (isCancelled?.invoke() == true) throw InterruptedException("QAMZOR operation cancelled before source removal")
        } catch (error: Exception) {
            target.delete()
            throw error
        }

        val removed = ArrayList<String>()
        if (deleteOriginal) {
            collectPaths(src, removed)
            deleteTree(src)
            require(!Files.exists(src.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "The archive was created and verified (${target.name}) but the original could not be fully removed"
            }
        }
        return SealResult(target, created.files, created.originalBytes, created.storedBytes, password != null, removed)
    }

    /**
     * Restore the original item next to the archive. Extraction happens into a hidden work
     * folder first and is moved into place only after every file has been verified.
     */
    @JvmStatic
    @JvmOverloads
    fun restore(
        archive: File,
        password: String?,
        removeArchive: Boolean,
        folderPasswords: Map<String, String> = emptyMap(),
        onProgress: ((ArchiveEngine.OperationProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<File> {
        val parent = archive.absoluteFile.parentFile ?: error("Invalid archive location")
        require(parent.canWrite()) { "No write access to ${parent.path}" }
        val entries = ArchiveEngine.list(archive, password, folderPasswords)
        require(entries.isNotEmpty()) { "The archive is empty" }
        val needed = entries.filter { it.type == "file" }.sumOf { it.size } + SPACE_MARGIN
        require(parent.usableSpace >= needed) {
            "Not enough free space: need about ${needed / (1024 * 1024)} MB free"
        }
        val topNames = entries.map { it.path.substringBefore('/') }.distinct()
        cleanupStaleRestoreWork(parent)
        val work = File(parent, ".qamzor-restore-${System.nanoTime()}")
        require(work.mkdir()) { "Cannot create a work folder in ${parent.path}" }
        val restored = ArrayList<File>()
        try {
            ArchiveEngine.extract(archive, work, false, password, folderPasswords, onProgress, isCancelled)
            for (name in topNames) {
                if (isCancelled?.invoke() == true) throw InterruptedException("QAMZOR operation cancelled during restore")
                val produced = File(work, name)
                require(produced.exists()) { "Extraction did not produce: $name" }
                val destination = uniqueRestoreTarget(parent, name)
                Files.move(produced.toPath(), destination.toPath())
                restored += destination
            }
            if (isCancelled?.invoke() == true) throw InterruptedException("QAMZOR operation cancelled before archive cleanup")
            if (removeArchive) {
                val deleted = archive.delete()
                if (!deleted && archive.exists()) {
                    // Restored files are complete and verified. Keep them even though archive cleanup failed.
                    throw ArchiveDeleteException("Content was restored successfully, but the archive could not be deleted: ${archive.name}. The restored files are safe and the archive remains available.")
                }
            }
            return restored
        } catch (failure: Exception) {
            if (failure !is ArchiveDeleteException) {
                val rollbackFailures = restored.asReversed().filter { item ->
                    try {
                        deleteTree(item)
                        item.exists()
                    } catch (_: Exception) { true }
                }
                if (rollbackFailures.isNotEmpty()) {
                    throw IllegalStateException(
                        "Restore failed and ${rollbackFailures.size} partially restored item(s) could not be rolled back safely. Original archive remains available.",
                        failure
                    )
                }
            }
            throw failure
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Extract one file or folder from an archive into the archive's own folder (the archive stays).
     * Same safety order as [restore]: hidden work folder, full verification, then move into place.
     */
    @JvmStatic
    @JvmOverloads
    fun restoreItem(
        archive: File,
        path: String,
        password: String?,
        folderPasswords: Map<String, String> = emptyMap(),
        onProgress: ((ArchiveEngine.OperationProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): File {
        val parent = archive.absoluteFile.parentFile ?: error("Invalid archive location")
        require(parent.canWrite()) { "No write access to ${parent.path}" }
        val entries = ArchiveEngine.list(archive, password, folderPasswords)
        val prefix = "$path/"
        val needed = entries.filter { it.type == "file" && (it.path == path || it.path.startsWith(prefix)) }
            .sumOf { it.size } + SPACE_MARGIN
        require(parent.usableSpace >= needed) {
            "Not enough free space: need about ${needed / (1024 * 1024)} MB free"
        }
        cleanupStaleRestoreWork(parent)
        val work = File(parent, ".qamzor-restore-${System.nanoTime()}")
        require(work.mkdir()) { "Cannot create a work folder in ${parent.path}" }
        try {
            ArchiveEngine.extractSelected(archive, work, setOf(path), password = password,
                folderPasswords = folderPasswords, onProgress = onProgress, isCancelled = isCancelled)
            if (isCancelled?.invoke() == true) throw InterruptedException("QAMZOR operation cancelled before extracted item was published")
            val produced = File(work, path)
            require(produced.exists()) { "Extraction did not produce: $path" }
            val destination = uniqueRestoreTarget(parent, path.substringAfterLast('/'))
            Files.move(produced.toPath(), destination.toPath())
            return destination
        } finally {
            work.deleteRecursively()
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /**
     * Remove only abandoned QAMZOR restore workspaces older than seven days.
     * Recent folders may belong to another live process and are deliberately preserved.
     */
    private fun cleanupStaleRestoreWork(parent: File): Int {
        val cutoff = System.currentTimeMillis() - RESTORE_WORK_TTL_MS
        var removed = 0
        parent.listFiles()?.forEach { candidate ->
            if (!candidate.name.startsWith(".qamzor-restore-") || !candidate.isDirectory || Files.isSymbolicLink(candidate.toPath())) return@forEach
            val newest = try {
                Files.walk(candidate.toPath()).use { stream ->
                    val iterator = stream.iterator()
                    var latest = candidate.lastModified()
                    while (iterator.hasNext()) {
                        val path = iterator.next()
                        if (!Files.isSymbolicLink(path)) latest = maxOf(latest, path.toFile().lastModified())
                    }
                    latest
                }
            } catch (_: Exception) { Long.MAX_VALUE }
            if (newest < cutoff && candidate.deleteRecursively()) removed++
        }
        return removed
    }

    private fun snapshot(root: File): Snapshot {
        if (root.isFile) return Snapshot(1, 0, root.length(), root.lastModified())
        var files = 0
        var directories = 0
        var bytes = 0L
        var newest = 0L
        Files.walk(root.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                require(!Files.isSymbolicLink(path)) { "Symbolic links are not supported: $path" }
                val file = path.toFile()
                if (file.isDirectory) directories++
                else {
                    files++
                    bytes += file.length()
                    if (file.lastModified() > newest) newest = file.lastModified()
                }
            }
        }
        return Snapshot(files, directories, bytes, newest)
    }

    private fun collectPaths(root: File, out: MutableList<String>) {
        if (root.isFile) { out.add(root.path); return }
        Files.walk(root.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext() && out.size < MAX_REPORTED_PATHS) {
                val file = iterator.next().toFile()
                if (file.isFile) out.add(file.path)
            }
        }
    }

    private fun deleteTree(root: File) {
        if (!root.exists()) return
        val deleted = if (root.isDirectory) root.deleteRecursively() else root.delete()
        require(deleted || !root.exists()) { "Could not remove ${root.path}" }
    }

    private fun uniqueFile(parent: File, baseName: String, suffix: String): File {
        var candidate = File(parent, baseName + suffix)
        var counter = 2
        while (candidate.exists()) {
            candidate = File(parent, "$baseName ($counter)$suffix")
            counter++
        }
        return candidate
    }

    private fun uniqueRestoreTarget(parent: File, name: String): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (true) {
            val label = if (counter == 1) " (restored)" else " (restored $counter)"
            candidate = File(parent, stem + label + extension)
            if (!candidate.exists()) return candidate
            counter++
        }
    }
}
