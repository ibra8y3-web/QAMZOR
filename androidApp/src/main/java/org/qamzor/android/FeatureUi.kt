package org.qamzor.android

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import org.qamzor.core.DuplicateScanner
import org.qamzor.core.QzraRecovery
import org.qamzor.core.QzraVolumes
import java.io.File
import java.text.DateFormat
import java.util.Date

/** What the feature screens need from the main screen. */
interface FeatureHost {
    val activity: Activity
    fun currentDirectory(): File
    fun storageRoots(): List<File>
    /** Runs [block] on the serialized worker with progress, cancel button, notifications and error reporting. */
    fun runTask(errorPrefix: String, block: () -> Unit)
    fun currentProgress(): OperationProgress?
    fun setStatus(text: String)
    fun refreshFiles()
}

/** Duplicate finder, secret vault, sharing, split/join, recovery, schedules and the app lock. */
class FeatureUi(private val host: FeatureHost) {
    private val activity = host.activity
    private val ui = UiKit(activity)
    private val handler = Handler(Looper.getMainLooper())

    // ───────────────────────────── Tools menu ─────────────────────────────

    fun showToolsMenu() {
        ui.sheet("أدوات QAMZOR", "", listOf(
            UiKit.SheetEntry("🔍", "كاشف الملفات المكررة") { showDuplicateFinder() },
            UiKit.SheetEntry("🔐", "الخزنة السرية") { openVault(null) },
            UiKit.SheetEntry("⏱", "الأرشفة التلقائية المجدولة") { showSchedules() },
            UiKit.SheetEntry("🛡", "القفل والبصمة والإشعارات") { showSecuritySettings() }
        )).show()
    }

    // ───────────────────────────── Duplicate finder ─────────────────────────────

    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff")
    private val videoExt = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "flv", "wmv")
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")
    private val documentExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "csv", "md", "epub", "zip", "qzra")

    fun showDuplicateFinder() {
        val panel = ui.panel("كاشف الملفات المكررة")
        panel.body.addView(ui.muted("يقارن المحتوى الفعلي (SHA-256) فيكتشف النسخ المطابقة حتى لو اختلفت أسماؤها أو أماكنها، ثم يعرض المساحة التي يمكن توفيرها. لا يُحذف شيء إلا بعد تأكيدك."))
        var wholeStorage = false
        var kind = 0
        panel.body.addView(ui.label("نطاق البحث", 12.5f, UiKit.WHITE, true))
        panel.body.addView(ui.radioGroup(listOf("المجلد الحالي (${host.currentDirectory().name})", "كل التخزين"), 0) { wholeStorage = it == 1 })
        panel.body.addView(ui.label("نوع الملفات", 12.5f, UiKit.WHITE, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        panel.body.addView(ui.radioGroup(listOf("كل الأنواع", "الصور", "الفيديوهات", "الصوتيات", "المستندات والأرشيفات"), 0) { kind = it })
        panel.body.addView(ui.space(8))
        val actions = ui.row()
        actions.addView(ui.button("ابدأ الفحص", primary = true) {
            panel.dialog.dismiss()
            startDuplicateScan(wholeStorage, kind)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.button("إغلاق") { panel.dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        panel.body.addView(actions)
        panel.dialog.show()
    }

    private fun startDuplicateScan(wholeStorage: Boolean, kind: Int) {
        val accept: (File) -> Boolean = { file ->
            val ext = file.extension.lowercase()
            !file.name.startsWith(".qamzor-") && when (kind) {
                1 -> ext in imageExt
                2 -> ext in videoExt
                3 -> ext in audioExt
                4 -> ext in documentExt
                else -> true
            }
        }
        val roots = if (wholeStorage) host.storageRoots() else listOf(host.currentDirectory())
        host.runTask("فشل البحث عن الملفات المكررة") {
            val result = DuplicateScanner.scan(roots, 1L, accept, host.currentProgress())
            activity.runOnUiThread { showDuplicateResults(result) }
        }
    }

    private fun showDuplicateResults(result: DuplicateScanner.Result) {
        if (result.groups.isEmpty()) {
            host.setStatus("لا توجد ملفات مكررة (تم فحص ${result.filesScanned} ملف).")
            ui.message("لا توجد ملفات مكررة", "تم فحص ${result.filesScanned} ملف ولم يُعثر على نسخ متطابقة المحتوى." +
                if (result.skipped > 0) "\nتعذّرت قراءة ${result.skipped} عنصر وتم تخطيها." else "")
            return
        }
        val shown = result.groups.take(150)
        // What the files looked like at scan time; anything that changed since is never deleted.
        val snapshot = HashMap<File, Pair<Long, Long>>()
        for (group in shown) for (file in group.files) snapshot[file] = Pair(file.length(), file.lastModified())
        val selected = HashSet<File>()
        for (group in shown) selected.addAll(group.files.drop(1)) // keep the oldest copy of each group by default
        val panel = ui.panel("الملفات المكررة")
        val summary = ui.label("", 12f, UiKit.MUTED)
        val deleteButton = ui.button("", danger = true) {}
        fun refresh() {
            val freed = shown.sumOf { group -> group.files.count { it in selected } * group.size }
            summary.text = "${result.groups.size} مجموعة • ${result.duplicateFiles} نسخة زائدة • يمكن توفير ${UiKit.formatBytes(result.reclaimableBytes)}\n" +
                "المحدد للحذف: ${selected.size} ملف = ${UiKit.formatBytes(freed)}" +
                if (result.groups.size > shown.size) "\nيُعرض أكبر ${shown.size} مجموعة فقط." else ""
            deleteButton.text = "حذف المحدد (${selected.size}) — يوفر ${UiKit.formatBytes(freed)}"
            deleteButton.alpha = if (selected.isEmpty()) 0.5f else 1f
        }
        panel.body.addView(summary)
        val list = ui.column()
        for (group in shown) {
            list.addView(ui.label("${group.files.size} نسخ • ${UiKit.formatBytes(group.size)} للنسخة • يوفر ${UiKit.formatBytes(group.reclaimableBytes)}",
                12.5f, UiKit.WHITE, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
            for (file in group.files) {
                val line = ui.row()
                val box = ui.checkBox("", file in selected)
                box.setOnCheckedChangeListener { button, checked ->
                    if (checked) {
                        // Selecting this copy must never leave a group without a surviving copy.
                        if (group.files.filter { it != file }.all { it in selected }) {
                            button.isChecked = false
                            ui.toast("احتفظ بنسخة واحدة على الأقل من كل مجموعة")
                            return@setOnCheckedChangeListener
                        }
                        selected.add(file)
                    } else {
                        selected.remove(file)
                    }
                    refresh()
                }
                line.addView(box)
                val texts = ui.column()
                texts.addView(ui.label(file.name, 12.5f, 0xFFF4F7FF.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
                texts.addView(ui.label((file.parent ?: "") + " • " + DateFormat.getDateInstance().format(Date(file.lastModified())), 10f, UiKit.MUTED).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START
                })
                line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(line)
            }
        }
        panel.body.addView(list)
        panel.body.addView(ui.space(8))
        deleteButton.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            ui.confirm("حذف الملفات المكررة", "سيتم حذف ${selected.size} ملف نهائيًا مع الإبقاء على نسخة واحدة على الأقل من كل مجموعة. لا يمكن التراجع.", "حذف") {
                panel.dialog.dismiss()
                deleteDuplicates(shown, selected.toSet(), snapshot)
            }
        }
        panel.body.addView(deleteButton)
        panel.body.addView(ui.space(6))
        panel.body.addView(ui.button("إغلاق") { panel.dialog.dismiss() })
        refresh()
        panel.dialog.show()
    }

    private fun deleteDuplicates(groups: List<DuplicateScanner.Group>, chosen: Set<File>, snapshot: Map<File, Pair<Long, Long>>) {
        host.runTask("فشل حذف الملفات المكررة") {
            var deleted = 0
            var freed = 0L
            var skipped = 0
            for (group in groups) {
                // A copy is only removed while at least one untouched, unselected copy still exists.
                val keepers = group.files.filter { it !in chosen && it.isFile && Pair(it.length(), it.lastModified()) == snapshot[it] }
                if (keepers.isEmpty()) {
                    skipped += group.files.count { it in chosen }
                    continue
                }
                for (file in group.files) {
                    if (file !in chosen) continue
                    if (!file.isFile || Pair(file.length(), file.lastModified()) != snapshot[file]) { skipped++; continue }
                    val size = file.length()
                    if (file.delete()) { deleted++; freed += size } else skipped++
                }
            }
            activity.runOnUiThread {
                host.setStatus("تم حذف $deleted ملف مكرر ووفّرت ${UiKit.formatBytes(freed)}" + if (skipped > 0) " • تم تخطي $skipped" else "")
                host.refreshFiles()
            }
        }
    }

    // ───────────────────────────── Sharing ─────────────────────────────

    fun share(files: List<File>, title: String = "مشاركة") {
        val usable = files.filter { it.isFile }
        if (usable.isEmpty()) {
            ui.toast("لا توجد ملفات قابلة للمشاركة")
            return
        }
        try {
            val authority = activity.packageName + ".fileprovider"
            val uris = ArrayList<Uri>()
            for (file in usable) uris.add(FileProvider.getUriForFile(activity, authority, file))
            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.type = if (usable.size == 1) mimeOf(usable[0]) else "*/*"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri(usable[0].name, uris[0])
            for (index in 1 until uris.size) clip.addItem(ClipData.Item(uris[index]))
            intent.clipData = clip
            activity.startActivity(Intent.createChooser(intent, title))
        } catch (error: Exception) {
            ui.message("تعذرت المشاركة", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    // ───────────────────────────── Split into volumes / join ─────────────────────────────

    fun showSplitDialog(archive: File) {
        val panel = ui.panel("تقسيم الأرشيف لأجزاء")
        panel.body.addView(ui.muted("${archive.name} • ${UiKit.formatBytes(archive.length())}\nيُنشأ ملف وصف (.qzset) ومجلد أجزاء، وكل جزء مغطّى بـ SHA-256 للتحقق عند الدمج."))
        val sizes = listOf(25L to "25 ميجابايت (بريد إلكتروني)", 100L to "100 ميجابايت", 500L to "500 ميجابايت", 1900L to "1900 ميجابايت (واتساب)")
        var choice = 1
        panel.body.addView(ui.radioGroup(sizes.map { it.second }, choice) { choice = it })
        val custom = ui.input("أو حجم مخصص بالميجابايت (اختياري، 1 على الأقل)", numeric = true)
        panel.body.addView(custom, ui.lp().apply { topMargin = ui.dp(6) })
        panel.body.addView(ui.space(8))
        val actions = ui.row()
        actions.addView(ui.button("تقسيم", primary = true) {
            val typed = custom.text.toString().trim()
            val megabytes = if (typed.isEmpty()) sizes[choice].first else typed.toLongOrNull() ?: 0L
            if (megabytes < 1L) {
                ui.toast("الحجم يجب ألا يقل عن 1 ميجابايت")
                return@button
            }
            panel.dialog.dismiss()
            splitArchive(archive, megabytes * 1024L * 1024L)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.button("إلغاء") { panel.dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        panel.body.addView(actions)
        panel.dialog.show()
    }

    private fun splitArchive(archive: File, partSize: Long) {
        host.runTask("فشل تقسيم الأرشيف") {
            val result = QzraVolumes.split(archive, archive.absoluteFile.parentFile ?: archive, partSize, false, host.currentProgress())
            activity.runOnUiThread {
                host.setStatus("تم التقسيم إلى ${result.partFiles.size} جزء ✓ (${result.descriptor.name})")
                host.refreshFiles()
                ui.confirm("اكتمل التقسيم", "تم إنشاء ${result.partFiles.size} جزء + ملف الوصف ${result.descriptor.name}. هل تريد مشاركتها الآن؟", "مشاركة") {
                    share(listOf(result.descriptor) + result.partFiles, "مشاركة الأجزاء")
                }
            }
        }
    }

    fun joinSet(descriptor: File) {
        host.runTask("فشل دمج الأجزاء") {
            val name = File(descriptor.name.removeSuffix(".qzset")).name.let { if (it.endsWith(".qzra", true)) it else "$it.qzra" }
            val directory = descriptor.absoluteFile.parentFile ?: descriptor
            var target = File(directory, name)
            var counter = 1
            while (target.exists()) {
                target = File(directory, name.removeSuffix(".qzra") + " ($counter).qzra")
                counter++
            }
            val result = QzraVolumes.join(descriptor, target, false, host.currentProgress())
            activity.runOnUiThread {
                host.setStatus("تم دمج الأجزاء والتحقق منها ✓ → ${result.archive.name} (${UiKit.formatBytes(result.bytes)})")
                host.refreshFiles()
            }
        }
    }

    fun shareSet(descriptor: File) {
        val base = descriptor.name.removeSuffix(".qzset")
        val directory = descriptor.absoluteFile.parentFile ?: return
        val partsDir = directory.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.startsWith("$base.parts-") }
        val parts = partsDir?.listFiles().orEmpty().filter { it.isFile && it.name.contains(".part") && !it.name.endsWith(".tmp") }.sortedBy { it.name }
        if (parts.isEmpty()) {
            ui.toast("لم يتم العثور على مجلد الأجزاء بجانب ملف الوصف")
            return
        }
        share(listOf(descriptor) + parts, "مشاركة الأجزاء")
    }

    // ───────────────────────────── Recovery data ─────────────────────────────

    fun sidecarFor(archive: File): File = File(archive.absoluteFile.parentFile, archive.name + ".qzrp")

    fun createRecovery(archive: File) {
        val sidecar = sidecarFor(archive)
        val panel = ui.panel("بيانات الاسترجاع")
        panel.body.addView(ui.muted("يُنشأ ملف ${sidecar.name} بجانب الأرشيف. يمكنه إصلاح كتلة تالفة واحدة داخل كل مجموعة، ولا يغني عن النسخ الاحتياطي المستقل. احتفظ به بعيدًا عن الأرشيف إن أمكن."))
        var level = 0
        panel.body.addView(ui.radioGroup(listOf("حماية عادية: كتلة تحقق لكل 8 كتل (إضافة ≈ 12٪)", "حماية أعلى: كتلة تحقق لكل 4 كتل (إضافة ≈ 25٪)"), 0) { level = it })
        panel.body.addView(ui.space(8))
        val actions = ui.row()
        actions.addView(ui.button("إنشاء", primary = true) {
            panel.dialog.dismiss()
            host.runTask("فشل إنشاء بيانات الاسترجاع") {
                val result = QzraRecovery.create(archive, sidecar, QzraRecovery.DEFAULT_BLOCK_SIZE, if (level == 0) 8 else 4, true, host.currentProgress())
                activity.runOnUiThread {
                    host.setStatus("تم إنشاء ${sidecar.name} ✓ (${UiKit.formatBytes(result.overheadBytes)}، ${result.parityGroups} مجموعة)")
                    host.refreshFiles()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.button("إلغاء") { panel.dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        panel.body.addView(actions)
        panel.dialog.show()
    }

    /** Checks [archive] against its sidecar and offers a repaired copy when blocks are damaged. */
    fun checkAndRepair(archive: File, sidecar: File = sidecarFor(archive)) {
        if (!sidecar.isFile) {
            ui.message("لا يوجد ملف استرجاع", "لم يُعثر على ${sidecar.name} بجانب الأرشيف. أنشئ بيانات الاسترجاع وهو سليم أولًا.")
            return
        }
        host.runTask("فشل فحص الأرشيف بملف الاسترجاع") {
            val check = QzraRecovery.check(archive, sidecar, host.currentProgress())
            activity.runOnUiThread {
                if (check.damagedBlocks.isEmpty() && check.sizeMatches) {
                    host.setStatus("الأرشيف مطابق لبيانات الاسترجاع (${check.blocks} كتلة سليمة) ✓")
                    ui.message("الأرشيف سليم", "جميع الكتل (${check.blocks}) مطابقة لبيانات الاسترجاع.")
                } else if (!check.repairable) {
                    ui.message("التلف أكبر من القابل للإصلاح",
                        "الكتل التالفة: ${check.damagedBlocks.size} من ${check.blocks}، وبعض المجموعات فيها أكثر من كتلة تالفة، ولا يمكن إصلاحها بهذا الملف.")
                } else {
                    ui.confirm("تم العثور على تلف قابل للإصلاح",
                        "الكتل التالفة: ${check.damagedBlocks.size} من ${check.blocks}. سيُنشأ ملف جديد مُصلَح ولن يُعدَّل الأصل.", "إصلاح") {
                        repairArchive(archive, sidecar)
                    }
                }
            }
        }
    }

    private fun repairArchive(archive: File, sidecar: File) {
        host.runTask("فشل إصلاح الأرشيف") {
            val directory = archive.absoluteFile.parentFile ?: archive
            val base = archive.name.removeSuffix(".qzra")
            var target = File(directory, "$base.repaired.qzra")
            var counter = 2
            while (target.exists()) {
                target = File(directory, "$base.repaired ($counter).qzra")
                counter++
            }
            val result = QzraRecovery.repair(archive, sidecar, target, false, host.currentProgress())
            activity.runOnUiThread {
                host.setStatus("تم الإصلاح ✓ ${result.repairedBlocks.size} كتلة → ${result.output.name}")
                host.refreshFiles()
            }
        }
    }

    // ───────────────────────────── Secret vault ─────────────────────────────

    private class VaultSession(val password: String)

    private var vault: VaultSession? = null
    private var vaultItems: List<VaultItem> = emptyList()
    private var vaultPanelDialog: android.app.Dialog? = null
    private val autoLock = Runnable { lockVault(true) }
    private val vaultBiometricId = "vault"

    private fun touchVault() {
        handler.removeCallbacks(autoLock)
        if (vault != null) handler.postDelayed(autoLock, 3 * 60 * 1000L)
    }

    fun lockVault(announce: Boolean, purge: Boolean = true) {
        handler.removeCallbacks(autoLock)
        val wasOpen = vault != null
        vault = null
        vaultItems = emptyList()
        vaultPanelDialog?.dismiss()
        vaultPanelDialog = null
        if (purge) SecureVault.purgeOpened(activity)
        if (wasOpen && announce) host.setStatus("تم قفل الخزنة السرية.")
    }

    private val delayedPurge = Runnable { SecureVault.purgeOpened(activity) }

    /** [pending] are files waiting to be moved in; they are added right after the vault is unlocked. */
    fun openVault(pending: List<File>?, deleteOriginals: Boolean = false) {
        val session = vault
        if (session != null) {
            if (pending != null) addToVault(session.password, pending, deleteOriginals) else showVaultContents()
            return
        }
        if (!SecureVault.isInitialized(activity)) showVaultSetup(pending, deleteOriginals) else showVaultUnlock(pending, deleteOriginals)
    }

    private fun afterUnlock(password: String, items: List<VaultItem>, pending: List<File>?, deleteOriginals: Boolean) {
        vault = VaultSession(password)
        vaultItems = items
        touchVault()
        if (pending != null) addToVault(password, pending, deleteOriginals) else showVaultContents()
    }

    private fun showVaultSetup(pending: List<File>?, deleteOriginals: Boolean) {
        val panel = ui.panel("إنشاء الخزنة السرية", secure = true)
        panel.body.addView(ui.muted("كل ما تضعه في الخزنة يُشفَّر بمحرك QAMZOR (AES-256-GCM مع scrypt) وتُخفى أسماؤه، ويُخزَّن داخل مساحة التطبيق الخاصة فقط. إن نسيت كلمة المرور فلا توجد طريقة لاستعادتها."))
        val password = ui.input("كلمة مرور الخزنة (${SecureVault.MIN_PASSWORD_LENGTH} أحرف على الأقل)", secret = true)
        val confirm = ui.input("تأكيد كلمة المرور", secret = true)
        panel.body.addView(password, ui.lp().apply { topMargin = ui.dp(6) })
        panel.body.addView(confirm, ui.lp().apply { topMargin = ui.dp(6) })
        val useBiometric = ui.checkBox("فتح الخزنة بالبصمة", BiometricSecrets.isAvailable(activity))
        if (BiometricSecrets.isAvailable(activity)) panel.body.addView(useBiometric)
        panel.body.addView(ui.space(8))
        val actions = ui.row()
        actions.addView(ui.button("إنشاء الخزنة", primary = true) {
            val value = password.text.toString()
            when {
                value.length < SecureVault.MIN_PASSWORD_LENGTH -> ui.toast("كلمة المرور قصيرة: ${SecureVault.MIN_PASSWORD_LENGTH} أحرف على الأقل")
                value != confirm.text.toString() -> ui.toast("كلمتا المرور غير متطابقتين")
                else -> {
                    panel.dialog.dismiss()
                    val wantBiometric = useBiometric.isChecked && BiometricSecrets.isAvailable(activity)
                    background("فشل إنشاء الخزنة") {
                        val items = SecureVault.create(activity, value)
                        activity.runOnUiThread {
                            if (wantBiometric) {
                                BiometricSecrets.store(activity, vaultBiometricId, value, "تفعيل فتح الخزنة بالبصمة") { ok, message ->
                                    if (!ok) ui.toast("لم تُفعَّل البصمة: ${message ?: ""}")
                                    afterUnlock(value, items, pending, deleteOriginals)
                                }
                            } else afterUnlock(value, items, pending, deleteOriginals)
                        }
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.button("إلغاء") { panel.dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        panel.body.addView(actions)
        panel.dialog.show()
    }

    private fun showVaultUnlock(pending: List<File>?, deleteOriginals: Boolean) {
        val panel = ui.panel("الخزنة السرية", secure = true)
        panel.body.addView(ui.muted("أدخل كلمة مرور الخزنة."))
        val password = ui.input("كلمة مرور الخزنة", secret = true)
        panel.body.addView(password, ui.lp().apply { topMargin = ui.dp(6) })
        fun unlockWith(value: String) {
            panel.dialog.dismiss()
            background("تعذر فتح الخزنة") {
                try {
                    val items = SecureVault.open(activity, value)
                    activity.runOnUiThread { afterUnlock(value, items, pending, deleteOriginals) }
                } catch (error: VaultPasswordException) {
                    activity.runOnUiThread {
                        ui.toast("كلمة المرور غير صحيحة")
                        showVaultUnlock(pending, deleteOriginals)
                    }
                }
            }
        }
        panel.body.addView(ui.space(8))
        val actions = ui.row()
        actions.addView(ui.button("فتح", primary = true) {
            val value = password.text.toString()
            if (value.isEmpty()) ui.toast("أدخل كلمة المرور") else unlockWith(value)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (BiometricSecrets.has(activity, vaultBiometricId) && BiometricSecrets.isAvailable(activity)) {
            actions.addView(ui.button("بالبصمة") {
                BiometricSecrets.retrieve(activity, vaultBiometricId, "فتح الخزنة السرية", { secret -> unlockWith(secret) }, { message -> ui.toast(message) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        }
        actions.addView(ui.button("إلغاء") { panel.dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        panel.body.addView(actions)
        panel.dialog.show()
    }

    private fun showVaultContents() {
        val session = vault ?: return
        touchVault()
        vaultPanelDialog?.dismiss()
        val panel = ui.panel("الخزنة السرية", secure = true)
        vaultPanelDialog = panel.dialog
        val total = vaultItems.sumOf { it.size }
        panel.body.addView(ui.muted("${vaultItems.size} عنصر • ${UiKit.formatBytes(total)} • تُقفل تلقائيًا بعد 3 دقائق من عدم النشاط أو عند مغادرة التطبيق"))
        val top = ui.row()
        top.addView(ui.button("إضافة من الجهاز", primary = true) { pickForVault() }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.button("قفل الخزنة") { lockVault(true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        panel.body.addView(top)
        panel.body.addView(ui.muted("لنقل ملفات موجودة: اضغط ⋮ على الملف في تبويب الملفات ← «نقل إلى الخزنة»."))
        if (vaultItems.isEmpty()) panel.body.addView(ui.label("الخزنة فارغة.", 13f, UiKit.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(18), 0, ui.dp(18)) })
        for (item in vaultItems.sortedByDescending { it.addedAt }) {
            val line = ui.row().apply {
                setPadding(0, ui.dp(8), 0, ui.dp(8))
                isClickable = true
                setOnClickListener { touchVault(); showVaultItemSheet(session.password, item) }
            }
            line.addView(ui.label(SecureVault.kindGlyph(item.kind), 22f, UiKit.WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(40), -2))
            val texts = ui.column()
            texts.addView(ui.label(item.name, 13.5f, 0xFFF4F7FF.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            texts.addView(ui.label("${SecureVault.kindLabel(item.kind)} • ${UiKit.formatBytes(item.size)} • " +
                DateFormat.getDateInstance().format(Date(item.addedAt)), 10.5f, UiKit.MUTED))
            line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            panel.body.addView(line)
        }
        panel.body.addView(ui.space(8))
        panel.body.addView(ui.button("إغلاق") { panel.dialog.dismiss() })
        panel.dialog.setOnDismissListener { if (vaultPanelDialog === panel.dialog) vaultPanelDialog = null }
        panel.dialog.show()
    }

    private fun showVaultItemSheet(password: String, item: VaultItem) {
        val entries = ArrayList<UiKit.SheetEntry>()
        if (item.kind != "folder") entries += UiKit.SheetEntry("👁", "فتح") { viewVaultItem(password, item) }
        entries += UiKit.SheetEntry("⬇", "تصدير إلى المجلد الحالي (${host.currentDirectory().name})") { exportVaultItem(password, item) }
        entries += UiKit.SheetEntry("🗑", "حذف من الخزنة نهائيًا", danger = true) {
            ui.confirm("حذف من الخزنة", "سيُحذف «${item.name}» نهائيًا ولا يمكن استرجاعه.", "حذف") { deleteVaultItem(password, item) }
        }
        ui.sheet(item.name, "${SecureVault.kindLabel(item.kind)} • ${UiKit.formatBytes(item.size)}", entries).show()
    }

    private fun viewVaultItem(password: String, item: VaultItem) {
        host.runTask("فشل فتح عنصر الخزنة") {
            val file = SecureVault.openForView(activity, password, item)
            activity.runOnUiThread {
                try {
                    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(intent, item.name))
                } catch (error: Exception) {
                    ui.message("لا يوجد تطبيق لفتح هذا الملف", "يمكنك تصديره ثم فتحه بتطبيق مناسب.")
                }
            }
        }
    }

    private fun exportVaultItem(password: String, item: VaultItem) {
        val destination = host.currentDirectory()
        host.runTask("فشل تصدير عنصر الخزنة") {
            val created = SecureVault.export(activity, password, item, destination)
            activity.runOnUiThread {
                host.setStatus("تم التصدير إلى ${created.absolutePath}")
                host.refreshFiles()
            }
        }
    }

    private fun deleteVaultItem(password: String, item: VaultItem) {
        host.runTask("فشل حذف عنصر الخزنة") {
            val remaining = SecureVault.delete(activity, password, item)
            activity.runOnUiThread {
                vaultItems = remaining
                host.setStatus("تم حذف «${item.name}» من الخزنة.")
                if (vaultPanelDialog != null) showVaultContents()
            }
        }
    }

    /** Moves [files] into the vault; asks first because the originals are deleted after verification. */
    fun moveToVault(files: List<File>) {
        if (files.isEmpty()) return
        ui.confirm("نقل إلى الخزنة السرية",
            "سيتم تشفير ${files.size} عنصر داخل الخزنة ثم حذف الأصل بعد التحقق من سلامة النسخة المشفّرة. (الحذف لا يمسح آثار الملف من ذاكرة الفلاش نهائيًا.)", "متابعة") {
            openVault(files, true)
        }
    }

    private fun addToVault(password: String, files: List<File>, deleteOriginals: Boolean) {
        host.runTask("فشل نقل الملفات إلى الخزنة") {
            val items = SecureVault.add(activity, password, files, deleteOriginals)
            activity.runOnUiThread {
                vaultItems = items
                host.setStatus("تمت إضافة ${files.size} عنصر إلى الخزنة وتشفيره ✓")
                host.refreshFiles()
                touchVault()
                showVaultContents()
            }
        }
    }

    private fun pickForVault() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            activity.startActivityForResult(intent, REQUEST_VAULT_PICK)
        } catch (error: Exception) {
            ui.toast("تعذر فتح منتقي الملفات")
        }
    }

    /** Result of the system picker started from the vault: copies the files privately, then encrypts them. */
    fun onVaultPicked(data: Intent?) {
        val session = vault ?: return
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip -> for (index in 0 until clip.itemCount) uris.add(clip.getItemAt(index).uri) }
        data?.data?.let { if (uris.isEmpty()) uris.add(it) }
        if (uris.isEmpty()) return
        host.runTask("فشل نقل الملفات إلى الخزنة") {
            val staging = File(activity.cacheDir, "vault-pick").also { it.deleteRecursively(); it.mkdirs() }
            try {
                val copies = ArrayList<File>()
                for ((position, uri) in uris.withIndex()) {
                    val name = queryName(uri) ?: "file-$position"
                    val folder = File(staging, position.toString()).also { it.mkdirs() }
                    val target = File(folder, name.replace('/', '_'))
                    activity.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    copies.add(target)
                }
                val items = SecureVault.add(activity, session.password, copies, false)
                activity.runOnUiThread {
                    vaultItems = items
                    host.setStatus("تمت إضافة ${copies.size} عنصر إلى الخزنة ✓ (الأصل باقٍ في مكانه)")
                    showVaultContents()
                }
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Light-weight background work that does not occupy the main operation slot (key derivation, unlocking). */
    private fun background(errorPrefix: String, block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (error: Throwable) {
                activity.runOnUiThread { ui.message(errorPrefix, error.message ?: error.javaClass.simpleName) }
            }
        }.start()
    }

    // ───────────────────────────── Scheduled archiving ─────────────────────────────

    fun showSchedules() {
        val panel = ui.panel("الأرشفة التلقائية المجدولة")
        panel.body.addView(ui.muted("تعمل في الخلفية حتى والتطبيق مغلق، وتتحقق من كل أرشيف بعد إنشائه وتحذف النسخ الأقدم حسب إعدادك. الجداول الدورية يضبطها النظام لتوفير البطارية، فقد يتأخر التنفيذ قليلًا عن الموعد."))
        if (!ArchiveSchedule.hasAllFilesAccess()) {
            panel.body.addView(ui.label("⚠ صلاحية «الوصول لكل الملفات» غير ممنوحة، لن تعمل الجداول بدونها.", 12f, UiKit.DANGER_TEXT))
        }
        val jobs = ArchiveSchedule.load(activity)
        if (jobs.isEmpty()) panel.body.addView(ui.label("لا توجد جداول بعد.", 13f, UiKit.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, ui.dp(14)) })
        for (job in jobs) {
            val card = ui.column().apply {
                background = ui.rounded(0xFF1B2D4F.toInt(), 14)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            }
            card.addView(ui.label(File(job.sourcePath).name + if (job.enabled) "" else "  (متوقف)", 13.5f, UiKit.WHITE, true))
            card.addView(ui.label((if (job.intervalDays == 1) "يوميًا" else "أسبوعيًا") +
                " • الاحتفاظ بآخر " + (if (job.keep > 0) job.keep.toString() else "كل") + " نسخة" +
                (if (job.encrypted) " • مشفّر" else "") + (if (job.chargingOnly) " • أثناء الشحن فقط" else ""), 11f, UiKit.MUTED))
            card.addView(ui.label("الوجهة: ${job.outputDir}", 10.5f, UiKit.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START })
            if (job.lastRun > 0) card.addView(ui.label("آخر تشغيل: " + DateFormat.getDateTimeInstance().format(Date(job.lastRun)) + "\n" + job.lastStatus, 10.5f,
                if (job.lastStatus.startsWith("فشلت")) UiKit.DANGER_TEXT else 0xFF9FE0B5.toInt()))
            val actions = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            actions.addView(ui.button("تشغيل الآن") {
                ArchiveSchedule.runNow(activity, job.id)
                ui.toast("بدأ التنفيذ في الخلفية؛ ستصلك نتيجة.")
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(ui.button(if (job.enabled) "إيقاف" else "تفعيل") {
                ArchiveSchedule.setEnabled(activity, job.id, !job.enabled)
                panel.dialog.dismiss(); showSchedules()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) })
            actions.addView(ui.button("حذف", danger = true) {
                ui.confirm("حذف الجدول", "سيتوقف هذا الجدول. الأرشيفات التي أُنشئت سابقًا لن تُحذف.", "حذف") {
                    ArchiveSchedule.remove(activity, job.id)
                    panel.dialog.dismiss(); showSchedules()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) })
            card.addView(actions)
            panel.body.addView(card, ui.lp().apply { topMargin = ui.dp(8) })
        }
        panel.body.addView(ui.space(10))
        panel.body.addView(ui.button("➕ جدول جديد للمجلد الحالي (${host.currentDirectory().name})", primary = true) {
            panel.dialog.dismiss(); showScheduleEditor(host.currentDirectory())
        })
        panel.body.addView(ui.space(6))
        panel.body.addView(ui.button("إغلاق") { panel.dialog.dismiss() })
        panel.dialog.show()
    }

    fun showScheduleEditor(source: File) {
        val panel = ui.panel("جدول أرشفة جديد", secure = true)
        panel.body.addView(ui.muted("المصدر: ${source.absolutePath}"))
        val defaultOutput = File(host.storageRoots().firstOrNull() ?: source.parentFile ?: source, "QAMZOR-Backups")
        val output = ui.input("مجلد الحفظ").apply { setText(defaultOutput.absolutePath) }
        val prefix = ui.input("بادئة اسم الأرشيف").apply { setText(safePrefix(source.name)) }
        panel.body.addView(output, ui.lp().apply { topMargin = ui.dp(4) })
        panel.body.addView(prefix, ui.lp().apply { topMargin = ui.dp(6) })
        var interval = 1
        panel.body.addView(ui.label("التكرار", 12.5f, UiKit.WHITE, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        panel.body.addView(ui.radioGroup(listOf("يوميًا", "أسبوعيًا"), 1) { interval = it })
        var keepIndex = 1
        val keepValues = listOf(3, 7, 14, 0)
        panel.body.addView(ui.label("عدد النسخ المحفوظة", 12.5f, UiKit.WHITE, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        panel.body.addView(ui.radioGroup(listOf("آخر 3 نسخ", "آخر 7 نسخ", "آخر 14 نسخة", "الاحتفاظ بكل النسخ"), 1) { keepIndex = it })
        val encrypt = ui.checkBox("تشفير الأرشيفات بكلمة مرور", false)
        val password = ui.input("كلمة المرور (10 أحرف على الأقل)", secret = true)
        password.visibility = View.GONE
        encrypt.setOnCheckedChangeListener { _, checked -> password.visibility = if (checked) View.VISIBLE else View.GONE }
        panel.body.addView(encrypt)
        panel.body.addView(password, ui.lp())
        panel.body.addView(ui.muted("تُحفظ كلمة المرور مغلّفة بمفتاح في الـKeystore لأن الجدولة لا تستطيع طلبها منك وقت التنفيذ."))
        val charging = ui.checkBox("التنفيذ أثناء الشحن فقط", false)
        panel.body.addView(charging)
        panel.body.addView(ui.space(8))
        val actions = ui.row()
        actions.addView(ui.button("حفظ الجدول", primary = true) {
            val folder = output.text.toString().trim()
            val name = prefix.text.toString().trim()
            val secret = password.text.toString()
            when {
                folder.isEmpty() || name.isEmpty() -> ui.toast("أكمل مجلد الحفظ والبادئة")
                File(folder).absoluteFile.startsWith(source.absoluteFile) -> ui.toast("مجلد الحفظ لا يجوز أن يكون داخل المصدر نفسه")
                encrypt.isChecked && secret.length < 10 -> ui.toast("كلمة المرور يجب ألا تقل عن 10 أحرف")
                else -> {
                    val id = ArchiveSchedule.newId()
                    try {
                        if (encrypt.isChecked) ArchiveSchedule.storePassword(activity, id, secret)
                        ArchiveSchedule.upsert(activity, ScheduledJob(id, source.absolutePath, folder, safePrefix(name), if (interval == 0) 1 else 7,
                            keepValues[keepIndex], encrypt.isChecked, charging.isChecked, true, 0L, ""))
                        panel.dialog.dismiss()
                        host.setStatus("تم حفظ جدول الأرشفة التلقائية لـ ${source.name} ✓")
                        showSchedules()
                    } catch (error: Exception) {
                        ui.message("تعذر حفظ الجدول", error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(ui.button("إلغاء") { panel.dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        panel.body.addView(actions)
        panel.dialog.show()
    }

    /** File-name safe prefix: keeps letters/digits (any language), turns everything else into "-". */
    private fun safePrefix(raw: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }.joinToString("").trim('-')
        return if (cleaned.isEmpty()) "backup" else cleaned.take(60)
    }

    // ───────────────────────────── App lock, fingerprint and notifications ─────────────────────────────

    fun showSecuritySettings() {
        val panel = ui.panel("القفل والبصمة والإشعارات")
        panel.body.addView(ui.label("قفل التطبيق", 13f, UiKit.WHITE, true))
        val lockSwitch = ui.checkBox("طلب البصمة أو قفل الجهاز عند فتح QAMZOR", AppLock.isEnabled(activity))
        panel.body.addView(lockSwitch)
        val timeouts = listOf(0L to "فورًا عند كل خروج", 30_000L to "بعد 30 ثانية", 120_000L to "بعد دقيقتين", 300_000L to "بعد 5 دقائق")
        val currentTimeout = timeouts.indexOfFirst { it.first == AppLock.timeoutMs(activity) }.let { if (it < 0) 1 else it }
        panel.body.addView(ui.radioGroup(timeouts.map { it.second }, currentTimeout) { AppLock.setTimeoutMs(activity, timeouts[it].first) })
        lockSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !AppLock.isDeviceSecure(activity)) {
                lockSwitch.setOnCheckedChangeListener(null)
                lockSwitch.isChecked = false
                ui.message("لا يوجد قفل للجهاز", "فعّل رمز PIN أو نمطًا أو بصمة من إعدادات الجهاز أولًا ثم أعد المحاولة.")
            } else {
                AppLock.setEnabled(activity, checked)
                applyWindowSecurity()
            }
        }
        panel.body.addView(ui.label("البصمة للخزنة وكلمات مرور الأرشيف", 13f, UiKit.WHITE, true).apply { setPadding(0, ui.dp(12), 0, 0) })
        if (BiometricSecrets.isAvailable(activity)) {
            val vaultBio = BiometricSecrets.has(activity, vaultBiometricId)
            panel.body.addView(ui.muted(if (vaultBio) "فتح الخزنة بالبصمة: مفعّل." else "فتح الخزنة بالبصمة: غير مفعّل. افتح الخزنة بكلمة المرور ثم فعّله من هنا."))
            if (!vaultBio && vault != null) {
                panel.body.addView(ui.button("تفعيل فتح الخزنة بالبصمة") {
                    val session = vault ?: return@button
                    BiometricSecrets.store(activity, vaultBiometricId, session.password, "تفعيل فتح الخزنة بالبصمة") { ok, message ->
                        ui.toast(if (ok) "تم التفعيل ✓" else "لم يتم التفعيل: ${message ?: ""}")
                    }
                    panel.dialog.dismiss()
                })
            }
            panel.body.addView(ui.space(6))
            panel.body.addView(ui.button("مسح كل كلمات المرور المحفوظة بالبصمة", danger = true) {
                BiometricSecrets.clearAll(activity)
                ui.toast("تم المسح.")
                panel.dialog.dismiss()
            })
        } else {
            panel.body.addView(ui.muted("البصمة غير متاحة: تتطلب أندرويد 10 أو أحدث وبصمة مسجّلة في الجهاز."))
        }
        panel.body.addView(ui.label("الإشعارات", 13f, UiKit.WHITE, true).apply { setPadding(0, ui.dp(12), 0, 0) })
        if (OperationNotifier.hasPermission(activity)) {
            panel.body.addView(ui.muted("الإشعارات مفعّلة: ستصلك نتيجة العمليات الطويلة والمجدولة عند خروجك من التطبيق."))
        } else {
            panel.body.addView(ui.button("السماح بالإشعارات") {
                panel.dialog.dismiss()
                requestNotificationPermission()
            })
        }
        panel.body.addView(ui.space(10))
        panel.body.addView(ui.button("إغلاق") { panel.dialog.dismiss() })
        panel.dialog.show()
    }

    fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !OperationNotifier.hasPermission(activity)) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    // Lock overlay -----------------------------------------------------------------------------------------

    private var overlay: View? = null
    private var stoppedAt = 0L
    private var coldStart = true
    @Volatile private var unlockedAt = 0L

    val isLocked: Boolean get() = overlay != null

    /** Hides the app preview in the recents screen whenever a lock is configured. */
    fun applyWindowSecurity() {
        if (AppLock.isEnabled(activity)) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun onAppStopped() {
        stoppedAt = System.currentTimeMillis()
        // Lock now (the password leaves memory), but keep decrypted copies for a while: an external viewer
        // that was just launched may still be reading them. They are removed 10 minutes later, on lock and on exit.
        lockVault(false, false)
        handler.removeCallbacks(delayedPurge)
        handler.postDelayed(delayedPurge, 10 * 60 * 1000L)
    }

    /** Call from onResume: shows the lock when the app was away longer than the timeout (and on a cold start). */
    fun lockIfNeeded() {
        if (!AppLock.isEnabled(activity) || !AppLock.isDeviceSecure(activity)) {
            coldStart = false
            return
        }
        val away = System.currentTimeMillis() - stoppedAt
        val needsLock = coldStart || (stoppedAt > 0L && away >= AppLock.timeoutMs(activity) && System.currentTimeMillis() - unlockedAt > 1500L)
        coldStart = false
        if (needsLock && overlay == null) showLockOverlay()
    }

    private fun showLockOverlay() {
        val decor = activity.window.decorView as ViewGroup
        val cover = FrameLayout(activity).apply {
            setBackgroundColor(0xFF0B1730.toInt())
            isClickable = true
            isFocusable = true
        }
        val column = ui.column().apply { gravity = Gravity.CENTER }
        column.addView(ui.label("🔒", 44f, UiKit.WHITE).apply { gravity = Gravity.CENTER })
        column.addView(ui.label("QAMZOR مقفل", 20f, UiKit.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(4)) })
        column.addView(ui.label("افتح بالبصمة أو بقفل الجهاز", 12.5f, UiKit.MUTED).apply { gravity = Gravity.CENTER })
        column.addView(ui.space(20))
        column.addView(ui.button("فتح", primary = true) { requestUnlock() }, LinearLayout.LayoutParams(ui.dp(200), -2))
        cover.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        decor.addView(cover, FrameLayout.LayoutParams(-1, -1))
        overlay = cover
        handler.postDelayed({ if (overlay === cover) requestUnlock() }, 250L)
    }

    private fun requestUnlock() {
        if (AppLock.usesCredentialScreen()) {
            val intent = AppLock.credentialIntent(activity)
            if (intent == null) {
                dismissOverlay()
                return
            }
            try {
                activity.startActivityForResult(intent, AppLock.REQUEST_CREDENTIAL)
            } catch (error: Exception) {
                ui.toast("تعذر فتح شاشة التحقق")
            }
        } else {
            AppLock.authenticate(activity, { dismissOverlay() }, { /* stay locked; the button can retry */ })
        }
    }

    fun onCredentialResult(success: Boolean) {
        if (success) dismissOverlay()
    }

    private fun dismissOverlay() {
        val cover = overlay ?: return
        (cover.parent as? ViewGroup)?.removeView(cover)
        overlay = null
        unlockedAt = System.currentTimeMillis()
    }

    fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        SecureVault.purgeOpened(activity)
    }

    companion object {
        const val REQUEST_VAULT_PICK = 9302
        const val REQUEST_NOTIFICATIONS = 9303
    }
}
