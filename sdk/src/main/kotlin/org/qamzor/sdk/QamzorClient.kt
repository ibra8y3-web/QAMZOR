package org.qamzor.sdk

import org.qamzor.core.ArchiveEngine
import java.io.File

/** Public JVM SDK facade. API is pre-1.0 and may change before a stable release. */
class QamzorClient {
    companion object {
        const val SDK_VERSION = "0.2.0-dev"
        const val WRITER_FORMAT = "QZRA 1.1"
        val READABLE_FORMATS = setOf("QZRA 1.0", "QZRA 1.1")
    }

    fun createArchive(destination: File, sources: List<File>, overwrite: Boolean = false): ArchiveEngine.CreateResult =
        ArchiveEngine.create(destination, sources, overwrite)

    /** Losslessly package ordinary files/folders in QAMZOR's native .qzra container. */
    fun convertToQzra(destination: File, inputs: List<File>, overwrite: Boolean = false): ArchiveEngine.CreateResult =
        ArchiveEngine.convertToQzra(destination, inputs, overwrite)

    fun listEntries(archive: File): List<ArchiveEngine.EntryInfo> = ArchiveEngine.list(archive)

    fun verifyArchive(archive: File): ArchiveEngine.VerifyResult = ArchiveEngine.verify(archive)

    fun extractArchive(archive: File, destinationDirectory: File, overwrite: Boolean = false): List<File> =
        ArchiveEngine.extract(archive, destinationDirectory, overwrite)
}
