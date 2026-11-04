package org.qamzor.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom

/** Integrity-checked, Python-compatible split sets for large QZRA archives. */
object ArchiveVolumes {
    private const val MIN_PART_SIZE = 1024L * 1024L
    private const val MAX_PARTS = 100_000
    private const val MAX_SET_BYTES = 16L * 1024L * 1024L
    private const val HEADER_SIZE = 32L
    private val magic = byteArrayOf(0x51, 0x5a, 0x52, 0x41, 0x0d, 0x0a, 0x1a, 0x0a)
    private val random = SecureRandom()
    private val json = Json { prettyPrint = false; explicitNulls = false; ignoreUnknownKeys = false }
    private val portableName = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,240}$")

    @Serializable
    private data class Part(val name: String, val size: Long, val sha256: String)

    @Serializable
    private data class Descriptor(
        val format: String,
        val version: Int,
        val archive_name: String,
        val archive_size: Long,
        val archive_sha256: String,
        val part_size: Long,
        val parts_directory: String? = null,
        val parts: List<Part>
    )

    data class SplitResult(
        val setFile: File,
        val partsDirectory: File,
        val partCount: Int,
        val archiveSize: Long,
        val archiveSha256: String
    )

    data class JoinResult(
        val archive: File,
        val partCount: Int,
        val archiveSize: Long,
        val archiveSha256: String,
        val valid: Boolean
    )

    /**
     * Split a QZRA archive into immutable numbered parts and an adjacent .qzset descriptor.
     * The descriptor format is byte-compatible with the Python QAMZOR volume implementation.
     */
    @JvmStatic
    @JvmOverloads
    fun split(archive: File, outputDirectory: File = archive.absoluteFile.parentFile ?: File("."), partSize: Long = 100L * 1024L * 1024L): SplitResult {
        require(partSize >= MIN_PART_SIZE) { "Part size must be at least 1 MiB" }
        require(!Files.isSymbolicLink(archive.toPath())) { "Split source cannot be a symbolic link" }
        val source = archive.canonicalFile
        require(source.isFile && source.length() >= HEADER_SIZE) { "Split source must be a QZRA archive" }
        require(source.name.matches(portableName)) { "Archive name contains characters unsupported by portable split sets" }
        RandomAccessFile(source, "r").use { input ->
            val signature = ByteArray(magic.size)
            input.readFully(signature)
            require(signature.contentEquals(magic)) { "Split input is not a QZRA archive" }
        }

        ensureNoSymlinkComponents(outputDirectory.absoluteFile)
        require(outputDirectory.mkdirs() || outputDirectory.isDirectory) { "Cannot create split output directory" }
        val output = outputDirectory.canonicalFile
        val setFile = File(output, "${source.name}.qzset")
        require(!Files.isSymbolicLink(setFile.toPath())) { "Split-set descriptor cannot be a symbolic link" }
        require(!setFile.exists()) { "Split-set descriptor already exists: ${setFile.name}" }

        val sourceDigest = digestFile(source)
        val partCountLong = 1L + (source.length() - 1L) / partSize
        require(partCountLong in 1..MAX_PARTS.toLong()) { "Archive requires too many split parts" }
        val partCount = partCountLong.toInt()
        val suffix = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val partsDirectoryName = "${source.name}.parts-$suffix"
        val partsDirectory = File(output, partsDirectoryName)
        require(partsDirectory.mkdir()) { "Cannot create split-parts directory" }

        val parts = ArrayList<Part>(partCount)
        val streamDigest = MessageDigest.getInstance("SHA-256")
        var streamTotal = 0L
        try {
            FileInputStream(source).use { input ->
                for (index in 0 until partCount) {
                    val name = "${source.name}.part${(index + 1).toString().padStart(5, '0')}"
                    val target = File(partsDirectory, name)
                    val temporary = File(partsDirectory, ".$name.${System.nanoTime()}.tmp")
                    val partDigest = MessageDigest.getInstance("SHA-256")
                    var partTotal = 0L
                    try {
                        FileOutputStream(temporary).use { outputStream ->
                            var remaining = minOf(partSize, source.length() - index.toLong() * partSize)
                            val buffer = ByteArray(1024 * 1024)
                            while (remaining > 0L) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                require(count > 0) { "Source archive changed or was truncated while splitting" }
                                outputStream.write(buffer, 0, count)
                                partDigest.update(buffer, 0, count)
                                streamDigest.update(buffer, 0, count)
                                partTotal += count
                                streamTotal += count
                                remaining -= count
                            }
                            outputStream.fd.sync()
                        }
                        moveNew(temporary, target)
                        parts += Part(name, partTotal, partDigest.digest().toHex())
                    } finally {
                        temporary.delete()
                    }
                }
            }
            val combinedDigest = streamDigest.digest().toHex()
            require(streamTotal == source.length() && combinedDigest == sourceDigest.first) {
                "Source archive changed while splitting; generated parts were discarded"
            }
            require(source.length() == sourceDigest.second) { "Source archive size changed while splitting" }

            val descriptor = Descriptor(
                format = "QZRA-PART-SET", version = 2, archive_name = source.name,
                archive_size = source.length(), archive_sha256 = combinedDigest,
                part_size = partSize, parts_directory = partsDirectoryName, parts = parts
            )
            val descriptorTemp = File(output, ".${setFile.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(descriptorTemp).use { stream ->
                    stream.write((json.encodeToString(descriptor) + "\n").toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                moveNew(descriptorTemp, setFile)
            } finally {
                descriptorTemp.delete()
            }
            return SplitResult(setFile, partsDirectory, partCount, source.length(), combinedDigest)
        } catch (error: Exception) {
            partsDirectory.deleteRecursively()
            throw error
        }
    }

    /** Reassemble a v1/v2 split set, verifying every part and the complete QZRA digest. */
    @JvmStatic
    @JvmOverloads
    fun join(setFile: File, outputArchive: File, overwrite: Boolean = false): JoinResult {
        require(!Files.isSymbolicLink(setFile.toPath())) { "Split-set descriptor cannot be a symbolic link" }
        val descriptorFile = setFile.canonicalFile
        require(descriptorFile.isFile && descriptorFile.length() in 2..MAX_SET_BYTES) { "Split-set descriptor is missing or exceeds its size limit" }
        val descriptor = try {
            json.decodeFromString<Descriptor>(descriptorFile.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalArgumentException("Split-set descriptor is not valid or is unsupported", error)
        }
        require(descriptor.format == "QZRA-PART-SET" && descriptor.version in 1..2) { "Unsupported split-set descriptor" }
        require(descriptor.archive_name.matches(portableName)) { "Invalid archive name in split-set descriptor" }
        require(descriptor.archive_size >= HEADER_SIZE && descriptor.part_size >= MIN_PART_SIZE) { "Invalid split-set sizes" }
        require(descriptor.archive_sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid archive digest in split-set descriptor" }
        val expectedCount = 1L + (descriptor.archive_size - 1L) / descriptor.part_size
        require(expectedCount in 1..MAX_PARTS.toLong() && descriptor.parts.size.toLong() == expectedCount) {
            "Split-set part list is incomplete or excessive"
        }

        val partsRoot = if (descriptor.version == 2) {
            val name = descriptor.parts_directory ?: throw IllegalArgumentException("Split-parts directory is missing")
            require(name == "${descriptor.archive_name}.parts-${name.substringAfterLast(".parts-", "")}" &&
                name.substringAfterLast(".parts-", "").matches(Regex("[0-9a-f]{12}"))) {
                "Split-parts directory name is invalid"
            }
            File(descriptorFile.parentFile, name)
        } else descriptorFile.parentFile
        require(!Files.isSymbolicLink(partsRoot.toPath()) && partsRoot.isDirectory) { "Split-parts directory is missing or unsafe" }

        ensureNoSymlinkComponents(outputArchive.absoluteFile.parentFile ?: File("."))
        val output = outputArchive.absoluteFile
        require(output.name.endsWith(".qzra", ignoreCase = true)) { "Reassembled output must use the .qzra extension" }
        require(!output.exists() || overwrite) { "Destination already exists: ${output.path}" }
        require(!Files.isSymbolicLink(output.toPath())) { "Output cannot be a symbolic link" }
        require(output.parentFile.mkdirs() || output.parentFile.isDirectory) { "Cannot create output directory" }
        val temporary = File(output.parentFile, ".${output.name}.${System.nanoTime()}.tmp")
        val archiveDigest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileOutputStream(temporary).use { merged ->
                for (index in descriptor.parts.indices) {
                    val expectedName = "${descriptor.archive_name}.part${(index + 1).toString().padStart(5, '0')}"
                    val part = descriptor.parts[index]
                    require(part.name == expectedName) { "Split part names are invalid or out of order" }
                    require(part.sha256.matches(Regex("[0-9a-f]{64}"))) { "Split part digest is malformed" }
                    val expectedSize = minOf(descriptor.part_size, descriptor.archive_size - index.toLong() * descriptor.part_size)
                    require(part.size == expectedSize) { "Split part size is inconsistent" }
                    val partFile = File(partsRoot, expectedName)
                    require(partFile.parentFile.canonicalFile == partsRoot.canonicalFile && !Files.isSymbolicLink(partFile.toPath()) && partFile.isFile) {
                        "Split part is missing or unsafe: $expectedName"
                    }
                    val partDigest = MessageDigest.getInstance("SHA-256")
                    var partTotal = 0L
                    FileInputStream(partFile).use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            merged.write(buffer, 0, count)
                            partDigest.update(buffer, 0, count)
                            archiveDigest.update(buffer, 0, count)
                            partTotal += count
                            total += count
                            require(partTotal <= expectedSize && total <= descriptor.archive_size) { "Split part exceeds its declared size" }
                        }
                    }
                    require(partTotal == expectedSize && partDigest.digest().toHex() == part.sha256) {
                        "Split part integrity check failed: $expectedName"
                    }
                }
                merged.fd.sync()
            }
            val digest = archiveDigest.digest().toHex()
            require(total == descriptor.archive_size && digest == descriptor.archive_sha256) {
                "Reassembled archive SHA-256 does not match the split-set descriptor"
            }
            RandomAccessFile(temporary, "r").use { input ->
                require(input.length() >= HEADER_SIZE)
                val signature = ByteArray(magic.size)
                input.readFully(signature)
                require(signature.contentEquals(magic)) { "Reassembled data is not a QZRA archive" }
            }
            moveOutput(temporary, output, overwrite)
            return JoinResult(output, descriptor.parts.size, total, digest, true)
        } finally {
            temporary.delete()
        }
    }

    private fun digestFile(file: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return digest.digest().toHex() to size
    }

    private fun ensureNoSymlinkComponents(path: File) {
        var current: File? = path.absoluteFile
        while (current != null) {
            val component = current
            require(!Files.isSymbolicLink(component.toPath())) { "Path cannot contain a symbolic-link component: ${component.path}" }
            current = component.parentFile
        }
    }

    private fun moveNew(source: File, target: File) {
        // Do not use ATOMIC_MOVE here: some providers may replace an existing target
        // despite the absence of REPLACE_EXISTING when ATOMIC_MOVE is requested.
        Files.move(source.toPath(), target.toPath())
    }

    private fun moveOutput(source: File, target: File, overwrite: Boolean) {
        if (overwrite) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        else Files.move(source.toPath(), target.toPath())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
