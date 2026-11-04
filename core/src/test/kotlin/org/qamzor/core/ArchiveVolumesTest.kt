package org.qamzor.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveVolumesTest {
    @Test
    fun splitsAndReassemblesQzraWithPythonCompatibleDescriptorAndChecksums() {
        val root = Files.createTempDirectory("qamzor-volumes-test").toFile()
        try {
            val source = File(root, "source.bin")
            val randomish = ByteArray(2_400_000).also { Random(420).nextBytes(it) }
            source.writeBytes(randomish)
            val originalArchive = File(root, "large.qzra")
            ArchiveEngine.create(originalArchive, listOf(source))
            val before = originalArchive.readBytes()

            val split = ArchiveVolumes.split(originalArchive, root, 1024L * 1024L)
            assertTrue(split.setFile.isFile)
            assertTrue(split.partsDirectory.isDirectory)
            assertEquals(3, split.partCount)
            val descriptorText = split.setFile.readText()
            assertTrue(descriptorText.contains("QZRA-PART-SET"))
            assertTrue(descriptorText.contains("\"version\":2"))

            val output = File(root, "joined.qzra")
            val joined = ArchiveVolumes.join(split.setFile, output)
            assertTrue(joined.valid)
            assertEquals(split.partCount, joined.partCount)
            assertEquals(before.size.toLong(), joined.archiveSize)
            assertEquals(before.toList(), output.readBytes().toList())
            assertTrue(ArchiveEngine.verify(output).valid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptedPartAndDoesNotPublishPartialArchive() {
        val root = Files.createTempDirectory("qamzor-volumes-corrupt-test").toFile()
        try {
            val source = File(root, "payload.bin").apply {
                writeBytes(ByteArray(1_800_000).also { Random(421).nextBytes(it) })
            }
            val archive = File(root, "payload.qzra")
            ArchiveEngine.create(archive, listOf(source))
            val split = ArchiveVolumes.split(archive, root, 1024L * 1024L)
            val firstPart = File(split.partsDirectory, "payload.qzra.part00001")
            RandomAccessFile(firstPart, "rw").use { file ->
                file.seek(12)
                file.write(file.read().let { it xor 0x5a })
            }

            val output = File(root, "must-not-exist.qzra")
            assertFailsWith<IllegalArgumentException> { ArchiveVolumes.join(split.setFile, output) }
            assertTrue(!output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesToReplaceAnExistingSplitDescriptorOrOutputByDefault() {
        val root = Files.createTempDirectory("qamzor-volumes-overwrite-test").toFile()
        try {
            val source = File(root, "notes.txt").apply { writeText("QAMZOR") }
            val archive = File(root, "notes.qzra")
            ArchiveEngine.create(archive, listOf(source))
            val split = ArchiveVolumes.split(archive, root, 1024L * 1024L)
            assertFailsWith<IllegalArgumentException> { ArchiveVolumes.split(archive, root, 1024L * 1024L) }
            val existing = File(root, "existing.qzra").apply { writeText("keep") }
            assertFailsWith<IllegalArgumentException> { ArchiveVolumes.join(split.setFile, existing) }
            assertEquals("keep", existing.readText())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun reportsAuthenticatedArchiveFormatMetadataForProtectedV13() {
        val root = Files.createTempDirectory("qamzor-inspect-test").toFile()
        try {
            val source = File(root, "private.txt").apply { writeText("private metadata") }
            val archive = File(root, "private.qzra")
            ArchiveEngine.create(archive, listOf(source), password = "correct horse battery staple", encryptMetadata = true)

            assertFailsWith<IllegalArgumentException> { ArchiveEngine.inspect(archive) }
            val info = ArchiveEngine.inspect(archive, "correct horse battery staple")
            assertEquals("1.3", info.version)
            assertEquals(1, info.entries)
            assertEquals(1, info.files)
            assertEquals(0, info.directories)
            assertEquals(source.length(), info.uncompressedBytes)
            assertTrue(info.encrypted)
            assertTrue(info.encryptedMetadata)
        } finally {
            root.deleteRecursively()
        }
    }

}
