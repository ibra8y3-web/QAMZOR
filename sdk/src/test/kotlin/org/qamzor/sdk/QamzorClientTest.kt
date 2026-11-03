package org.qamzor.sdk

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QamzorClientTest {
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
