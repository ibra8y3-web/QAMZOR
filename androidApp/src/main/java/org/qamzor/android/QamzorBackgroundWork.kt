package org.qamzor.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import org.qamzor.core.ArchiveEngine
import org.qamzor.core.InPlaceVault
import org.qamzor.core.SnapshotArchiveStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** WorkManager-backed tasks survive Activity destruction and are restored by WorkManager after reboot. */
internal object QamzorBackgroundWork {
    const val TAG_ALL = "qamzor-background"
    private const val CHANNEL_ID = "qamzor_background_tasks"
    private const val DESCRIPTOR_DIR = "qamzor-work-descriptors"

    private fun descriptorDir(context: Context) = File(context.filesDir, DESCRIPTOR_DIR).apply { mkdirs() }

    private fun saveDescriptor(context: Context, descriptor: JSONObject): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        val file = File(descriptorDir(context), "$id.json")
        val temporary = File(file.parentFile, ".$id.tmp")
        temporary.writeText(descriptor.toString(), Charsets.UTF_8)
        require(temporary.renameTo(file)) { "Could not save the QAMZOR operation descriptor" }
        return id to file
    }

    private fun saveSecrets(context: Context, json: JSONObject): String {
        val reference = UUID.randomUUID().toString().replace("-", "")
        AndroidSecretVault.put(context, reference, json.toString().toByteArray(Charsets.UTF_8))
        return reference
    }

    private fun request(context: Context, descriptor: JSONObject, secrets: JSONObject?, requiresNetwork: Boolean = false): UUID {
        val (id, file) = saveDescriptor(context, descriptor.put("jobId", "${System.currentTimeMillis()}-${UUID.randomUUID()}"))
        val secretRef = secrets?.let { saveSecrets(context, it) }
        val dataBuilder = Data.Builder().putString("descriptor_path", file.absolutePath).putString("job_id", id)
        if (secretRef != null) dataBuilder.putString("secret_ref", secretRef)
        val data = dataBuilder.build()
        val builder = OneTimeWorkRequestBuilder<QamzorArchiveWorker>()
            .setInputData(data).addTag(TAG_ALL).addTag("qamzor-$id")
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (requiresNetwork) builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        val work = builder.build()
        WorkManager.getInstance(context).enqueueUniqueWork("qamzor-$id", ExistingWorkPolicy.KEEP, work)
        return work.id
    }

    fun enqueueSeal(
        context: Context, sources: List<File>, masterPassword: String?,
        folderPasswordsBySource: Map<String, Map<String, String>>, deleteOriginal: Boolean
    ): UUID {
        require(sources.isNotEmpty()) { "Select at least one source" }
        val secret = JSONObject()
            .put("masterPassword", masterPassword ?: JSONObject.NULL)
            .put("folderPasswordsBySource", JSONObject().apply {
                folderPasswordsBySource.forEach { (source, scopes) -> put(source, JSONObject(scopes)) }
            })
        val descriptor = JSONObject().put("type", "SEAL").put("sources", JSONArray(sources.map { it.absolutePath }))
            .put("deleteOriginal", deleteOriginal)
        return request(context, descriptor, secret)
    }

    fun enqueueRestore(
        context: Context, archive: File, password: String?, folderPasswords: Map<String, String>, removeArchive: Boolean
    ): UUID {
        val secret = JSONObject().put("masterPassword", password ?: JSONObject.NULL)
            .put("folderPasswords", JSONObject(folderPasswords))
        val descriptor = JSONObject().put("type", "RESTORE").put("archive", archive.absolutePath).put("removeArchive", removeArchive)
        return request(context, descriptor, secret)
    }

    fun enqueueVerify(context: Context, archive: File, password: String?, folderPasswords: Map<String, String>): UUID {
        val secret = JSONObject().put("masterPassword", password ?: JSONObject.NULL)
            .put("folderPasswords", JSONObject(folderPasswords))
        val descriptor = JSONObject().put("type", "VERIFY").put("archive", archive.absolutePath)
        return request(context, descriptor, secret)
    }

    fun scheduleSnapshot(context: Context, source: File, destination: File, intervalDays: Long, password: String?): UUID {
        require(intervalDays in 1L..365L) { "Backup interval must be between 1 and 365 days" }
        require(source.exists() && source.canRead()) { "Backup source is not readable" }
        val identity = java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.canonicalPath.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val scheduleKey = "backup-secret-ref-$identity"
        val preferences = context.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE)
        val previousSecretRef = preferences.getString(scheduleKey, null)
        // Keep a currently running periodic worker's old secret valid until the replacement is stored.
        if (previousSecretRef != null) preferences.edit().putString("backup-secret-ref-orphan-$previousSecretRef", previousSecretRef).apply()
        val secretRef = if (password.isNullOrEmpty()) null else saveSecrets(context,
            JSONObject().put("masterPassword", password))
        val dataBuilder = Data.Builder().putString("type", "SNAPSHOT")
            .putString("source", source.absolutePath).putString("destination", destination.absolutePath)
            .putBoolean("periodic", true)
        if (secretRef != null) dataBuilder.putString("secret_ref", secretRef)
        val data = dataBuilder.build()
        val request = PeriodicWorkRequestBuilder<QamzorArchiveWorker>(intervalDays, TimeUnit.DAYS)
            .setInputData(data).addTag(TAG_ALL).addTag("qamzor-periodic-backup")
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "qamzor-periodic-backup-$identity", ExistingPeriodicWorkPolicy.UPDATE, request
        )
        preferences.edit().apply {
            if (secretRef == null) remove(scheduleKey) else putString(scheduleKey, secretRef)
        }.apply()
        return request.id
    }

    fun cancelAutomaticSnapshots(context: Context) {
        val preferences = context.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE)
        preferences.all.keys.filter { it.startsWith("backup-secret-ref-") || it.startsWith("backup-secret-ref-orphan-") }.forEach { key ->
            preferences.getString(key, null)?.let { AndroidSecretVault.remove(context, it) }
            preferences.edit().remove(key).apply()
        }
        WorkManager.getInstance(context).cancelAllWorkByTag("qamzor-periodic-backup")
    }

    fun enqueueSnapshot(context: Context, source: File, destination: File, password: String?): UUID {
        val secretRef = if (password.isNullOrEmpty()) null else saveSecrets(context,
            JSONObject().put("masterPassword", password))
        val dataBuilder = Data.Builder().putString("type", "SNAPSHOT")
            .putString("source", source.absolutePath).putString("destination", destination.absolutePath)
            .putBoolean("periodic", false)
        if (secretRef != null) dataBuilder.putString("secret_ref", secretRef)
        val data = dataBuilder.build()
        val request = OneTimeWorkRequestBuilder<QamzorArchiveWorker>().setInputData(data).addTag(TAG_ALL).build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }


    fun enqueueCloudPush(context: Context, archive: File, accountId: String): UUID {
        require(archive.isFile && archive.extension.equals("qzra", true)) { "Select an existing QZRA archive" }
        require(accountId.matches(Regex("[0-9a-f]{32}"))) { "Invalid cloud account" }
        val descriptor = JSONObject().put("type", "CLOUD_PUSH")
            .put("archive", archive.canonicalPath).put("accountId", accountId)
        return request(context, descriptor, null, requiresNetwork = true)
    }

    fun enqueueCloudSync(context: Context, accountId: String, localDirectory: File): UUID {
        require(accountId.matches(Regex("[0-9a-f]{32}"))) { "Invalid cloud account" }
        require(localDirectory.isDirectory && localDirectory.canRead() && localDirectory.canWrite()) {
            "Choose a readable and writable local folder"
        }
        val descriptor = JSONObject().put("type", "CLOUD_SYNC").put("accountId", accountId)
            .put("localDirectory", localDirectory.canonicalPath)
        return request(context, descriptor, null, requiresNetwork = true)
    }

    fun enqueueCloudPull(context: Context, accountId: String, remoteName: String, destination: File): UUID {
        require(accountId.matches(Regex("[0-9a-f]{32}"))) { "Invalid cloud account" }
        require(remoteName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.qzra", RegexOption.IGNORE_CASE))) {
            "Invalid remote archive name"
        }
        val descriptor = JSONObject().put("type", "CLOUD_PULL").put("accountId", accountId)
            .put("remoteName", remoteName).put("destination", destination.canonicalPath)
        return request(context, descriptor, null, requiresNetwork = true)
    }

    fun enqueueSnapshotRestore(context: Context, store: File, snapshotId: String, destination: File, password: String?): UUID {
        val descriptor = JSONObject().put("type", "SNAPSHOT_RESTORE").put("store", store.absolutePath)
            .put("snapshotId", snapshotId).put("destination", destination.absolutePath)
        val secret = password?.takeIf { it.isNotEmpty() }?.let { JSONObject().put("masterPassword", it) }
        return request(context, descriptor, secret)
    }

    fun enqueueSnapshotVerify(context: Context, store: File, snapshotId: String, password: String?): UUID {
        val descriptor = JSONObject().put("type", "SNAPSHOT_VERIFY").put("store", store.absolutePath).put("snapshotId", snapshotId)
        val secret = password?.takeIf { it.isNotEmpty() }?.let { JSONObject().put("masterPassword", it) }
        return request(context, descriptor, secret)
    }

    fun pause(context: Context, workId: UUID) {
        context.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE).edit().putBoolean("pause-$workId", true).apply()
    }

    fun resume(context: Context, workId: UUID) {
        context.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE).edit().putBoolean("pause-$workId", false).apply()
    }

    fun cancel(context: Context, workId: UUID) {
        WorkManager.getInstance(context).cancelWorkById(workId)
        context.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE).edit().remove("pause-$workId").apply()
    }

    internal fun notificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "عمليات QAMZOR الخلفية", NotificationManager.IMPORTANCE_LOW))
        }
    }

    internal fun channelId() = CHANNEL_ID
}

class QamzorArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val notificationId = (id.hashCode() and 0x7fffffff).coerceAtLeast(1)
    private var lastProgressAt = 0L
    private var lastProgressBytes = 0L
    private var lastSpeed = 0.0
    private var lastPhase: String? = null
    private val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        QamzorBackgroundWork.notificationChannel(applicationContext)
        setForeground(foregroundInfo("جارٍ تجهيز عملية QAMZOR", 0))
        val descriptorPath = inputData.getString("descriptor_path")
        val descriptor = if (descriptorPath != null) {
            val file = File(descriptorPath)
            require(file.isFile && file.canonicalPath.startsWith(File(applicationContext.filesDir, "qamzor-work-descriptors").canonicalPath + File.separator)) {
                "Invalid operation descriptor path"
            }
            JSONObject(file.readText(Charsets.UTF_8))
        } else JSONObject().apply {
            put("type", inputData.getString("type"))
            listOf("source", "destination", "store", "snapshotId", "archive", "remoteName", "localDirectory", "accountId")
                .forEach { key -> inputData.getString(key)?.let { put(key, it) } }
            listOf("periodic", "removeArchive").forEach { key ->
                if (inputData.keyValueMap.containsKey(key)) put(key, inputData.getBoolean(key, false))
            }
        }
        val secretRef = inputData.getString("secret_ref")
        val secrets = if (secretRef.isNullOrBlank()) JSONObject() else {
            val secretBytes = AndroidSecretVault.get(applicationContext, secretRef)
            try { JSONObject(String(secretBytes, Charsets.UTF_8)) } finally { secretBytes.fill(0) }
        }
        val password = secrets.optString("masterPassword").takeIf { it.isNotEmpty() && it != "null" }
        val folderPasswords = secrets.optJSONObject("folderPasswords")?.toStringMap() ?: emptyMap()
        return try {
            var output = Data.Builder().putString("result", "completed").build()
            when (descriptor.optString("type")) {
                "SEAL" -> runSeal(descriptor, secrets, password)
                "RESTORE" -> runRestore(descriptor, password, folderPasswords)
                "VERIFY" -> runVerify(descriptor, password, folderPasswords)
                "SNAPSHOT" -> runSnapshot(descriptor, password)
                "SNAPSHOT_RESTORE" -> runSnapshotRestore(descriptor, password)
                "SNAPSHOT_VERIFY" -> runSnapshotVerify(descriptor, password)
                "CLOUD_PUSH" -> runCloudPush(descriptor)
                "CLOUD_PULL" -> output = runCloudPull(descriptor)
                "CLOUD_SYNC" -> output = runCloudSync(descriptor)
                else -> throw IllegalArgumentException("Unsupported QAMZOR background operation")
            }
            notificationManager.cancel(notificationId)
            if (descriptorPath != null) File(descriptorPath).delete()
            applicationContext.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE).edit().remove("pause-$id").apply()
            if (!inputData.getBoolean("periodic", false)) secretRef?.let { AndroidSecretVault.remove(applicationContext, it) }
            Result.success(output)
        } catch (cancelled: InterruptedException) {
            if (isStopped) {
                if (!inputData.getBoolean("periodic", false)) secretRef?.let { AndroidSecretVault.remove(applicationContext, it) }
                if (descriptorPath != null) File(descriptorPath).delete()
                applicationContext.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE).edit().remove("pause-$id").apply()
                Result.failure(workDataOfSafe("result" to "cancelled"))
            } else Result.retry()
        } catch (error: Exception) {
            notificationManager.cancel(notificationId)
            if (error is IOException && !isStopped) return Result.retry()
            if (!inputData.getBoolean("periodic", false)) secretRef?.let { AndroidSecretVault.remove(applicationContext, it) }
            if (descriptorPath != null) File(descriptorPath).delete()
            applicationContext.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE).edit().remove("pause-$id").apply()
            Result.failure(workDataOfSafe("error" to (error.message ?: error.javaClass.simpleName)))
        }
    }

    private suspend fun runSeal(descriptor: JSONObject, secrets: JSONObject, password: String?) {
        val sourcesJson = descriptor.getJSONArray("sources")
        val sources = (0 until sourcesJson.length()).map { File(sourcesJson.getString(it)) }
        val bySource = secrets.optJSONObject("folderPasswordsBySource") ?: JSONObject()
        val total = sources.sumOf { treeBytes(it) }.coerceAtLeast(1L)
        var base = 0L
        for (source in sources) {
            checkpoint()
            val sourceBytes = treeBytes(source)
            val scope = bySource.optJSONObject(source.absolutePath)?.toStringMap() ?: emptyMap()
            InPlaceVault.seal(source, password, descriptor.optBoolean("deleteOriginal", true), scope,
                onProgress = { progress -> publish(progress, base, total) }, isCancelled = { isStopped })
            base += sourceBytes
        }
    }

    private suspend fun runRestore(descriptor: JSONObject, password: String?, folderPasswords: Map<String, String>) {
        val archive = File(descriptor.getString("archive"))
        val restored = InPlaceVault.restore(archive, password, descriptor.optBoolean("removeArchive", false), folderPasswords,
            onProgress = { progress -> publish(progress, 0L, progress.totalBytes.coerceAtLeast(1L)) },
            isCancelled = { isStopped })
        setProgressAsync(workDataOfSafe("phase" to "completed", "restored_items" to restored.size))
    }

    private suspend fun runVerify(descriptor: JSONObject, password: String?, folderPasswords: Map<String, String>) {
        val result = ArchiveEngine.verify(File(descriptor.getString("archive")), password, folderPasswords,
            onProgress = { progress -> publish(progress, 0L, progress.totalBytes.coerceAtLeast(1L)) },
            isCancelled = { isStopped })
        require(result.valid) { "Archive verification failed" }
    }

    private suspend fun runSnapshot(descriptor: JSONObject, password: String?) {
        val source = File(descriptor.getString("source"))
        val destination = File(descriptor.getString("destination"))
        val store = SnapshotArchiveStore(destination)
        val snapshot = store.createSnapshot(source, password,
            onProgress = { progress -> publish(progress, 0L, progress.totalBytes.coerceAtLeast(1L)) },
            isCancelled = { isStopped })
        val keepLast = applicationContext.getSharedPreferences("qamzor-settings", Context.MODE_PRIVATE)
            .getInt("backup_retention", 30).coerceIn(1, 1000)
        val pruned = store.prune(keepLast)
        setProgressAsync(workDataOfSafe("phase" to "snapshot-saved", "snapshot_id" to snapshot.id,
            "snapshot_file" to snapshot.archiveName, "pruned_versions" to pruned))
    }

    private suspend fun runSnapshotRestore(descriptor: JSONObject, password: String?) {
        val destination = File(descriptor.getString("destination"))
        val restored = SnapshotArchiveStore(File(descriptor.getString("store"))).restoreSnapshot(
            descriptor.getString("snapshotId"), destination, password = password,
            onProgress = { progress -> publish(progress, 0L, progress.totalBytes.coerceAtLeast(1L)) },
            isCancelled = { isStopped })
        setProgressAsync(workDataOfSafe("phase" to "snapshot-restored", "restored_items" to restored.size))
    }

    private suspend fun runSnapshotVerify(descriptor: JSONObject, password: String?) {
        val verified = SnapshotArchiveStore(File(descriptor.getString("store"))).verifySnapshot(
            descriptor.getString("snapshotId"), password,
            onProgress = { progress -> publish(progress, 0L, progress.totalBytes.coerceAtLeast(1L)) },
            isCancelled = { isStopped }
        )
        require(verified.valid) { "Snapshot integrity verification failed" }
    }

    private suspend fun runCloudPush(descriptor: JSONObject) {
        val accountId = descriptor.getString("accountId")
        val account = QamzorCloudAccounts.find(applicationContext, accountId)
        val passwordBytes = AndroidSecretVault.get(applicationContext, "cloud-$accountId")
        val password = try { String(passwordBytes, Charsets.UTF_8) } finally { passwordBytes.fill(0) }
        val archive = File(descriptor.getString("archive"))
        require(archive.isFile && archive.extension.equals("qzra", true)) { "QZRA archive is missing" }
        val total = archive.length().coerceAtLeast(1L)
        val result = QamzorWebDavClient(account, password).push(archive) { phase, sent, expected ->
            publish(
                ArchiveEngine.OperationProgress(if (phase == "verify") "cloud-verify" else "cloud-upload", sent, expected.coerceAtLeast(1L), 0, 1),
                0L,
                total
            )
        }
        setProgressAsync(workDataOfSafe("phase" to "cloud-verified", "remote_name" to (result["name"] ?: archive.name),
            "sha256" to (result["sha256"] ?: ""), "uploaded_bytes" to total))
    }

    private suspend fun runCloudPull(descriptor: JSONObject): Data {
        val accountId = descriptor.getString("accountId")
        val account = QamzorCloudAccounts.find(applicationContext, accountId)
        val passwordBytes = AndroidSecretVault.get(applicationContext, "cloud-$accountId")
        val password = try { String(passwordBytes, Charsets.UTF_8) } finally { passwordBytes.fill(0) }
        val destination = File(descriptor.getString("destination"))
        val remoteName = descriptor.getString("remoteName")
        val result = QamzorWebDavClient(account, password).pull(remoteName, destination) { received, total ->
            publish(
                ArchiveEngine.OperationProgress("cloud-download", received, total.coerceAtLeast(1L), 0, 1),
                0L,
                total.coerceAtLeast(1L)
            )
        }
        val output = workDataOfSafe("phase" to "cloud-download-verified", "local_path" to result.absolutePath,
            "downloaded_bytes" to result.length())
        setProgressAsync(output)
        return output
    }

    private suspend fun runCloudSync(descriptor: JSONObject): Data {
        val accountId = descriptor.getString("accountId")
        val account = QamzorCloudAccounts.find(applicationContext, accountId)
        val passwordBytes = AndroidSecretVault.get(applicationContext, "cloud-$accountId")
        val password = try { String(passwordBytes, Charsets.UTF_8) } finally { passwordBytes.fill(0) }
        val result = QamzorWebDavClient(account, password).sync(File(descriptor.getString("localDirectory"))) { phase, completed, total ->
            publish(ArchiveEngine.OperationProgress("cloud-sync-$phase", completed, total.coerceAtLeast(1L), 0, 1), 0L, total.coerceAtLeast(1L))
        }
        val output = workDataOfSafe(
            "phase" to "cloud-sync-completed", "uploaded" to result.uploaded, "downloaded" to result.downloaded,
            "unchanged" to result.unchanged, "conflicts" to result.conflicts.joinToString("\n").take(8000)
        )
        setProgressAsync(output)
        return output
    }

    private fun publish(progress: ArchiveEngine.OperationProgress, base: Long, total: Long) {
        checkpoint()
        val now = SystemClock.elapsedRealtime()
        val completed = (base + progress.completedBytes).coerceAtMost(total)
        if (lastPhase != progress.phase) {
            lastPhase = progress.phase
            lastProgressAt = now
            lastProgressBytes = completed
            lastSpeed = 0.0
        }
        if (lastProgressAt > 0 && now > lastProgressAt) {
            val delta = completed - lastProgressBytes
            val seconds = (now - lastProgressAt) / 1000.0
            if (delta >= 0 && seconds > 0) lastSpeed = delta / seconds
        }
        lastProgressAt = now
        lastProgressBytes = completed
        val percent = if (total <= 0) 0 else ((completed * 100.0 / total).toInt()).coerceIn(0, 100)
        val eta = if (lastSpeed > 0) ((total - completed) / lastSpeed).toLong().coerceAtLeast(0) else -1L
        val data = Data.Builder()
            .putInt("progress", percent).putLong("completed_bytes", completed).putLong("total_bytes", total)
            .putLong("speed_bytes_per_second", lastSpeed.toLong()).putLong("eta_seconds", eta)
            .putString("phase", progress.phase).putInt("completed_files", progress.completedFiles)
            .putInt("total_files", progress.totalFiles).build()
        setProgressAsync(data)
        notificationManager.notify(notificationId, notification("${progress.phaseLabel()} • $percent%", percent))
    }

    private fun checkpoint() {
        while (paused() && !isStopped) {
            setProgressAsync(workDataOfSafe("phase" to "paused"))
            Thread.sleep(250)
        }
        if (isStopped) throw InterruptedException("Cancelled by user or system")
    }

    private fun paused(): Boolean = applicationContext.getSharedPreferences("qamzor-work-control", Context.MODE_PRIVATE)
        .getBoolean("pause-$id", false)

    private fun treeBytes(file: File): Long = if (file.isFile) file.length().coerceAtLeast(0L)
        else file.walkTopDown().filter { it.isFile }.sumOf { it.length().coerceAtLeast(0L) }

    private fun foregroundInfo(text: String, progress: Int): ForegroundInfo {
        val notification = notification(text, progress)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }

    private fun notification(text: String, progress: Int) = NotificationCompat.Builder(applicationContext, QamzorBackgroundWork.channelId())
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("QAMZOR • عملية خلفية")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, progress.coerceIn(0, 100), false)
        .build()

    private fun ArchiveEngine.OperationProgress.phaseLabel(): String = when (phase) {
        "creating" -> "ضغط وأرشفة"
        "verifying" -> "فحص السلامة"
        "extracting" -> "استخراج"
        "publishing" -> "حفظ الأرشيف"
        "snapshot-verifying" -> "التحقق من النسخة"
        "cloud-upload" -> "رفع الأرشيف إلى السحابة"
        "cloud-verify" -> "التحقق من النسخة المرفوعة"
        "cloud-download" -> "تنزيل الأرشيف والتحقق منه"
        else -> when {
            phase.startsWith("cloud-sync") -> "مزامنة أرشيفات QZRA"
            else -> phase
        }
    }

    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
    private fun workDataOfSafe(vararg pairs: Pair<String, Any>): Data = Data.Builder().apply {
        pairs.forEach { (key, value) -> when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Boolean -> putBoolean(key, value)
        } }
    }.build()
}
