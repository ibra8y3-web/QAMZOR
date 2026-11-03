package org.qamzor.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Kotlin reference client for QZRA 1.1. It reads legacy 1.0 and writes chunked 1.1.
 * Encrypted 1.2 is rejected explicitly until the audited cross-platform crypto adapter is integrated.
 */
object ArchiveEngine {
    private val magic = byteArrayOf(0x51, 0x5a, 0x52, 0x41, 0x0d, 0x0a, 0x1a, 0x0a)
    private const val HEADER_SIZE = 32
    private const val MAX_MANIFEST = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 1_000_000
    private const val MIN_CHUNK = 64 * 1024
    private const val MAX_CHUNK = 1024 * 1024
    private const val BOUNDARY_MASK = 0x3ffff
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Serializable private data class Manifest(val format: String, val version: String, val entries: List<Entry>)
    @Serializable private data class Entry(
        val path: String, val type: String, val size: Long,
        val sha256: String? = null, val chunks: List<Chunk>? = null,
        val offset: Long? = null, val compressed_size: Long? = null, val compression: String? = null
    )
    @Serializable private data class Chunk(
        val offset: Long, val compressed_size: Long, val size: Long,
        val compression: String, val sha256: String
    )

    data class EntryInfo(val path: String, val type: String, val size: Long)
    data class CreateResult(val archive: File, val entries: Int, val files: Int, val originalBytes: Long, val storedBytes: Long)
    data class VerifyResult(val valid: Boolean, val filesChecked: Int, val bytesChecked: Long, val version: String)

    @JvmStatic
    @JvmOverloads
    fun create(archive: File, sources: List<File>, overwrite: Boolean = false): CreateResult {
        require(sources.isNotEmpty()) { "Select at least one source file or folder" }
        require(!archive.exists() || overwrite) { "Destination already exists: ${archive.path}" }
        archive.absoluteFile.parentFile?.mkdirs()
        val items = collectSources(sources, archive)
        require(items.isNotEmpty()) { "No supported files were found" }
        require(items.map { it.name.lowercase() }.toSet().size == items.size) { "Archive paths collide ignoring case" }
        val kinds = items.associate { it.name to if (it.directory) "directory" else "file" }
        for (item in items) {
            val parts = item.name.split('/')
            for (index in 1 until parts.size) require(kinds[parts.take(index).joinToString("/")] != "file") { "A file cannot be a parent directory" }
            if (!item.directory) require(items.none { it.name.startsWith(item.name + "/") }) { "A file entry cannot contain child entries" }
        }
        require(items.size <= MAX_ENTRIES) { "Too many archive entries" }

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
                    FileInputStream(source).use { input ->
                        forEachChunk(input) { raw ->
                            fileDigest.update(raw)
                            fileSize += raw.size
                            val digest = sha256(raw)
                            val key = "$digest:${raw.size}"
                            val existing = index[key]
                            if (existing != null) {
                                refs += existing
                            } else {
                                val compressed = deflate(raw)
                                val method: String
                                val bytes: ByteArray
                                if (compressed.size < raw.size) { method = "zlib"; bytes = compressed }
                                else { method = "store"; bytes = raw }
                                val block = Chunk(payloadLength, bytes.size.toLong(), raw.size.toLong(), method, digest)
                                payload.seek(payloadLength)
                                payload.write(bytes)
                                payloadLength += bytes.size
                                index[key] = block
                                refs += block
                            }
                        }
                    }
                    entries += Entry(name, "file", fileSize, fileDigest.digest().toHex(), refs)
                    originalBytes += fileSize
                }
            }
            val manifestBytes = json.encodeToString(Manifest("QZRA", "1.1", entries)).toByteArray(Charsets.UTF_8)
            require(manifestBytes.size <= MAX_MANIFEST) { "Manifest exceeds QZRA limit" }
            val temporary = File.createTempFile(".${archive.name}.", ".tmp", archive.absoluteFile.parentFile)
            try {
                DataOutputStream(FileOutputStream(temporary)).use { output ->
                    output.write(magic)
                    output.writeShort(1)
                    output.writeShort(1)
                    output.writeInt(0)
                    output.writeLong(manifestBytes.size.toLong())
                    output.writeInt(entries.size)
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
        } finally { spool.delete() }
    }

    @JvmStatic
    fun list(archive: File): List<EntryInfo> = readManifest(archive).second.entries.map { EntryInfo(it.path, it.type, it.size) }

    @JvmStatic
    fun verify(archive: File): VerifyResult {
        val (version, manifest, payloadStart, fileLength) = readManifest(archive)
        var files = 0
        var bytes = 0L
        RandomAccessFile(archive, "r").use { input ->
            for (entry in manifest.entries) {
                validatePath(entry.path)
                if (entry.type == "directory") continue
                require(entry.type == "file") { "Unsupported entry type" }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val blocks = entry.chunks ?: if (entry.size == 0L) emptyList() else listOf(
                    Chunk(entry.offset ?: error("Missing legacy offset"), entry.compressed_size ?: error("Missing legacy compressed size"), entry.size,
                        entry.compression ?: error("Missing legacy compression"), entry.sha256 ?: error("Missing digest"))
                )
                for (block in blocks) {
                    require(block.offset >= 0 && block.compressed_size > 0 && block.size > 0) { "Invalid chunk length" }
                    require(payloadStart + block.offset + block.compressed_size <= fileLength) { "Chunk extends beyond archive" }
                    input.seek(payloadStart + block.offset)
                    val encoded = ByteArray(block.compressed_size.toInt())
                    input.readFully(encoded)
                    val raw = when (block.compression) {
                        "store" -> encoded
                        "zlib" -> InflaterInputStream(ByteArrayInputStream(encoded)).use { it.readBytesBounded(block.size) }
                        else -> error("Unsupported compression: ${block.compression}")
                    }
                    require(raw.size.toLong() == block.size && sha256(raw) == block.sha256) { "Chunk integrity check failed" }
                    digest.update(raw)
                    size += raw.size
                }
                require(size == entry.size && digest.digest().toHex() == entry.sha256) { "File integrity check failed: ${entry.path}" }
                files++
                bytes += size
            }
        }
        return VerifyResult(true, files, bytes, version)
    }

    @JvmStatic
    @JvmOverloads
    fun extract(archive: File, destination: File, overwrite: Boolean = false): List<File> {
        verify(archive)
        val (version, manifest, payloadStart, fileLength) = readManifest(archive)
        val root = destination.canonicalFile
        root.mkdirs()
        val outputs = ArrayList<File>()
        RandomAccessFile(archive, "r").use { input ->
            for (entry in manifest.entries) {
                validatePath(entry.path)
                val unresolvedTarget = File(root, entry.path)
                val target = unresolvedTarget.canonicalFile
                require(!Files.isSymbolicLink(unresolvedTarget.toPath())) { "Refusing to write through a symbolic link" }
                require(target.toPath().startsWith(root.toPath())) { "Unsafe extraction path" }
                if (entry.type == "directory") { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                require(!target.exists() || overwrite) { "Refusing to overwrite ${target.path}" }
                val temp = File.createTempFile(".qamzor-", ".part", target.parentFile)
                try {
                    FileOutputStream(temp).use { output ->
                        val blocks = entry.chunks ?: if (entry.size == 0L) emptyList() else listOf(Chunk(entry.offset!!, entry.compressed_size!!, entry.size, entry.compression!!, entry.sha256!!))
                        for (block in blocks) {
                            require(payloadStart + block.offset + block.compressed_size <= fileLength) { "Chunk extends beyond archive" }
                            input.seek(payloadStart + block.offset)
                            val encoded = ByteArray(block.compressed_size.toInt())
                            input.readFully(encoded)
                            val raw = if (block.compression == "store") encoded else InflaterInputStream(ByteArrayInputStream(encoded)).use { it.readBytesBounded(block.size) }
                            output.write(raw)
                        }
                    }
                    if (target.exists() && !target.delete()) error("Cannot replace ${target.path}")
                    if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
                    outputs += target
                } finally { temp.delete() }
            }
        }
        return outputs
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

    private fun readManifest(archive: File): Quadruple<String, Manifest, Long, Long> {
        RandomAccessFile(archive, "r").use { input ->
            require(input.length() >= HEADER_SIZE) { "File is too small to be QZRA" }
            val signature = ByteArray(8); input.readFully(signature)
            require(signature.contentEquals(magic)) { "Invalid QZRA signature" }
            val major = input.readUnsignedShort(); val minor = input.readUnsignedShort()
            val flags = input.readInt()
            val manifestLength = input.readLong()
            val entryCount = input.readInt()
            val reserved = input.readInt()
            require(major == 1 && minor in 0..2) { "Unsupported QZRA version $major.$minor" }
            require(flags == 0 && reserved == 0) { "Unsupported QZRA header flags/reserved field" }
            require(minor != 2) { "QZRA 1.2 encryption is not yet supported by this Kotlin client" }
            require(manifestLength in 2..MAX_MANIFEST.toLong() && entryCount in 0..MAX_ENTRIES) { "Invalid manifest bounds" }
            require(HEADER_SIZE + manifestLength <= input.length()) { "Manifest extends beyond file" }
            val raw = ByteArray(manifestLength.toInt()); input.seek(HEADER_SIZE.toLong()); input.readFully(raw)
            val manifest = json.decodeFromString<Manifest>(raw.toString(Charsets.UTF_8))
            require(manifest.format == "QZRA" && manifest.version == "$major.$minor" && manifest.entries.size == entryCount) { "Manifest/header mismatch" }
            val payloadStart = HEADER_SIZE + manifestLength
            validateManifest(manifest, minor, input.length() - payloadStart)
            return Quadruple(manifest.version, manifest, payloadStart, input.length())
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
        if (block.compression == "store") require(block.compressed_size == block.size) { "Stored block size mismatch" }
        require(block.offset <= payloadLength && block.compressed_size <= payloadLength - block.offset) { "Block extends beyond payload" }
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
