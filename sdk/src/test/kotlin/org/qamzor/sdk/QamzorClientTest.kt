package org.qamzor.sdk

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QamzorClientTest {
    @Test fun sdkConvertsMixedSourcesToQzraContainer() {
        val root = Files.createTempDirectory("qamzor-sdk-convert-test").toFile()
        try {
            val file = File(root, "document.txt").apply { writeText("keep original bytes") }
            val folder = File(root, "assets/sub").apply { mkdirs() }
            File(folder, "image.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
            val destination = File(root, "converted.qzra")
            val client = QamzorClient()
            val result = client.convertToQzra(destination, listOf(file, File(root, "assets")))
            assertTrue(client.verifyArchive(result.archive).valid)
            assertEquals("keep original bytes", file.readText())
            assertTrue(client.listEntries(destination).any { it.path == "assets/sub/image.bin" })
        } finally { root.deleteRecursively() }
    }

    @Test fun publicFacadeCreatesAndVerifiesArchive() {
        val root = Files.createTempDirectory("qamzor-sdk-test").toFile()
        try {
            val source = File(root, "hello.txt").apply { writeText("QAMZOR SDK integration test") }
            val archive = File(root, "hello.qzra")
            val client = QamzorClient()
            val created = client.createArchive(archive, listOf(source))
            val verification = client.verifyArchive(created.archive)
            assertTrue(verification.valid)
            assertEquals(1, verification.filesChecked)
            assertEquals("hello.txt", client.listEntries(archive).single().path)
        } finally { root.deleteRecursively() }
    }
}
