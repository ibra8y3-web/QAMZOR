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

    @Test fun sdkCreatesPasswordProtectedQzra12Archive() {
        val root = Files.createTempDirectory("qamzor-sdk-encrypted-test").toFile()
        try {
            val source = File(root, "secret.txt").apply { writeText("SDK encrypted archive") }
            val archive = File(root, "secret.qzra")
            val client = QamzorClient()
            val result = client.convertToQzra(archive, listOf(source), password = "correct horse battery staple")
            assertEquals("1.2", client.verifyArchive(result.archive, "correct horse battery staple").version)
            assertTrue(client.listEntries(archive, "correct horse battery staple").any { it.path == "secret.txt" })
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
    @Test fun sdkComparesVerifiedSha256Content() {
        val root = Files.createTempDirectory("qamzor-sdk-compare-test").toFile()
        try {
            val leftSource = File(root, "left.txt").apply { writeText("AAAA") }
            val rightSource = File(root, "right.txt").apply { writeText("BBBB") }
            val left = File(root, "left.qzra")
            val right = File(root, "right.qzra")
            val client = QamzorClient()
            client.createArchive(left, listOf(leftSource))
            client.createArchive(right, listOf(rightSource))
            val comparison = client.compareArchives(left, right)
            assertTrue(comparison.contentVerified)
            assertEquals(listOf("right.txt"), comparison.added)
            assertEquals(listOf("left.txt"), comparison.removed)
        } finally { root.deleteRecursively() }
    }

}
