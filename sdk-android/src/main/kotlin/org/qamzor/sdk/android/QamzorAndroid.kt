package org.qamzor.sdk.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.qamzor.javasdk.ArchiveReader
import org.qamzor.javasdk.CreateResult
import org.qamzor.javasdk.Qamzor
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Storage Access Framework bridge for the QAMZOR SDK.
 *
 * The QZRA engine works on [File]s, so content URIs are staged in the app's private cache and
 * removed again on [close]. Every method performs blocking disk and cryptographic work (scrypt
 * needs ~32 MiB): call them from a background thread, never the main thread.
 */
class QamzorAndroid(context: Context) {
    private val appContext = context.applicationContext
    private val stagingRoot: File get() = File(appContext.cacheDir, "qamzor-sdk").also { it.mkdirs() }

    /** A staged copy of an archive (or any set of source files). Delete it with [close]. */
    class Staged internal constructor(val directory: File) : Closeable {
        override fun close() { directory.deleteRecursively() }
    }

    /** An archive copied from a content URI. Configure passwords through [reader]. */
    class OpenArchive internal constructor(private val staged: Staged, val file: File) : Closeable {
        val reader: ArchiveReader = Qamzor.open(file)
        override fun close() { staged.close() }
    }

    /** Copies the archive behind [uri] into private cache and opens it. */
    fun openArchive(uri: Uri): OpenArchive {
        val staged = newStaging()
        try {
            val target = File(staged.directory, "archive.qzra")
            copyUriTo(uri, target)
            return OpenArchive(staged, target)
        } catch (error: Throwable) {
            staged.close()
            throw error
        }
    }

    /**
     * Extracts [archive] (all entries, or only [selectedPaths]) into the SAF folder [treeUri].
     * Content is fully extracted and verified in cache first; nothing is written to [treeUri] if
     * verification fails. Existing files in the destination are never overwritten.
     * Returns the number of files written.
     */
    fun extractToTree(archive: OpenArchive, treeUri: Uri, selectedPaths: Set<String>? = null): Int {
        val temporary = File(stagingRoot, "extract-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val extracted = if (selectedPaths == null) {
                archive.reader.extractAll(temporary, false)
                temporary.walkTopDown().toList()
            } else {
                archive.reader.extract(selectedPaths, temporary, false)
            }
            val tree = DocumentFile.fromTreeUri(appContext, treeUri)
                ?: throw IllegalArgumentException("Cannot access the destination folder")
            val root = temporary.toPath()
            val directories = extracted.filter { it.isDirectory && it != temporary }.sortedBy { it.toPath().nameCount }
            for (directory in directories) {
                var parent = tree
                for (part in relative(root, directory).split('/')) parent = ensureDirectory(parent, part)
            }
            var written = 0
            for (file in extracted.filter { it.isFile }) {
                val parts = relative(root, file).split('/')
                var parent = tree
                for (part in parts.dropLast(1)) parent = ensureDirectory(parent, part)
                require(parent.findFile(parts.last()) == null) { "Destination already contains ${parts.last()}" }
                val target = parent.createFile(mimeFor(file.extension), parts.last())
                    ?: throw IllegalStateException("Cannot create ${parts.last()}")
                val output = appContext.contentResolver.openOutputStream(target.uri, "w")
                    ?: throw IllegalStateException("Cannot open ${parts.last()} for writing")
                output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                written++
            }
            return written
        } finally {
            temporary.deleteRecursively()
        }
    }

    /**
     * Creates an archive from SAF documents and/or folders and writes it to [outputUri] (for
     * example the result of ACTION_CREATE_DOCUMENT). Sources are staged in cache first.
     */
    fun createArchive(
        sources: List<Uri>,
        outputUri: Uri,
        password: String? = null,
        encryptMetadata: Boolean = false,
        folderPasswords: Map<String, String> = emptyMap(),
    ): CreateResult {
        val staged = newStaging()
        try {
            val input = File(staged.directory, "input").apply { mkdirs() }
            val stagedFiles = sources.map { stageSource(it, input) }
            val archiveFile = File(staged.directory, "output.qzra")
            val builder = Qamzor.create(archiveFile, stagedFiles).password(password).encryptMetadata(encryptMetadata)
            for ((folder, secret) in folderPasswords) builder.folderPassword(folder, secret)
            val result = builder.execute()
            val output = appContext.contentResolver.openOutputStream(outputUri, "w")
                ?: throw IllegalStateException("Cannot open the output document for writing")
            output.use { stream -> archiveFile.inputStream().use { it.copyTo(stream) } }
            return result
        } finally {
            staged.close()
        }
    }

    // ---- internals ----
    private fun newStaging(): Staged = Staged(File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() })

    private fun copyUriTo(uri: Uri, target: File) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot read the selected document")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
    }

    /** Stages a document or a whole folder tree under [parent] and returns the staged top-level File. */
    private fun stageSource(uri: Uri, parent: File): File {
        val tree = if (isTree(uri)) DocumentFile.fromTreeUri(appContext, uri) else null
        val document = tree ?: DocumentFile.fromSingleUri(appContext, uri)
            ?: throw IllegalArgumentException("Cannot access the selected source")
        val name = safeName(document.name ?: "item-${UUID.randomUUID()}")
        val target = File(parent, name)
        require(!target.exists()) { "Two sources share the name $name" }
        if (document.isDirectory) stageDirectory(document, target) else copyUriTo(document.uri, target)
        return target
    }

    private fun stageDirectory(directory: DocumentFile, target: File) {
        require(target.mkdirs() || target.isDirectory) { "Cannot stage ${target.name}" }
        for (child in directory.listFiles()) {
            val childName = safeName(child.name ?: continue)
            val childTarget = File(target, childName)
            if (child.isDirectory) stageDirectory(child, childTarget) else copyUriTo(child.uri, childTarget)
        }
    }

    private fun isTree(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == "tree"

    private fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            require(existing.isDirectory) { "A file already uses the folder name $name" }
            return existing
        }
        return parent.createDirectory(name) ?: throw IllegalStateException("Cannot create folder $name")
    }

    private fun relative(root: java.nio.file.Path, file: File): String =
        root.relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun safeName(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').trim()
        require(cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") { "Unsafe document name" }
        return cleaned
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "txt", "md", "csv", "log" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
