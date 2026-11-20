package org.qamzor.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.qamzor.core.ArchiveEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

class VaultItem(val id: String, val name: String, val kind: String, val size: Long, val files: Int, val addedAt: Long)

class VaultPasswordException : Exception("كلمة مرور الخزنة غير صحيحة")

/**
 * Private encrypted storage for photos, videos and documents.
 *
 * Layout (all inside the app's private directory, never visible to other apps or file managers):
 *   vault/index.qzra       QZRA 1.3 archive (hidden names, AES-256-GCM) holding index.json
 *   vault/items/<id>.qzra  one QZRA 1.3 archive per stored file or folder, same password
 *
 * Every file is therefore protected by the QAMZOR engine itself: scrypt-derived key, per-chunk authenticated
 * encryption and SHA-256 verification, with original file names hidden.
 */
object SecureVault {
    private const val INDEX_NAME = "index.qzra"
    private const val INDEX_ENTRY = "index.json"
    private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
    const val MIN_PASSWORD_LENGTH = 10

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff")
    private val VIDEO = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "flv", "wmv")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")
    private val DOCUMENT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "csv", "md", "epub")

    fun root(context: Context): File = File(context.filesDir, "vault").also { it.mkdirs() }
    private fun itemsDir(context: Context): File = File(root(context), "items").also { it.mkdirs() }
    private fun indexFile(context: Context): File = File(root(context), INDEX_NAME)

    fun isInitialized(context: Context): Boolean = indexFile(context).isFile

    fun classify(file: File): String {
        if (file.isDirectory) return "folder"
        return when (file.extension.lowercase()) {
            in IMAGE -> "image"
            in VIDEO -> "video"
            in AUDIO -> "audio"
            in DOCUMENT -> "document"
            else -> "other"
        }
    }

    fun kindLabel(kind: String): String = when (kind) {
        "image" -> "صورة"
        "video" -> "فيديو"
        "audio" -> "صوت"
        "document" -> "مستند"
        "folder" -> "مجلد"
        else -> "ملف"
    }

    fun kindGlyph(kind: String): String = when (kind) {
        "image" -> "🖼"
        "video" -> "🎞"
        "audio" -> "🎵"
        "document" -> "📄"
        "folder" -> "📁"
        else -> "📦"
    }

    /** Creates an empty vault protected by [password]. */
    fun create(context: Context, password: String): List<VaultItem> {
        require(password.length >= MIN_PASSWORD_LENGTH) { "كلمة مرور الخزنة يجب ألا تقل عن $MIN_PASSWORD_LENGTH أحرف" }
        check(!isInitialized(context)) { "الخزنة موجودة بالفعل" }
        writeIndex(context, password, emptyList())
        return emptyList()
    }

    /** Unlocks the vault; throws [VaultPasswordException] for a wrong password. */
    fun open(context: Context, password: String): List<VaultItem> = readIndex(context, password)

    /**
     * Encrypts each of [sources] into its own item. With [deleteOriginals] a source is removed only after its
     * encrypted copy was verified end to end. Returns the new full item list.
     */
    fun add(context: Context, password: String, sources: List<File>, deleteOriginals: Boolean): List<VaultItem> {
        var items = readIndex(context, password)
        for (source in sources) {
            if (!source.exists()) continue
            val id = randomId()
            val archive = File(itemsDir(context), "$id.qzra")
            try {
                val result = ArchiveEngine.create(archive = archive, sources = listOf(source), password = password, encryptMetadata = true)
                val verified = ArchiveEngine.verify(archive, password)
                check(verified.valid && verified.filesChecked == result.files) { "فشل التحقق من النسخة المشفّرة لـ ${source.name}" }
                items = items + VaultItem(id, source.name, classify(source), result.originalBytes, result.files, System.currentTimeMillis())
                writeIndex(context, password, items)
            } catch (error: Throwable) {
                archive.delete()
                throw error
            }
            if (deleteOriginals) {
                if (source.isDirectory) source.deleteRecursively() else source.delete()
            }
        }
        return items
    }

    /** Extracts [item] to the private cache so a viewer app can open it; call [purgeOpened] when done. */
    fun openForView(context: Context, password: String, item: VaultItem): File {
        val target = File(context.cacheDir, "vault-open/${item.id}")
        target.deleteRecursively()
        target.mkdirs()
        try {
            ArchiveEngine.extract(archiveOf(context, item), target, false, password)
        } catch (error: Exception) {
            target.deleteRecursively()
            throw translate(error)
        }
        return File(target, item.name)
    }

    /** Decrypts [item] into [destination], choosing a free name if one is taken. Returns the created file or folder. */
    fun export(context: Context, password: String, item: VaultItem, destination: File): File {
        val staging = File(context.cacheDir, "vault-export/${item.id}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ArchiveEngine.extract(archiveOf(context, item), staging, false, password)
            val extracted = File(staging, item.name)
            check(extracted.exists()) { "العنصر غير موجود داخل الخزنة" }
            destination.mkdirs()
            val target = uniqueTarget(destination, item.name)
            try {
                Files.move(extracted.toPath(), target.toPath())
            } catch (_: Exception) {
                extracted.copyRecursively(target, overwrite = false)
            }
            return target
        } catch (error: Exception) {
            throw translate(error)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(context: Context, password: String, item: VaultItem): List<VaultItem> {
        val remaining = readIndex(context, password).filter { it.id != item.id }
        writeIndex(context, password, remaining)
        archiveOf(context, item).delete()
        return remaining
    }

    fun purgeOpened(context: Context) {
        File(context.cacheDir, "vault-open").deleteRecursively()
        File(context.cacheDir, "vault-export").deleteRecursively()
        File(context.cacheDir, "vault-index").deleteRecursively()
        File(context.cacheDir, "vault-pick").deleteRecursively()
    }

    private fun archiveOf(context: Context, item: VaultItem): File {
        require(Regex("[0-9a-f]{32}").matches(item.id)) { "معرّف عنصر غير صالح" }
        return File(itemsDir(context), "${item.id}.qzra")
    }

    private fun readIndex(context: Context, password: String): List<VaultItem> {
        val index = indexFile(context)
        if (!index.isFile) return emptyList()
        val preview = try {
            ArchiveEngine.preview(index, INDEX_ENTRY, MAX_INDEX_BYTES, password)
        } catch (error: Exception) {
            throw translate(error)
        }
        check(!preview.truncated) { "فهرس الخزنة كبير جدًا" }
        val root = JSONObject(String(preview.data, Charsets.UTF_8))
        val array = root.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<VaultItem>(array.length())
        for (position in 0 until array.length()) {
            val entry = array.getJSONObject(position)
            items += VaultItem(
                entry.getString("id"), entry.getString("name"), entry.optString("kind", "other"),
                entry.optLong("size", 0L), entry.optInt("files", 1), entry.optLong("added", 0L)
            )
        }
        return items
    }

    private fun writeIndex(context: Context, password: String, items: List<VaultItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind)
                .put("size", item.size).put("files", item.files).put("added", item.addedAt))
        }
        val staging = File(context.cacheDir, "vault-index").also { it.deleteRecursively(); it.mkdirs() }
        val json = File(staging, INDEX_ENTRY)
        try {
            json.writeText(JSONObject().put("v", 1).put("items", array).toString(), Charsets.UTF_8)
            ArchiveEngine.create(archive = indexFile(context), sources = listOf(json), overwrite = true, password = password, encryptMetadata = true)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun translate(error: Exception): Exception {
        val message = error.message.orEmpty()
        return if (message.contains("password", ignoreCase = true) || message.contains("authentication", ignoreCase = true)) {
            VaultPasswordException()
        } else error
    }

    private fun uniqueTarget(directory: File, name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($counter)$extension")
            counter++
        }
        return candidate
    }

    private fun randomId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
