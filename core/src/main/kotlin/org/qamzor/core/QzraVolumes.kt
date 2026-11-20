package org.qamzor.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/** Raised when a split set is malformed, incomplete or fails an integrity check. */
class VolumeException(message: String) : RuntimeException(message)

/**
 * Integrity-checked splitting and joining of QZRA archives.
 *
 * The on-disk layout is the same "QZRA-PART-SET" descriptor (version 2) the Python implementation
 * (`qamzor/volumes.py`) writes, so sets can be exchanged between the two implementations:
 * `<name>.qzset` plus a `<name>.parts-<12 hex>/` directory of `<name>.partNNNNN` files, each part and
 * the reassembled archive being covered by SHA-256.
 */
object QzraVolumes {
    const val MIN_PART_SIZE: Long = 1024L * 1024L
    const val DEFAULT_PART_SIZE: Long = 100L * 1024L * 1024L
    private const val MAX_PARTS = 100_000
    private const val MAX_SET_BYTES = 16L * 1024L * 1024L
    private const val IO_SIZE = 1024 * 1024
    private const val HEADER_SIZE = 32L
    private val NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,240}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val MAGIC = byteArrayOf(0x51, 0x5a, 0x52, 0x41, 0x0d, 0x0a, 0x1a, 0x0a)

    data class SplitResult(
        val descriptor: File,
        val partsDirectory: File,
        val partFiles: List<File>,
        val archiveSize: Long,
        val archiveSha256: String
    )

    data class JoinResult(val archive: File, val bytes: Long, val sha256: String)

    /** Maps any archive file name to a name that satisfies the portable descriptor rules (ASCII, no leading dot). */
    @JvmStatic
    fun portableName(original: String): String {
        var cleaned = original.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (cleaned.isEmpty() || !cleaned[0].isLetterOrDigit() || cleaned[0].code > 127) cleaned = "a$cleaned"
        if (cleaned.length > 240) cleaned = cleaned.substring(0, 240)
        return cleaned
    }

    @JvmStatic
    @JvmOverloads
    fun split(
        archive: File,
        outputDirectory: File,
        partSize: Long = DEFAULT_PART_SIZE,
        overwrite: Boolean = false,
        progress: ProgressListener? = null
    ): SplitResult {
        if (Files.isSymbolicLink(archive.toPath())) throw VolumeException("Split source cannot be a symbolic link")
        if (!archive.isFile) throw VolumeException("Split source must be a regular archive file")
        val archiveSize = archive.length()
        if (archiveSize < HEADER_SIZE) throw VolumeException("Input is too small to be a QZRA archive")
        FileInputStream(archive).use { stream ->
            val head = ByteArray(MAGIC.size)
            var read = 0
            while (read < head.size) {
                val count = stream.read(head, read, head.size - read)
                if (count < 0) break
                read += count
            }
            if (read != head.size || !head.contentEquals(MAGIC)) throw VolumeException("Split input is not a QZRA archive")
        }
        if (partSize < MIN_PART_SIZE) throw VolumeException("Part size must be at least 1 MiB")
        val partCount = (archiveSize + partSize - 1) / partSize
        if (partCount > MAX_PARTS) throw VolumeException("Archive requires too many split parts")

        outputDirectory.mkdirs()
        if (!outputDirectory.isDirectory || Files.isSymbolicLink(outputDirectory.toPath())) {
            throw VolumeException("Split output must be a regular directory")
        }
        val safeName = portableName(archive.name)
        val setFile = File(outputDirectory, "$safeName.qzset")
        if (setFile.exists() && !overwrite) throw VolumeException("Split-set descriptor already exists: ${setFile.name}")
        val oldPartsDirectory = readOldPartsDirectory(setFile, safeName, outputDirectory)

        val partsName = "$safeName.parts-" + randomHex(6)
        val partsDirectory = File(outputDirectory, partsName)
        if (!partsDirectory.mkdir()) throw VolumeException("Cannot create the parts directory")
        val modifiedBefore = archive.lastModified()
        val partFiles = ArrayList<File>()
        val partInfo = ArrayList<Triple<String, Long, String>>()
        val streamDigest = MessageDigest.getInstance("SHA-256")
        var streamTotal = 0L
        progress?.onPhase("split", archiveSize)
        try {
            FileInputStream(archive).use { input ->
                val buffer = ByteArray(IO_SIZE)
                for (index in 0 until partCount.toInt()) {
                    val name = "$safeName.part" + String.format(Locale.ROOT, "%05d", index + 1)
                    val temp = File.createTempFile(".$name.", ".tmp", partsDirectory)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    try {
                        FileOutputStream(temp).use { output ->
                            var remaining = minOf(partSize, archiveSize - index.toLong() * partSize)
                            while (remaining > 0) {
                                if (progress?.isCancelled() == true) throw OperationCancelledException()
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw VolumeException("Source archive changed or was truncated while splitting")
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                streamDigest.update(buffer, 0, count)
                                size += count
                                streamTotal += count
                                remaining -= count
                                progress?.onBytes(count.toLong())
                            }
                            output.flush()
                            output.fd.sync()
                        }
                        val target = File(partsDirectory, name)
                        Files.move(temp.toPath(), target.toPath())
                        partFiles += target
                    } finally {
                        temp.delete()
                    }
                    partInfo += Triple(name, size, digest.digest().toHex())
                }
            }
            if (streamTotal != archiveSize || archive.length() != archiveSize || archive.lastModified() != modifiedBefore) {
                throw VolumeException("Source archive changed while it was being split; partial volumes were discarded")
            }
            val archiveDigest = streamDigest.digest().toHex()
            // Keys are written in sorted order so the output is byte-identical to Python's sort_keys=True form.
            val descriptor = buildJsonObject {
                put("archive_name", safeName)
                put("archive_sha256", archiveDigest)
                put("archive_size", archiveSize)
                put("format", "QZRA-PART-SET")
                put("part_size", partSize)
                put("parts", buildJsonArray {
                    for ((name, size, sha) in partInfo) {
                        add(buildJsonObject {
                            put("name", name)
                            put("sha256", sha)
                            put("size", size)
                        })
                    }
                })
                put("parts_directory", partsName)
                put("version", 2)
            }
            writeTextAtomically(setFile, descriptor.toString() + "\n", overwrite)
            if (oldPartsDirectory != null && oldPartsDirectory.absolutePath != partsDirectory.absolutePath) {
                oldPartsDirectory.deleteRecursively()
            }
            return SplitResult(setFile, partsDirectory, partFiles, archiveSize, archiveDigest)
        } catch (error: Throwable) {
            partsDirectory.deleteRecursively()
            throw error
        }
    }

    @JvmStatic
    @JvmOverloads
    fun join(setFile: File, output: File, overwrite: Boolean = false, progress: ProgressListener? = null): JoinResult {
        if (Files.isSymbolicLink(setFile.toPath())) throw VolumeException("Split-set descriptor cannot be a symbolic link")
        if (!setFile.isFile) throw VolumeException("Split-set descriptor is missing")
        if (setFile.length() > MAX_SET_BYTES) throw VolumeException("Split-set descriptor exceeds its size limit")
        val descriptor: JsonObject = try {
            Json.parseToJsonElement(setFile.readText(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw VolumeException("Split-set descriptor is not valid JSON")
        }
        if (descriptor.string("format") != "QZRA-PART-SET") throw VolumeException("Unsupported split-set descriptor")
        val version = descriptor.long("version")
        if (version != 1L && version != 2L) throw VolumeException("Unsupported split-set descriptor")
        val archiveName = descriptor.string("archive_name")
        val archiveSize = descriptor.long("archive_size")
        val archiveDigest = descriptor.string("archive_sha256")
        val partSize = descriptor.long("part_size")
        val parts: JsonArray = try { descriptor["parts"]?.jsonArray } catch (_: Exception) { null }
            ?: throw VolumeException("Split-set part list is incomplete or excessive")
        if (archiveName == null || !NAME_RE.matches(archiveName)) throw VolumeException("Invalid archive name in split-set descriptor")
        if (archiveSize == null || archiveSize < HEADER_SIZE) throw VolumeException("Invalid archive size in split-set descriptor")
        if (partSize == null || partSize < MIN_PART_SIZE) throw VolumeException("Invalid part size in split-set descriptor")
        if (archiveDigest == null || !HEX64.matches(archiveDigest)) throw VolumeException("Invalid archive digest in split-set descriptor")
        val expectedCount = (archiveSize + partSize - 1) / partSize
        if (parts.size < 1 || parts.size > MAX_PARTS || parts.size.toLong() != expectedCount) {
            throw VolumeException("Split-set part list is incomplete or excessive")
        }
        var partsRoot = setFile.absoluteFile.parentFile ?: throw VolumeException("Split-set has no parent directory")
        if (version == 2L) {
            val partsDirectory = descriptor.string("parts_directory")
            if (partsDirectory == null || !Regex(Regex.escape("$archiveName.parts-") + "[0-9a-f]{12}").matches(partsDirectory)) {
                throw VolumeException("Split-set parts directory is invalid")
            }
            val candidate = File(partsRoot, partsDirectory)
            // Convenience for sets received file-by-file: when the sub-directory is absent, parts next to the descriptor are used.
            // Every part is still verified against the descriptor's SHA-256 values, so this cannot weaken integrity.
            if (candidate.isDirectory && !Files.isSymbolicLink(candidate.toPath())) partsRoot = candidate
        }
        if (output.exists() && !overwrite) throw VolumeException("Destination already exists: ${output.name}")
        val outputParent = output.absoluteFile.parentFile ?: throw VolumeException("Destination has no parent directory")
        outputParent.mkdirs()
        val temp = File.createTempFile(".${output.name}.", ".tmp", outputParent)
        val total = MessageDigest.getInstance("SHA-256")
        var written = 0L
        progress?.onPhase("join", archiveSize)
        try {
            FileOutputStream(temp).use { merged ->
                val buffer = ByteArray(IO_SIZE)
                for ((position, element) in parts.withIndex()) {
                    val index = position + 1
                    val part = try { element.jsonObject } catch (_: Exception) { throw VolumeException("Split-set part names are invalid or out of order") }
                    val expectedName = "$archiveName.part" + String.format(Locale.ROOT, "%05d", index)
                    if (part.string("name") != expectedName) throw VolumeException("Split-set part names are invalid or out of order")
                    val partDigest = part.string("sha256")
                    if (partDigest == null || !HEX64.matches(partDigest)) throw VolumeException("Split part digest is malformed")
                    val expectedSize = minOf(partSize, archiveSize - (index - 1).toLong() * partSize)
                    if (part.long("size") != expectedSize) throw VolumeException("Split part size is inconsistent")
                    val partFile = File(partsRoot, expectedName)
                    if (Files.isSymbolicLink(partFile.toPath()) || !partFile.isFile) throw VolumeException("Split part is missing or unsafe: $expectedName")
                    val partHash = MessageDigest.getInstance("SHA-256")
                    var partTotal = 0L
                    FileInputStream(partFile).use { stream ->
                        while (true) {
                            if (progress?.isCancelled() == true) throw OperationCancelledException()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            partHash.update(buffer, 0, count)
                            total.update(buffer, 0, count)
                            merged.write(buffer, 0, count)
                            partTotal += count
                            written += count
                            progress?.onBytes(count.toLong())
                        }
                    }
                    if (partTotal != expectedSize || partHash.digest().toHex() != partDigest) {
                        throw VolumeException("Split part integrity check failed: $expectedName")
                    }
                }
                merged.flush()
                merged.fd.sync()
            }
            val digestHex = total.digest().toHex()
            if (written != archiveSize || digestHex != archiveDigest) {
                throw VolumeException("Reassembled archive size or SHA-256 does not match the descriptor")
            }
            FileInputStream(temp).use { stream ->
                val head = ByteArray(MAGIC.size)
                var read = 0
                while (read < head.size) {
                    val count = stream.read(head, read, head.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read != head.size || !head.contentEquals(MAGIC)) throw VolumeException("Reassembled file is not a QZRA archive")
            }
            if (overwrite) Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temp.toPath(), output.toPath())
            return JoinResult(output, written, digestHex)
        } finally {
            temp.delete()
        }
    }

    private fun readOldPartsDirectory(setFile: File, archiveName: String, outputDirectory: File): File? {
        if (!setFile.isFile) return null
        return try {
            val old = Json.parseToJsonElement(setFile.readText(Charsets.UTF_8)).jsonObject
            val name = old.string("parts_directory")
            if (old.string("format") == "QZRA-PART-SET" && old.long("version") == 2L && name != null &&
                Regex(Regex.escape("$archiveName.parts-") + "[0-9a-f]{12}").matches(name)) File(outputDirectory, name) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeTextAtomically(target: File, text: String, overwrite: Boolean) {
        val parent = target.absoluteFile.parentFile ?: throw VolumeException("Descriptor has no parent directory")
        val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            if (overwrite) Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temp.toPath(), target.toPath())
        } finally {
            temp.delete()
        }
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }

    private fun JsonObject.long(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        return if (value.isString) null else value.longOrNull
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.toHex()

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            builder.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return builder.toString()
    }
}
