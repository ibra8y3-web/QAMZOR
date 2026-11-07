package org.qamzor.sdk

import org.qamzor.core.ArchiveEngine
import java.io.File

/** Public JVM SDK facade. API is pre-1.0 and may change before a stable release. */
class QamzorClient {
    companion object {
        const val SDK_VERSION = "0.7.2-dev"
        const val WRITER_FORMAT = "QZRA 1.1 / QZRA 1.2 encrypted / QZRA 1.3 private metadata and folder scopes"
        val READABLE_FORMATS = setOf("QZRA 1.0", "QZRA 1.1", "QZRA 1.2 encrypted", "QZRA 1.3 encrypted metadata and protected folders")
        const val QZRA_13_STATUS = "Implemented in Kotlin Core; verify cross-language CI before production use"
    }

    fun createArchive(
        destination: File,
        sources: List<File>,
        overwrite: Boolean = false,
        password: String? = null,
        encryptMetadata: Boolean = false,
        folderPasswords: Map<String, String> = emptyMap()
    ): ArchiveEngine.CreateResult = ArchiveEngine.create(destination, sources, overwrite, password, encryptMetadata, folderPasswords)

    /** Losslessly package ordinary files/folders in QAMZOR's native .qzra container.
     * Supplying a password writes authenticated AES-256-GCM QZRA 1.2. Set
     * encryptMetadata=true for private-metadata QZRA 1.3, and folderPasswords
     * to protect selected archive-relative folders with independent passwords.
     */
    fun convertToQzra(
        destination: File,
        inputs: List<File>,
        overwrite: Boolean = false,
        password: String? = null,
        encryptMetadata: Boolean = false,
        folderPasswords: Map<String, String> = emptyMap()
    ): ArchiveEngine.CreateResult = ArchiveEngine.convertToQzra(destination, inputs, overwrite, password, encryptMetadata, folderPasswords)

    fun listEntries(archive: File, password: String? = null, folderPasswords: Map<String, String> = emptyMap()): List<ArchiveEngine.EntryInfo> = ArchiveEngine.list(archive, password, folderPasswords)

    fun verifyArchive(archive: File, password: String? = null, folderPasswords: Map<String, String> = emptyMap()): ArchiveEngine.VerifyResult = ArchiveEngine.verify(archive, password, folderPasswords)

    fun compareArchives(
        left: File, right: File, leftPassword: String? = null, rightPassword: String? = null,
        leftFolderPasswords: Map<String, String> = emptyMap(), rightFolderPasswords: Map<String, String> = emptyMap()
    ): ArchiveEngine.CompareResult = ArchiveEngine.compareContent(left, right, leftPassword, rightPassword, leftFolderPasswords, rightFolderPasswords)

    fun extractArchive(archive: File, destinationDirectory: File, overwrite: Boolean = false, password: String? = null, folderPasswords: Map<String, String> = emptyMap()): List<File> =
        ArchiveEngine.extract(archive, destinationDirectory, overwrite, password, folderPasswords)
}
