package org.qamzor.core

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Finds files with identical *content* regardless of their names, locations or dates.
 *
 * Candidates are narrowed in three exact stages so large libraries stay fast without ever guessing:
 * 1. equal byte size, 2. equal SHA-256 of the first and last 64 KiB, 3. equal SHA-256 of the whole file.
 * Only stage 3 decides; two files are reported as duplicates only when their full SHA-256 matches.
 * Symbolic links are never followed and unreadable files are skipped and counted.
 */
object DuplicateScanner {
    private const val SAMPLE = 64 * 1024
    private const val IO_SIZE = 256 * 1024

    class Group(val size: Long, val sha256: String, val files: List<File>) {
        /** Space freed by keeping one copy and removing the rest. */
        val reclaimableBytes: Long get() = size * (files.size - 1)
    }

    class Result(
        val groups: List<Group>,
        val filesScanned: Long,
        val bytesHashed: Long,
        val skipped: Long
    ) {
        val reclaimableBytes: Long get() = groups.sumOf { it.reclaimableBytes }
        val duplicateFiles: Int get() = groups.sumOf { it.files.size - 1 }
    }

    @JvmStatic
    @JvmOverloads
    fun scan(
        roots: List<File>,
        minSize: Long = 1L,
        accept: ((File) -> Boolean)? = null,
        progress: ProgressListener? = null
    ): Result {
        var skipped = 0L
        var scanned = 0L
        val bySize = HashMap<Long, MutableList<File>>()
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.exists()) continue
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (progress?.isCancelled() == true) throw OperationCancelledException()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (progress?.isCancelled() == true) throw OperationCancelledException()
                    if (attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() >= minSize) {
                        val item = file.toFile()
                        if ((accept == null || accept(item)) && seen.add(item.absolutePath)) {
                            scanned++
                            bySize.getOrPut(attrs.size()) { ArrayList() }.add(item)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    skipped++
                    return FileVisitResult.CONTINUE
                }
            })
        }

        val candidates = bySize.filter { it.value.size > 1 }
        progress?.onPhase("duplicates", candidates.entries.sumOf { it.key * it.value.size })
        var hashed = 0L
        val confirmed = ArrayList<Group>()
        for ((size, sameSize) in candidates) {
            // Stage 2: sampled fingerprint.
            val bySample = HashMap<String, MutableList<File>>()
            for (file in sameSize) {
                if (progress?.isCancelled() == true) throw OperationCancelledException()
                val fingerprint = try { sampleHash(file, size) } catch (_: IOException) { null }
                if (fingerprint == null) {
                    skipped++
                    progress?.onBytes(size)
                } else {
                    bySample.getOrPut(fingerprint) { ArrayList() }.add(file)
                }
            }
            for (sampled in bySample.values) {
                if (sampled.size < 2) {
                    progress?.onBytes(size * sampled.size)
                    continue
                }
                // Stage 3: full-content SHA-256 decides.
                val byFull = HashMap<String, MutableList<File>>()
                for (file in sampled) {
                    val digest = try { fullHash(file, progress) } catch (_: IOException) { null }
                    if (digest == null) {
                        skipped++
                    } else {
                        hashed += size
                        byFull.getOrPut(digest) { ArrayList() }.add(file)
                    }
                }
                for ((digest, same) in byFull) {
                    if (same.size > 1) {
                        val ordered = same.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })
                        confirmed += Group(size, digest, ordered)
                    }
                }
            }
        }
        confirmed.sortByDescending { it.reclaimableBytes }
        return Result(confirmed, scanned, hashed, skipped)
    }

    private fun sampleHash(file: File, size: Long): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(SAMPLE)
            val head = readUpTo(input, buffer)
            digest.update(buffer, 0, head)
            if (size > SAMPLE.toLong() * 2) {
                input.channel.position(size - SAMPLE)
                val tail = readUpTo(input, buffer)
                digest.update(buffer, 0, tail)
            }
        }
        return digest.digest().toHexString()
    }

    private fun fullHash(file: File, progress: ProgressListener?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(IO_SIZE)
            while (true) {
                if (progress?.isCancelled() == true) throw OperationCancelledException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                progress?.onBytes(count.toLong())
            }
        }
        return digest.digest().toHexString()
    }

    private fun readUpTo(input: FileInputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return read
    }

    private fun ByteArray.toHexString(): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            builder.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return builder.toString()
    }
}
