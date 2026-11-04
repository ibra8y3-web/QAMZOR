package org.qamzor.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InPlaceVaultTest {
    private val password = "1000020000"

    private fun makeFolder(root: File): File {
        val folder = File(root, "ابراهيم").apply { mkdirs() }
        File(folder, "notes.txt").writeText("private notes\n".repeat(500))
        File(folder, "data.bin").writeBytes(ByteArray(150_000) { ((it * 31) % 251).toByte() })
        File(folder, "empty/nested").mkdirs()
        return folder
    }

    @Test fun encryptedSealReplacesFolderAndHidesItsContents() {
        val root = Files.createTempDirectory("qamzor-vault-test").toFile()
        try {
            val folder = makeFolder(root)
            val expected = File(folder, "data.bin").readBytes()
            val result = InPlaceVault.seal(folder, password)

            assertFalse(folder.exists(), "the original folder must be gone")
            assertTrue(result.archive.isFile)
            assertEquals(File(root, "ابراهيم.qzra"), result.archive)
            assertTrue(result.encrypted)
            assertTrue(InPlaceVault.isEncrypted(result.archive))
            assertEquals(2, result.files)

            assertFailsWith<IllegalArgumentException> { ArchiveEngine.browse(result.archive) }
            assertFailsWith<Exception> { ArchiveEngine.browse(result.archive, "", "wrong password!!") }
            assertEquals(listOf("ابراهيم"), ArchiveEngine.browse(result.archive, "", password).map { it.name })
            val bytes = result.archive.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("notes.txt"), "names must not appear in clear text")

            val restored = InPlaceVault.restore(result.archive, password, removeArchive = true)
            assertEquals(1, restored.size)
            assertFalse(result.archive.exists())
            assertContentEquals(expected, File(restored[0], "data.bin").readBytes())
            assertTrue(File(restored[0], "empty/nested").isDirectory)
        } finally { root.deleteRecursively() }
    }

    @Test fun plainSealWorksForSingleFileAndRestoreKeepsArchiveWhenAsked() {
        val root = Files.createTempDirectory("qamzor-vault-plain").toFile()
        try {
            val file = File(root, "report.pdf").apply { writeBytes(ByteArray(70_000) { (it % 200).toByte() }) }
            val expected = file.readBytes()
            val result = InPlaceVault.seal(file, null)
            assertFalse(file.exists())
            assertFalse(result.encrypted)
            assertFalse(InPlaceVault.isEncrypted(result.archive))
            assertEquals("report.pdf.qzra", result.archive.name)

            val restored = InPlaceVault.restore(result.archive, null, removeArchive = false)
            assertTrue(result.archive.exists())
            assertContentEquals(expected, restored[0].readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun failureLeavesTheOriginalUntouched() {
        val root = Files.createTempDirectory("qamzor-vault-fail").toFile()
        try {
            val folder = makeFolder(root)
            assertFailsWith<IllegalArgumentException> { InPlaceVault.seal(File(root, "missing"), password) }
            assertFailsWith<IllegalArgumentException> { InPlaceVault.seal(folder, "") }
            assertTrue(File(folder, "notes.txt").isFile)

            val sealed = InPlaceVault.seal(folder, password, deleteOriginal = false)
            assertTrue(folder.exists(), "deleteOriginal=false must keep the source")
            assertTrue(sealed.archive.isFile)
            val second = InPlaceVault.seal(folder, password, deleteOriginal = false)
            assertEquals("ابراهيم (2).qzra", second.archive.name)
        } finally { root.deleteRecursively() }
    }

    @Test fun protectedFolderSealAndRestoreRequireIndependentPassword() {
        val root = Files.createTempDirectory("qamzor-vault-scopes").toFile()
        try {
            val folder = File(root, "project").apply { mkdirs() }
            File(folder, "public.txt").writeText("public data")
            File(folder, "private").mkdirs()
            val expected = "secret project data".repeat(2500).toByteArray()
            File(folder, "private/secret.txt").writeBytes(expected)

            val sealed = InPlaceVault.seal(
                folder,
                password = password,
                folderPasswords = mapOf("project/private" to "independent-secret")
            )
            assertTrue(sealed.archive.isFile)
            assertEquals("1.3", ArchiveEngine.inspect(sealed.archive, password).version)
            assertEquals(listOf("project/private"), ArchiveEngine.protectedFolders(sealed.archive, password))
            assertFailsWith<IllegalStateException> { InPlaceVault.restore(sealed.archive, password, removeArchive = false) }
            assertFailsWith<Exception> {
                InPlaceVault.restore(
                    sealed.archive, password, removeArchive = false,
                    folderPasswords = mapOf("project/private" to "wrong-independent-password")
                )
            }
            assertTrue(sealed.archive.isFile, "a wrong scoped password must not alter or delete the archive")

            val restored = InPlaceVault.restore(
                sealed.archive, password, removeArchive = false,
                folderPasswords = mapOf("project/private" to "independent-secret")
            )
            assertTrue(sealed.archive.isFile)
            assertContentEquals(expected, File(restored.single(), "private/secret.txt").readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun protectedFolderPasswordsCannotBeUsedWithoutMasterPassword() {
        val root = Files.createTempDirectory("qamzor-vault-scopes-invalid").toFile()
        try {
            val folder = File(root, "project/private").apply { mkdirs() }
            File(folder, "secret.txt").writeText("secret")
            assertFailsWith<IllegalArgumentException> {
                InPlaceVault.seal(File(root, "project"), null, deleteOriginal = false,
                    folderPasswords = mapOf("project/private" to "independent-secret"))
            }
            assertTrue(File(folder, "secret.txt").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun restoreWithWrongPasswordChangesNothing() {
        val root = Files.createTempDirectory("qamzor-vault-wrong").toFile()
        try {
            val result = InPlaceVault.seal(makeFolder(root), password)
            assertFailsWith<Exception> { InPlaceVault.restore(result.archive, "not the password", removeArchive = true) }
            assertTrue(result.archive.isFile, "archive must survive a failed restore")
            assertEquals(listOf("ابراهيم.qzra"), root.list()!!.toList().filterNot { it.startsWith(".") })
        } finally { root.deleteRecursively() }
    }
    @Test fun encryptionDetectionChecksTheQzraSignature() {
        val root = Files.createTempDirectory("qamzor-vault-signature").toFile()
        try {
            val fake = File(root, "not-an-archive.bin")
            fake.writeBytes(ByteArray(32).also {
                it[8] = 0; it[9] = 1; it[10] = 0; it[11] = 3
            })
            assertFalse(InPlaceVault.isEncrypted(fake), "random files must not be misidentified as encrypted QZRA archives")
        } finally { root.deleteRecursively() }
    }

}
