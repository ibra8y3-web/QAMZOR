package org.qamzor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ArchiveSignaturesTest {
    @TempDir lateinit var temp: Path

    @Test fun signAndVerifyUsesInteroperableEd25519Envelope() {
        val source = temp.resolve("payload.txt").toFile().apply { writeText("signed QZRA payload") }
        val archive = temp.resolve("payload.qzra").toFile()
        ArchiveEngine.create(archive, listOf(source))
        val pair = ArchiveSignatures.generateKeyPair()
        val signature = File(temp.toFile(), "payload.qzra.qzsig")
        val result = ArchiveSignatures.signArchive(archive, pair.privateKeyPkcs8, pair.publicKeyX509, signature)
        assertEquals(archive.length(), result.archiveSize)
        assertTrue(ArchiveSignatures.verifyArchive(archive, pair.publicKeyX509, signature).archiveSha256.isNotBlank())
    }

    @Test fun modifiedArchiveFailsSignatureVerification() {
        val source = temp.resolve("payload.txt").toFile().apply { writeText("signed") }
        val archive = temp.resolve("payload.qzra").toFile()
        ArchiveEngine.create(archive, listOf(source))
        val pair = ArchiveSignatures.generateKeyPair()
        val signature = temp.resolve("payload.qzra.qzsig").toFile()
        ArchiveSignatures.signArchive(archive, pair.privateKeyPkcs8, pair.publicKeyX509, signature)
        archive.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSignatures.verifyArchive(archive, pair.publicKeyX509, signature)
        }
    }

    @Test fun publicPemRoundTrips() {
        val pair = ArchiveSignatures.generateKeyPair()
        val file = temp.resolve("public.pem").toFile()
        file.writeText(ArchiveSignatures.publicKeyPem(pair.publicKeyX509))
        assertArrayEquals(pair.publicKeyX509, ArchiveSignatures.parsePublicKey(file))
    }
}
