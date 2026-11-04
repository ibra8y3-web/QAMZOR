package org.qamzor.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.UUID

/**
 * Local, immutable QZRA snapshots for Android/desktop front-ends.
 * Each snapshot is a separately verified archive. This is deliberately not
 * advertised as block-level deduplication; the Python Repository offers that.
 */
class SnapshotArchiveStore(private val root: File) {
    @Serializable
data class Snapshot(
        val id: String,
        val sourceName: String,
        val archiveName: String,
        val createdAtEpochMillis: Long,
        val files: Int,
        val originalBytes: Long,
        val archiveBytes: Long,
        val archiveSha256: String
    )

    @Serializable
    private data class Catalog(val format: String = "QAMZOR-SNAPSHOT-ARCHIVE", val version: Int = 1, val snapshots: List<Snapshot> = emptyList())

    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }
    private val catalogFile get() = File(root, "catalog.json")

    init {
        require(!Files.isSymbolicLink(root.toPath())) { "Snapshot store cannot be a symbolic link" }
        require(root.mkdirs() || root.isDirectory) { "Cannot create snapshot store: $root" }
        require(!Files.isSymbolicLink(catalogFile.toPath())) { "Snapshot catalog cannot be a symbolic link" }
        if (!catalogFile.exists()) writeCatalog(Catalog()) else readCatalog()
    }

    @Synchronized
    fun createSnapshot(
        source: File,
        password: String? = null,
        folderPasswords: Map<String, String> = emptyMap(),
        onProgress: ((ArchiveEngine.OperationProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Snapshot {
        require(source.exists() && !Files.isSymbolicLink(source.toPath())) { "Snapshot source is missing or unsafe" }
        val id = UUID.randomUUID().toString().replace("-", "")
        val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "backup" }
        val archiveName = "$safeName-${System.currentTimeMillis()}-${id.take(8)}.qzra"
        val archive = File(root, archiveName)
        try {
            val created = ArchiveEngine.create(
                archive, listOf(source), overwrite = false, password = password,
                encryptMetadata = password != null || folderPasswords.isNotEmpty(),
                folderPasswords = folderPasswords, onProgress = onProgress, isCancelled = isCancelled
            )
            val verified = ArchiveEngine.verify(archive, password, folderPasswords,
                onProgress = { value -> onProgress?.invoke(value.copy(phase = "snapshot-verifying")) },
                isCancelled = isCancelled)
            require(verified.valid && verified.filesChecked == created.files && verified.bytesChecked == created.originalBytes) {
                "Snapshot verification did not match its source inventory"
            }
            if (isCancelled?.invoke() == true) throw InterruptedException("Snapshot cancelled before catalog publication")
            val record = Snapshot(
                id = id, sourceName = source.name, archiveName = archiveName,
                createdAtEpochMillis = System.currentTimeMillis(), files = created.files,
                originalBytes = created.originalBytes, archiveBytes = archive.length(),
                archiveSha256 = digest(archive)
            )
            val current = readCatalog()
            writeCatalog(current.copy(snapshots = current.snapshots + record))
            return record
        } catch (failure: Exception) {
            archive.delete()
            throw failure
        }
    }

    @Synchronized
    fun listSnapshots(): List<Snapshot> = readCatalog().snapshots.sortedByDescending { it.createdAtEpochMillis }

    @Synchronized
    fun verifySnapshot(
        id: String, password: String? = null, folderPasswords: Map<String, String> = emptyMap(),
        onProgress: ((ArchiveEngine.OperationProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): ArchiveEngine.VerifyResult {
        val snapshot = find(id)
        val archive = safeArchive(snapshot)
        require(archive.length() == snapshot.archiveBytes) { "Snapshot archive size mismatch: $id" }
        val actualDigest = digest(archive) { completed ->
            if (isCancelled?.invoke() == true) throw InterruptedException("Snapshot verification cancelled")
            onProgress?.invoke(ArchiveEngine.OperationProgress("snapshot-digest", completed, snapshot.archiveBytes.coerceAtLeast(1L), 0, 1))
        }
        require(actualDigest == snapshot.archiveSha256) { "Snapshot archive SHA-256 mismatch: $id" }
        return ArchiveEngine.verify(archive, password, folderPasswords, onProgress, isCancelled)
    }

    @Synchronized
    fun restoreSnapshot(
        id: String, destination: File, password: String? = null,
        folderPasswords: Map<String, String> = emptyMap(),
        onProgress: ((ArchiveEngine.OperationProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): List<File> {
        val snapshot = find(id)
        val archive = safeArchive(snapshot)
        require(archive.length() == snapshot.archiveBytes && digest(archive) == snapshot.archiveSha256) {
            "Snapshot archive digest/size mismatch: $id"
        }
        return ArchiveEngine.extract(archive, destination, overwrite = false, password = password,
            folderPasswords = folderPasswords, onProgress = onProgress, isCancelled = isCancelled)
    }

    @Synchronized
    fun prune(keepLast: Int): Int {
        require(keepLast in 1..10000) { "keepLast must be between 1 and 10000" }
        val catalog = readCatalog()
        val ordered = catalog.snapshots.sortedByDescending { it.createdAtEpochMillis }
        val removed = ordered.drop(keepLast)
        for (record in removed) safeArchive(record).delete()
        writeCatalog(catalog.copy(snapshots = ordered.take(keepLast)))
        return removed.size
    }

    private fun find(id: String): Snapshot {
        require(id.matches(Regex("[0-9a-f]{32}"))) { "Invalid snapshot identifier" }
        return readCatalog().snapshots.firstOrNull { it.id == id } ?: error("Snapshot not found: $id")
    }

    private fun safeArchive(snapshot: Snapshot): File {
        require(snapshot.archiveName.matches(Regex("[A-Za-z0-9._-]{1,160}\\.qzra"))) { "Unsafe snapshot archive name" }
        val file = File(root, snapshot.archiveName)
        require(file.canonicalFile.parentFile == root.canonicalFile && file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "Snapshot archive is missing or unsafe: ${snapshot.id}"
        }
        return file
    }

    private fun readCatalog(): Catalog {
        require(!Files.isSymbolicLink(catalogFile.toPath()) && catalogFile.isFile) { "Snapshot catalog is missing or unsafe" }
        require(catalogFile.length() <= 16L * 1024 * 1024) { "Snapshot catalog is too large" }
        val parsed = json.decodeFromString<Catalog>(catalogFile.readText(Charsets.UTF_8))
        require(parsed.format == "QAMZOR-SNAPSHOT-ARCHIVE" && parsed.version == 1) { "Unsupported snapshot catalog" }
        require(parsed.snapshots.size <= 100_000 && parsed.snapshots.map { it.id }.toSet().size == parsed.snapshots.size) { "Invalid snapshot catalog entries" }
        for (item in parsed.snapshots) {
            require(item.id.matches(Regex("[0-9a-f]{32}")) && item.archiveSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid snapshot record" }
            require(item.archiveBytes >= 0 && item.originalBytes >= 0 && item.files >= 0 && item.createdAtEpochMillis > 0) { "Invalid snapshot metadata" }
        }
        return parsed
    }

    private fun writeCatalog(catalog: Catalog) {
        val temporary = File.createTempFile(".qamzor-catalog-", ".tmp", root)
        try {
            temporary.writeText(json.encodeToString(catalog), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), catalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), catalogFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun digest(file: File, onProgress: ((Long) -> Unit)? = null): String {
        val value = MessageDigest.getInstance("SHA-256")
        var completed = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Snapshot digest interrupted")
                val count = input.read(buffer)
                if (count < 0) break
                value.update(buffer, 0, count)
                completed += count
                onProgress?.invoke(completed)
            }
        }
        return value.digest().joinToString("") { "%02x".format(it) }
    }
}
