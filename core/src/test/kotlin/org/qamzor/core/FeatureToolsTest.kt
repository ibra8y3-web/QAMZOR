package org.qamzor.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureToolsTest {
    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    /** Incompressible content so the archive is large enough to span several parts/blocks. */
    private fun makeArchive(root: File, bytes: Int, name: String = "sample.qzra"): File {
        val source = File(root, "payload.bin")
        source.writeBytes(ByteArray(bytes).also { Random(42).nextBytes(it) })
        val archive = File(root, name)
        ArchiveEngine.convertToQzra(archive, listOf(source))
        return archive
    }

    private class Recorder(private val stopAfter: Long = Long.MAX_VALUE) : ProgressListener {
        var total = 0L
        var done = 0L
        var phases = ArrayList<String>()
        override fun onPhase(phase: String, totalBytes: Long) { phases.add(phase); total = totalBytes; done = 0L }
        override fun onBytes(deltaBytes: Long) { done += deltaBytes }
        override fun isCancelled(): Boolean = done >= stopAfter
    }

    @Test fun splitAndJoinRoundTripProducesIdenticalArchive() {
        val root = tempDir("qamzor-split")
        try {
            val archive = makeArchive(root, 2_500_000)
            val result = QzraVolumes.split(archive, File(root, "out"), partSize = 1024L * 1024L)
            assertEquals(3, result.partFiles.size)
            assertTrue(result.descriptor.name.endsWith(".qzset"))
            val joined = File(root, "joined.qzra")
            val back = QzraVolumes.join(result.descriptor, joined)
            assertEquals(archive.length(), back.bytes)
            assertContentEquals(archive.readBytes(), joined.readBytes())
            assertTrue(ArchiveEngine.verify(joined).valid)
        } finally { root.deleteRecursively() }
    }

    @Test fun splitNameIsPortableForNonAsciiArchiveNames() {
        val root = tempDir("qamzor-split-name")
        try {
            val archive = makeArchive(root, 1_500_000, "أرشيف.qzra")
            val result = QzraVolumes.split(archive, File(root, "out"), partSize = 1024L * 1024L)
            assertTrue(Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.qzset$").matches(result.descriptor.name))
            val joined = File(root, "back.qzra")
            QzraVolumes.join(result.descriptor, joined)
            assertContentEquals(archive.readBytes(), joined.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun joinRejectsTamperedAndMissingParts() {
        val root = tempDir("qamzor-split-bad")
        try {
            val archive = makeArchive(root, 2_200_000)
            val result = QzraVolumes.split(archive, File(root, "out"), partSize = 1024L * 1024L)
            RandomAccessFile(result.partFiles[1], "rw").use { it.seek(10); it.write(0x55); it.write(0x66) }
            val target = File(root, "tampered.qzra")
            assertFailsWith<VolumeException> { QzraVolumes.join(result.descriptor, target) }
            assertFalse(target.exists())
            result.partFiles[2].delete()
            assertFailsWith<VolumeException> { QzraVolumes.join(result.descriptor, File(root, "missing.qzra")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun splitRefusesSmallPartsAndNonArchives() {
        val root = tempDir("qamzor-split-guard")
        try {
            val archive = makeArchive(root, 100_000)
            assertFailsWith<VolumeException> { QzraVolumes.split(archive, File(root, "out"), partSize = 1000L) }
            val fake = File(root, "fake.qzra").apply { writeBytes(ByteArray(4096)) }
            assertFailsWith<VolumeException> { QzraVolumes.split(fake, File(root, "out2"), partSize = 1024L * 1024L) }
        } finally { root.deleteRecursively() }
    }

    @Test fun recoveryRepairsOneDamagedBlockPerGroup() {
        val root = tempDir("qamzor-recovery")
        try {
            val archive = makeArchive(root, 900_000)
            val original = archive.readBytes()
            val sidecar = File(root, "sample.qzrp")
            val created = QzraRecovery.create(archive, sidecar, blockSize = 64 * 1024, groupSize = 4)
            assertTrue(created.blocks >= 8)
            assertTrue(QzraRecovery.check(archive, sidecar).damagedBlocks.isEmpty())

            // Damage block 1 (group 0) and block 6 (group 1): both are repairable.
            RandomAccessFile(archive, "rw").use {
                it.seek(64L * 1024 + 100); it.write(byteArrayOf(1, 2, 3, 4))
                it.seek(64L * 1024 * 6 + 5); it.write(byteArrayOf(9, 9, 9))
            }
            val check = QzraRecovery.check(archive, sidecar)
            assertEquals(listOf(1, 6), check.damagedBlocks)
            assertTrue(check.repairable)

            val repaired = File(root, "repaired.qzra")
            val result = QzraRecovery.repair(archive, sidecar, repaired)
            assertEquals(listOf(1, 6), result.repairedBlocks)
            assertContentEquals(original, repaired.readBytes())
            assertTrue(ArchiveEngine.verify(repaired).valid)
        } finally { root.deleteRecursively() }
    }

    @Test fun recoveryFailsHonestlyWhenTwoBlocksOfOneGroupAreDamaged() {
        val root = tempDir("qamzor-recovery-limit")
        try {
            val archive = makeArchive(root, 600_000)
            val sidecar = File(root, "sample.qzrp")
            QzraRecovery.create(archive, sidecar, blockSize = 64 * 1024, groupSize = 4)
            RandomAccessFile(archive, "rw").use {
                it.seek(10); it.write(7)
                it.seek(64L * 1024 + 10); it.write(7)
            }
            assertFalse(QzraRecovery.check(archive, sidecar).repairable)
            val output = File(root, "out.qzra")
            assertFailsWith<RecoveryException> { QzraRecovery.repair(archive, sidecar, output) }
            assertFalse(output.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun recoveryRebuildsATruncatedTailBlock() {
        val root = tempDir("qamzor-recovery-truncate")
        try {
            val archive = makeArchive(root, 400_000)
            val original = archive.readBytes()
            val sidecar = File(root, "sample.qzrp")
            QzraRecovery.create(archive, sidecar, blockSize = 64 * 1024, groupSize = 8)
            RandomAccessFile(archive, "rw").use { it.setLength(original.size - 3000L) }
            val repaired = File(root, "repaired.qzra")
            QzraRecovery.repair(archive, sidecar, repaired)
            assertContentEquals(original, repaired.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun duplicateScannerGroupsByContentIgnoringNames() {
        val root = tempDir("qamzor-dups")
        try {
            val data = ByteArray(300_000).also { Random(1).nextBytes(it) }
            File(root, "a/photo.jpg").apply { parentFile.mkdirs(); writeBytes(data) }
            File(root, "b/renamed-copy.bin").apply { parentFile.mkdirs(); writeBytes(data) }
            File(root, "c/another.dat").apply { parentFile.mkdirs(); writeBytes(data) }
            // Same size and same first/last 64 KiB, but different middle: must NOT be reported.
            val almost = data.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
            File(root, "c/almost.dat").writeBytes(almost)
            // Unique and tiny files.
            File(root, "unique.txt").writeText("only one")
            File(root, "empty1.txt").writeBytes(ByteArray(0))
            File(root, "empty2.txt").writeBytes(ByteArray(0))

            val result = DuplicateScanner.scan(listOf(root))
            assertEquals(1, result.groups.size)
            val group = result.groups[0]
            assertEquals(3, group.files.size)
            assertEquals(300_000L * 2, group.reclaimableBytes)
            assertEquals(300_000L * 2, result.reclaimableBytes)
            assertEquals(setOf("photo.jpg", "renamed-copy.bin", "another.dat"), group.files.map { it.name }.toSet())
        } finally { root.deleteRecursively() }
    }

    @Test fun duplicateScannerHonoursFilterAndMinimumSize() {
        val root = tempDir("qamzor-dups-filter")
        try {
            val data = ByteArray(5000) { (it % 251).toByte() }
            File(root, "x.png").writeBytes(data)
            File(root, "y.png").writeBytes(data)
            File(root, "x.txt").writeBytes(data)
            File(root, "y.txt").writeBytes(data)
            val onlyPng = DuplicateScanner.scan(listOf(root), accept = { it.extension == "png" })
            assertEquals(1, onlyPng.groups.size)
            assertEquals(2, onlyPng.groups[0].files.size)
            assertTrue(DuplicateScanner.scan(listOf(root), minSize = 10_000L).groups.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun progressReportsBytesAndCancellationLeavesNoPartialArchive() {
        val root = tempDir("qamzor-progress")
        try {
            val source = File(root, "big.bin").apply { writeBytes(ByteArray(3_000_000).also { Random(7).nextBytes(it) }) }
            val archive = File(root, "p.qzra")
            val recorder = Recorder()
            ArchiveEngine.withProgress(recorder) { ArchiveEngine.convertToQzra(archive, listOf(source)) }
            assertEquals("create", recorder.phases.first())
            assertEquals(3_000_000L, recorder.total)
            assertEquals(3_000_000L, recorder.done)
            ArchiveEngine.withProgress(recorder) { ArchiveEngine.verify(archive) }
            assertEquals("verify", recorder.phases.last())
            assertEquals(recorder.total, recorder.done)

            val cancelled = File(root, "cancelled.qzra")
            assertFailsWith<OperationCancelledException> {
                ArchiveEngine.withProgress(Recorder(stopAfter = 500_000L)) { ArchiveEngine.convertToQzra(cancelled, listOf(source)) }
            }
            assertFalse(cancelled.exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally { root.deleteRecursively() }
    }
}
