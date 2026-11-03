package org.qamzor.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class ArchiveEngineTest {
    @Test fun convertsMixedFilesAndFoldersToNativeQzraWithoutChangingSources() {
        val root = Files.createTempDirectory("qamzor-convert-test").toFile()
        try {
            val single = File(root, "notes.txt").apply { writeText("native QZRA conversion\n") }
            val project = File(root, "Project Folder").apply { mkdirs() }
            val binary = ByteArray(96_000) { ((it * 19) % 251).toByte() }
            File(project, "data.bin").writeBytes(binary)
            File(project, "empty/nested").mkdirs()
            val expectedText = single.readText()
            val expectedBinary = File(project, "data.bin").readBytes()
            val archive = File(root, "converted.qzra")

            val result = ArchiveEngine.convertToQzra(archive, listOf(single, project))
            assertTrue(archive.isFile)
            assertEquals(5, result.entries)
            assertEquals(2, result.files)
            assertTrue(ArchiveEngine.verify(archive).valid)
            assertEquals(setOf("notes.txt", "Project Folder"), ArchiveEngine.browse(archive).map { it.name }.toSet())
            assertEquals("native QZRA conversion\n", ArchiveEngine.preview(archive, "notes.txt").data.toString(Charsets.UTF_8))
            assertTrue(ArchiveEngine.list(archive).any { it.path == "Project Folder/empty/nested" && it.type == "directory" })

            val restored = File(root, "restored")
            ArchiveEngine.extract(archive, restored)
            assertEquals(expectedText, single.readText())
            assertContentEquals(expectedBinary, File(restored, "Project Folder/data.bin").readBytes())
            assertEquals(expectedText, File(restored, "notes.txt").readText())
            assertTrue(File(restored, "Project Folder/empty/nested").isDirectory)
            assertFailsWith<IllegalArgumentException> {
                ArchiveEngine.convertToQzra(File(root, "converted.zip"), listOf(single))
            }
        } finally { root.deleteRecursively() }
    }

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


    @Test fun preservesCompleteFolderTreeIncludingEmptyDirectories() {
        val root = Files.createTempDirectory("qamzor-folder-tree-test").toFile()
        try {
            val source = File(root, "Project Folder").apply { mkdirs() }
            File(source, "README.md").writeText("root file")
            val nested = File(source, "src/main/kotlin").apply { mkdirs() }
            File(nested, "Main.kt").writeText("fun main() = Unit")
            File(source, "empty/sub-empty").mkdirs()
            val archive = File(root, "folder-tree.qzra")

            val created = ArchiveEngine.create(archive, listOf(source))
            assertEquals(8, created.entries)
            assertEquals(2, created.files)
            assertTrue(ArchiveEngine.verify(archive).valid)

            val destination = File(root, "restored")
            val extracted = ArchiveEngine.extract(archive, destination)
            assertEquals(2, extracted.size)
            assertEquals("root file", File(destination, "Project Folder/README.md").readText())
            assertEquals("fun main() = Unit", File(destination, "Project Folder/src/main/kotlin/Main.kt").readText())
            assertTrue(File(destination, "Project Folder/empty/sub-empty").isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun virtualExplorerBrowsesDirectoriesAndPreviewsFileWithoutExtraction() {
        val root = Files.createTempDirectory("qamzor-virtual-explorer-test").toFile()
        try {
            val source = File(root, "Project").apply { mkdirs() }
            File(source, "README.md").writeText("QAMZOR preview\nSecond line\n")
            File(source, "src/main/kotlin").apply { mkdirs() }
            File(source, "src/main/kotlin/Main.kt").writeText("fun main() = println(\"QAMZOR\")\n")
            File(source, "empty-directory").mkdirs()
            val archive = File(root, "virtual.qzra")
            ArchiveEngine.create(archive, listOf(source))

            val rootItems = ArchiveEngine.browse(archive)
            assertEquals(listOf("Project"), rootItems.map { it.name })
            assertEquals("directory", rootItems.single().type)
            val projectItems = ArchiveEngine.browse(archive, "Project")
            assertEquals(setOf("README.md", "empty-directory", "src"), projectItems.map { it.name }.toSet())
            assertEquals("directory", projectItems.first { it.name == "empty-directory" }.type)
            val srcItems = ArchiveEngine.browse(archive, "Project/src/main/kotlin")
            assertEquals(listOf("Main.kt"), srcItems.map { it.name })

            val preview = ArchiveEngine.preview(archive, "Project/README.md", 7)
            assertContentEquals("QAMZOR ".toByteArray(), preview.data)
            assertEquals(27L, preview.bytesTotal)
            assertTrue(preview.truncated)
            assertTrue(preview.verified)
            assertTrue(!File(root, "Project/README.md").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun extractsOnlySelectedFileOrDirectorySubtree() {
        val root = Files.createTempDirectory("qamzor-selective-extract-test").toFile()
        try {
            val source = File(root, "Project").apply { mkdirs() }
            File(source, "root.txt").writeText("root")
            File(source, "docs/nested").apply { mkdirs() }
            File(source, "docs/one.txt").writeText("one")
            File(source, "docs/nested/two.txt").writeText("two")
            File(source, "other.txt").writeText("other")
            val archive = File(root, "selective.qzra")
            ArchiveEngine.create(archive, listOf(source))

            val onlyFileRoot = File(root, "only-file")
            val one = ArchiveEngine.extractSelected(archive, onlyFileRoot, setOf("Project/root.txt"))
            assertEquals(1, one.size)
            assertEquals("root", File(onlyFileRoot, "Project/root.txt").readText())
            assertTrue(!File(onlyFileRoot, "Project/other.txt").exists())

            val subtreeRoot = File(root, "only-docs")
            val subtree = ArchiveEngine.extractSelected(archive, subtreeRoot, setOf("Project/docs"))
            assertTrue(File(subtreeRoot, "Project/docs/one.txt").isFile)
            assertEquals("two", File(subtreeRoot, "Project/docs/nested/two.txt").readText())
            assertTrue(!File(subtreeRoot, "Project/root.txt").exists())
            assertTrue(subtree.any { it.isDirectory && it.name == "nested" })
        } finally { root.deleteRecursively() }
    }

    @Test fun virtualExplorerReadsLegacyArchiveWithoutFullExtraction() {
        val root = Files.createTempDirectory("qamzor-legacy-preview-test").toFile()
        try {
            val fixture = javaClass.getResourceAsStream("/python-reference-v1.0.qzra")
                ?: error("Legacy Python interoperability fixture is missing")
            val archive = File(root, "legacy.qzra")
            fixture.use { input -> archive.outputStream().use { input.copyTo(it) } }
            val items = ArchiveEngine.browse(archive)
            assertTrue(items.any { it.path == "legacy.txt" && it.type == "file" })
            val preview = ArchiveEngine.preview(archive, "legacy.txt", 12)
            assertEquals("legacy QAMZOR", preview.data.toString(Charsets.UTF_8))
            assertTrue(preview.truncated)
            assertTrue(preview.verified)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsUnsafeSelectedExtractionAndEnforcesQuotasBeforeWriting() {
        val root = Files.createTempDirectory("qamzor-selected-safety-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "payload.txt").writeText("123456789")
            val archive = File(root, "safe.qzra")
            ArchiveEngine.create(archive, listOf(source))
            val blocked = File(root, "blocked")
            assertFailsWith<IllegalArgumentException> {
                ArchiveEngine.extractSelected(archive, blocked, setOf("source/payload.txt"), maxTotalBytes = 2)
            }
            assertTrue(!blocked.exists())
            assertFailsWith<IllegalArgumentException> {
                ArchiveEngine.extractSelected(archive, File(root, "bad"), setOf("../escape"))
            }
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

    @Test fun virtualExplorerReadsEncryptedPythonReferenceQzra12() {
        val root = Files.createTempDirectory("qamzor-encrypted-explorer-test").toFile()
        try {
            val fixture = javaClass.getResourceAsStream("/python-reference-v1.2-encrypted.qzra")
                ?: error("Encrypted QZRA 1.2 interoperability fixture is missing")
            val archive = File(root, "encrypted.qzra")
            fixture.use { input -> archive.outputStream().use { input.copyTo(it) } }

            assertFailsWith<IllegalArgumentException> { ArchiveEngine.browse(archive) }
            assertFailsWith<IllegalArgumentException> { ArchiveEngine.browse(archive, password = "wrong password") }
            val top = ArchiveEngine.browse(archive, password = "correct horse battery staple")
            assertEquals(listOf("secure"), top.map { it.name })
            val nested = ArchiveEngine.browse(archive, "secure/nested", "correct horse battery staple")
            assertEquals(listOf("data.json"), nested.map { it.name })

            val preview = ArchiveEngine.preview(
                archive, "secure/hello.txt", 26, "correct horse battery staple"
            )
            assertEquals("This is QZRA 1.2 encrypted", preview.data.toString(Charsets.UTF_8))
            assertEquals(49L, preview.bytesTotal)
            assertTrue(preview.truncated)
            assertTrue(preview.verified)

            val extracted = File(root, "selected")
            ArchiveEngine.extractSelected(
                archive, extracted, setOf("secure/nested"),
                password = "correct horse battery staple"
            )
            assertEquals("{\"qamzor\":true,\"edition\":\"encrypted\"}\n", File(extracted, "secure/nested/data.json").readText())
            assertTrue(!File(extracted, "secure/hello.txt").exists())
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
