package org.qamzor.android

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.text.TextUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import org.qamzor.core.ArchiveEngine
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Android front-end for QAMZOR: a built-in file manager plus QZRA archive tools. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pageTitle: TextView
    private lateinit var fileToolbar: LinearLayout
    private lateinit var fileListContainer: LinearLayout
    private lateinit var fileScroll: ScrollView
    private lateinit var permissionCard: View
    private lateinit var explorerContainer: LinearLayout
    private lateinit var explorerPathText: TextView
    private lateinit var archiveInfoText: TextView
    private lateinit var logSummary: TextView
    private lateinit var logText: TextView
    private val pages = ArrayList<View>()
    private val navBoxes = ArrayList<View>()
    private val navIcons = ArrayList<QamzorLineIconView>()
    private val navLabels = ArrayList<TextView>()
    private var currentTab = 0

    private val storageRoot: File = android.os.Environment.getExternalStorageDirectory()
    private var browseRoot: File = storageRoot
    private var currentDir: File = storageRoot
    private var currentItems: List<File> = emptyList()
    private val selection = LinkedHashSet<File>()

    private val backgroundTaskLock = Any()
    @Volatile private var backgroundBusy = false
    private var pendingExtraction: File? = null
    private var pendingExtractionPaths: Set<String>? = null
    private var openedArchive: File? = null
    private var openedArchiveTemp = false
    private var openedArchiveLabel: String = ""
    private var openedArchivePassword: String? = null
    private var openedProtected: Set<String> = emptySet()
    private var explorerPath: String = ""
    private val operationHistory = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val openedFolderPasswords: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())
    @Volatile private var activeOperationLabel: String? = null
    @Volatile private var activeOperationStartedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) purgeStaleCache()
        restoreOperationHistory()
        buildInterface()
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::fileListContainer.isInitialized) {
            if (hasStorageAccess()) {
                if (permissionCard.visibility == View.VISIBLE || currentItems.isEmpty()) loadDirectory(currentDir)
            } else showPermissionState(true)
        }
    }

    /** Working copies made from system-picker files never outlive the process that made them. */
    private fun purgeStaleCache() {
        try {
            cacheDir.listFiles()?.filter {
                it.name.startsWith("qamzor-") || it.name.startsWith("QAMZOR-") || it.name.startsWith("selected-archive-")
            }?.forEach { it.deleteRecursively() }
        } catch (_: Exception) { /* best effort */ }
    }

    /** Opening a .qzra from another app launches QAMZOR. */
    private fun handleViewIntent(launch: Intent?) {
        val uri = launch?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        selectTab(1)
        browseSelectedArchive(uri)
    }

    override fun onBackPressed() {
        when {
            currentTab == 0 && selection.isNotEmpty() -> { selection.clear(); refreshFileViews() }
            currentTab == 0 && currentDir.absolutePath != browseRoot.absolutePath -> { currentDir.parentFile?.let { loadDirectory(it) } }
            currentTab == 1 && openedArchive != null && explorerPath.isNotEmpty() -> showVirtualExplorer(explorerPath.substringBeforeLast('/', ""))
            currentTab != 0 -> selectTab(0)
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ───────────────────────────── Storage permission ─────────────────────────────

    private fun hasStorageAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")), REQUEST_ALL_FILES)
            } catch (_: Exception) {
                try {
                    startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQUEST_ALL_FILES)
                } catch (error: Exception) {
                    status.text = "تعذر فتح إعدادات الإذن: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_ALL_FILES)
        }
    }

    private fun showPermissionState(needed: Boolean) {
        permissionCard.visibility = if (needed) View.VISIBLE else View.GONE
        fileScroll.visibility = if (needed) View.GONE else View.VISIBLE
        fileToolbar.visibility = if (needed) View.GONE else View.VISIBLE
    }

    private fun operationPreferences() = getSharedPreferences("qamzor-operation-center", Context.MODE_PRIVATE)

    private fun restoreOperationHistory() {
        val preferences = operationPreferences()
        val saved = preferences.getString("events", "").orEmpty()
        synchronized(operationHistory) {
            operationHistory.clear()
            if (saved.isNotEmpty()) operationHistory.addAll(saved.lineSequence().filter { it.isNotBlank() }.toList().takeLast(100))
            val previousPid = preferences.getInt("active_pid", -1)
            val previousLabel = preferences.getString("active_label", null)
            if (previousPid > 0 && previousPid != android.os.Process.myPid()) {
                val whenText = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
                operationHistory.add("[$whenText] توقفت العملية بسبب إغلاق التطبيق أو إنهاء النظام — ${previousLabel ?: "عملية ملفات"}")
                while (operationHistory.size > 100) operationHistory.removeAt(0)
                preferences.edit().putString("events", operationHistory.joinToString("\n"))
                    .remove("active_pid").remove("active_label").remove("active_started_at").apply()
            }
        }
    }

    private fun recordOperationEvent(message: String) {
        synchronized(operationHistory) {
            operationHistory.add(message)
            while (operationHistory.size > 100) operationHistory.removeAt(0)
            operationPreferences().edit().putString("events", operationHistory.joinToString("\n")).apply()
        }
        runOnUiThread { refreshOperationLog() }
    }

    // ───────────────────────────── Shell: header, pages, status strip, bottom navigation ─────────────────────────────

    private fun buildInterface() {
        window.statusBarColor = COLOR_BAR
        window.navigationBarColor = COLOR_BAR

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(COLOR_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF1B3263.toInt(), 0xFF101D36.toInt()))
        }
        val mark = TextView(this).apply {
            text = "▣"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(COLOR_ACCENT, 9)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(30), dp(30)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("QAMZOR", 15f, 0xFFF7FAFF.toInt(), true))
        pageTitle = label(TAB_TITLES[0], 11f, 0xFFA9BEE3.toInt(), false)
        titles.addView(pageTitle)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        shell.addView(header, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            text = "جاهز."
            textSize = 11.5f
            setTextColor(0xFFDCE7F8.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setBackgroundColor(0xFF14223F.toInt())
            isClickable = true
            setOnClickListener { selectTab(2) }
        }

        val pageHost = FrameLayout(this)
        pages.add(buildFilesPage())
        pages.add(buildOpenPage())
        pages.add(buildOperationsPage())
        for (page in pages) pageHost.addView(page, FrameLayout.LayoutParams(-1, -1))
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(status, LinearLayout.LayoutParams(-1, -2))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(COLOR_BAR)
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        val specs = listOf("folder" to "الملفات", "archive" to "الأرشيف", "activity" to "العمليات")
        for ((index, spec) in specs.withIndex()) {
            val icon = QamzorLineIconView(this, spec.first, NAV_IDLE)
            val caption = TextView(this).apply {
                text = spec.second
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(NAV_IDLE)
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(5))
                isClickable = true
                isFocusable = true
                contentDescription = spec.second
                setOnClickListener { selectTab(index) }
            }
            box.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            box.addView(caption, LinearLayout.LayoutParams(-2, -2))
            navBoxes.add(box); navIcons.add(icon); navLabels.add(caption)
            nav.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        shell.addView(nav, LinearLayout.LayoutParams(-1, -2))

        setContentView(shell)
        selectTab(0)
        renderEmptyExplorer()
        refreshOperationLog()
        if (hasStorageAccess()) loadDirectory(currentDir) else showPermissionState(true)
    }

    private fun selectTab(index: Int) {
        currentTab = index
        for ((position, page) in pages.withIndex()) page.visibility = if (position == index) View.VISIBLE else View.GONE
        for (position in navBoxes.indices) {
            val selected = position == index
            val color = if (selected) 0xFFFFFFFF.toInt() else NAV_IDLE
            navIcons[position].applyTint(color)
            navLabels[position].setTextColor(color)
            navBoxes[position].background = rounded(if (selected) 0xFF2A4678.toInt() else 0x00000000, 14)
        }
        pageTitle.text = TAB_TITLES[index]
        if (index == 2) refreshOperationLog()
    }

    private fun pageScroll(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = false
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        setBackgroundColor(COLOR_BG)
        addView(content)
    }

    private fun pageColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(14), dp(12), dp(14), dp(18))
    }

    // ───────────────────────────── Page 1: Files (built-in file manager) ─────────────────────────────

    private fun buildFilesPage(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(COLOR_BG)
        }
        fileToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(8), dp(4), dp(8), dp(4))
        }
        column.addView(fileToolbar, LinearLayout.LayoutParams(-1, dp(54)))
        fileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(16))
        }
        fileScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(fileListContainer)
        }
        column.addView(fileScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        permissionCard = buildPermissionCard()
        permissionCard.visibility = View.GONE
        column.addView(permissionCard, LinearLayout.LayoutParams(-1, 0, 1f))
        renderToolbar()
        return column
    }

    private fun buildPermissionCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
        }
        box.addView(iconBadge("folder", 0x334C8DFF, COLOR_ACCENT, 84), LinearLayout.LayoutParams(dp(84), dp(84)))
        box.addView(label("اسمح بالوصول إلى ملفات هاتفك", 17f, 0xFFFFFFFF.toInt(), true)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(8)) })
        box.addView(label("لتصفح المجلدات وإنشاء الأرشيفات وفتحها مباشرة من الذاكرة، يحتاج QAMZOR إذن الوصول إلى كل الملفات. التطبيق لا يملك إذن الإنترنت، فلا يخرج أي شيء من جهازك.", 12.5f, 0xFFB7C9E8.toInt(), false)
            .apply { gravity = Gravity.CENTER })
        box.addView(actionButton("check", "منح الإذن", true) { requestStorageAccess() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(22) })
        box.addView(label("أو افتح أرشيفًا عبر منتقي النظام", 12f, 0xFF9FB4D3.toInt(), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
            isClickable = true
            setOnClickListener { pickArchive(REQUEST_BROWSE) }
        })
        return box
    }

    private fun toolbarButton(kind: String, description: String, tint: Int, action: () -> Unit): View = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        contentDescription = description
        background = rounded(0x00000000, 12)
        setOnClickListener { action() }
        addView(QamzorLineIconView(this@MainActivity, kind, tint), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }

    private fun renderToolbar() {
        fileToolbar.removeAllViews()
        val size = LinearLayout.LayoutParams(dp(42), dp(42))
        if (selection.isEmpty()) {
            val atRoot = currentDir.absolutePath == browseRoot.absolutePath
            fileToolbar.addView(
                toolbarButton(if (atRoot) "home" else "up", if (atRoot) "التخزين" else "المجلد الأعلى", 0xFFFFFFFF.toInt()) {
                    if (atRoot) showStorageSheet() else currentDir.parentFile?.let { loadDirectory(it) }
                }, size)
            val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(label(if (atRoot) volumeName(browseRoot) else currentDir.name, 15f, 0xFFFFFFFF.toInt(), true)
                .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setSingleLine(true) })
            titles.addView(label(displayPath(currentDir), 10.5f, 0xFF9FB4D3.toInt(), false)
                .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true) })
            fileToolbar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            fileToolbar.addView(toolbarButton("storage", "التخزين", 0xFFBBD2FF.toInt()) { showStorageSheet() }, size)
            fileToolbar.addView(toolbarButton("folder_add", "مجلد جديد", 0xFFBBD2FF.toInt()) { promptNewFolder() }, size)
        } else {
            fileToolbar.addView(toolbarButton("close", "إلغاء التحديد", 0xFFFFFFFF.toInt()) { selection.clear(); refreshFileViews() }, size)
            fileToolbar.addView(label("${selection.size} محدد", 15f, 0xFFFFFFFF.toInt(), true),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            fileToolbar.addView(toolbarButton("archive", "أرشفة", 0xFFFFFFFF.toInt()) { showArchiveDialog(selection.toList(), false) }, size)
            fileToolbar.addView(toolbarButton("lock", "حماية بكلمة مرور", 0xFFFFD27A.toInt()) { showArchiveDialog(selection.toList(), true) }, size)
            fileToolbar.addView(toolbarButton("trash", "حذف", 0xFFFFB6B6.toInt()) { confirmDelete(selection.toList()) }, size)
            fileToolbar.addView(toolbarButton("more", "المزيد", 0xFFFFFFFF.toInt()) { showSelectionSheet() }, size)
        }
    }

    private fun volumeName(root: File): String =
        if (root.absolutePath == storageRoot.absolutePath) "الذاكرة الداخلية" else "تخزين خارجي"

    private fun displayPath(dir: File): String {
        val base = browseRoot.absolutePath
        val full = dir.absolutePath
        return if (full == base) volumeName(browseRoot) else volumeName(browseRoot) + full.removePrefix(base)
    }

    private fun refreshFileViews() {
        renderToolbar()
        val y = fileScroll.scrollY
        renderFileList(currentItems, false)
        fileScroll.post { fileScroll.scrollTo(0, y) }
    }

    private fun loadDirectory(dir: File) {
        if (!hasStorageAccess()) {
            showPermissionState(true)
            return
        }
        showPermissionState(false)
        currentDir = dir
        selection.clear()
        renderToolbar()
        Thread {
            val raw = try { dir.listFiles() } catch (_: Exception) { null }
            val items = (raw?.toList() ?: emptyList())
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                .take(3000)
            runOnUiThread {
                if (currentDir == dir) {
                    currentItems = items
                    renderFileList(items, raw == null)
                    fileScroll.scrollTo(0, 0)
                }
            }
        }.start()
    }

    private fun renderFileList(items: List<File>, unreadable: Boolean) {
        fileListContainer.removeAllViews()
        if (unreadable) {
            fileListContainer.addView(label("تعذر قراءة هذا المجلد.", 13f, 0xFFFFB6B6.toInt(), false)
                .apply { setPadding(dp(6), dp(24), dp(6), dp(24)); gravity = Gravity.CENTER })
            return
        }
        if (items.isEmpty()) {
            fileListContainer.addView(label("هذا المجلد فارغ.", 13f, 0xFF9FB4D3.toInt(), false)
                .apply { setPadding(dp(6), dp(24), dp(6), dp(24)); gravity = Gravity.CENTER })
            return
        }
        for (file in items) {
            fileListContainer.addView(fileRow(file), LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(6) })
        }
    }

    private fun isQzra(file: File): Boolean = file.isFile && file.name.endsWith(".qzra", ignoreCase = true)

    /** Icon kind, badge colour and icon colour by file type. */
    private fun typeStyle(file: File): Triple<String, Int, Int> {
        if (file.isDirectory) return Triple("folder", 0x33FFC857, 0xFFFFC857.toInt())
        return when (file.extension.lowercase(Locale.ROOT)) {
            "qzra" -> Triple("archive", 0x554C8DFF, 0xFF8FB4FF.toInt())
            "zip", "rar", "7z", "tar", "gz", "xz", "bz2" -> Triple("archive", 0x33B0BEC5, 0xFFCFD8DC.toInt())
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Triple("image", 0x33F06292, 0xFFF48FB1.toInt())
            "mp4", "mkv", "avi", "mov", "3gp", "webm" -> Triple("video", 0x33B388FF, 0xFFD1B3FF.toInt())
            "mp3", "m4a", "wav", "ogg", "flac", "aac" -> Triple("audio", 0x3369DB7C, 0xFF9BE8A8.toInt())
            else -> Triple("file", 0x334B73AA, 0xFFBBD2FF.toInt())
        }
    }

    private fun fileRow(file: File): View {
        val style = typeStyle(file)
        val selected = selection.contains(file)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(10), dp(6), dp(4), dp(6))
            background = rounded(if (selected) 0xFF27457A.toInt() else 0xFF14223F.toInt(), 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { onFileTap(file) }
            setOnLongClickListener { toggleSelection(file); true }
        }
        val badge = if (selected) iconBadge("check", COLOR_ACCENT, 0xFFFFFFFF.toInt(), 42) else iconBadge(style.first, style.second, style.third, 42)
        row.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(file.name, 14f, 0xFFF4F7FF.toInt(), true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true)
        })
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(file.lastModified()))
        val detail = when {
            file.isDirectory -> "مجلد  •  $date"
            isQzra(file) -> "QZRA  •  ${formatBytes(file.length())}  •  $date"
            else -> "${formatBytes(file.length())}  •  $date"
        }
        texts.addView(label(detail, 10.5f, 0xFF9FB4D3.toInt(), false).apply { maxLines = 1 })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val more = toolbarButton("more", "خيارات ${file.name}", 0xFF9FB4D3.toInt()) { showItemSheet(file) }
        row.addView(more, LinearLayout.LayoutParams(dp(40), dp(40)))
        return row
    }

    private fun toggleSelection(file: File) {
        if (!selection.remove(file)) selection.add(file)
        refreshFileViews()
    }

    private fun onFileTap(file: File) {
        when {
            selection.isNotEmpty() -> toggleSelection(file)
            file.isDirectory -> loadDirectory(file)
            isQzra(file) -> openArchiveFile(file, file.name, false)
            else -> showItemSheet(file)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun iconBadge(kind: String, backgroundColor: Int, tint: Int, size: Int): View = FrameLayout(this).apply {
        background = rounded(backgroundColor, 10)
        val inner = dp((size * 0.55f).toInt())
        addView(QamzorLineIconView(this@MainActivity, kind, tint), FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Small outline icons drawn locally; no external icon dependency. */
    private inner class QamzorLineIconView(context: Context, private val kind: String, tint: Int) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = 1.9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val shape = Path()

        fun applyTint(color: Int) {
            stroke.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width, height) / 24f
            if (scale <= 0f) return
            canvas.save()
            canvas.translate((width - 24f * scale) / 2f, (height - 24f * scale) / 2f)
            canvas.scale(scale, scale)
            when (kind) {
                "folder", "folder_add", "folder_search" -> {
                    shape.reset()
                    shape.moveTo(3f, 7f); shape.lineTo(9.2f, 7f); shape.lineTo(11.2f, 9f)
                    shape.lineTo(21f, 9f); shape.quadTo(21f, 9f, 21f, 11f)
                    shape.lineTo(21f, 18f); shape.quadTo(21f, 20f, 19f, 20f)
                    shape.lineTo(5f, 20f); shape.quadTo(3f, 20f, 3f, 18f); shape.close()
                    canvas.drawPath(shape, stroke)
                    if (kind == "folder_add") {
                        canvas.drawLine(12f, 12f, 12f, 18f, stroke)
                        canvas.drawLine(9f, 15f, 15f, 15f, stroke)
                    } else if (kind == "folder_search") {
                        canvas.drawCircle(16.4f, 14.7f, 2.5f, stroke)
                        canvas.drawLine(18.2f, 16.5f, 20.7f, 19f, stroke)
                    }
                }
                "convert" -> {
                    canvas.drawArc(RectF(3f, 4f, 20f, 20f), 205f, 145f, false, stroke)
                    shape.reset(); shape.moveTo(3.5f, 5f); shape.lineTo(3f, 9f); shape.lineTo(7f, 8f)
                    canvas.drawPath(shape, stroke)
                    canvas.drawArc(RectF(4f, 4f, 21f, 20f), 25f, 145f, false, stroke)
                    shape.reset(); shape.moveTo(20.5f, 19f); shape.lineTo(21f, 15f); shape.lineTo(17f, 16f)
                    canvas.drawPath(shape, stroke)
                }
                "compare" -> {
                    canvas.drawLine(8f, 4f, 8f, 20f, stroke)
                    canvas.drawLine(16f, 4f, 16f, 20f, stroke)
                    shape.reset(); shape.moveTo(4.5f, 8f); shape.lineTo(8f, 4.5f); shape.lineTo(11.5f, 8f)
                    canvas.drawPath(shape, stroke)
                    shape.reset(); shape.moveTo(12.5f, 16f); shape.lineTo(16f, 19.5f); shape.lineTo(19.5f, 16f)
                    canvas.drawPath(shape, stroke)
                }
                "shield_check" -> {
                    shape.reset(); shape.moveTo(12f, 2.5f); shape.lineTo(20f, 5.5f)
                    shape.lineTo(20f, 11.3f); shape.cubicTo(20f, 16f, 16.7f, 19.8f, 12f, 22f)
                    shape.cubicTo(7.3f, 19.8f, 4f, 16f, 4f, 11.3f); shape.lineTo(4f, 5.5f); shape.close()
                    canvas.drawPath(shape, stroke)
                    shape.reset(); shape.moveTo(8.2f, 12.2f); shape.lineTo(10.8f, 14.8f); shape.lineTo(16.2f, 9.5f)
                    canvas.drawPath(shape, stroke)
                }
                "extract" -> {
                    canvas.drawRect(4f, 14f, 20f, 21f, stroke)
                    canvas.drawLine(8f, 17.5f, 16f, 17.5f, stroke)
                    canvas.drawLine(12f, 3f, 12f, 14f, stroke)
                    shape.reset(); shape.moveTo(8.5f, 10.5f); shape.lineTo(12f, 14f); shape.lineTo(15.5f, 10.5f)
                    canvas.drawPath(shape, stroke)
                }
                "file" -> {
                    shape.reset(); shape.moveTo(6f, 2.8f); shape.lineTo(14.5f, 2.8f); shape.lineTo(20f, 8.3f)
                    shape.lineTo(20f, 21f); shape.lineTo(6f, 21f); shape.close()
                    canvas.drawPath(shape, stroke)
                    canvas.drawLine(14.5f, 3f, 14.5f, 8.5f, stroke)
                    canvas.drawLine(14.5f, 8.5f, 19.8f, 8.5f, stroke)
                    canvas.drawLine(9f, 13f, 17f, 13f, stroke)
                    canvas.drawLine(9f, 16.5f, 17f, 16.5f, stroke)
                }
                "trash" -> {
                    canvas.drawLine(4f, 6f, 20f, 6f, stroke)
                    canvas.drawLine(9f, 3.5f, 15f, 3.5f, stroke)
                    canvas.drawLine(6f, 6f, 7f, 20f, stroke)
                    canvas.drawLine(18f, 6f, 17f, 20f, stroke)
                    canvas.drawLine(7f, 20f, 17f, 20f, stroke)
                    canvas.drawLine(10f, 9.5f, 10.5f, 17f, stroke)
                    canvas.drawLine(14f, 9.5f, 13.5f, 17f, stroke)
                }
                "lock", "unlock" -> {
                    canvas.drawRect(5f, 11f, 19f, 21f, stroke)
                    canvas.drawArc(RectF(8f, 3f, 16f, 11f), 180f, 180f, false, stroke)
                    canvas.drawLine(8f, 7f, 8f, 11f, stroke)
                    if (kind == "lock") canvas.drawLine(16f, 7f, 16f, 11f, stroke)
                    canvas.drawCircle(12f, 16f, 1.3f, stroke)
                }
                "plus" -> {
                    canvas.drawLine(12f, 5f, 12f, 19f, stroke)
                    canvas.drawLine(5f, 12f, 19f, 12f, stroke)
                }
                "close" -> {
                    canvas.drawLine(6f, 6f, 18f, 18f, stroke)
                    canvas.drawLine(18f, 6f, 6f, 18f, stroke)
                }
                "create" -> {
                    canvas.drawRect(3f, 4f, 21f, 9f, stroke)
                    canvas.drawRect(5f, 9f, 19f, 20f, stroke)
                    canvas.drawLine(10f, 13f, 14f, 13f, stroke)
                }
                "tools" -> {
                    canvas.drawRect(4f, 4f, 10f, 10f, stroke)
                    canvas.drawRect(14f, 4f, 20f, 10f, stroke)
                    canvas.drawRect(4f, 14f, 10f, 20f, stroke)
                    canvas.drawRect(14f, 14f, 20f, 20f, stroke)
                }
                "activity" -> {
                    shape.reset(); shape.moveTo(3f, 12f); shape.lineTo(8f, 12f); shape.lineTo(11f, 5f)
                    shape.lineTo(14f, 19f); shape.lineTo(17f, 12f); shape.lineTo(21f, 12f)
                    canvas.drawPath(shape, stroke)
                }
                "archive" -> {
                    canvas.drawRect(5f, 3f, 19f, 21f, stroke)
                    canvas.drawLine(12f, 3f, 12f, 10f, stroke)
                    canvas.drawRect(10f, 10f, 14f, 15f, stroke)
                }
                "image" -> {
                    canvas.drawRect(3f, 5f, 21f, 19f, stroke)
                    canvas.drawCircle(9f, 10f, 1.6f, stroke)
                    shape.reset(); shape.moveTo(4f, 18f); shape.lineTo(10f, 12f); shape.lineTo(14f, 16f); shape.lineTo(17f, 13f); shape.lineTo(20f, 16f)
                    canvas.drawPath(shape, stroke)
                }
                "video" -> {
                    canvas.drawRect(3f, 6f, 15f, 18f, stroke)
                    shape.reset(); shape.moveTo(15f, 10f); shape.lineTo(21f, 7f); shape.lineTo(21f, 17f); shape.lineTo(15f, 14f)
                    canvas.drawPath(shape, stroke)
                }
                "audio" -> {
                    canvas.drawCircle(7f, 17f, 2.6f, stroke)
                    canvas.drawLine(9.6f, 17f, 9.6f, 5f, stroke)
                    shape.reset(); shape.moveTo(9.6f, 5f); shape.lineTo(18f, 7.5f); shape.lineTo(18f, 10f); shape.lineTo(9.6f, 8f)
                    canvas.drawPath(shape, stroke)
                }
                "more" -> {
                    canvas.drawCircle(12f, 5f, 1.3f, stroke)
                    canvas.drawCircle(12f, 12f, 1.3f, stroke)
                    canvas.drawCircle(12f, 19f, 1.3f, stroke)
                }
                "check" -> {
                    shape.reset(); shape.moveTo(5f, 12.5f); shape.lineTo(10f, 17.5f); shape.lineTo(19f, 7f)
                    canvas.drawPath(shape, stroke)
                }
                "up" -> {
                    canvas.drawLine(12f, 19f, 12f, 5f, stroke)
                    shape.reset(); shape.moveTo(6f, 11f); shape.lineTo(12f, 5f); shape.lineTo(18f, 11f)
                    canvas.drawPath(shape, stroke)
                }
                "home" -> {
                    shape.reset(); shape.moveTo(3f, 11f); shape.lineTo(12f, 3.5f); shape.lineTo(21f, 11f)
                    canvas.drawPath(shape, stroke)
                    canvas.drawRect(6f, 11f, 18f, 21f, stroke)
                }
                "storage" -> {
                    canvas.drawRect(4f, 5f, 20f, 19f, stroke)
                    canvas.drawLine(4f, 14f, 20f, 14f, stroke)
                    canvas.drawCircle(16.5f, 16.5f, 0.9f, stroke)
                }
                "info" -> {
                    canvas.drawCircle(12f, 12f, 9f, stroke)
                    canvas.drawLine(12f, 11f, 12f, 17f, stroke)
                    canvas.drawCircle(12f, 7.6f, 0.8f, stroke)
                }
                "edit" -> {
                    shape.reset(); shape.moveTo(4f, 20f); shape.lineTo(5f, 15f); shape.lineTo(16f, 4f); shape.lineTo(20f, 8f); shape.lineTo(9f, 19f); shape.close()
                    canvas.drawPath(shape, stroke)
                }
                else -> canvas.drawCircle(12f, 12f, 8f, stroke)
            }
            canvas.restore()
        }
    }

    // ───────────────────────────── Action sheets (tap ⋮ on any file or folder) ─────────────────────────────

    private class SheetAction(val icon: String, val caption: String, val danger: Boolean, val run: () -> Unit)

    private fun showSheet(title: String, subtitle: String, actions: List<SheetAction>) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val radius = dp(22).toFloat()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(16), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(0xFF14223F.toInt())
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        box.addView(label(title, 15f, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true)
        })
        if (subtitle.isNotEmpty()) box.addView(label(subtitle, 11f, 0xFF9FB4D3.toInt(), false).apply { setPadding(0, dp(2), 0, dp(4)) })
        for (action in actions) {
            val tint = if (action.danger) 0xFFFFB6B6.toInt() else 0xFFBBD2FF.toInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(6), 0, dp(6), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); action.run() }
            }
            row.addView(QamzorLineIconView(this, action.icon, tint), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            row.addView(label(action.caption, 14f, if (action.danger) 0xFFFFB6B6.toInt() else 0xFFF4F7FF.toInt(), false))
            box.addView(row, LinearLayout.LayoutParams(-1, dp(50)))
        }
        dialog.setContentView(ScrollView(this).apply { addView(box) })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    private fun showItemSheet(file: File) {
        val actions = ArrayList<SheetAction>()
        if (file.isDirectory) {
            actions += SheetAction("folder", "فتح المجلد", false) { loadDirectory(file) }
        }
        if (isQzra(file)) {
            actions += SheetAction("folder_search", "فتح الأرشيف", false) { openArchiveFile(file, file.name, false) }
            actions += SheetAction("extract", "استخراج هنا", false) { quickExtract(file) }
            actions += SheetAction("shield_check", "فحص السلامة", false) { verifyFile(file) }
        } else {
            actions += SheetAction("archive", "إضافة إلى أرشيف QZRA…", false) { showArchiveDialog(listOf(file), false) }
            actions += SheetAction("lock", "حماية بكلمة مرور…", false) { showArchiveDialog(listOf(file), true) }
        }
        actions += SheetAction("edit", "إعادة تسمية", false) { promptRename(file) }
        actions += SheetAction("info", "التفاصيل", false) { showDetails(file) }
        actions += SheetAction("trash", "حذف", true) { confirmDelete(listOf(file)) }
        showSheet(file.name, if (file.isDirectory) "مجلد" else formatBytes(file.length()), actions)
    }

    private fun showSelectionSheet() {
        val actions = ArrayList<SheetAction>()
        actions += SheetAction("check", "تحديد الكل", false) { selection.addAll(currentItems); refreshFileViews() }
        val chosen = selection.toList()
        if (chosen.size == 2 && chosen.all { isQzra(it) }) {
            actions += SheetAction("compare", "مقارنة الأرشيفين", false) { compareArchiveFiles(chosen[0], chosen[1]) }
        }
        if (chosen.size == 1) actions += SheetAction("edit", "إعادة تسمية", false) { promptRename(chosen[0]) }
        actions += SheetAction("close", "إلغاء التحديد", false) { selection.clear(); refreshFileViews() }
        showSheet("${chosen.size} عنصر محدد", "", actions)
    }

    private fun storageVolumes(): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>()
        result.add("الذاكرة الداخلية" to storageRoot)
        try {
            for (dir in getExternalFilesDirs(null)) {
                val path = dir?.absolutePath ?: continue
                val marker = path.indexOf("/Android/data")
                if (marker <= 0) continue
                val root = File(path.substring(0, marker))
                if (result.none { it.second.absolutePath == root.absolutePath }) result.add("تخزين خارجي (${root.name})" to root)
            }
        } catch (_: Exception) { /* best effort */ }
        return result
    }

    private fun showStorageSheet() {
        val actions = storageVolumes().map { volume ->
            SheetAction(if (volume.second == storageRoot) "home" else "storage", volume.first, false) {
                browseRoot = volume.second
                loadDirectory(volume.second)
            }
        }
        showSheet("التخزين", "اختر مكان التصفح", actions)
    }

    // ───────────────────────────── File operations: new folder, rename, delete, details ─────────────────────────────

    private fun promptNewFolder() {
        val input = EditText(this).apply { hint = "اسم المجلد"; setSingleLine(true); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        AlertDialog.Builder(this).setTitle("مجلد جديد").setView(input)
            .setPositiveButton("إنشاء") { _, _ ->
                val name = input.text.toString().trim()
                val target = File(currentDir, name)
                when {
                    name.isEmpty() || name.contains('/') -> { status.text = "اسم المجلد غير صالح." }
                    target.exists() -> { status.text = "يوجد عنصر بنفس الاسم." }
                    target.mkdir() -> { status.text = "تم إنشاء المجلد: $name"; loadDirectory(currentDir) }
                    else -> { status.text = "تعذر إنشاء المجلد." }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun promptRename(file: File) {
        val input = EditText(this).apply { setText(file.name); setSingleLine(true); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        AlertDialog.Builder(this).setTitle("إعادة تسمية").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val name = input.text.toString().trim()
                val target = File(file.parentFile, name)
                when {
                    name.isEmpty() || name.contains('/') -> { status.text = "الاسم غير صالح." }
                    target.exists() -> { status.text = "يوجد عنصر بنفس الاسم." }
                    file.renameTo(target) -> { status.text = "تمت إعادة التسمية."; loadDirectory(currentDir) }
                    else -> { status.text = "تعذرت إعادة التسمية." }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty()) return
        val what = if (files.size == 1) "«${files[0].name}»" else "${files.size} عناصر"
        AlertDialog.Builder(this).setTitle("حذف نهائي")
            .setMessage("سيتم حذف $what نهائيًا من الهاتف ولا يمكن التراجع.")
            .setPositiveButton("حذف") { _, _ ->
                runOnWorker("فشل الحذف") {
                    var failed = 0
                    for (file in files) if (!file.deleteRecursively()) failed++
                    runOnUiThread {
                        status.text = if (failed == 0) "تم الحذف." else "تعذر حذف $failed عنصر."
                        loadDirectory(currentDir)
                    }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showDetails(file: File) {
        val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))
        if (file.isFile) {
            AlertDialog.Builder(this).setTitle(file.name)
                .setMessage("المسار: ${file.absolutePath}\nالحجم: ${formatBytes(file.length())}\nآخر تعديل: $date")
                .setPositiveButton("تم", null).show()
            return
        }
        status.text = "جارٍ حساب حجم المجلد..."
        Thread {
            var size = 0L
            var count = 0
            try { file.walkTopDown().forEach { if (it.isFile) { size += it.length(); count++ } } } catch (_: Exception) { /* partial */ }
            runOnUiThread {
                status.text = "جاهز."
                AlertDialog.Builder(this).setTitle(file.name)
                    .setMessage("المسار: ${file.absolutePath}\nالملفات: $count\nالحجم: ${formatBytes(size)}\nآخر تعديل: $date")
                    .setPositiveButton("تم", null).show()
            }
        }.start()
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val stem = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")
        var number = 2
        while (true) {
            candidate = File(dir, if (extension.isEmpty()) "$stem ($number)" else "$stem ($number).$extension")
            if (!candidate.exists()) return candidate
            number++
        }
    }

    private fun uniqueDir(parent: File, name: String): File {
        var candidate = File(parent, name)
        var number = 2
        while (candidate.exists()) {
            candidate = File(parent, "$name ($number)")
            number++
        }
        return candidate
    }

    // ───────────────────────────── Create archive / protect with password ─────────────────────────────

    private fun collectDirectoryPaths(sources: List<File>): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, path: String, depth: Int) {
            if (out.size >= 40) return
            out.add(path)
            if (depth >= 3) return
            val children = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: return
            for (child in children) walk(child, "$path/${child.name}", depth + 1)
        }
        for (source in sources) if (source.isDirectory) walk(source, source.name, 0)
        return out
    }

    private fun promptFolderLock(path: String, locks: MutableMap<String, String>, onChanged: () -> Unit) {
        val existing = locks.containsKey(path)
        val first = dialogField("كلمة مرور المجلد (10 أحرف على الأقل)")
        val second = dialogField("تأكيد كلمة المرور")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(first); addView(second)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("كلمة مرور المجلد: ${path.substringAfterLast('/')}")
            .setMessage("كلمة مرور مستقلة لهذا المجلد فقط. لا يمكن قراءة ملفاته أو فحصها أو استخراجها بدونها، حتى مع كلمة مرور الأرشيف.")
            .setView(panel)
            .setPositiveButton("حماية المجلد") { _, _ ->
                val value = first.text.toString()
                val confirmation = second.text.toString()
                first.text?.clear(); second.text?.clear()
                when {
                    value.length < 10 -> { status.text = "كلمة مرور المجلد قصيرة؛ استخدم 10 أحرف على الأقل."; promptFolderLock(path, locks, onChanged) }
                    value != confirmation -> { status.text = "كلمتا مرور المجلد غير متطابقتين."; promptFolderLock(path, locks, onChanged) }
                    !existing && locks.size >= 32 -> { status.text = "الحد الأقصى 32 مجلدًا بكلمات مرور مستقلة في الأرشيف الواحد." }
                    else -> { locks[path] = value; onChanged() }
                }
            }
            .setNegativeButton("إلغاء", null)
        if (existing) builder.setNeutralButton("إزالة الحماية") { _, _ -> locks.remove(path); onChanged() }
        builder.show()
    }

    private fun showArchiveDialog(sources: List<File>, protectPreset: Boolean) {
        if (sources.isEmpty()) return
        if (backgroundBusy) {
            status.text = "هناك عملية قيد التنفيذ. انتظر حتى تنتهي."
            return
        }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val locks = LinkedHashMap<String, String>()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = rounded(0xFF14223F.toInt(), 22)
        }
        card.addView(label(if (protectPreset) "حماية بكلمة مرور" else "إنشاء أرشيف QZRA", 17f, 0xFFFFFFFF.toInt(), true))
        card.addView(label(if (sources.size == 1) sources[0].name else "${sources.size} عناصر محددة", 11.5f, 0xFF9FB4D3.toInt(), false)
            .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true); setPadding(0, dp(2), 0, dp(10)) })

        val defaultName = if (sources.size == 1) sources[0].name.substringBeforeLast('.', sources[0].name).ifEmpty { sources[0].name } else currentDir.name.ifEmpty { "Archive" }
        val nameField = inputField("اسم الأرشيف", false).apply { setText(defaultName) }
        card.addView(nameField, LinearLayout.LayoutParams(-1, -2))
        card.addView(label("يُحفظ في: ${displayPath(currentDir)}", 10.5f, 0xFF8FA6C9.toInt(), false).apply { setPadding(dp(2), dp(4), dp(2), dp(6)) })

        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (protectPreset) View.VISIBLE else View.GONE }
        val password = inputField("كلمة مرور الأرشيف (10 أحرف على الأقل)", true)
        val confirm = inputField("تأكيد كلمة المرور", true)
        val hideNames = CheckBox(this).apply {
            text = "إخفاء أسماء الملفات والمجلدات وأحجامها"
            textSize = 12.5f
            isChecked = true
            setTextColor(0xFFD8E5F8.toInt())
            buttonTintList = ColorStateList.valueOf(COLOR_ACCENT)
        }
        fields.addView(password, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        fields.addView(confirm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fields.addView(hideNames)
        val toggle = Switch(this).apply {
            text = "تشفير الأرشيف بكلمة مرور"
            textSize = 14f
            isChecked = protectPreset
            setTextColor(0xFFF4F7FF.toInt())
            setOnCheckedChangeListener { _, checked -> fields.visibility = if (checked) View.VISIBLE else View.GONE }
        }
        card.addView(toggle, LinearLayout.LayoutParams(-1, dp(44)))
        card.addView(fields, LinearLayout.LayoutParams(-1, -2))

        val lockSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderLocks() {
            lockSection.removeAllViews()
            val directories = collectDirectoryPaths(sources)
            if (directories.isEmpty()) return
            lockSection.addView(label("كلمة مرور مستقلة لمجلدات معيّنة (اختياري)", 12f, 0xFFB9D0F7.toInt(), true).apply { setPadding(0, dp(10), 0, dp(4)) })
            for (path in directories) {
                val locked = locks.containsKey(path)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    gravity = Gravity.CENTER_VERTICAL
                    setPaddingRelative(dp(8) + dp(10) * path.count { it == '/' }, 0, dp(4), 0)
                    background = rounded(0xFF1D3152.toInt(), 10)
                }
                row.addView(QamzorLineIconView(this, "folder", 0xFFFFC857.toInt()), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                row.addView(label(path.substringAfterLast('/'), 12.5f, 0xFFF4F7FF.toInt(), false).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setSingleLine(true)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val lockButton = iconBadge(if (locked) "lock" else "unlock", if (locked) 0x44FFD27A else 0x334B73AA,
                    if (locked) 0xFFFFD27A.toInt() else 0xFFBBD2FF.toInt(), 32).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = "كلمة مرور مستقلة للمجلد $path"
                    setOnClickListener {
                        promptFolderLock(path, locks) {
                            if (locks.isNotEmpty()) toggle.isChecked = true
                            renderLocks()
                        }
                    }
                }
                row.addView(lockButton, LinearLayout.LayoutParams(dp(32), dp(32)))
                lockSection.addView(row, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(4) })
            }
        }
        renderLocks()
        card.addView(lockSection, LinearLayout.LayoutParams(-1, -2))

        val deleteWarning = label("سيُحذف الأصل نهائيًا بعد نجاح الإنشاء والتحقق، ويبقى الأرشيف فقط.", 11f, 0xFFFFB6B6.toInt(), false)
            .apply { visibility = View.GONE; setPadding(dp(2), 0, dp(2), dp(2)) }
        val deleteBox = CheckBox(this).apply {
            text = "حذف الأصل بعد الأرشفة (استبداله بالأرشيف)"
            textSize = 12.5f
            setTextColor(0xFFD8E5F8.toInt())
            buttonTintList = ColorStateList.valueOf(0xFFFF8A8A.toInt())
            setOnCheckedChangeListener { _, checked -> deleteWarning.visibility = if (checked) View.VISIBLE else View.GONE }
        }
        card.addView(deleteBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        card.addView(deleteWarning)

        val errorText = label("", 11.5f, 0xFFFF9C9C.toInt(), true).apply { visibility = View.GONE; setPadding(dp(2), dp(8), dp(2), 0) }
        card.addView(errorText)
        fun fail(message: String) { errorText.text = message; errorText.visibility = View.VISIBLE }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        buttons.addView(actionButton("check", "إنشاء", true) {
            val rawName = nameField.text.toString().trim().removeSuffix(".qzra").trim()
            if (rawName.isEmpty() || rawName.contains('/')) { fail("اسم الأرشيف غير صالح."); return@actionButton }
            val protect = toggle.isChecked || locks.isNotEmpty()
            var secret: String? = null
            if (protect) {
                val first = password.text.toString()
                val second = confirm.text.toString()
                if (first.length < 10) { fail("كلمة مرور الأرشيف مطلوبة (10 أحرف على الأقل)."); return@actionButton }
                if (first != second) { fail("كلمتا مرور الأرشيف غير متطابقتين."); return@actionButton }
                secret = first
            }
            val output = uniqueFile(currentDir, "$rawName.qzra")
            val hide = protect && (hideNames.isChecked || locks.isNotEmpty())
            val removeOriginals = deleteBox.isChecked
            password.text?.clear(); confirm.text?.clear()
            dialog.dismiss()
            createArchiveFromFiles(sources, output, secret, hide, LinkedHashMap(locks), removeOriginals)
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        buttons.addView(actionButton("close", "إلغاء", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        dialog.setContentView(ScrollView(this).apply { addView(card) })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }

    private fun createArchiveFromFiles(
        sources: List<File>, output: File, password: String?, encryptMetadata: Boolean,
        locks: Map<String, String>, deleteOriginals: Boolean
    ) {
        status.text = "جارٍ إنشاء ${output.name}…"
        runOnWorker("فشل إنشاء أرشيف QZRA") {
            try {
                val outputPath = output.canonicalFile.toPath()
                if (deleteOriginals) {
                    require(sources.none { outputPath.startsWith(it.canonicalFile.toPath()) }) { "لا يمكن حذف الأصل لأن الأرشيف داخله" }
                }
                val result = ArchiveEngine.convertToQzra(
                    output, sources, password = password, encryptMetadata = encryptMetadata, folderPasswords = locks
                )
                val verified = ArchiveEngine.verify(output, password, locks)
                require(verified.valid && verified.filesChecked == result.files) { "تعذر التحقق من الأرشيف بعد الإنشاء" }
                var removed = 0
                if (deleteOriginals) for (source in sources) if (source.deleteRecursively()) removed++
                runOnUiThread {
                    status.text = "تم إنشاء ${output.name} ✓ (${verified.version}) • الملفات: ${result.files} • " +
                        "${formatBytes(result.originalBytes)} ← ${formatBytes(result.storedBytes)}" +
                        (if (locks.isNotEmpty()) " • مجلدات محمية: ${locks.size}" else "") +
                        (if (deleteOriginals) " • حُذف الأصل ($removed)" else "")
                    loadDirectory(currentDir)
                }
            } catch (error: Exception) {
                try { output.delete() } catch (_: Exception) { /* best effort */ }
                throw error
            }
        }
    }

    // ───────────────────────────── Page 2: opened archive ─────────────────────────────

    private fun buildOpenPage(): View {
        val column = pageColumn()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        row.addView(actionButton("extract", "استخراج", true) { extractOpenedArchive() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        row.addView(actionButton("shield_check", "فحص", false) { verifyOpenedArchive() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        row.addView(actionButton("close", "إغلاق", false) { closeOpenedArchive() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        column.addView(row)
        archiveInfoText = label("", 14f, 0xFFF4F7FF.toInt(), true).apply { setPadding(dp(2), dp(14), dp(2), dp(0)) }
        explorerPathText = label("", 11f, 0xFF9FB4D3.toInt(), false).apply { setPadding(dp(2), dp(2), dp(2), dp(8)) }
        column.addView(archiveInfoText)
        column.addView(explorerPathText)
        explorerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(explorerContainer, LinearLayout.LayoutParams(-1, -2))
        return pageScroll(column)
    }

    private fun renderEmptyExplorer() {
        explorerContainer.removeAllViews()
        archiveInfoText.text = "لا يوجد أرشيف مفتوح"
        explorerPathText.text = ""
        explorerContainer.addView(label("اضغط على أي ملف .qzra في تبويب «الملفات» لفتحه هنا. الأرشيفات المحمية لا يقرأها أي تطبيق آخر.", 12.5f, 0xFF9FB4D3.toInt(), false)
            .apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
        explorerContainer.addView(actionButton("folder_search", "فتح عبر منتقي النظام", false) { pickArchive(REQUEST_BROWSE) },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
    }

    private fun explorerRow(kind: String, title: String, subtitle: String, highlight: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0xFF14223F.toInt(), 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val tint = if (highlight) 0xFFFFD27A.toInt() else if (kind == "folder") 0xFFFFC857.toInt() else 0xFFBBD2FF.toInt()
        val badgeColor = if (highlight) 0x44FFD27A else if (kind == "folder") 0x33FFC857 else 0x334B73AA
        row.addView(iconBadge(kind, badgeColor, tint, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 14f, 0xFFF4F7FF.toInt(), true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true)
        })
        if (subtitle.isNotEmpty()) texts.addView(label(subtitle, 10.5f, 0xFF9FB4D3.toInt(), false).apply { maxLines = 1 })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun renderExplorer(path: String, children: List<ArchiveEngine.BrowseItem>) {
        explorerPath = path
        explorerContainer.removeAllViews()
        archiveInfoText.text = openedArchiveLabel.ifEmpty { "أرشيف QZRA" }
        explorerPathText.text = if (path.isBlank()) "/  •  ${children.size} عنصر" else "/$path  •  ${children.size} عنصر"
        if (path.isNotBlank()) {
            explorerContainer.addView(
                explorerRow("up", "المجلد السابق", "", false, { showVirtualExplorer(path.substringBeforeLast('/', "")) }),
                LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) })
        }
        if (children.isEmpty()) {
            explorerContainer.addView(label("هذا المجلد فارغ.", 12.5f, 0xFF9FB4D3.toInt(), false).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
        }
        for (item in children) {
            val isDirectory = item.type == "directory"
            val protectedFolder = isDirectory && openedProtected.contains(item.path)
            val unlocked = protectedFolder && openedFolderPasswords.containsKey(item.path)
            val icon = when {
                !isDirectory -> "file"
                protectedFolder && unlocked -> "unlock"
                protectedFolder -> "lock"
                else -> "folder"
            }
            val subtitle = when {
                !isDirectory -> formatBytes(item.size)
                protectedFolder && unlocked -> "محمي بكلمة مرور مستقلة — مفتوح"
                protectedFolder -> "محمي بكلمة مرور مستقلة"
                else -> "مجلد"
            }
            val row = if (isDirectory) {
                explorerRow(icon, item.name, subtitle, protectedFolder && !unlocked, { enterFolder(item.path) }, {
                    showSheet(item.name, "مجلد داخل الأرشيف", listOf(
                        SheetAction("folder", "فتح المجلد", false) { enterFolder(item.path) },
                        SheetAction("extract", "استخراج هذا المجلد فقط", false) { requestSelectedExtraction(item.path) }
                    ))
                })
            } else {
                explorerRow(icon, item.name, subtitle, false, { showFileActions(item.path, item.size) }, { showFileActions(item.path, item.size) })
            }
            explorerContainer.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(6) })
        }
    }

    /** Entering a protected folder asks for that folder's own password first. */
    private fun enterFolder(path: String) {
        val archive = openedArchive ?: return
        val scope = openedProtected.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
        if (scope != null && !openedFolderPasswords.containsKey(scope)) {
            promptFolderPassword(scope, archive, openedArchivePassword) { showVirtualExplorer(path) }
        } else {
            showVirtualExplorer(path)
        }
    }

    private fun showVirtualExplorer(path: String) {
        val archive = openedArchive ?: return
        runOnWorker("تعذر قراءة مجلد الأرشيف") {
            val children = ArchiveEngine.browse(archive, path, openedArchivePassword)
            runOnUiThread { renderExplorer(path, children) }
        }
    }

    private fun closeOpenedArchive() {
        val archive = openedArchive
        if (archive == null) {
            status.text = "لا يوجد أرشيف مفتوح."
            return
        }
        // Only private cache copies are deleted; archives opened from storage are never touched.
        if (openedArchiveTemp) {
            try { archive.delete() } catch (_: Exception) { /* best effort */ }
        }
        openedArchive = null
        openedArchiveTemp = false
        pendingExtraction = null
        pendingExtractionPaths = null
        openedArchivePassword = null
        openedProtected = emptySet()
        openedFolderPasswords.clear()
        explorerPath = ""
        renderEmptyExplorer()
        status.text = "تم إغلاق الأرشيف ومسح كلمات المرور من الذاكرة."
    }

    private fun extractOpenedArchive() {
        if (openedArchive == null) {
            status.text = "افتح أرشيفًا أولًا من تبويب «الملفات»."
            return
        }
        requestSelectedExtraction(null)
    }

    private fun verifyOpenedArchive() {
        val archive = openedArchive
        if (archive == null) {
            status.text = "افتح أرشيفًا أولًا من تبويب «الملفات»."
            return
        }
        resetVerificationMetrics()
        runOnWorker("فشل فحص الأرشيف") { verifyArchiveFile(archive, openedArchivePassword) }
    }

    private fun installOpenedArchive(archive: File, name: String, password: String?, protectedPaths: Set<String>, temp: Boolean) {
        val previous = openedArchive
        val previousTemp = openedArchiveTemp
        openedArchive = archive
        openedArchiveTemp = temp
        pendingExtraction = archive
        openedArchiveLabel = name
        openedArchivePassword = password
        openedProtected = protectedPaths
        openedFolderPasswords.clear()
        explorerPath = ""
        if (previous != null && previous != archive && previousTemp) {
            try { previous.delete() } catch (_: Exception) { /* best effort */ }
        }
    }

    private fun needsArchivePassword(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return (message.contains("encrypted", ignoreCase = true) && message.contains("password", ignoreCase = true)) ||
            message.contains("QZRA 1.3", ignoreCase = true)
    }

    /** Opens a .qzra that lives in phone storage directly, with no copy. */
    private fun openArchiveFile(file: File, name: String, temp: Boolean, afterOpen: (() -> Unit)? = null) {
        status.text = "جارٍ فتح $name…"
        runOnWorker("تعذر فتح الأرشيف") {
            try {
                ArchiveEngine.browse(file)
                installOpenedArchive(file, name, null, emptySet(), temp)
                runOnUiThread {
                    status.text = "تم فتح $name. تُقرأ الملفات من داخله دون فكّه بالكامل."
                    if (afterOpen != null) afterOpen() else { selectTab(1); showVirtualExplorer("") }
                }
            } catch (error: IllegalArgumentException) {
                if (needsArchivePassword(error)) {
                    runOnUiThread { promptArchivePassword(file, name, temp, afterOpen) }
                } else throw error
            }
        }
    }

    /** Archive picked through the system picker or launched from another app: work on a private copy. */
    private fun browseSelectedArchive(uri: Uri?) {
        val selected = uri
        if (selected == null) {
            status.text = "لم يتم اختيار أرشيف."
            return
        }
        status.text = "جارٍ قراءة الأرشيف…"
        val name = displayName(selected) ?: "archive.qzra"
        runOnWorker("تعذرت قراءة الأرشيف") {
            val copy = copyUriToCache(selected, "qamzor-opened-${System.currentTimeMillis()}.qzra")
            runOnUiThread { openArchiveFile(copy, name, true) }
        }
    }

    private fun promptArchivePassword(archive: File, name: String, temp: Boolean, afterAuthenticated: (() -> Unit)? = null) {
        val entry = dialogField("كلمة مرور الأرشيف")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(entry)
        }
        AlertDialog.Builder(this)
            .setTitle("أرشيف QZRA مشفّر")
            .setMessage("أدخل كلمة مرور الأرشيف لفتح الفهرس وعرض الأسماء.")
            .setView(panel)
            .setPositiveButton("فتح الأرشيف") { _, _ ->
                val password = entry.text.toString()
                entry.text?.clear()
                if (password.isEmpty()) {
                    status.text = "يجب إدخال كلمة المرور للأرشيف المشفّر."
                } else {
                    runOnWorker("تعذر فتح الأرشيف المشفّر") {
                        try {
                            ArchiveEngine.browse(archive, "", password)
                            val protectedPaths = ArchiveEngine.protectedFolders(archive, password).toSet()
                            installOpenedArchive(archive, name, password, protectedPaths, temp)
                            runOnUiThread {
                                status.text = "تمت مصادقة الأرشيف المشفّر ✓" +
                                    if (protectedPaths.isEmpty()) "" else " • مجلدات محمية: ${protectedPaths.size}"
                                if (afterAuthenticated != null) afterAuthenticated() else { selectTab(1); showVirtualExplorer("") }
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setTitle("تعذر فتح الأرشيف")
                                    .setMessage(error.message ?: "كلمة المرور غير صحيحة أو الأرشيف تالف")
                                    .setPositiveButton("إعادة المحاولة") { _, _ -> promptArchivePassword(archive, name, temp, afterAuthenticated) }
                                    .setNegativeButton("إغلاق", null)
                                    .show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ───────────────────────────── Extract / verify / compare ─────────────────────────────

    /** "Extract here": a new folder next to the archive named after it. */
    private fun quickExtract(file: File) {
        openArchiveFile(file, file.name, false) {
            pendingExtractionPaths = null
            val parent = file.parentFile
            if (parent != null) extractToDirectory(uniqueDir(parent, file.nameWithoutExtension))
        }
    }

    private fun requestSelectedExtraction(path: String?) {
        val archive = openedArchive ?: pendingExtraction ?: return
        pendingExtraction = archive
        pendingExtractionPaths = path?.let { setOf(it) }
        val description = if (path == null) "الأرشيف بالكامل" else "العنصر: $path"
        val parent = archive.parentFile
        if (openedArchiveTemp || parent == null) {
            pickExtractionTree(description)
            return
        }
        val stem = archive.nameWithoutExtension
        showSheet("استخراج $description", "اختر الوجهة", listOf(
            SheetAction("folder", "إلى مجلد «$stem» بجانب الأرشيف", false) { extractToDirectory(uniqueDir(parent, stem)) },
            SheetAction("folder_search", "اختيار مجلد آخر…", false) { pickExtractionTree(description) }
        ))
    }

    private fun pickExtractionTree(description: String) {
        status.text = "اختر مجلد الوجهة لاستخراج $description."
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            ), REQUEST_OUTPUT_TREE
        )
    }

    private fun extractToDirectory(dest: File) {
        val archive = openedArchive ?: return
        val selectedPaths = pendingExtractionPaths
        status.text = "جارٍ الاستخراج إلى ${dest.name}…"
        runOnWorker("فشل استخراج الأرشيف") {
            val wanted = selectedPaths ?: ArchiveEngine.list(archive, openedArchivePassword, openedFolderPasswords).map { it.path }.toSet()
            val extracted = try {
                ArchiveEngine.extractSelected(archive, dest, wanted, password = openedArchivePassword, folderPasswords = openedFolderPasswords)
            } catch (error: Exception) {
                dest.deleteRecursively()
                val lockedPath = lockedFolderPath(error.message.orEmpty())
                if (lockedPath != null) {
                    runOnUiThread { promptFolderPassword(lockedPath, archive, openedArchivePassword) { extractToDirectory(dest) } }
                    return@runOnWorker
                }
                throw error
            }
            val files = extracted.count { it.isFile }
            val folders = extracted.count { it.isDirectory }
            runOnUiThread {
                status.text = "اكتمل الاستخراج والتحقق ✓ • الملفات: $files • المجلدات: $folders • ${dest.name}"
                pendingExtractionPaths = null
                if (hasStorageAccess() && currentTab == 0) loadDirectory(currentDir)
            }
        }
    }

    private fun verifyFile(file: File) {
        if (file != openedArchive) openedFolderPasswords.clear()
        resetVerificationMetrics()
        runOnWorker("فشل فحص الأرشيف") {
            try {
                verifyArchiveFile(file, if (file == openedArchive) openedArchivePassword else null)
            } catch (error: IllegalArgumentException) {
                if (error.message.orEmpty().contains("password", true) || error.message.orEmpty().contains("encrypted", true)) {
                    runOnUiThread { promptPasswordForVerification(file) }
                } else throw error
            }
        }
    }

    private fun compareArchiveFiles(left: File, right: File) {
        status.text = "جارٍ مقارنة الأرشيفين ببصمات SHA-256…"
        runOnWorker("فشلت مقارنة الأرشيفين") {
            try {
                showCompareResult(ArchiveEngine.compareContent(left, right))
            } catch (error: IllegalArgumentException) {
                val message = error.message.orEmpty()
                if (message.contains("password", true) || message.contains("encrypted", true)) {
                    runOnUiThread { promptComparePasswords(left, right) }
                } else throw error
            }
        }
    }

    private fun resetVerificationMetrics() {
        status.text = "جارٍ فحص الأرشيف والتحقق من SHA-256…"
    }

    private fun refreshVerificationMetrics(valid: Boolean, files: Int, bytes: Long) {
        AlertDialog.Builder(this)
            .setTitle(if (valid) "الأرشيف سليم ✓" else "الأرشيف غير سليم")
            .setMessage("الملفات المفحوصة: $files\nالبيانات المتحققة: ${formatBytes(bytes)}")
            .setPositiveButton("تم", null)
            .show()
    }

    private fun pickArchive(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("SAF callbacks are kept compatible with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ALL_FILES) {
            if (hasStorageAccess()) loadDirectory(currentDir)
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        try {
            when (requestCode) {
                REQUEST_BROWSE -> browseSelectedArchive(data.data)
                REQUEST_OUTPUT_TREE -> {
                    data.data?.let { takeTreePermission(it, data) }
                    extractToTree(data.data)
                }
            }
        } catch (error: Exception) {
            status.text = "فشلت العملية: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun promptPasswordForVerification(archive: File) {
        val field = EditText(this).apply {
            hint = "كلمة مرور الأرشيف"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("فحص أرشيف مشفّر")
            .setMessage("أدخل كلمة المرور للتحقق من سلامة جميع الملفات.")
            .setView(field)
            .setPositiveButton("فحص") { _, _ ->
                val password = field.text.toString()
                field.text?.clear()
                if (password.isEmpty()) status.text = "أدخل كلمة المرور أولًا."
                else runOnWorker("فشل فحص الأرشيف المشفّر") { verifyArchiveFile(archive, password) }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun verifyArchiveFile(archive: File, password: String?) {
        val result = try {
            ArchiveEngine.verify(archive, password, openedFolderPasswords)
        } catch (error: Exception) {
            val lockedPath = lockedFolderPath(error.message.orEmpty())
            if (lockedPath != null) {
                runOnUiThread { promptFolderPassword(lockedPath, archive, password) { runOnWorker("فشل فحص مجلد محمي") { verifyArchiveFile(archive, password) } } }
                return
            }
            throw error
        }
        runOnUiThread {
            refreshVerificationMetrics(result.valid, result.filesChecked, result.bytesChecked)
            status.text = if (result.valid) {
                "اكتمل فحص الأرشيف والتحقق من بصمات SHA-256 ✓\nالإصدار: ${result.version}"
            } else "لم يكتمل التحقق من سلامة الأرشيف."
        }
    }

    private fun promptComparePasswords(left: File, right: File) {
        val first = EditText(this).apply {
            hint = "كلمة مرور ${left.name} (اتركها فارغة إن لم يكن مشفرًا)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val second = EditText(this).apply {
            hint = "كلمة مرور ${right.name} (اتركها فارغة إن لم يكن مشفرًا)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(first)
            addView(second)
        }
        AlertDialog.Builder(this)
            .setTitle("مقارنة أرشيفات محمية")
            .setMessage("أدخل كلمة مرور كل أرشيف بصورة مستقلة.")
            .setView(panel)
            .setPositiveButton("تحقق وقارن") { _, _ ->
                val leftPassword = first.text.toString().ifEmpty { null }
                val rightPassword = second.text.toString().ifEmpty { null }
                first.text?.clear(); second.text?.clear()
                runOnWorker("فشلت مقارنة الأرشيفين") {
                    showCompareResult(ArchiveEngine.compareContent(left, right, leftPassword, rightPassword))
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showCompareResult(result: ArchiveEngine.CompareResult) {
        val message = "تحقق SHA-256 للمحتوى: ${if (result.contentVerified) "ناجح" else "غير مكتمل"}\n" +
            "إضافة: ${result.added.size} | حذف: ${result.removed.size}\n" +
            "تغيير في المحتوى/النوع/الحجم: ${result.changed.size}\n" +
            "متطابق: ${result.unchanged.size}\n\n" +
            result.added.take(15).joinToString("\n") { "+ $it" } + "\n" +
            result.removed.take(15).joinToString("\n") { "- $it" } + "\n" +
            result.changed.take(20).joinToString("\n") { "~ $it" }
        runOnUiThread {
            status.text = "اكتملت المقارنة مع التحقق من المحتوى."
            AlertDialog.Builder(this).setTitle("نتيجة مقارنة QAMZOR")
                .setMessage(message).setPositiveButton("تم", null).show()
        }
    }

    private fun showFileActions(path: String, size: Long) {
        AlertDialog.Builder(this)
            .setTitle(path.substringAfterLast('/'))
            .setMessage("المسار: $path\nالحجم الأصلي: ${formatBytes(size)}\nسيتم التحقق من سلامة الملف عند القراءة.")
            .setItems(arrayOf("معاينة / قراءة داخل الأرشيف", "استخراج هذا الملف")) { _, which ->
                if (which == 0) previewArchiveFile(path) else requestSelectedExtraction(path)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun previewArchiveFile(path: String) {
        val archive = openedArchive ?: return
        status.text = "جارٍ قراءة الملف من داخل الأرشيف والتحقق من SHA-256..."
        runOnWorker("تعذرت معاينة الملف") {
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val isImage = extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
            val isPdf = extension == "pdf"
            val limit = if (isImage || isPdf) 16 * 1024 * 1024 else 2 * 1024 * 1024
            val preview = try {
                ArchiveEngine.preview(archive, path, limit, openedArchivePassword, openedFolderPasswords)
            } catch (error: Exception) {
                val lockedPath = lockedFolderPath(error.message.orEmpty())
                if (lockedPath != null) {
                    runOnUiThread { promptFolderPassword(lockedPath, archive, openedArchivePassword) { previewArchiveFile(path) } }
                    return@runOnWorker
                }
                throw error
            }
            runOnUiThread {
                status.text = "تمت قراءة الملف من الأرشيف والتحقق من سلامته ✓ (${formatBytes(preview.bytesTotal)})"
                when {
                    isImage -> showImagePreview(path, preview.data, preview.truncated)
                    isPdf -> showPdfPreview(path, preview.data, preview.truncated)
                    extension in TEXT_EXTENSIONS -> showTextPreview(path, preview.data, preview.bytesTotal, preview.truncated)
                    else -> AlertDialog.Builder(this)
                        .setTitle("نوع الملف غير مدعوم للمعاينة")
                        .setMessage("تمت قراءة الملف والتحقق من سلامته، لكن لا توجد عارض مدمج لهذا النوع حتى الآن. يمكنك استخراج الملف المحدد لفتحه بالتطبيق المناسب.\n\nالأنواع النصية المدعومة: TXT, MD, CSV, JSON, XML, HTML, CSS, JS, PY, KT, JAVA, YAML, TOML, LOG, SQL. الصور: PNG, JPEG, GIF, WEBP, BMP. المستندات: PDF (الصفحة الأولى).")
                        .setPositiveButton("استخراج الملف") { _, _ -> requestSelectedExtraction(path) }
                        .setNegativeButton("إغلاق", null)
                        .show()
                }
            }
        }
    }

    private fun showTextPreview(path: String, bytes: ByteArray, total: Long, truncated: Boolean) {
        val text = TextView(this).apply {
            this.text = buildString {
                append(bytes.toString(Charsets.UTF_8))
                if (truncated) append("\n\n… تمت معاينة أول ${formatBytes(bytes.size.toLong())} من ${formatBytes(total)}. استخرج الملف لقراءة الباقي.")
            }
            textSize = 14f
            setTextColor(0xFF182236.toInt())
            setPadding(20, 16, 20, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("معاينة نصية — ${path.substringAfterLast('/')}")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showImagePreview(path: String, bytes: ByteArray, truncated: Boolean) {
        if (truncated) {
            AlertDialog.Builder(this).setTitle("الصورة كبيرة للمعاينة المباشرة")
                .setMessage("تم التحقق من الملف، لكن حجم الصورة يتجاوز حد المعاينة البالغ 16 MiB. استخرجها لعرضها بالكامل.")
                .setPositiveButton("استخراج") { _, _ -> requestSelectedExtraction(path) }
                .setNegativeButton("إغلاق", null).show()
            return
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap == null) {
            AlertDialog.Builder(this).setMessage("تعذر فك ترميز الصورة بهذا التنسيق.").setPositiveButton("إغلاق", null).show()
            return
        }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(8, 8, 8, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("معاينة صورة — ${path.substringAfterLast('/')}")
            .setView(ScrollView(this).apply { addView(image) })
            .setPositiveButton("إغلاق") { _, _ -> bitmap.recycle() }
            .setOnCancelListener { bitmap.recycle() }
            .show()
    }

    private fun showPdfPreview(path: String, bytes: ByteArray, truncated: Boolean) {
        if (truncated) {
            AlertDialog.Builder(this).setTitle("ملف PDF كبير للمعاينة المباشرة")
                .setMessage("تم التحقق من الملف، لكن حجمه يتجاوز حد المعاينة البالغ 16 MiB. استخرجه لفتحه بقارئ PDF.")
                .setPositiveButton("استخراج") { _, _ -> requestSelectedExtraction(path) }
                .setNegativeButton("إغلاق", null).show()
            return
        }
        val temp = File(cacheDir, "qamzor-pdf-preview-${System.currentTimeMillis()}.pdf")
        try {
            temp.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(descriptor)
            require(renderer.pageCount > 0) { "ملف PDF لا يحتوي على صفحات" }
            val page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(page.width.coerceAtMost(1600), page.height.coerceAtMost(2200), Bitmap.Config.ARGB_8888)
            val transform = Matrix().apply {
                setScale(bitmap.width.toFloat() / page.width, bitmap.height.toFloat() / page.height)
            }
            page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            descriptor.close()
            val image = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
            AlertDialog.Builder(this)
                .setTitle("معاينة PDF — ${path.substringAfterLast('/')}")
                .setView(ScrollView(this).apply { addView(image) })
                .setPositiveButton("إغلاق") { _, _ -> bitmap.recycle(); temp.delete() }
                .setOnCancelListener { bitmap.recycle(); temp.delete() }
                .show()
        } catch (error: Exception) {
            temp.delete()
            AlertDialog.Builder(this).setTitle("تعذرت معاينة PDF")
                .setMessage(error.message ?: "صيغة PDF غير مدعومة")
                .setPositiveButton("استخراج الملف") { _, _ -> requestSelectedExtraction(path) }
                .setNegativeButton("إغلاق", null).show()
        }
    }

    private fun extractToTree(treeUri: Uri?) {
        val archive = pendingExtraction ?: error("لم يتم تحديد أرشيف")
        val destination = treeUri ?: error("لم يتم اختيار مجلد الوجهة")
        val selectedPaths = pendingExtractionPaths
        status.text = if (selectedPaths == null) "جارٍ استخراج الأرشيف بالكامل..." else "جارٍ استخراج العنصر المحدد فقط..."
        runOnWorker("فشل استخراج الأرشيف") {
            val temporary = File(cacheDir, "qamzor-extract-${System.currentTimeMillis()}").apply { deleteRecursively(); mkdirs() }
            try {
                val selection = selectedPaths ?: ArchiveEngine.list(archive, openedArchivePassword, openedFolderPasswords).map { it.path }.toSet()
                val extracted = try {
                    ArchiveEngine.extractSelected(archive, temporary, selection, password = openedArchivePassword, folderPasswords = openedFolderPasswords)
                } catch (error: Exception) {
                    val lockedPath = lockedFolderPath(error.message.orEmpty())
                    if (lockedPath != null) {
                        runOnUiThread { promptFolderPassword(lockedPath, archive, openedArchivePassword) { requestSelectedExtraction(selectedPaths?.firstOrNull()) } }
                        return@runOnWorker
                    }
                    throw error
                }
                val tree = DocumentFile.fromTreeUri(this, destination) ?: error("لا يمكن الوصول إلى مجلد الوجهة")
                val directories = extracted.filter { it.isDirectory }.sortedBy { it.toPath().nameCount }
                for (directory in directories) {
                    val relative = temporary.toPath().relativize(directory.toPath()).toString().replace(File.separatorChar, '/')
                    var parent = tree
                    for (part in relative.split('/')) parent = ensureDirectory(parent, part)
                }
                val files = extracted.filter { it.isFile }
                for (file in files) {
                    val relative = temporary.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    val parts = relative.split('/')
                    var parent = tree
                    for (part in parts.dropLast(1)) parent = ensureDirectory(parent, part)
                    require(parent.findFile(parts.last()) == null) { "المجلد الوجهة يحتوي بالفعل على ${parts.last()}" }
                    val mime = when (file.extension.lowercase(Locale.ROOT)) {
                        "txt", "md", "csv", "json", "xml", "html", "log" -> "text/plain"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "pdf" -> "application/pdf"
                        else -> "application/octet-stream"
                    }
                    val target = parent.createFile(mime, parts.last()) ?: error("تعذر إنشاء الملف ${parts.last()}")
                    val output = contentResolver.openOutputStream(target.uri, "w") ?: error("تعذر فتح الملف الناتج")
                    output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                }
                runOnUiThread {
                    val label = if (selectedPaths == null) "الأرشيف بالكامل" else selectedPaths.joinToString()
                    status.text = "اكتمل الاستخراج والتحقق من الملفات المحددة ✓\nالنطاق: $label\nالملفات: ${files.size}\nالمجلدات: ${directories.size}\nالوجهة: ${tree.name}"
                    pendingExtraction = null
                    pendingExtractionPaths = null
                }
            } finally {
                temporary.deleteRecursively()
            }
        }
    }

    private fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            require(existing.isDirectory) { "يوجد ملف بنفس اسم المجلد: $name" }
            return existing
        }
        return parent.createDirectory(name) ?: error("تعذر إنشاء المجلد: $name")
    }

    private fun lockedFolderPath(message: String): String? =
        Regex("Folder is locked; unlock it with its independent password: (.+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun promptFolderPassword(path: String, archive: File, masterPassword: String?, retry: () -> Unit) {
        val field = EditText(this).apply {
            hint = "كلمة مرور المجلد: $path"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("فتح مجلد محمي مستقل")
            .setMessage("هذا المجلد له كلمة مرور مستقلة عن كلمة مرور الأرشيف. سيتم التحقق منها قبل حفظها في ذاكرة الجلسة.")
            .setView(field)
            .setPositiveButton("فتح المجلد") { _, _ ->
                val value = field.text.toString()
                field.text?.clear()
                if (value.isEmpty()) status.text = "أدخل كلمة مرور المجلد."
                else runOnWorker("كلمة مرور المجلد غير صحيحة") {
                    // Authenticate the scope before caching its password for this archive session.
                    ArchiveEngine.list(archive, masterPassword, mapOf(path to value))
                    openedFolderPasswords[path] = value
                    retry()
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun takeTreePermission(uri: Uri, data: Intent) {
        val requested = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val flags = if (requested == 0) Intent.FLAG_GRANT_READ_URI_PERMISSION else requested
        try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) { /* Some providers grant transient access only. */ }
    }

    private fun runOnWorker(errorPrefix: String, block: () -> Unit) {
        Thread {
            synchronized(backgroundTaskLock) {
                backgroundBusy = true
                activeOperationLabel = errorPrefix
                val startedAt = System.currentTimeMillis()
                activeOperationStartedAt = startedAt
                operationPreferences().edit()
                    .putInt("active_pid", android.os.Process.myPid())
                    .putString("active_label", errorPrefix)
                    .putLong("active_started_at", startedAt)
                    .apply()
                recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(startedAt))}] قيد التنفيذ — $errorPrefix")
                runOnUiThread { status.text = "جارٍ التنفيذ: $errorPrefix" }
                try {
                    block()
                    recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] اكتملت — $errorPrefix")
                } catch (error: Exception) {
                    recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] فشلت — $errorPrefix: ${error.message ?: error.javaClass.simpleName}")
                    runOnUiThread { status.text = "$errorPrefix: ${error.message ?: error.javaClass.simpleName}" }
                } finally {
                    operationPreferences().edit()
                        .remove("active_pid").remove("active_label").remove("active_started_at").apply()
                    activeOperationLabel = null
                    activeOperationStartedAt = null
                    backgroundBusy = false
                }
            }
        }.start()
    }

    private fun copyUriToCache(uri: Uri, name: String): File {
        val destination = File(cacheDir, safeName(name))
        return copyUriTo(uri, destination)
    }

    private fun copyUriTo(uri: Uri, target: File): File {
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "لا يمكن قراءة المستند المحدد" }
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } finally { cursor?.close() }
    }

    private fun safeName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        val cleaned = normalized.replace(Regex("[/\\\\:\\u0000-\\u001F]"), "_").trim().trimEnd('.')
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "unnamed"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(Locale.ROOT, bytes / 1024.0)} KB"
        else -> "${"%.2f".format(Locale.ROOT, bytes / (1024.0 * 1024.0))} MB"
    }

    private fun buildOperationsPage(): View {
        val column = pageColumn()
        logSummary = label("", 12.5f, 0xFFE7EFFD.toInt(), true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF1A2A49.toInt(), 12)
        }
        column.addView(logSummary, LinearLayout.LayoutParams(-1, -2))
        logText = label("", 11.5f, 0xFFC9D8F0.toInt(), false).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF142441.toInt(), 12)
        }
        column.addView(logText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        column.addView(actionButton("trash", "مسح السجل", false) {
            synchronized(operationHistory) {
                operationHistory.clear()
                operationPreferences().edit().remove("events").apply()
            }
            refreshOperationLog()
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(10) })
        return pageScroll(column)
    }

    private fun refreshOperationLog() {
        if (!::logText.isInitialized || !::logSummary.isInitialized) return
        val snapshot = synchronized(operationHistory) { operationHistory.toList().takeLast(60) }
        val current = activeOperationLabel?.let {
            val elapsed = activeOperationStartedAt?.let { start -> ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0) } ?: 0
            "العملية النشطة: $it  (${elapsed} ثانية)"
        } ?: "لا توجد عملية نشطة الآن."
        val completed = snapshot.count { it.contains("اكتملت") }
        val failed = snapshot.count { it.contains("فشلت") }
        val interrupted = snapshot.count { it.contains("توقفت العملية") }
        logSummary.text = current + "\nالسجل: ${snapshot.size} • ناجحة: $completed • فاشلة: $failed • متوقفة: $interrupted"
        logText.text = if (snapshot.isEmpty()) "لا توجد عمليات مسجلة بعد." else snapshot.asReversed().joinToString("\n\n")
    }

    // ───────────────────────────── Small view helpers ─────────────────────────────

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun sectionLabel(text: String): TextView = label(text, 13f, 0xFFB9D0F7.toInt(), true).apply {
        setPadding(dp(2), dp(14), dp(2), dp(7))
    }

    private fun inputField(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(0xFF7F95BA.toInt())
        setTextColor(0xFFF4F7FF.toInt())
        textSize = 13.5f
        inputType = if (secret) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else android.text.InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        background = rounded(0xFF1D3152.toInt(), 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun dialogField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun actionButton(kind: String, caption: String, primary: Boolean, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = rounded(if (primary) 0xFF3F6FD8.toInt() else 0xFF263D63.toInt(), 12)
        isClickable = true
        isFocusable = true
        contentDescription = caption
        setOnClickListener { action() }
        addView(QamzorLineIconView(this@MainActivity, kind, 0xFFF4F7FF.toInt()),
            LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(6) })
        addView(TextView(this@MainActivity).apply {
            text = caption
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF4F7FF.toInt())
            maxLines = 1
        })
    }


    companion object {
        private val COLOR_BG = 0xFF0B1730.toInt()
        private val COLOR_BAR = 0xFF101D36.toInt()
        private val COLOR_ACCENT = 0xFF4C8DFF.toInt()
        private val NAV_IDLE = 0xFF8FA4C8.toInt()
        private val TAB_TITLES = listOf("الملفات", "الأرشيف المفتوح", "سجل العمليات")
        private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "py", "kt", "java", "yaml", "yml", "toml", "ini", "properties", "sh", "sql", "log")
        private const val REQUEST_BROWSE = 23
        private const val REQUEST_OUTPUT_TREE = 25
        private const val REQUEST_ALL_FILES = 30
    }
}
