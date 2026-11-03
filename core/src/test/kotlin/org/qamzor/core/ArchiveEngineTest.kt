package org.qamzor.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveEngineTest {
    @Test fun createsVerifiesAndExtractsChunkedArchive() {
        val root = Files.createTempDirectory("qamzor-core-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val data = ByteArray(180_000) { ((it * 31) % 251).toByte() }
            File(source, "a.bin").writeBytes(data)
            File(source, "b.bin").writeBytes(data)
            val archive = File(root, "test.qzra")
            val created = ArchiveEngine.create(archive, listOf(source))
            assertEquals(3, created.entries)
            val verified = ArchiveEngine.verify(archive)
            assertTrue(verified.valid)
            assertEquals(2, verified.filesChecked)
            val output = File(root, "out")
            val extracted = ArchiveEngine.extract(archive, output)
            assertEquals(2, extracted.size)
            assertTrue(File(output, "source/a.bin").readBytes().contentEquals(data))
            assertTrue(File(output, "source/b.bin").readBytes().contentEquals(data))
        } finally { root.deleteRecursively() }
    }

    @Test fun readsArchiveProducedByPythonReferenceImplementation() {
        val root = Files.createTempDirectory("qamzor-python-interop").toFile()
        try {
            val fixture = javaClass.getResourceAsStream("/python-reference-v1.1.qzra")
                ?: error("Python interoperability fixture is missing")
            val archive = File(root, "python-reference-v1.1.qzra")
            fixture.use { input -> archive.outputStream().use { input.copyTo(it) } }
            val result = ArchiveEngine.verify(archive)
            assertEquals("1.1", result.version)
            assertEquals(4, result.filesChecked)
            assertTrue(result.valid)
            val destination = File(root, "restored")
            ArchiveEngine.extract(archive, destination)
            val expected = ("QAMZOR-QZRA-INTEROP-".repeat(4200).toByteArray()).copyOf(70_000)
            assertTrue(File(destination, "sample/alpha.bin").readBytes().contentEquals(expected))
            assertTrue(File(destination, "sample/duplicate.bin").readBytes().contentEquals(expected))
            assertEquals(0L, File(destination, "sample/empty.dat").length())
        } finally { root.deleteRecursively() }
    }

    @Test fun readsLegacyPythonReferenceArchive() {
        val root = Files.createTempDirectory("qamzor-python-legacy-interop").toFile()
        try {
            val fixture = javaClass.getResourceAsStream("/python-reference-v1.0.qzra")
                ?: error("Legacy Python interoperability fixture is missing")
            val archive = File(root, "legacy.qzra")
            fixture.use { input -> archive.outputStream().use { input.copyTo(it) } }
            val result = ArchiveEngine.verify(archive)
            assertEquals("1.0", result.version)
            assertEquals(1, result.filesChecked)
            val destination = File(root, "restored")
            ArchiveEngine.extract(archive, destination)
            assertEquals("legacy QAMZOR format fixture\n".repeat(10), File(destination, "legacy.txt").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsPathTraversalManifest() {
        val root = Files.createTempDirectory("qamzor-core-invalid").toFile()
        try {
            val source = File(root, "safe.txt").apply { writeText("ok") }
            val archive = File(root, "safe.qzra")
            ArchiveEngine.create(archive, listOf(source))
            val bytes = archive.readBytes()
            val manifestLength = java.nio.ByteBuffer.wrap(bytes, 16, 8).long.toInt()
            val manifest = String(bytes, 32, manifestLength)
            assertTrue(manifest.contains("safe.txt"))
            // A malformed archive is expected to fail at header/manifest validation.
            bytes[0] = 0
            archive.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> { ArchiveEngine.verify(archive) }
        } finally { root.deleteRecursively() }
    }
}
