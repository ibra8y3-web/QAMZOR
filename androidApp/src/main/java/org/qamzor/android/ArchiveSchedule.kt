package org.qamzor.android

import android.content.Context
import android.os.Build
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import org.qamzor.core.ArchiveEngine
import org.qamzor.core.OperationCancelledException
import org.qamzor.core.ProgressListener
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ScheduledJob(
    val id: String,
    val sourcePath: String,
    val outputDir: String,
    val prefix: String,
    val intervalDays: Int,
    val keep: Int,
    val encrypted: Boolean,
    val chargingOnly: Boolean,
    val enabled: Boolean,
    val lastRun: Long,
    val lastStatus: String
) {
    fun copy(enabled: Boolean = this.enabled, lastRun: Long = this.lastRun, lastStatus: String = this.lastStatus): ScheduledJob =
        ScheduledJob(id, sourcePath, outputDir, prefix, intervalDays, keep, encrypted, chargingOnly, enabled, lastRun, lastStatus)
}

/** Recurring automatic archiving, run by WorkManager so it works even when the app is closed. */
object ArchiveSchedule {
    private const val PREFS = "qamzor-schedule"
    private const val KEY_JOBS = "jobs"
    private const val PASSWORD_ALIAS = "qamzor.schedule.passwords.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    @Synchronized
    fun load(context: Context): List<ScheduledJob> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JOBS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { position ->
                val entry = array.getJSONObject(position)
                ScheduledJob(
                    entry.getString("id"), entry.getString("source"), entry.getString("output"), entry.getString("prefix"),
                    entry.optInt("days", 7), entry.optInt("keep", 7), entry.optBoolean("encrypted", false),
                    entry.optBoolean("charging", false), entry.optBoolean("enabled", true),
                    entry.optLong("lastRun", 0L), entry.optString("lastStatus", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(context: Context, jobs: List<ScheduledJob>) {
        val array = JSONArray()
        for (job in jobs) {
            array.put(JSONObject().put("id", job.id).put("source", job.sourcePath).put("output", job.outputDir)
                .put("prefix", job.prefix).put("days", job.intervalDays).put("keep", job.keep)
                .put("encrypted", job.encrypted).put("charging", job.chargingOnly).put("enabled", job.enabled)
                .put("lastRun", job.lastRun).put("lastStatus", job.lastStatus))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JOBS, array.toString()).apply()
    }

    fun newId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun upsert(context: Context, job: ScheduledJob) {
        val jobs = load(context).filter { it.id != job.id } + job
        save(context, jobs)
        if (job.enabled) schedule(context, job) else cancel(context, job.id)
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
        cancel(context, id)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pw.$id.iv").remove("pw.$id.ct").apply()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val job = load(context).firstOrNull { it.id == id } ?: return
        upsert(context, job.copy(enabled = enabled))
    }

    fun recordRun(context: Context, id: String, status: String) {
        val jobs = load(context).map { if (it.id == id) it.copy(lastRun = System.currentTimeMillis(), lastStatus = status) else it }
        save(context, jobs)
    }

    private fun workName(id: String) = "qamzor-job-$id"

    fun schedule(context: Context, job: ScheduledJob) {
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .setRequiresCharging(job.chargingOnly)
            .build()
        val request = PeriodicWorkRequest.Builder(ScheduledArchiveWorker::class.java, job.intervalDays.toLong(), TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("id", job.id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(workName(job.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    fun runNow(context: Context, id: String) {
        val request = OneTimeWorkRequest.Builder(ScheduledArchiveWorker::class.java)
            .setInputData(Data.Builder().putString("id", id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id) + "-now", ExistingWorkPolicy.REPLACE, request)
    }

    /** Re-registers enabled jobs (cheap and idempotent); keeps schedules alive after data clears or app updates. */
    fun rescheduleAll(context: Context) {
        for (job in load(context)) if (job.enabled) schedule(context, job)
    }

    // ── Passwords for encrypted scheduled archives ────────────────────────────────────────────────────────────
    // Background jobs cannot ask for a fingerprint, so the password is sealed with an Android Keystore key that
    // never leaves the device's secure hardware; it protects against copying the app's data files elsewhere.

    private fun passwordKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(PASSWORD_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(PASSWORD_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun storePassword(context: Context, id: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pw.$id.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("pw.$id.ct", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun loadPassword(context: Context, id: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString("pw.$id.iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val encrypted = prefs.getString("pw.$id.ct", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, passwordKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
}

class ScheduledArchiveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val job = ArchiveSchedule.load(applicationContext).firstOrNull { it.id == id } ?: return Result.success()
        if (!job.enabled) return Result.success()
        val title = "أرشفة مجدولة: ${File(job.sourcePath).name}"
        val status = try {
            runJob(job)
        } catch (_: OperationCancelledException) {
            return Result.success()
        } catch (error: Exception) {
            "فشلت: ${error.message ?: error.javaClass.simpleName}"
        }
        ArchiveSchedule.recordRun(applicationContext, id, status)
        OperationNotifier.notifyResult(applicationContext, title, status)
        return Result.success()
    }

    private fun runJob(job: ScheduledJob): String {
        val source = File(job.sourcePath)
        if (!source.exists()) return "فشلت: المصدر غير موجود (${job.sourcePath})"
        if (!ArchiveSchedule.hasAllFilesAccess()) return "فشلت: صلاحية الوصول لكل الملفات غير ممنوحة"
        val outputDir = File(job.outputDir)
        outputDir.mkdirs()
        if (!outputDir.isDirectory || !outputDir.canWrite()) return "فشلت: لا يمكن الكتابة في ${job.outputDir}"
        val password = if (job.encrypted) {
            ArchiveSchedule.loadPassword(applicationContext, job.id) ?: return "فشلت: تعذر قراءة كلمة مرور الأرشفة المجدولة"
        } else null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        var output = File(outputDir, "${job.prefix}-$stamp.qzra")
        var counter = 2
        while (output.exists()) {
            output = File(outputDir, "${job.prefix}-$stamp ($counter).qzra")
            counter++
        }
        val listener = object : ProgressListener {
            override fun isCancelled(): Boolean = isStopped
        }
        val result = try {
            ArchiveEngine.withProgress(listener) {
                val created = ArchiveEngine.convertToQzra(
                    destination = output, inputs = listOf(source), password = password, encryptMetadata = password != null
                )
                val verified = ArchiveEngine.verify(output, password)
                check(verified.valid && verified.filesChecked == created.files) { "فشل التحقق من الأرشيف الناتج" }
                created
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
        val removed = applyRetention(outputDir, job)
        val extra = if (removed > 0) " • حُذفت $removed نسخة قديمة" else ""
        return "نجحت: ${output.name} (${UiKit.formatBytes(output.length())}، ${result.files} ملف)$extra"
    }

    private fun applyRetention(directory: File, job: ScheduledJob): Int {
        if (job.keep <= 0) return 0
        val pattern = Regex(Regex.escape(job.prefix) + "-\\d{8}-\\d{4}(?: \\(\\d+\\))?\\.qzra")
        val mine = directory.listFiles().orEmpty().filter { it.isFile && pattern.matches(it.name) }.sortedByDescending { it.name }
        var removed = 0
        for (old in mine.drop(job.keep)) if (old.delete()) removed++
        return removed
    }
}
