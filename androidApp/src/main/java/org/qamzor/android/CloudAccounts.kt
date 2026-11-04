package org.qamzor.android

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.Locale
import java.util.Base64

internal data class QamzorCloudAccount(
    val id: String,
    val name: String,
    val collectionUrl: String,
    val username: String
)

/** Account metadata is non-secret; the password is stored only in AndroidSecretVault. */
internal object QamzorCloudAccounts {
    private const val PREFS = "qamzor-cloud-accounts"
    private const val KEY = "accounts"

    fun list(context: Context): List<QamzorCloudAccount> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            QamzorCloudAccount(item.optString("id"), item.optString("name"), item.optString("url"), item.optString("username"))
                .takeIf { it.id.matches(Regex("[0-9a-f]{32}")) && it.name.isNotBlank() && it.collectionUrl.isNotBlank() }
        }
    }

    fun save(context: Context, name: String, collectionUrl: String, username: String, password: String): QamzorCloudAccount {
        require(name.isNotBlank() && name.length <= 80) { "Enter a valid account name" }
        val parsed = java.net.URI(collectionUrl.trim())
        require(parsed.scheme.equals("https", true) && parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
            "WebDAV account requires an HTTPS collection URL without embedded credentials, query or fragment"
        }
        require(username.isNotBlank() && ':' !in username && username.none { it.isISOControl() } && password.isNotEmpty()) {
            "Username and password are required; username cannot contain a colon or control characters"
        }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val account = QamzorCloudAccount(id, name.trim(), collectionUrl.trim().trimEnd('/'), username.trim())
        AndroidSecretVault.put(context, "cloud-$id", password.toByteArray(Charsets.UTF_8))
        val current = list(context).toMutableList().apply { add(account) }
        persist(context, current)
        return account
    }

    fun remove(context: Context, id: String) {
        require(id.matches(Regex("[0-9a-f]{32}")))
        persist(context, list(context).filterNot { it.id == id })
        AndroidSecretVault.remove(context, "cloud-$id")
    }

    fun password(context: Context, id: String): String {
        val bytes = AndroidSecretVault.get(context, "cloud-$id")
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    fun find(context: Context, id: String): QamzorCloudAccount = list(context).firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Cloud account no longer exists")

    private fun persist(context: Context, accounts: List<QamzorCloudAccount>) {
        val array = JSONArray()
        accounts.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)
            .put("url", it.collectionUrl).put("username", it.username)) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit()) {
            "Could not save cloud account settings"
        }
    }
}

internal data class QamzorRemoteArchive(
    val name: String,
    val size: Long,
    val sha256: String,
    val uploadedAt: Long
)

internal data class QamzorCloudSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val unchanged: Int,
    val conflicts: List<String>
)

/** HTTPS WebDAV transfer with post-upload/download SHA-256 verification. */
internal class QamzorWebDavClient(
    private val account: QamzorCloudAccount,
    password: String,
    private val timeoutMs: Int = 60_000
) {
    private val authorization = "Basic " + Base64.getEncoder().encodeToString("${account.username}:$password".toByteArray(Charsets.UTF_8))
    private val baseUrl = account.collectionUrl.trimEnd('/')

    init {
        require(timeoutMs in 5_000..300_000)
        val uri = java.net.URI(baseUrl)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(!uri.path.split('/').any { it == "." || it == ".." }) { "Unsafe WebDAV collection path" }
    }

    fun testConnection(): Int {
        val connection = open("PROPFIND", baseUrl + "/")
        return try {
            connection.setRequestProperty("Depth", "0")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            require(status == 207 || status in 200..299) { "WebDAV connection test failed (HTTP $status)" }
            status
        } finally { connection.disconnect() }
    }

    fun listArchives(): List<QamzorRemoteArchive> {
        val connection = open("PROPFIND", "$baseUrl/")
        try {
            connection.setRequestProperty("Depth", "1")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            require(status == 207 || status in 200..299) { "WebDAV folder listing failed (HTTP $status)" }
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
                setInput(connection.inputStream, "UTF-8")
            }
            val names = LinkedHashSet<String>()
            var href: String? = null
            var isCollection = false
            var event = parser.eventType
            val baseUri = java.net.URI("$baseUrl/")
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                        "response" -> { href = null; isCollection = false }
                        "href" -> href = parser.nextText()
                        "collection" -> isCollection = true
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("response", true)) {
                        val value = href
                        if (!isCollection && !value.isNullOrBlank()) {
                            runCatching {
                                val remoteUri = baseUri.resolve(value)
                                val expectedParent = baseUri.path.trimEnd('/')
                                val actualParent = remoteUri.path.substringBeforeLast('/', "")
                                val name = Uri.decode(remoteUri.path.substringAfterLast('/'))
                                require(remoteUri.scheme == baseUri.scheme && remoteUri.host == baseUri.host)
                                require(actualParent == expectedParent)
                                require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.qzra", RegexOption.IGNORE_CASE)))
                                names.add(name)
                            }
                        }
                    }
                }
                event = parser.next()
            }
            require(names.size <= 5000) { "WebDAV folder contains too many QZRA archives to list safely" }
            return names.mapNotNull { name -> readMetadata(name) }.sortedByDescending { it.uploadedAt }
        } finally { connection.disconnect() }
    }

    fun push(archive: File, onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }): Map<String, String> {
        require(archive.isFile && archive.length() >= 32) { "Select an existing QZRA archive" }
        require(archive.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.qzra", RegexOption.IGNORE_CASE))) {
            "Archive name must use portable characters and end in .qzra"
        }
        val size = archive.length()
        val digest = digest(archive)
        val existingMetadata = readMetadata(archive.name)
        if (existingMetadata != null) {
            require(existingMetadata.size == size && existingMetadata.sha256 == digest) {
                "A different archive with this name already exists remotely. Rename the local archive to keep both versions; QAMZOR will not overwrite it."
            }
            verifyRemote(archive.name, size, digest) { received -> onProgress("verify", received, size) }
            return mapOf("name" to archive.name, "size" to size.toString(), "sha256" to digest, "result" to "already-identical")
        }
        val remoteStatus = headStatus(archive.name)
        require(remoteStatus == 404) {
            if (remoteStatus in 200..299) "A remote object with this name already exists without valid QAMZOR metadata; it will not be overwritten."
            else "The WebDAV server could not safely confirm that the destination name is unused (HTTP $remoteStatus)."
        }
        try {
            putFile(archive.name, archive, size) { sent -> onProgress("upload", sent, size) }
            val metadata = JSONObject().put("format", "QAMZOR-CLOUD-OBJECT").put("version", 1)
                .put("provider", "webdav").put("name", archive.name).put("size", size)
                .put("sha256", digest).put("uploaded_at", System.currentTimeMillis()).toString() + "\n"
            putBytes(archive.name + ".qamzor.json", metadata.toByteArray(Charsets.UTF_8), "application/json")
            verifyRemote(archive.name, size, digest) { received -> onProgress("verify", received, size) }
        } catch (failure: Exception) {
            // This object did not exist before the upload. Best-effort cleanup prevents a partial orphan.
            runCatching { deleteRemote(archive.name + ".qamzor.json") }
            runCatching { deleteRemote(archive.name) }
            throw failure
        }
        return mapOf("name" to archive.name, "size" to size.toString(), "sha256" to digest, "result" to "uploaded")
    }

    /**
     * Safe archive-folder sync: uploads local-only QZRA files, downloads remote-only files,
     * skips identical objects, and never overwrites/deletes conflicting user data.
     */
    fun sync(localDirectory: File, onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }): QamzorCloudSyncResult {
        require(localDirectory.isDirectory && localDirectory.canRead() && localDirectory.canWrite() &&
            !Files.isSymbolicLink(localDirectory.toPath())) {
            "Choose a readable, writable, non-symbolic local folder for cloud sync"
        }
        val localFiles = localDirectory.listFiles()?.filter { it.isFile && it.extension.equals("qzra", true) && !Files.isSymbolicLink(it.toPath()) }
            ?.associateBy { it.name } ?: emptyMap()
        val remoteFiles = listArchives().associateBy { it.name }
        val remoteOnly = remoteFiles.filterKeys { it !in localFiles }
        val localBytes = localFiles.values.sumOf { it.length().coerceAtLeast(0L) }
        val remoteOnlyBytes = remoteOnly.values.sumOf { it.size }
        val totalBytes = (localBytes + remoteOnlyBytes).coerceAtLeast(1L)
        var completedBytes = 0L
        var uploaded = 0
        var downloaded = 0
        var unchanged = 0
        val conflicts = ArrayList<String>()
        for ((name, local) in localFiles) {
            val remote = remoteFiles[name]
            when {
                remote == null -> {
                    push(local) { phase, sent, _ -> onProgress("sync-upload-$phase", completedBytes + sent, totalBytes) }
                    uploaded++
                }
                remote.size == local.length() && remote.sha256 == digest(local) -> unchanged++
                else -> conflicts += name
            }
            completedBytes += local.length().coerceAtLeast(0L)
            onProgress("sync-local", completedBytes, totalBytes)
        }
        for ((name, remote) in remoteOnly) {
            val destination = File(localDirectory, name)
            if (destination.exists()) {
                conflicts += name
                completedBytes += remote.size
                continue
            }
            pull(name, destination) { received, _ -> onProgress("sync-download", completedBytes + received, totalBytes) }
            downloaded++
            completedBytes += remote.size
            onProgress("sync-remote", completedBytes, totalBytes)
        }
        return QamzorCloudSyncResult(uploaded, downloaded, unchanged, conflicts.distinct().sorted())
    }

    fun pull(name: String, destination: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.qzra", RegexOption.IGNORE_CASE))) {
            "Invalid remote QZRA name"
        }
        val metadata = readMetadata(name) ?: throw IllegalArgumentException("QAMZOR metadata is missing for $name")
        val parent = destination.absoluteFile.parentFile ?: error("Invalid download destination")
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create cloud download folder" }
        require(!destination.exists() && !Files.isSymbolicLink(parent.toPath())) { "Download destination already exists or is unsafe" }
        val temporary = File.createTempFile(".qamzor-cloud-", ".part", parent)
        try {
            val connection = open("GET", target(name))
            try {
                val status = connection.responseCode
                require(status == 200) { "WebDAV download failed (HTTP $status)" }
                val md = MessageDigest.getInstance("SHA-256")
                var received = 0L
                connection.inputStream.use { input -> temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        md.update(buffer, 0, count)
                        received += count
                        require(received <= metadata.size) { "Remote archive exceeds its signed metadata size" }
                        onProgress(received, metadata.size)
                    }
                    output.flush()
                } }
                val actualDigest = md.digest().joinToString("") { "%02x".format(it) }
                require(received == metadata.size && actualDigest == metadata.sha256) {
                    "Downloaded cloud archive failed SHA-256/size verification"
                }
            } finally { connection.disconnect() }
            require(!destination.exists()) { "Download destination appeared during transfer" }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath())
            }
            return destination
        } finally { temporary.delete() }
    }

    private fun readMetadata(name: String): QamzorRemoteArchive? {
        val connection = open("GET", target(name + ".qamzor.json"))
        try {
            val status = connection.responseCode
            if (status == 404) return null
            require(status == 200) { "WebDAV metadata request failed (HTTP $status) for $name" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 16 * 1024) { "Cloud metadata file is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.optString("format") == "QAMZOR-CLOUD-OBJECT" && json.optInt("version") == 1 &&
                json.optString("provider") == "webdav" && json.optString("name") == name) {
                "Unsupported or mismatched QAMZOR cloud metadata"
            }
            val size = json.getLong("size")
            val sha = json.getString("sha256")
            val uploadedAt = json.optLong("uploaded_at", 0L)
            require(size >= 32 && sha.matches(Regex("[0-9a-f]{64}")) && uploadedAt > 0) { "Invalid QAMZOR cloud metadata" }
            return QamzorRemoteArchive(name, size, sha, uploadedAt)
        } finally { connection.disconnect() }
    }

    private fun headStatus(name: String): Int {
        val connection = open("HEAD", target(name))
        return try { connection.responseCode } finally { connection.disconnect() }
    }

    private fun deleteRemote(name: String) {
        val connection = open("DELETE", target(name))
        try {
            val status = connection.responseCode
            require(status == 404 || status in 200..299) { "WebDAV cleanup failed (HTTP $status)" }
        } finally { connection.disconnect() }
    }

    private fun putFile(name: String, source: File, size: Long, progress: (Long) -> Unit) {
        val connection = open("PUT", target(name))
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/vnd.qamzor.qzra")
            connection.setFixedLengthStreamingMode(size)
            var sent = 0L
            FileInputStream(source).use { input -> connection.outputStream.use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    sent += count
                    progress(sent)
                }
                output.flush()
            } }
            val status = connection.responseCode
            require(status in 200..299) { "WebDAV archive upload failed (HTTP $status)" }
        } finally { connection.disconnect() }
    }

    private fun putBytes(name: String, bytes: ByteArray, contentType: String) {
        val connection = open("PUT", target(name))
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            require(status in 200..299) { "WebDAV metadata upload failed (HTTP $status)" }
        } finally { connection.disconnect() }
    }

    /** Re-download into a streaming digest only; never write a second plaintext archive to disk. */
    private fun verifyRemote(name: String, expectedSize: Long, expectedDigest: String, onProgress: (Long) -> Unit) {
        val connection = open("GET", target(name))
        try {
            val status = connection.responseCode
            require(status == 200) { "WebDAV verification download failed (HTTP $status)" }
            val md = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                    received += count
                    onProgress(received)
                }
            }
            val actualDigest = md.digest().joinToString("") { "%02x".format(it) }
            require(received == expectedSize && actualDigest == expectedDigest) {
                "Cloud object verification failed after upload; remote file may be incomplete or changed"
            }
        } finally { connection.disconnect() }
    }

    private fun open(method: String, url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", authorization)
        connection.setRequestProperty("User-Agent", "QAMZOR-Android/0.9.0")
        connection.useCaches = false
        return connection
    }

    private fun target(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,240}")) && name != "." && name != "..")
        return "$baseUrl/${Uri.encode(name, "-_.~")}" 
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
