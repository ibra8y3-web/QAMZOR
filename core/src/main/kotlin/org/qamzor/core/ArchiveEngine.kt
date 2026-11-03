package org.qamzor.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.SCrypt
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Kotlin QZRA engine: reads and writes QZRA 1.0-1.3 with authenticated encryption. */
object ArchiveEngine {
    private val magic = byteArrayOf(0x51, 0x5a, 0x52, 0x41, 0x0d, 0x0a, 0x1a, 0x0a)
    private const val HEADER_SIZE = 32
    private const val MAX_MANIFEST = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 1_000_000
    private const val MIN_CHUNK = 64 * 1024
    private const val MAX_CHUNK = 1024 * 1024
    private const val BOUNDARY_MASK = 0x3ffff
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable private data class Manifest(
        val format: String,
        val version: String,
        val entries: List<Entry>,
        val security: Security? = null,
        val manifest_auth: ManifestAuth? = null
    )
    @Serializable private data class Security(
        val encryption: String, val kdf: Kdf,
        val manifest_encryption: String? = null,
        val scopes: List<FolderScope> = emptyList()
    )
    @Serializable private data class Kdf(val name: String, val n: Int, val r: Int, val p: Int, val salt: String)
    @Serializable private data class ManifestAuth(val nonce: String, val tag: String)
    @Serializable private data class ScopeCheck(val nonce: String, val tag: String)
    @Serializable private data class FolderScope(
        val id: String, val path: String, val kdf: Kdf, val key_check: ScopeCheck
    )
    private data class ArchiveKeys(
        val minor: Int, val rootChunkKey: ByteArray?,
        val scopeKeys: Map<String, ByteArray> = emptyMap(),
        val scopePaths: Map<String, String> = emptyMap()
    )
    @Serializable private data class Entry(
        val path: String, val type: String, val size: Long,
        val sha256: String? = null, val chunks: List<Chunk>? = null,
        val offset: Long? = null, val compressed_size: Long? = null, val compression: String? = null
    )
    @Serializable private data class Chunk(
        val offset: Long, val compressed_size: Long, val size: Long,
        val compression: String, val sha256: String, val nonce: String? = null,
        val scope_id: String? = null
    )

    data class EntryInfo(val path: String, val type: String, val size: Long)
    data class CreateResult(val archive: File, val entries: Int, val files: Int, val originalBytes: Long, val storedBytes: Long)
    data class VerifyResult(val valid: Boolean, val filesChecked: Int, val bytesChecked: Long, val version: String)
    data class BrowseItem(val path: String, val name: String, val type: String, val size: Long)
    data class PreviewResult(val path: String, val data: ByteArray, val bytesTotal: Long, val truncated: Boolean, val verified: Boolean)
    data class CompareResult(
        val leftVersion: String, val rightVersion: String,
        val added: List<String>, val removed: List<String>,
        val changed: List<String>, val unchanged: List<String>,
        val contentVerified: Boolean
    )

    @JvmStatic
    @JvmOverloads
    fun create(
        archive: File,
        sources: List<File>,
        overwrite: Boolean = false,
        password: String? = null,
        encryptMetadata: Boolean = false,
        folderPasswords: Map<String, String> = emptyMap()
    ): CreateResult {
        require(sources.isNotEmpty()) { "Select at least one source file or folder" }
        require(password == null || password.isNotEmpty()) { "Encryption password must not be empty" }
        val useV13 = encryptMetadata || folderPasswords.isNotEmpty()
        require(!useV13 || !password.isNullOrEmpty()) { "QZRA 1.3 requires an archive master password" }
        require(folderPasswords.size <= 32) { "At most 32 independently protected folders are supported" }
        require(!archive.exists() || overwrite) { "Destination already exists: ${archive.path}" }
        archive.absoluteFile.parentFile?.mkdirs()
        val items = collectSources(sources, archive)
        require(items.isNotEmpty()) { "No source entries were found; the selected folder may be empty or inaccessible" }
        require(items.map { it.name.lowercase() }.toSet().size == items.size) { "Archive paths collide ignoring case" }
        val kinds = items.associate { it.name to if (it.directory) "directory" else "file" }
        for (item in items) {
            val parts = item.name.split('/')
            for (index in 1 until parts.size) require(kinds[parts.take(index).joinToString("/")] != "file") { "A file cannot be a parent directory" }
            if (!item.directory) require(items.none { it.name.startsWith(item.name + "/") }) { "A file entry cannot contain child entries" }
        }
        require(items.size <= MAX_ENTRIES) { "Too many archive entries" }

        val secureRandom = SecureRandom()
        val encryptionSalt = if (password != null) ByteArray(16).also(secureRandom::nextBytes) else null
        val encryptionKey = if (password != null && encryptionSalt != null) deriveScryptKey(password, encryptionSalt) else null
        val formatMinor = if (useV13) 3 else if (password != null) 2 else 1
        val rootChunkKey = if (useV13 && encryptionKey != null) deriveV13Subkey(encryptionKey, "root-chunks") else encryptionKey
        val folderScopes = LinkedHashMap<String, FolderScope>()
        val scopeKeys = LinkedHashMap<String, ByteArray>()
        if (useV13) {
            val directories = items.filter { it.directory }.map { it.name }.toSet()
            for ((folderPath, folderPassword) in folderPasswords.toSortedMap()) {
                validatePath(folderPath)
                require(folderPath in directories) { "Protected folder does not exist in selected sources: $folderPath" }
                require(folderPassword.isNotEmpty()) { "Folder password must not be empty: $folderPath" }
                val scopeId = ByteArray(16).also(secureRandom::nextBytes).toHex()
                val scopeSalt = ByteArray(16).also(secureRandom::nextBytes)
                val folderMaster = deriveScryptKey(folderPassword, scopeSalt)
                val checkKey = deriveV13Subkey(folderMaster, "folder-check")
                val payloadKey = deriveV13Subkey(folderMaster, "folder-chunks")
                val checkNonce = ByteArray(12).also(secureRandom::nextBytes)
                val checkTag = encryptAead(ByteArray(0), checkKey, checkNonce, scopeCheckAad(scopeId, folderPath))
                folderScopes[folderPath] = FolderScope(
                    scopeId, folderPath, Kdf("scrypt", 1 shl 15, 8, 1, scopeSalt.toHex()),
                    ScopeCheck(checkNonce.toHex(), checkTag.toHex())
                )
                scopeKeys[scopeId] = payloadKey
                folderMaster.fill(0); checkKey.fill(0)
            }
        }
        val spool = File.createTempFile("qamzor-payload-", ".tmp", archive.absoluteFile.parentFile)
        val index = LinkedHashMap<String, Chunk>()
        val entries = ArrayList<Entry>(items.size)
        var payloadLength = 0L
        var originalBytes = 0L
        try {
            RandomAccessFile(spool, "rw").use { payload ->
                for ((name, source, isDirectory) in items) {
                    if (isDirectory) {
                        entries += Entry(name, "directory", 0)
                        continue
                    }
                    val fileDigest = MessageDigest.getInstance("SHA-256")
                    var fileSize = 0L
                    val refs = ArrayList<Chunk>()
                    val activeFolder = folderScopes.keys.filter { name.startsWith("$it/") }.maxByOrNull { it.length }
                    val scopeId = activeFolder?.let { folderScopes.getValue(it).id }
                    val chunkKey = if (scopeId != null) scopeKeys.getValue(scopeId) else rootChunkKey
                    FileInputStream(source).use { input ->
                        forEachChunk(input) { raw ->
                            fileDigest.update(raw)
                            fileSize += raw.size
                            val digest = sha256(raw)
                            val key = "${scopeId ?: "-"}:$digest:${raw.size}"
                            val existing = index[key]
                            if (existing != null) {
                                refs += existing
                            } else {
                                val compressed = deflate(raw)
                                val method: String
                                val plainEncoded: ByteArray
                                if (compressed.size < raw.size) { method = "zlib"; plainEncoded = compressed }
                                else { method = "store"; plainEncoded = raw }
                                val nonce = if (chunkKey != null) ByteArray(12).also(secureRandom::nextBytes) else null
                                val encoded = if (chunkKey != null && nonce != null) {
                                    encryptChunk(plainEncoded, raw.size.toLong(), digest, method, chunkKey, nonce, formatMinor, scopeId)
                                } else plainEncoded
                                val block = Chunk(payloadLength, encoded.size.toLong(), raw.size.toLong(), method, digest, nonce?.toHex(), scopeId)
                                payload.seek(payloadLength)
                                payload.write(encoded)
                                payloadLength += encoded.size
                                index[key] = block
                                refs += block
                            }
                        }
                    }
                    entries += Entry(name, "file", fileSize, fileDigest.digest().toHex(), refs)
                    originalBytes += fileSize
                }
            }

            val security = if (encryptionKey != null && encryptionSalt != null) {
                Security(
                    "AES-256-GCM", Kdf("scrypt", 1 shl 15, 8, 1, encryptionSalt.toHex()),
                    if (formatMinor == 3) "AES-256-GCM" else null,
                    if (formatMinor == 3) folderScopes.toSortedMap().values.toList() else emptyList()
                )
            } else null
            val manifest = when (formatMinor) {
                3 -> Manifest("QZRA", "1.3", entries, requireNotNull(security))
                2 -> {
                    val unsigned = Manifest("QZRA", "1.2", entries, requireNotNull(security), null)
                    val unsignedElement = sortJson(json.encodeToJsonElement(unsigned))
                    val canonicalUnsigned = json.encodeToString(JsonElement.serializer(), unsignedElement).toByteArray(Charsets.UTF_8)
                    val manifestNonce = ByteArray(12).also(secureRandom::nextBytes)
                    val authTag = authenticateManifestForWrite(requireNotNull(encryptionKey), manifestNonce, canonicalUnsigned)
                    Manifest("QZRA", "1.2", entries, security, ManifestAuth(manifestNonce.toHex(), authTag.toHex()))
                }
                else -> Manifest("QZRA", "1.1", entries)
            }
            val manifestElement = json.encodeToJsonElement(manifest)
            val plainManifestBytes = if (formatMinor >= 2) {
                json.encodeToString(JsonElement.serializer(), sortJson(manifestElement)).toByteArray(Charsets.UTF_8)
            } else json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            require(plainManifestBytes.size <= MAX_MANIFEST) { "Manifest exceeds QZRA limit" }
            val manifestBytes = if (formatMinor == 3) {
                val manifestNonce = ByteArray(12).also(secureRandom::nextBytes)
                val aad = manifestAad(requireNotNull(encryptionSalt), manifestNonce, 0, 0)
                val encrypted = encryptAead(plainManifestBytes, deriveV13Subkey(requireNotNull(encryptionKey), "manifest"), manifestNonce, aad)
                "QZ13".toByteArray(Charsets.US_ASCII) + requireNotNull(encryptionSalt) + manifestNonce + encrypted
            } else plainManifestBytes
            val temporary = File.createTempFile(".${archive.name}.", ".tmp", archive.absoluteFile.parentFile)
            try {
                DataOutputStream(FileOutputStream(temporary)).use { output ->
                    output.write(magic)
                    output.writeShort(1)
                    output.writeShort(formatMinor)
                    output.writeInt(0)
                    output.writeLong(manifestBytes.size.toLong())
                    output.writeInt(if (formatMinor == 3) 0 else entries.size)
                    output.writeInt(0)
                    output.write(manifestBytes)
                    FileInputStream(spool).use { it.copyTo(output) }
                    output.flush()
                }
                if (overwrite) {
                    try {
                        Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } else {
                    Files.move(temporary.toPath(), archive.toPath())
                }
            } finally { temporary.delete() }
            return CreateResult(archive, entries.size, entries.count { it.type == "file" }, originalBytes, payloadLength)
        } finally {
            encryptionKey?.fill(0)
            if (rootChunkKey !== encryptionKey) rootChunkKey?.fill(0)
            scopeKeys.values.forEach { it.fill(0) }
            spool.delete()
        }
    }

    /**
     * Convert one or more ordinary files/directories into the native QZRA container.
     * This is lossless packaging, not a media/document codec conversion: source bytes,
     * filenames and directory structure are preserved inside the resulting .qzra file.
     * The source files are never modified or deleted.
     */
    @JvmStatic
    @JvmOverloads
    fun convertToQzra(
        destination: File,
        inputs: List<File>,
        overwrite: Boolean = false,
        password: String? = null,
        encryptMetadata: Boolean = false,
        folderPasswords: Map<String, String> = emptyMap()
    ): CreateResult {
        require(destination.extension.equals("qzra", ignoreCase = true)) {
            "QZRA conversion destination must use the .qzra extension"
        }
        return create(destination, inputs, overwrite, password, encryptMetadata, folderPasswords)
    }

    @JvmStatic
    @JvmOverloads
    fun list(archive: File, password: String? = null, folderPasswords: Map<String, String> = emptyMap()): List<EntryInfo> = readManifest(archive, password, folderPasswords).second.entries.map { EntryInfo(it.path, it.type, it.size) }

    /** List only the immediate children of a virtual directory inside the archive.
     * No payload data is expanded or written to disk. Implicit parent directories
     * are synthesized for archives that contain a child path without an explicit
     * directory record.
     */
    @JvmStatic
    @JvmOverloads
    fun browse(archive: File, directoryPath: String = "", password: String? = null, folderPasswords: Map<String, String> = emptyMap()): List<BrowseItem> {
        val (_, manifest, _, _) = readManifest(archive, password, folderPasswords)
        val parent = directoryPath.trim('/')
        if (parent.isNotEmpty()) validatePath(parent)
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        val children = LinkedHashMap<String, BrowseItem>()
        for (entry in manifest.entries) {
            if (!entry.path.startsWith(prefix)) continue
            val remainder = entry.path.removePrefix(prefix)
            if (remainder.isEmpty()) continue
            val slash = remainder.indexOf('/')
            if (slash >= 0) {
                val childName = remainder.substring(0, slash)
                val childPath = prefix + childName
                val existing = children[childPath]
                if (existing == null || existing.type != "directory") {
                    children[childPath] = BrowseItem(childPath, childName, "directory", 0)
                }
            } else {
                children[entry.path] = BrowseItem(entry.path, remainder, entry.type, entry.size)
            }
        }
        return children.values.sortedWith(compareBy<BrowseItem> { if (it.type == "directory") 0 else 1 }.thenBy { it.name.lowercase() }.thenBy { it.name })
    }

    /** Decode and authenticate one file in-place from the archive payload.
     * Only maxBytes are retained for preview; every chunk and the whole-file
     * SHA-256 are still checked, so a successful result means the complete file
     * was read and verified even when the returned preview is truncated.
     */
    @JvmStatic
    @JvmOverloads
    fun preview(archive: File, path: String, maxBytes: Int = 2 * 1024 * 1024, password: String? = null, folderPasswords: Map<String, String> = emptyMap()): PreviewResult {
        require(maxBytes in 0..16 * 1024 * 1024) { "Preview limit must be between 0 and 16 MiB" }
        validatePath(path)
        val context = readManifest(archive, password, folderPasswords)
        val (_, manifest, payloadStart, fileLength) = context
        val entry = manifest.entries.firstOrNull { it.path == path } ?: error("Archive entry does not exist: $path")
        require(entry.type == "file") { "A directory cannot be previewed as a file" }
        val retained = ByteArrayOutputStream(minOf(maxBytes, entry.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        val total = RandomAccessFile(archive, "r").use { input ->
            decodeEntry(entry, input, payloadStart, fileLength, null, retained, maxBytes, context.fifth)
        }
        return PreviewResult(path, retained.toByteArray(), total, total > retained.size().toLong(), true)
    }

    /** Extract selected file(s) and/or directory subtrees without unpacking the
     * rest of the archive. Paths are kept relative to the extraction root.
     */
    @JvmStatic
    @JvmOverloads
    fun extractSelected(
        archive: File,
        destination: File,
        selectedPaths: Set<String>,
        overwrite: Boolean = false,
        maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
        maxFiles: Int = 100_000,
        password: String? = null,
        folderPasswords: Map<String, String> = emptyMap()
    ): List<File> {
        require(selectedPaths.isNotEmpty()) { "Select at least one archive item" }
        require(maxTotalBytes >= 0 && maxFiles >= 0) { "Extraction limits must be non-negative" }
        val context = readManifest(archive, password, folderPasswords)
        val (_, manifest, payloadStart, fileLength) = context
        val byPath = manifest.entries.associateBy { it.path }
        val chosen = LinkedHashMap<String, Entry>()
        for (requested in selectedPaths) {
            validatePath(requested)
            val exact = byPath[requested]
            val hasChildren = manifest.entries.any { it.path.startsWith("$requested/") }
            require(exact != null || hasChildren) { "Archive item does not exist: $requested" }
            if (exact != null) chosen[exact.path] = exact
            if (exact?.type == "directory" || (exact == null && hasChildren)) {
                for (entry in manifest.entries) if (entry.path.startsWith("$requested/")) chosen[entry.path] = entry
            }
        }
        val ordered = chosen.values.sortedWith(compareBy<Entry> { it.path.count { ch -> ch == '/' } }.thenBy { it.path })
        val files = ordered.filter { it.type == "file" }
        val totalBytes = files.sumOf { it.size }
        require(files.size <= maxFiles) { "Selected extraction exceeds file-count limit ($maxFiles)" }
        require(totalBytes <= maxTotalBytes) { "Selected extraction exceeds byte limit ($maxTotalBytes)" }

        if (destination.exists()) require(!Files.isSymbolicLink(destination.toPath()) && destination.isDirectory) { "Extraction destination must be a regular directory" }
        require(destination.mkdirs() || destination.isDirectory) { "Cannot create extraction destination" }
        val root = destination.canonicalFile
        val outputs = ArrayList<File>()
        RandomAccessFile(archive, "r").use { input ->
            for (entry in ordered) {
                validatePath(entry.path)
                val target = File(root, entry.path)
                require(target.canonicalFile.toPath().startsWith(root.toPath())) { "Unsafe extraction path: ${entry.path}" }
                ensureSafeParents(root, target.parentFile ?: root)
                if (entry.type == "directory") {
                    if (target.exists()) require(!Files.isSymbolicLink(target.toPath()) && target.isDirectory) { "Extraction path conflicts with a non-directory: ${entry.path}" }
                    else require(target.mkdir()) { "Cannot create directory: ${entry.path}" }
                    outputs += target
                    continue
                }
                require(!Files.isSymbolicLink(target.toPath())) { "Refusing to write through a symbolic link: ${entry.path}" }
                if (target.exists()) require(overwrite && target.isFile) { "Refusing to overwrite existing item: ${entry.path}" }
                val temp = File.createTempFile(".qamzor-", ".part", target.parentFile)
                try {
                    FileOutputStream(temp).use { output -> decodeEntry(entry, input, payloadStart, fileLength, output, null, 0, context.fifth) }
                    if (overwrite) {
                        try {
                            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } else {
                        Files.move(temp.toPath(), target.toPath())
                    }
                    outputs += target
                } finally { temp.delete() }
            }
        }
        return outputs
    }

    private fun ensureSafeParents(root: File, parent: File) {
        val relative = root.toPath().relativize(parent.canonicalFile.toPath())
        var current = root
        for (part in relative) {
            current = File(current, part.toString())
            if (current.exists() || Files.isSymbolicLink(current.toPath())) {
                require(!Files.isSymbolicLink(current.toPath()) && current.isDirectory) { "Unsafe extraction parent: $current" }
            } else require(current.mkdir()) { "Cannot create extraction parent: $current" }
            require(current.canonicalFile.toPath().startsWith(root.toPath())) { "Extraction parent escapes destination" }
        }
    }

    private fun decodeEntry(
        entry: Entry,
        input: RandomAccessFile,
        payloadStart: Long,
        fileLength: Long,
        output: OutputStream?,
        retained: ByteArrayOutputStream?,
        retainLimit: Int,
        archiveKeys: ArchiveKeys?
    ): Long {
        require(entry.type == "file") { "Only file entries can be decoded" }
        if (archiveKeys?.minor == 3) {
            val protectedScope = archiveKeys.scopePaths.entries
                .filter { entry.path.startsWith(it.value + "/") }
                .maxByOrNull { it.value.length }
            if (protectedScope != null && protectedScope.key !in archiveKeys.scopeKeys) {
                error("Folder is locked; unlock it with its independent password: ${protectedScope.value}")
            }
        }
        val wholeDigest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val blocks = entry.chunks ?: if (entry.size == 0L) emptyList() else listOf(
            Chunk(entry.offset ?: error("Missing legacy offset"), entry.compressed_size ?: error("Missing legacy compressed size"), entry.size,
                entry.compression ?: error("Missing legacy compression"), entry.sha256 ?: error("Missing file digest"))
        )
        for (block in blocks) {
            require(block.offset >= 0 && block.compressed_size > 0 && block.size > 0) { "Invalid chunk bounds" }
            require(block.compressed_size <= Int.MAX_VALUE && payloadStart + block.offset <= fileLength && block.compressed_size <= fileLength - (payloadStart + block.offset)) { "Chunk extends beyond archive" }
            input.seek(payloadStart + block.offset)
            val encoded = ByteArray(block.compressed_size.toInt())
            input.readFully(encoded)
            val compressed = if (block.nonce != null) {
                val keys = archiveKeys ?: error("This QZRA archive is encrypted; password is required")
                val key = if (keys.minor == 3 && block.scope_id != null) {
                    keys.scopeKeys[block.scope_id] ?: error("Folder is locked; unlock it with its independent password: ${keys.scopePaths[block.scope_id] ?: block.scope_id}")
                } else requireNotNull(keys.rootChunkKey)
                decryptChunk(encoded, block, key, keys.minor)
            } else {
                require(archiveKeys == null) { "Encrypted archive contains an unauthenticated chunk" }
                encoded
            }
            val raw = when (block.compression) {
                "store" -> compressed.also { require(it.size.toLong() == block.size) { "Stored block size mismatch" } }
                "zlib" -> InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytesBounded(block.size) }
                else -> error("Unsupported compression: ${block.compression}")
            }
            require(sha256(raw) == block.sha256) { "Chunk integrity check failed" }
            wholeDigest.update(raw)
            output?.write(raw)
            if (retained != null && retained.size() < retainLimit) {
                val count = minOf(raw.size, retainLimit - retained.size())
                if (count > 0) retained.write(raw, 0, count)
            }
            total += raw.size
            require(total <= entry.size) { "Expanded file exceeds declared size" }
        }
        require(total == entry.size && wholeDigest.digest().toHex() == entry.sha256) { "File integrity check failed: ${entry.path}" }
        return total
    }

    @JvmStatic
    @JvmOverloads
    fun verify(archive: File, password: String? = null, folderPasswords: Map<String, String> = emptyMap()): VerifyResult {
        val context = readManifest(archive, password, folderPasswords)
        val (version, manifest, payloadStart, fileLength) = context
        var files = 0
        var bytes = 0L
        RandomAccessFile(archive, "r").use { input ->
            for (entry in manifest.entries) {
                validatePath(entry.path)
                if (entry.type == "directory") continue
                bytes += decodeEntry(entry, input, payloadStart, fileLength, null, null, 0, context.fifth)
                files++
            }
        }
        return VerifyResult(true, files, bytes, version)
    }

    /**
     * Compare two archives by verified SHA-256 content fingerprints. Each archive
     * is fully decoded and verified before its manifest digests are compared;
     * matching names and sizes alone are never treated as content equality.
     * Supports verified content comparison for QZRA 1.0-1.3 archives.
     */
    @JvmStatic
    fun compareContent(
        leftArchive: File, rightArchive: File,
        leftPassword: String? = null, rightPassword: String? = null,
        leftFolderPasswords: Map<String, String> = emptyMap(), rightFolderPasswords: Map<String, String> = emptyMap()
    ): CompareResult {
        val leftVerification = verify(leftArchive, leftPassword, leftFolderPasswords)
        val rightVerification = verify(rightArchive, rightPassword, rightFolderPasswords)
        require(leftVerification.valid && rightVerification.valid) { "An archive failed content verification" }
        val leftContext = readManifest(leftArchive, leftPassword, leftFolderPasswords)
        val rightContext = readManifest(rightArchive, rightPassword, rightFolderPasswords)
        // Directory records describe the archive tree, not file content. Exclude
        // them from the content delta so an unchanged parent directory is not
        // reported as an unchanged payload item. Empty-directory changes do not
        // affect the file-content comparison result.
        val leftEntries = leftContext.second.entries.filter { it.type == "file" }.associateBy { it.path }
        val rightEntries = rightContext.second.entries.filter { it.type == "file" }.associateBy { it.path }
        val added = (rightEntries.keys - leftEntries.keys).sorted()
        val removed = (leftEntries.keys - rightEntries.keys).sorted()
        val changed = ArrayList<String>()
        val unchanged = ArrayList<String>()
        for (path in (leftEntries.keys intersect rightEntries.keys).sorted()) {
            val before = leftEntries.getValue(path)
            val after = rightEntries.getValue(path)
            if (before.type != after.type || before.size != after.size || before.sha256 != after.sha256) changed += path
            else unchanged += path
        }
        return CompareResult(leftContext.first, rightContext.first, added, removed, changed, unchanged, true)
    }

    @JvmStatic
    @JvmOverloads
    fun extract(archive: File, destination: File, overwrite: Boolean = false, password: String? = null, folderPasswords: Map<String, String> = emptyMap()): List<File> {
        val allPaths = list(archive, password, folderPasswords).map { it.path }.toSet()
        return extractSelected(archive, destination, allPaths, overwrite, password = password, folderPasswords = folderPasswords).filter { it.isFile }
    }

    private data class SourceItem(val name: String, val file: File, val directory: Boolean)

    private fun collectSources(sources: List<File>, archive: File): List<SourceItem> {
        val result = ArrayList<SourceItem>()
        val archivePath = archive.canonicalFile.toPath()
        for (sourceValue in sources) {
            require(Files.exists(sourceValue.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Source does not exist: $sourceValue" }
            require(!Files.isSymbolicLink(sourceValue.toPath())) { "Symbolic links are not supported" }
            val source = sourceValue.canonicalFile
            require(source.toPath() != archivePath) { "Archive cannot be its own source" }
            val base = source.name.ifBlank { error("Cannot archive filesystem root") }
            if (source.isFile) {
                validatePath(base)
                result += SourceItem(base, source, false)
            } else {
                require(source.isDirectory) { "Only regular files and directories are supported" }
                validatePath(base)
                result += SourceItem(base, source, true)
                Files.walk(source.toPath()).use { paths ->
                    paths.filter { it != source.toPath() }.sorted().forEach { path ->
                        require(!Files.isSymbolicLink(path)) { "Symbolic links are not supported: $path" }
                        if (path.toRealPath() == archivePath) return@forEach
                        val relative = source.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                        val name = "$base/$relative"
                        validatePath(name)
                        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) result += SourceItem(name, path.toFile(), true)
                        else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) result += SourceItem(name, path.toFile(), false)
                        else error("Unsupported filesystem entry: $path")
                    }
                }
            }
        }
        return result.sortedBy { it.name }
    }

    private fun readManifest(
        archive: File, password: String? = null, folderPasswords: Map<String, String> = emptyMap()
    ): Quadruple<String, Manifest, Long, Long, ArchiveKeys?> {
        RandomAccessFile(archive, "r").use { input ->
            require(input.length() >= HEADER_SIZE) { "File is too small to be QZRA" }
            val signature = ByteArray(8); input.readFully(signature)
            require(signature.contentEquals(magic)) { "Invalid QZRA signature" }
            val major = input.readUnsignedShort(); val minor = input.readUnsignedShort()
            val flags = input.readInt(); val manifestLength = input.readLong()
            val entryCount = input.readInt(); val reserved = input.readInt()
            require(major == 1 && minor in 0..3) { "Unsupported QZRA version $major.$minor" }
            require(flags == 0 && reserved == 0) { "Unsupported QZRA header flags/reserved field" }
            val maxManifest = MAX_MANIFEST.toLong() + if (minor == 3) 44L else 0L
            require(manifestLength in 2..maxManifest && entryCount in 0..MAX_ENTRIES) { "Invalid manifest bounds" }
            require(HEADER_SIZE + manifestLength <= input.length()) { "Manifest extends beyond file" }
            val raw = ByteArray(manifestLength.toInt()); input.seek(HEADER_SIZE.toLong()); input.readFully(raw)
            var manifestRaw = raw
            var rootKey: ByteArray? = null
            if (minor == 3) {
                require(entryCount == 0) { "QZRA 1.3 must hide the manifest entry count" }
                require(raw.size >= 4 + 16 + 12 + 16 && raw.copyOfRange(0, 4).contentEquals("QZ13".toByteArray(Charsets.US_ASCII))) { "Invalid QZRA 1.3 encrypted manifest envelope" }
                val salt = raw.copyOfRange(4, 20); val nonce = raw.copyOfRange(20, 32)
                val supplied = password ?: throw IllegalArgumentException("QZRA 1.3 hides filenames and metadata; enter the archive master password")
                val masterKey = deriveScryptKey(supplied, salt)
                rootKey = deriveV13Subkey(masterKey, "root-chunks")
                val manifestKey = deriveV13Subkey(masterKey, "manifest")
                try {
                    manifestRaw = decryptAead(raw.copyOfRange(32, raw.size), manifestKey, nonce, manifestAad(salt, nonce, flags, reserved))
                } finally { masterKey.fill(0); manifestKey.fill(0) }
                require(manifestRaw.size <= MAX_MANIFEST) { "Decrypted QZRA 1.3 manifest exceeds the size limit" }
            }
            val rawText = manifestRaw.toString(Charsets.UTF_8)
            val manifest = json.decodeFromString<Manifest>(rawText)
            require(manifest.format == "QZRA" && manifest.version == "$major.$minor") { "Manifest/header version mismatch" }
            if (minor != 3) require(manifest.entries.size == entryCount) { "Manifest/header entry count mismatch" }
            val scopeKeys = LinkedHashMap<String, ByteArray>()
            val scopePaths = LinkedHashMap<String, String>()
            val keys: ArchiveKeys? = when (minor) {
                2 -> {
                    val security = manifest.security ?: error("QZRA 1.2 security metadata is missing")
                    require(security.encryption == "AES-256-GCM") { "Unsupported QZRA encryption algorithm" }
                    val kdf = security.kdf
                    require(kdf.name == "scrypt" && kdf.n == 32768 && kdf.r == 8 && kdf.p == 1 && kdf.salt.matches(Regex("[0-9a-f]{32}"))) { "Unsupported QZRA key-derivation parameters" }
                    val auth = manifest.manifest_auth ?: error("QZRA manifest authentication metadata is missing")
                    require(auth.nonce.matches(Regex("[0-9a-f]{24}")) && auth.tag.matches(Regex("[0-9a-f]{32}"))) { "Malformed QZRA manifest authenticator" }
                    val key = deriveScryptKey(password ?: throw IllegalArgumentException("This archive is encrypted. Enter its password to browse it."), hexToBytes(kdf.salt))
                    authenticateManifest(key, hexToBytes(auth.nonce), hexToBytes(auth.tag), rawText)
                    ArchiveKeys(2, key)
                }
                3 -> {
                    val security = manifest.security ?: error("QZRA 1.3 security metadata is missing")
                    require(security.encryption == "AES-256-GCM" && security.manifest_encryption == "AES-256-GCM") { "Unsupported QZRA 1.3 encryption configuration" }
                    val kdf = security.kdf
                    val envelopeSalt = raw.copyOfRange(4, 20).toHex()
                    require(kdf.name == "scrypt" && kdf.n == 32768 && kdf.r == 8 && kdf.p == 1 && kdf.salt == envelopeSalt) { "Invalid QZRA 1.3 KDF metadata" }
                    require(security.scopes.size <= 32) { "Too many QZRA 1.3 protected folders" }
                    val ids = HashSet<String>(); val paths = HashSet<String>()
                    for (scope in security.scopes) {
                        validatePath(scope.path)
                        require(scope.id.matches(Regex("[0-9a-f]{32}")) && ids.add(scope.id) && paths.add(scope.path)) { "Duplicate or malformed QZRA 1.3 scope" }
                        require(scope.kdf.name == "scrypt" && scope.kdf.n == 32768 && scope.kdf.r == 8 && scope.kdf.p == 1 && scope.kdf.salt.matches(Regex("[0-9a-f]{32}"))) { "Invalid folder KDF parameters" }
                        require(scope.key_check.nonce.matches(Regex("[0-9a-f]{24}")) && scope.key_check.tag.matches(Regex("[0-9a-f]{32}"))) { "Invalid folder key-check record" }
                        scopePaths[scope.id] = scope.path
                    }
                    for ((path, folderPassword) in folderPasswords) {
                        val scope = security.scopes.firstOrNull { it.path == path } ?: error("No protected folder exists at: $path")
                        val folderMaster = deriveScryptKey(folderPassword, hexToBytes(scope.kdf.salt))
                        val checkKey = deriveV13Subkey(folderMaster, "folder-check")
                        val payloadKey = deriveV13Subkey(folderMaster, "folder-chunks")
                        try {
                            decryptAead(hexToBytes(scope.key_check.tag), checkKey, hexToBytes(scope.key_check.nonce), scopeCheckAad(scope.id, scope.path))
                            scopeKeys[scope.id] = payloadKey
                        } catch (error: Exception) {
                            payloadKey.fill(0)
                            throw IllegalArgumentException("Wrong password for protected folder: $path", error)
                        } finally { folderMaster.fill(0); checkKey.fill(0) }
                    }
                    ArchiveKeys(3, rootKey, scopeKeys, scopePaths)
                }
                else -> {
                    require(manifest.security == null && manifest.manifest_auth == null) { "Unencrypted QZRA archives cannot contain encryption metadata" }
                    null
                }
            }
            val payloadStart = HEADER_SIZE + manifestLength
            validateManifest(manifest, minor, input.length() - payloadStart)
            return Quadruple(manifest.version, manifest, payloadStart, input.length(), keys)
        }
    }

    private data class Quadruple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    private fun validateManifest(manifest: Manifest, minor: Int, payloadLength: Long) {
        require(manifest.entries.size <= MAX_ENTRIES) { "Too many manifest entries" }
        val exact = HashSet<String>()
        val portable = HashSet<String>()
        val kinds = HashMap<String, String>()
        val physical = LinkedHashMap<String, Chunk>()
        for (entry in manifest.entries) {
            validatePath(entry.path)
            require(exact.add(entry.path)) { "Duplicate archive path: ${entry.path}" }
            require(portable.add(entry.path.lowercase())) { "Case-insensitive path collision: ${entry.path}" }
            require(entry.size >= 0) { "Negative file size" }
            require(entry.type == "file" || entry.type == "directory") { "Unsupported entry type" }
            kinds[entry.path] = entry.type
            if (entry.type == "directory") {
                require(entry.size == 0L && entry.chunks == null) { "Invalid directory entry" }
            } else {
                require(entry.sha256?.matches(Regex("[0-9a-f]{64}")) == true) { "Missing or invalid file digest" }
                if (minor == 0) {
                    require(entry.chunks == null) { "QZRA 1.0 entries cannot contain chunks" }
                    val legacyOffset = entry.offset ?: error("Missing legacy offset")
                    val legacySize = entry.compressed_size ?: error("Missing legacy compressed size")
                    val legacyMethod = entry.compression ?: error("Missing legacy compression")
                    if (entry.size == 0L) {
                        require(legacySize == 0L && legacyMethod == "store" && legacyOffset <= payloadLength) { "Invalid empty legacy file" }
                    } else {
                        val legacy = Chunk(legacyOffset, legacySize, entry.size, legacyMethod, entry.sha256!!)
                        require(minor < 2) { "Legacy blocks cannot be used in encrypted QZRA 1.2" }
                        validateBlock(legacy, payloadLength, false)
                        val range = "${legacy.offset}:${legacy.compressed_size}"
                        val previous = physical[range]
                        require(previous == null || previous == legacy) { "Inconsistent shared legacy block reference" }
                        physical[range] = legacy
                    }
                } else {
                    val chunks = entry.chunks ?: error("Chunked QZRA entries must contain chunks")
                    require((entry.size == 0L && chunks.isEmpty()) || (entry.size > 0L && chunks.isNotEmpty())) { "Invalid empty/non-empty chunk list" }
                    require(chunks.sumOf { it.size } == entry.size) { "Chunk sizes do not match file size" }
                    for (block in chunks) {
                        if (minor >= 2) require(block.nonce?.matches(Regex("[0-9a-f]{24}")) == true) { "Encrypted QZRA chunk is missing a valid AES-GCM nonce" }
                        else require(block.nonce == null && block.scope_id == null) { "AES-GCM metadata requires QZRA 1.2+" }
                        if (minor == 3) {
                            if (block.scope_id != null) require(block.scope_id.matches(Regex("[0-9a-f]{32}"))) { "Malformed QZRA 1.3 chunk scope" }
                        } else require(block.scope_id == null) { "Folder-scoped chunks require QZRA 1.3" }
                        validateBlock(block, payloadLength, true)
                        val range = "${block.offset}:${block.compressed_size}"
                        val previous = physical[range]
                        require(previous == null || previous == block) { "Inconsistent shared chunk reference" }
                        physical[range] = block
                    }
                }
            }
        }
        for (entry in manifest.entries) {
            val parts = entry.path.split('/')
            for (index in 1 until parts.size) require(kinds[parts.take(index).joinToString("/")] != "file") { "File entry is also a parent directory" }
            if (entry.type == "file") require(exact.none { it.startsWith(entry.path + "/") }) { "File entry has child paths" }
        }
        if (minor == 3) {
            val scopes = manifest.security?.scopes ?: error("QZRA 1.3 folder scopes are missing")
            val directoryPaths = manifest.entries.filter { it.type == "directory" }.map { it.path }.toSet()
            val scopeById = scopes.associateBy { it.id }
            for (scope in scopes) require(scope.path in directoryPaths) { "Protected folder is not declared as a directory: ${scope.path}" }
            for (entry in manifest.entries.filter { it.type == "file" }) {
                val expectedScope = scopes.filter { entry.path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }?.id
                for (block in entry.chunks.orEmpty()) require(block.scope_id == expectedScope) { "QZRA 1.3 chunk scope does not match its protected folder: ${entry.path}" }
            }
            for (block in physical.values) require(block.scope_id == null || block.scope_id in scopeById) { "Chunk references an unknown protected-folder scope" }
        }
        var cursor = 0L
        for (block in physical.values.sortedBy { it.offset }) {
            require(block.offset == cursor) { "Payload contains a gap or overlapping block" }
            cursor += block.compressed_size
        }
        require(cursor == payloadLength) { "Payload has unreferenced trailing bytes" }
    }

    private fun validateBlock(block: Chunk, payloadLength: Long, chunked: Boolean) {
        require(block.offset >= 0 && block.compressed_size > 0 && block.size > 0) { "Invalid block bounds" }
        require(block.compression == "zlib" || block.compression == "store") { "Unsupported compression method" }
        require(block.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid block digest" }
        val maximumOriginal = if (chunked) MAX_CHUNK.toLong() else 16L * 1024 * 1024
        require(block.size <= maximumOriginal && block.compressed_size <= maximumOriginal + 65536) { "Block exceeds Kotlin client memory safety limit" }
        if (block.compression == "store") {
            val expectedStoredSize = block.size + (if (block.nonce != null) 16L else 0L)
            require(block.compressed_size == expectedStoredSize) { "Stored block size mismatch" }
        }
        require(block.offset <= payloadLength && block.compressed_size <= payloadLength - block.offset) { "Block extends beyond payload" }
    }

    private fun deriveScryptKey(password: String, salt: ByteArray): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        return SCrypt.generate(password.toByteArray(Charsets.UTF_8), salt, 1 shl 15, 8, 1, 32)
    }

    private fun authenticateManifest(key: ByteArray, nonce: ByteArray, tag: ByteArray, rawManifest: String) {
        val root = json.parseToJsonElement(rawManifest).jsonObject
        val unsigned = JsonObject(root.filterKeys { it != "manifest_auth" })
        val canonical = json.encodeToString(JsonElement.serializer(), sortJson(unsigned)).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(canonical)
            val empty = cipher.doFinal(tag)
            require(empty.isEmpty()) { "Invalid manifest authentication payload" }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong password or QZRA manifest authentication failed")
        }
    }

    private fun sortJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortJson(it.value) })
        is JsonArray -> JsonArray(element.map { sortJson(it) })
        else -> element
    }

    private fun authenticateManifestForWrite(key: ByteArray, nonce: ByteArray, canonicalManifest: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(canonicalManifest)
        // AES-GCM over an empty plaintext produces only the 16-byte authentication tag.
        return cipher.doFinal(ByteArray(0))
    }

    private fun deriveV13Subkey(masterKey: ByteArray, purpose: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        return mac.doFinal("QZRA-1.3-SUBKEY\u0000$purpose".toByteArray(Charsets.US_ASCII))
    }

    private fun manifestAad(salt: ByteArray, nonce: ByteArray, flags: Int, reserved: Int): ByteArray {
        val prefix = "QZRA-1.3-MANIFEST\u0000".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(prefix.size + 2 + 2 + 4 + 4 + 16 + 12)
            .put(prefix).putShort(1.toShort()).putShort(3.toShort()).putInt(flags).putInt(reserved).put(salt).put(nonce).array()
    }

    private fun scopeCheckAad(scopeId: String, path: String): ByteArray =
        "QZRA-1.3-SCOPE-CHECK\u0000".toByteArray(Charsets.US_ASCII) + hexToBytes(scopeId) + path.toByteArray(Charsets.UTF_8)

    private fun encryptAead(plain: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun decryptAead(cipherText: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(cipherText)
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("Wrong password or QZRA authentication failed", error)
        }
    }

    private fun encryptChunk(
        compressed: ByteArray, originalSize: Long, digest: String, compression: String,
        key: ByteArray, nonce: ByteArray, minor: Int, scopeId: String?
    ): ByteArray {
        val aad = if (minor == 3) chunkAadV13(originalSize, digest, compression, scopeId)
        else chunkAadV12(originalSize, digest, compression)
        return encryptAead(compressed, key, nonce, aad)
    }

    private fun decryptChunk(encoded: ByteArray, block: Chunk, key: ByteArray, minor: Int): ByteArray {
        val nonce = block.nonce ?: error("Encrypted QZRA chunk is missing nonce")
        val aad = if (minor == 3) chunkAadV13(block.size, block.sha256, block.compression, block.scope_id)
        else chunkAadV12(block.size, block.sha256, block.compression)
        return decryptAead(encoded, key, hexToBytes(nonce), aad)
    }

    private fun chunkAadV12(size: Long, digest: String, compression: String): ByteArray {
        val prefix = "QZRA-1.2-CHUNK\u0000".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(prefix.size + 8 + 32 + compression.length)
            .put(prefix).putLong(size).put(hexToBytes(digest)).put(compression.toByteArray(Charsets.US_ASCII)).array()
    }

    private fun chunkAadV13(size: Long, digest: String, compression: String, scopeId: String?): ByteArray {
        val prefix = "QZRA-1.3-CHUNK\u0000".toByteArray(Charsets.US_ASCII)
        val scope = (scopeId ?: "-").toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(prefix.size + 8 + 32 + compression.length + 1 + scope.size)
            .put(prefix).putLong(size).put(hexToBytes(digest)).put(compression.toByteArray(Charsets.US_ASCII)).put(0.toByte()).put(scope).array()
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]*"))) { "Invalid hexadecimal data" }
        return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun forEachChunk(input: FileInputStream, action: (ByteArray) -> Unit) {
        val readBuffer = ByteArray(64 * 1024)
        val chunk = ByteArrayOutputStream(MAX_CHUNK)
        var gear = 0L
        while (true) {
            val count = input.read(readBuffer)
            if (count < 0) break
            for (index in 0 until count) {
                val value = readBuffer[index].toInt() and 0xff
                gear = ((gear shl 1) + gearValue(value)) and 0xffffffffL
                chunk.write(value)
                val length = chunk.size()
                if (length >= MIN_CHUNK && ((gear and BOUNDARY_MASK.toLong()) == 0L || length >= MAX_CHUNK)) {
                    action(chunk.toByteArray())
                    chunk.reset(); gear = 0
                }
            }
        }
        if (chunk.size() > 0) action(chunk.toByteArray())
    }

    private fun gearValue(byte: Int): Long = (((byte.toLong() * 0x9E3779B1L) xor (byte.toLong() shl 16) xor 0xA5A5A5A5L) and 0xffffffffL)

    private fun deflate(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val deflater = Deflater(6, false)
        try { DeflaterOutputStream(output, deflater).use { it.write(data) } }
        finally { deflater.end() }
        return output.toByteArray()
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && '\u0000' !in path) { "Unsafe archive path: $path" }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe archive path: $path" }
        require(path == java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC)) { "Archive path must use Unicode NFC" }
        val devices = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
        require(path.split('/').none { it.substringBefore('.').uppercase() in devices }) { "Reserved device path: $path" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun java.io.InputStream.readBytesBounded(expected: Long): ByteArray {
        require(expected in 0..(16L * 1024 * 1024)) { "Block exceeds decompression limit" }
        val output = ByteArrayOutputStream(expected.toInt())
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = read(buffer); if (count < 0) break
            total += count
            require(total <= expected) { "Decompressed chunk exceeds declared size" }
            output.write(buffer, 0, count)
        }
        require(total == expected) { "Decompressed chunk size mismatch" }
        return output.toByteArray()
    }
}
