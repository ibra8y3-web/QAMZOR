package org.qamzor.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Recovery data is invalid, or the damage is beyond what the sidecar can repair. */
class RecoveryException(message: String) : RuntimeException(message)

/**
 * Sidecar XOR recovery data ("QZRP" version 1), format-compatible with `qamzor/recovery.py`.
 *
 * One parity block is stored for every group of `groupSize` archive blocks, so a single damaged or
 * missing block per group can be reconstructed. This is erasure recovery, not a replacement for
 * backups: two damaged blocks inside one group cannot be repaired. The original file is never overwritten.
 */
object QzraRecovery {
    const val MIN_BLOCK_SIZE = 64 * 1024
    const val MAX_BLOCK_SIZE = 4 * 1024 * 1024
    const val DEFAULT_BLOCK_SIZE = 1024 * 1024
    const val MAX_GROUP_SIZE = 16
    const val DEFAULT_GROUP_SIZE = 8
    private const val MAX_MANIFEST_SIZE = 64L * 1024L * 1024L
    private const val MAX_ARCHIVE_BLOCKS = 1_000_000
    private const val HEADER_LENGTH = 12
    private const val IO_SIZE = 1024 * 1024
    private val MAGIC = byteArrayOf(0x51, 0x5a, 0x52, 0x50, 0x0d, 0x0a, 0x1a, 0x0a)
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    data class CreateResult(
        val recoveryFile: File, val archiveSize: Long, val blocks: Int,
        val parityGroups: Int, val blockSize: Int, val groupSize: Int, val overheadBytes: Long
    )

    data class RepairResult(val output: File, val repairedBlocks: List<Int>, val blocksChecked: Int, val archiveSha256: String)

    /** [damagedBlocks] lists block indexes that failed their SHA-256; [repairable] is false when any group has more than one. */
    data class CheckResult(val archiveSize: Long, val blocks: Int, val damagedBlocks: List<Int>, val repairable: Boolean, val sizeMatches: Boolean)

    private class Group(
        val start: Int, val count: Int, val offset: Long, val compressedSize: Int,
        val method: String, val sha256: String
    )

    private class Manifest(
        val archiveSize: Long, val archiveSha256: String, val blockSize: Int, val groupSize: Int,
        val blockSizes: IntArray, val blockHashes: Array<String>, val groups: List<Group>, val payloadOffset: Long
    )

    @JvmStatic
    @JvmOverloads
    fun create(
        archive: File,
        recoveryFile: File,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        groupSize: Int = DEFAULT_GROUP_SIZE,
        overwrite: Boolean = false,
        progress: ProgressListener? = null
    ): CreateResult {
        if (blockSize < MIN_BLOCK_SIZE || blockSize > MAX_BLOCK_SIZE) throw RecoveryException("block_size must be between $MIN_BLOCK_SIZE and $MAX_BLOCK_SIZE")
        if (groupSize < 2 || groupSize > MAX_GROUP_SIZE) throw RecoveryException("group_size must be between 2 and $MAX_GROUP_SIZE")
        if (Files.isSymbolicLink(archive.toPath()) || !archive.isFile) throw RecoveryException("Archive must be a regular, non-symbolic-link file")
        if (archive.absoluteFile == recoveryFile.absoluteFile) throw RecoveryException("Recovery sidecar must not overwrite the archive")
        if (recoveryFile.exists() && !overwrite) throw RecoveryException("Destination already exists: ${recoveryFile.name}")
        val parent = recoveryFile.absoluteFile.parentFile ?: throw RecoveryException("Destination has no parent directory")
        parent.mkdirs()
        val sizeBefore = archive.length()
        val modifiedBefore = archive.lastModified()
        if (sizeBefore <= 0L) throw RecoveryException("Recovery archive size is invalid")
        val allHash = MessageDigest.getInstance("SHA-256")
        val blockEntries = ArrayList<Pair<Int, String>>()
        val groups = ArrayList<Group>()
        val spool = File.createTempFile(".qamzor-recovery-", ".spool", parent)
        var temp: File? = null
        var archiveSize = 0L
        var parityOffset = 0L
        progress?.onPhase("recovery-create", sizeBefore)
        try {
            FileOutputStream(spool).use { spoolOut ->
                FileInputStream(archive).use { input ->
                    val data = ByteArray(blockSize)
                    var finished = false
                    while (!finished) {
                        val parity = ByteArray(blockSize)
                        var inGroup = 0
                        while (inGroup < groupSize) {
                            if (progress?.isCancelled() == true) throw OperationCancelledException()
                            val read = readFully(input, data)
                            if (read <= 0) { finished = true; break }
                            archiveSize += read
                            allHash.update(data, 0, read)
                            blockEntries += Pair(read, sha256Hex(data, read))
                            for (position in 0 until read) parity[position] = (parity[position].toInt() xor data[position].toInt()).toByte()
                            inGroup++
                            progress?.onBytes(read.toLong())
                            if (read < blockSize) { finished = true; break }
                        }
                        if (inGroup == 0) break
                        val encoded = deflate(parity)
                        val method = if (encoded.size < parity.size) "zlib" else "store"
                        val stored = if (method == "store") parity else encoded
                        groups += Group(blockEntries.size - inGroup, inGroup, parityOffset, stored.size, method, sha256Hex(parity, parity.size))
                        spoolOut.write(stored)
                        parityOffset += stored.size
                    }
                }
                spoolOut.flush()
            }
            if (archive.length() != sizeBefore || archive.lastModified() != modifiedBefore) {
                throw RecoveryException("Archive changed while recovery data was being created")
            }
            if (archiveSize != sizeBefore) throw RecoveryException("Archive size changed while recovery data was being created")
            if (blockEntries.size > MAX_ARCHIVE_BLOCKS) throw RecoveryException("Archive has too many recovery blocks")
            // Sorted keys + compact separators reproduce Python's json.dumps(sort_keys=True, separators=(",", ":")).
            val manifest = buildJsonObject {
                put("archive_sha256", allHash.digest().toHex())
                put("archive_size", archiveSize)
                put("block_size", blockSize)
                put("blocks", buildJsonArray {
                    for ((size, sha) in blockEntries) add(buildJsonObject { put("sha256", sha); put("size", size) })
                })
                put("format", "QZRP")
                put("group_size", groupSize)
                put("groups", buildJsonArray {
                    for (group in groups) add(buildJsonObject {
                        put("compressed_size", group.compressedSize)
                        put("compression", group.method)
                        put("count", group.count)
                        put("offset", group.offset)
                        put("sha256", group.sha256)
                        put("start_block", group.start)
                    })
                })
                put("version", 1)
            }
            val encodedManifest = manifest.toString().toByteArray(Charsets.UTF_8)
            if (encodedManifest.size > MAX_MANIFEST_SIZE) throw RecoveryException("Recovery manifest exceeds its size limit")
            val written = File.createTempFile(".${recoveryFile.name}.", ".tmp", parent)
            temp = written
            FileOutputStream(written).use { target ->
                target.write(MAGIC)
                val length = encodedManifest.size
                target.write(byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()))
                target.write(encodedManifest)
                FileInputStream(spool).use { it.copyTo(target, IO_SIZE) }
                target.flush()
                target.fd.sync()
            }
            if (overwrite) Files.move(written.toPath(), recoveryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.move(written.toPath(), recoveryFile.toPath())
            temp = null
            return CreateResult(recoveryFile, archiveSize, blockEntries.size, groups.size, blockSize, groupSize, recoveryFile.length())
        } finally {
            spool.delete()
            temp?.delete()
        }
    }

    /** Verifies every block of [archive] against the sidecar without writing anything. */
    @JvmStatic
    @JvmOverloads
    fun check(archive: File, recoveryFile: File, progress: ProgressListener? = null): CheckResult {
        val manifest = load(recoveryFile)
        if (Files.isSymbolicLink(archive.toPath()) || !archive.isFile) throw RecoveryException("A regular archive file is required for repair")
        val damaged = ArrayList<Int>()
        progress?.onPhase("recovery-check", manifest.archiveSize)
        RandomAccessFile(archive, "r").use { source ->
            for (index in manifest.blockSizes.indices) {
                if (progress?.isCancelled() == true) throw OperationCancelledException()
                val data = readBlock(source, index.toLong() * manifest.blockSize, manifest.blockSizes[index])
                if (data == null || sha256Hex(data, data.size) != manifest.blockHashes[index]) damaged += index
                progress?.onBytes(manifest.blockSizes[index].toLong())
            }
        }
        val repairable = manifest.groups.all { group -> damaged.count { it >= group.start && it < group.start + group.count } <= 1 }
        return CheckResult(manifest.archiveSize, manifest.blockSizes.size, damaged, repairable, archive.length() == manifest.archiveSize)
    }

    /** Writes a repaired copy to [output]; at most one damaged block per group can be rebuilt. */
    @JvmStatic
    @JvmOverloads
    fun repair(
        archive: File, recoveryFile: File, output: File, overwrite: Boolean = false, progress: ProgressListener? = null
    ): RepairResult {
        if (archive.absoluteFile == output.absoluteFile || recoveryFile.absoluteFile == output.absoluteFile) {
            throw RecoveryException("Repair output must be different from both input files")
        }
        if (Files.isSymbolicLink(archive.toPath()) || !archive.isFile) throw RecoveryException("A regular archive file is required for repair")
        if (output.exists() && !overwrite) throw RecoveryException("Repair output already exists: ${output.name}")
        val manifest = load(recoveryFile)
        val parent = output.absoluteFile.parentFile ?: throw RecoveryException("Destination has no parent directory")
        parent.mkdirs()
        val repaired = ArrayList<Int>()
        val full = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val temp = File.createTempFile(".${output.name}.", ".tmp", parent)
        progress?.onPhase("recovery-repair", manifest.archiveSize)
        try {
            RandomAccessFile(archive, "r").use { source ->
                RandomAccessFile(recoveryFile, "r").use { sidecar ->
                    FileOutputStream(temp).use { target ->
                        for (group in manifest.groups) {
                            val blocks = arrayOfNulls<ByteArray>(group.count)
                            val bad = ArrayList<Int>()
                            for (position in 0 until group.count) {
                                if (progress?.isCancelled() == true) throw OperationCancelledException()
                                val index = group.start + position
                                val data = readBlock(source, index.toLong() * manifest.blockSize, manifest.blockSizes[index])
                                if (data == null || sha256Hex(data, data.size) != manifest.blockHashes[index]) bad += position else blocks[position] = data
                            }
                            if (bad.size > 1) {
                                throw RecoveryException("Group starting at block ${group.start} contains ${bad.size} damaged blocks; only one is recoverable")
                            }
                            if (bad.size == 1) {
                                val parity = readParity(sidecar, manifest, group)
                                for (data in blocks) {
                                    if (data == null) continue
                                    for (position in data.indices) parity[position] = (parity[position].toInt() xor data[position].toInt()).toByte()
                                }
                                val damagedIndex = group.start + bad[0]
                                val expected = manifest.blockSizes[damagedIndex]
                                val recovered = parity.copyOf(expected)
                                if (sha256Hex(recovered, recovered.size) != manifest.blockHashes[damagedIndex]) {
                                    throw RecoveryException("Recovered block $damagedIndex failed its SHA-256 check")
                                }
                                blocks[bad[0]] = recovered
                                repaired += damagedIndex
                            }
                            for (data in blocks) {
                                val block = data ?: throw RecoveryException("Internal recovery failure: unresolved damaged block")
                                target.write(block)
                                full.update(block)
                                size += block.size
                                progress?.onBytes(block.size.toLong())
                            }
                        }
                        target.flush()
                        target.fd.sync()
                    }
                }
            }
            val digest = full.digest().toHex()
            if (size != manifest.archiveSize || digest != manifest.archiveSha256) {
                throw RecoveryException("Reconstructed archive does not match its recorded whole-file digest")
            }
            if (overwrite) Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temp.toPath(), output.toPath())
            return RepairResult(output, repaired, manifest.blockSizes.size, digest)
        } finally {
            temp.delete()
        }
    }

    private fun load(recoveryFile: File): Manifest {
        if (Files.isSymbolicLink(recoveryFile.toPath()) || !recoveryFile.isFile) throw RecoveryException("A regular recovery sidecar is required")
        val total = recoveryFile.length()
        val parsed: Pair<JsonObject, Int> = FileInputStream(recoveryFile).use { stream ->
            val header = ByteArray(HEADER_LENGTH)
            if (readFully(stream, header) != HEADER_LENGTH) throw RecoveryException("Recovery file header is truncated")
            for (index in MAGIC.indices) if (header[index] != MAGIC[index]) throw RecoveryException("Invalid recovery file signature")
            val declared = ((header[8].toLong() and 0xff) shl 24) or ((header[9].toLong() and 0xff) shl 16) or
                ((header[10].toLong() and 0xff) shl 8) or (header[11].toLong() and 0xff)
            if (declared > MAX_MANIFEST_SIZE) throw RecoveryException("Recovery manifest exceeds its size limit")
            if (declared > total - HEADER_LENGTH) throw RecoveryException("Recovery manifest is truncated")
            val length = declared.toInt()
            val encoded = ByteArray(length)
            if (readFully(stream, encoded) != length) throw RecoveryException("Recovery manifest is truncated")
            val element = try {
                Json.parseToJsonElement(String(encoded, Charsets.UTF_8)).jsonObject
            } catch (error: Exception) {
                throw RecoveryException("Recovery manifest is not valid UTF-8 JSON")
            }
            Pair(element, length)
        }
        val root = parsed.first
        val manifestSize = parsed.second
        if (text(root, "format") != "QZRP" || number(root, "version") != 1L) throw RecoveryException("Unsupported recovery data format")
        val payloadOffset = HEADER_LENGTH.toLong() + manifestSize
        val payloadLength = total - payloadOffset
        val archiveSize = number(root, "archive_size")
        val blockSizeValue = number(root, "block_size")
        val groupSizeValue = number(root, "group_size")
        val archiveDigest = text(root, "archive_sha256")
        if (archiveSize == null || archiveSize <= 0) throw RecoveryException("Recovery archive size is invalid")
        if (blockSizeValue == null || blockSizeValue < MIN_BLOCK_SIZE || blockSizeValue > MAX_BLOCK_SIZE) throw RecoveryException("Recovery block size is invalid")
        if (groupSizeValue == null || groupSizeValue < 2 || groupSizeValue > MAX_GROUP_SIZE) throw RecoveryException("Recovery group size is invalid")
        if (archiveDigest == null || !HEX64.matches(archiveDigest)) throw RecoveryException("Recovery archive digest is malformed")
        val blockSize = blockSizeValue.toInt()
        val groupSize = groupSizeValue.toInt()
        val expectedBlocks = (archiveSize + blockSize - 1) / blockSize
        val blockArray = try { root["blocks"]?.jsonArray } catch (_: Exception) { null }
        if (blockArray == null || blockArray.size.toLong() != expectedBlocks || blockArray.size > MAX_ARCHIVE_BLOCKS) {
            throw RecoveryException("Recovery block table is inconsistent")
        }
        val sizes = IntArray(blockArray.size)
        val hashes = Array(blockArray.size) { "" }
        for ((index, element) in blockArray.withIndex()) {
            val entry = try { element.jsonObject } catch (_: Exception) { throw RecoveryException("Recovery block size entry is invalid") }
            val expectedSize = minOf(blockSize.toLong(), archiveSize - index.toLong() * blockSize)
            if (number(entry, "size") != expectedSize) throw RecoveryException("Recovery block size entry is invalid")
            val sha = text(entry, "sha256")
            if (sha == null || !HEX64.matches(sha)) throw RecoveryException("Recovery block digest is malformed")
            sizes[index] = expectedSize.toInt()
            hashes[index] = sha
        }
        val groupArray = try { root["groups"]?.jsonArray } catch (_: Exception) { null }
        if (groupArray == null || groupArray.size.toLong() != (expectedBlocks + groupSize - 1) / groupSize) {
            throw RecoveryException("Recovery parity group table is inconsistent")
        }
        val groups = ArrayList<Group>()
        var nextBlock = 0L
        var nextOffset = 0L
        for (element in groupArray) {
            val entry = try { element.jsonObject } catch (_: Exception) { throw RecoveryException("Recovery parity group is invalid") }
            val start = number(entry, "start_block")
            val count = number(entry, "count")
            val offset = number(entry, "offset")
            val compressed = number(entry, "compressed_size")
            val method = text(entry, "compression")
            val sha = text(entry, "sha256")
            if (start == null || start != nextBlock || count == null || count < 1 || count > groupSize) {
                throw RecoveryException("Recovery parity groups are not contiguous")
            }
            if (offset == null || offset != nextOffset) throw RecoveryException("Recovery parity payload offsets are invalid")
            if (compressed == null || compressed <= 0 || compressed > blockSize + 65536L) throw RecoveryException("Recovery parity block size is invalid")
            if (method == null || (method != "zlib" && method != "store")) throw RecoveryException("Unsupported parity compression method")
            if (method == "store" && compressed != blockSize.toLong()) throw RecoveryException("Stored parity block has an invalid size")
            if (sha == null || !HEX64.matches(sha)) throw RecoveryException("Parity digest is malformed")
            groups += Group(start.toInt(), count.toInt(), offset, compressed.toInt(), method, sha)
            nextBlock += count
            nextOffset += compressed
        }
        if (nextBlock != expectedBlocks || nextOffset != payloadLength) throw RecoveryException("Recovery parity payload coverage is incomplete")
        return Manifest(archiveSize, archiveDigest, blockSize, groupSize, sizes, hashes, groups, payloadOffset)
    }

    private fun readParity(sidecar: RandomAccessFile, manifest: Manifest, group: Group): ByteArray {
        sidecar.seek(manifest.payloadOffset + group.offset)
        val encoded = ByteArray(group.compressedSize)
        try {
            sidecar.readFully(encoded)
        } catch (_: java.io.EOFException) {
            throw RecoveryException("Parity payload is truncated")
        }
        val parity = if (group.method == "store") encoded else inflateBounded(encoded, manifest.blockSize)
        if (parity.size != manifest.blockSize || sha256Hex(parity, parity.size) != group.sha256) {
            throw RecoveryException("Parity block integrity check failed")
        }
        return parity
    }

    private fun inflateBounded(encoded: ByteArray, blockSize: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(encoded)
            val out = ByteArray(blockSize + 1)
            var produced = 0
            while (!inflater.finished() && produced < out.size) {
                val count = try {
                    inflater.inflate(out, produced, out.size - produced)
                } catch (_: java.util.zip.DataFormatException) {
                    throw RecoveryException("Parity payload is invalid")
                }
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                produced += count
            }
            if (!inflater.finished() || inflater.remaining != 0 || produced > blockSize) {
                throw RecoveryException("Parity payload expands beyond its permitted size")
            }
            return out.copyOf(produced)
        } finally {
            inflater.end()
        }
    }

    private fun readBlock(file: RandomAccessFile, offset: Long, size: Int): ByteArray? {
        if (offset >= file.length()) return null
        file.seek(offset)
        val data = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = file.read(data, read, size - read)
            if (count < 0) return null
            read += count
        }
        return data
    }

    private fun readFully(stream: FileInputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val count = stream.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return read
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(6)
        try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun text(obj: JsonObject, key: String): String? {
        val value = obj[key] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }

    private fun number(obj: JsonObject, key: String): Long? {
        val value = obj[key] as? JsonPrimitive ?: return null
        return if (value.isString) null else value.longOrNull
    }

    private fun sha256Hex(data: ByteArray, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, 0, length)
        return digest.digest().toHex()
    }

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
