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

/** Android SAF front-end for the QAMZOR reference archive engine. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var sourcesListContainer: LinearLayout
    private lateinit var operationHealth: TextView
    private lateinit var verifiedFiles: TextView
    private lateinit var verifiedBytes: TextView
    private lateinit var pageTitle: TextView
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
    private val selectedSources = ArrayList<File>()
    private val backgroundTaskLock = Any()
    @Volatile private var backgroundBusy = false
    private var stagingDirectory: File? = null
    private var pendingArchive: File? = null
    private var pendingExtraction: File? = null
    private var pendingExtractionPaths: Set<String>? = null
    private var openedArchive: File? = null
    private var openedArchiveLabel: String = ""
    private var openedArchivePassword: String? = null
    private var openedProtected: Set<String> = emptySet()
    private var explorerPath: String = ""
    private val operationHistory = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val openedFolderPasswords: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())
    /** Independent folder passwords chosen on the Create page: archive-relative folder path -> password. */
    private val folderLocks = LinkedHashMap<String, String>()
    private val expandedDirs = HashSet<String>()
    @Volatile private var activeOperationLabel: String? = null
    @Volatile private var activeOperationStartedAt: Long? = null
    private var protectSwitch: Switch? = null
    private var protectFields: View? = null
    private var passwordField: EditText? = null
    private var confirmField: EditText? = null
    private var hideNamesBox: CheckBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) purgeStaleCache()
        restoreOperationHistory()
        buildInterface()
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    /** Plain-text working copies never outlive the process that made them. */
    private fun purgeStaleCache() {
        try {
            File(cacheDir, "qamzor-selected-sources").deleteRecursively()
            cacheDir.listFiles()?.filter {
                it.name.startsWith("qamzor-") || it.name.startsWith("QAMZOR-") || it.name.startsWith("selected-archive-")
            }?.forEach { it.deleteRecursively() }
        } catch (_: Exception) { /* best effort */ }
    }

    /** Double-tapping a .qzra file in any file manager opens it here. */
    private fun handleViewIntent(launch: Intent?) {
        val uri = launch?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        selectTab(1)
        browseSelectedArchive(uri)
    }

    override fun onBackPressed() {
        if (currentTab == 1 && openedArchive != null && explorerPath.isNotEmpty()) {
            showVirtualExplorer(explorerPath.substringBeforeLast('/', ""))
        } else if (currentTab != 0) {
            selectTab(0)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
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
            setBackgroundColor(COLOR_BAR)
        }
        val mark = TextView(this).apply {
            text = "▣"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFBBD2FF.toInt())
            background = rounded(0xFF263F70.toInt(), 9)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(30), dp(30)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("QAMZOR", 15f, 0xFFF7FAFF.toInt(), true))
        pageTitle = label(TAB_TITLES[0], 11f, 0xFF9FB5D9.toInt(), false)
        titles.addView(pageTitle)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        shell.addView(header, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            text = "جاهز للعمل."
            textSize = 11.5f
            setTextColor(0xFFDCE7F8.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setBackgroundColor(0xFF14223F.toInt())
            isClickable = true
            setOnClickListener { selectTab(3) }
        }

        val pageHost = FrameLayout(this)
        pages.add(buildCreatePage())
        pages.add(buildOpenPage())
        pages.add(buildToolsPage())
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
        val specs = listOf("create" to "إنشاء", "folder_search" to "فتح", "tools" to "أدوات", "activity" to "العمليات")
        for ((index, spec) in specs.withIndex()) {
            val icon = QamzorLineIconView(this, spec.first, NAV_IDLE)
            val caption = TextView(this).apply {
                text = spec.second
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(NAV_IDLE)
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(4))
                isClickable = true
                isFocusable = true
                contentDescription = spec.second
                setOnClickListener { selectTab(index) }
            }
            box.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
            box.addView(caption, LinearLayout.LayoutParams(-2, -2))
            navBoxes.add(box); navIcons.add(icon); navLabels.add(caption)
            nav.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        shell.addView(nav, LinearLayout.LayoutParams(-1, -2))

        setContentView(shell)
        selectTab(0)
        renderEmptyExplorer()
        refreshSelectionSummary()
        refreshOperationLog()
    }

    private fun selectTab(index: Int) {
        currentTab = index
        for ((position, page) in pages.withIndex()) page.visibility = if (position == index) View.VISIBLE else View.GONE
        for (position in navBoxes.indices) {
            val selected = position == index
            val color = if (selected) 0xFFFFFFFF.toInt() else NAV_IDLE
            navIcons[position].applyTint(color)
            navLabels[position].setTextColor(color)
            navBoxes[position].background = rounded(if (selected) 0xFF2A4678.toInt() else 0x00000000, 12)
        }
        pageTitle.text = TAB_TITLES[index]
        if (index == 3) refreshOperationLog()
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

    // ───────────────────────────── Page 1: Create ─────────────────────────────

    private fun buildCreatePage(): View {
        val column = pageColumn()
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        addRow.addView(actionButton("plus", "إضافة ملفات", false) { pickFiles() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        addRow.addView(actionButton("folder_add", "إضافة مجلد", false) { pickFolder() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        column.addView(addRow)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xFF142441.toInt(), 14)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("العناصر المحددة", 13f, 0xFFE7EFFD.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label("مسح الكل", 11.5f, 0xFF9FB4D3.toInt(), false).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener { clearSelection() }
        })
        panel.addView(head)
        sourcesListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(2))
        }
        panel.addView(sourcesListContainer)
        panel.addView(label("اضغط أيقونة القفل بجانب أي مجلد لتعيين كلمة مرور مستقلة له.", 10.5f, 0xFF8FA6C9.toInt(), false)
            .apply { setPadding(dp(2), dp(4), dp(2), 0) })
        column.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val protection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = rounded(0xFF142441.toInt(), 14)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val password = inputField("كلمة مرور الأرشيف (10 أحرف على الأقل)", true)
        val confirm = inputField("تأكيد كلمة المرور", true)
        val hideNames = CheckBox(this).apply {
            text = "إخفاء أسماء الملفات والمجلدات وأحجامها (QZRA 1.3)"
            textSize = 12f
            isChecked = true
            setTextColor(0xFFD8E5F8.toInt())
            buttonTintList = ColorStateList.valueOf(0xFF8FB4F4.toInt())
        }
        fields.addView(password, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fields.addView(confirm, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fields.addView(hideNames, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        fields.addView(label("كلمات المرور لا تُحفظ في أي مكان. فقدانها يعني عدم إمكانية استعادة الملفات.", 10.5f, 0xFF8FA6C9.toInt(), false))
        val toggle = Switch(this).apply {
            text = "حماية الأرشيف بكلمة مرور"
            textSize = 14f
            setTextColor(0xFFF4F7FF.toInt())
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> fields.visibility = if (checked) View.VISIBLE else View.GONE }
        }
        protection.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        protection.addView(fields, LinearLayout.LayoutParams(-1, -2))
        protectSwitch = toggle; protectFields = fields; passwordField = password; confirmField = confirm; hideNamesBox = hideNames
        column.addView(protection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        column.addView(actionButton("create", "إنشاء أرشيف QZRA", true) { convertSelectedToQzra() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        return pageScroll(column)
    }

    // ───────────────────────────── Page 2: Open / explorer ─────────────────────────────

    private fun buildOpenPage(): View {
        val column = pageColumn()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        row.addView(actionButton("folder_search", "فتح أرشيف", true) { pickArchive(REQUEST_BROWSE) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        row.addView(actionButton("extract", "استخراج", false) { extractOpenedArchive() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        row.addView(actionButton("shield_check", "فحص", false) { verifyOpenedArchive() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        row.addView(actionButton("close", "إغلاق", false) { closeOpenedArchive() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        column.addView(row)
        archiveInfoText = label("", 12.5f, 0xFFE7EFFD.toInt(), true).apply { setPadding(dp(2), dp(12), dp(2), dp(0)) }
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
        explorerContainer.addView(label("افتح ملف .qzra لعرض محتوياته. الأرشيفات المشفّرة لا يقرأها أي تطبيق آخر.", 12f, 0xFF9FB4D3.toInt(), false)
            .apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
    }

    private fun explorerRow(kind: String, title: String, subtitle: String, highlight: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0xFF1D3152.toInt(), 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        row.addView(QamzorLineIconView(this, kind, if (highlight) 0xFFFFD27A.toInt() else 0xFFBBD2FF.toInt()),
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 13f, 0xFFF4F7FF.toInt(), true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setSingleLine(true)
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
        val gap = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(5) }
        if (path.isNotBlank()) {
            explorerContainer.addView(
                explorerRow("folder", "..  المجلد السابق", "", false, { showVirtualExplorer(path.substringBeforeLast('/', "")) }),
                LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(5) })
        }
        if (children.isEmpty()) {
            explorerContainer.addView(label("هذا المجلد فارغ.", 12f, 0xFF9FB4D3.toInt(), false).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
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
                protectedFolder && unlocked -> "محمي بكلمة مرور مستقلة — مفتوح في هذه الجلسة"
                protectedFolder -> "محمي بكلمة مرور مستقلة"
                else -> "مجلد"
            }
            val row = if (isDirectory) {
                explorerRow(icon, item.name, subtitle, protectedFolder && !unlocked, { enterFolder(item.path) }, {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(item.name)
                        .setItems(arrayOf("فتح المجلد", "استخراج هذا المجلد فقط")) { _, which ->
                            if (which == 0) enterFolder(item.path) else requestSelectedExtraction(item.path)
                        }.show()
                })
            } else {
                explorerRow(icon, item.name, subtitle, false, { showFileActions(item.path, item.size) }, { showFileActions(item.path, item.size) })
            }
            explorerContainer.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(5) })
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
        if (openedArchive == null) {
            status.text = "لا يوجد أرشيف مفتوح."
            return
        }
        try { openedArchive?.delete() } catch (_: Exception) { /* best effort */ }
        openedArchive = null
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
            status.text = "افتح أرشيفًا أولًا من زر «فتح أرشيف»."
            return
        }
        requestSelectedExtraction(null)
    }

    private fun verifyOpenedArchive() {
        val archive = openedArchive
        if (archive == null) {
            status.text = "افتح أرشيفًا أولًا من زر «فتح أرشيف»."
            return
        }
        resetVerificationMetrics()
        runOnWorker("فشل فحص الأرشيف") { verifyArchiveFile(archive, openedArchivePassword) }
    }

    private fun installOpenedArchive(archive: File, name: String, password: String?, protectedPaths: Set<String>) {
        val previous = openedArchive
        openedArchive = archive
        pendingExtraction = archive
        openedArchiveLabel = name
        openedArchivePassword = password
        openedProtected = protectedPaths
        openedFolderPasswords.clear()
        explorerPath = ""
        if (previous != null && previous != archive) {
            try { previous.delete() } catch (_: Exception) { /* best effort */ }
        }
    }

    private fun needsArchivePassword(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return (message.contains("encrypted", ignoreCase = true) && message.contains("password", ignoreCase = true)) ||
            message.contains("QZRA 1.3", ignoreCase = true)
    }

    // ───────────────────────────── Page 3: Tools ─────────────────────────────

    private fun toolRow(kind: String, title: String, subtitle: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0xFF1D3152.toInt(), 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            contentDescription = "$title. $subtitle"
        }
        row.addView(iconBadge(kind, 0x334B73AA, 0xFFBBD2FF.toInt(), 34), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 13.5f, 0xFFF4F7FF.toInt(), true))
        texts.addView(label(subtitle, 11f, 0xFF9FB4D3.toInt(), false))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun buildToolsPage(): View {
        val column = pageColumn()
        val rowParams = { LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(7) } }
        column.addView(toolRow("shield_check", "فحص السلامة", "تحقق SHA-256 لكل ملفات الأرشيف") { pickArchive(REQUEST_VERIFY) }, rowParams())
        column.addView(toolRow("extract", "استخراج أرشيف", "كامل أو ملف/مجلد محدد") { pickArchive(REQUEST_EXTRACT) }, rowParams())
        column.addView(toolRow("compare", "مقارنة أرشيفين", "مقارنة المحتوى ببصمات SHA-256") { pickArchiveComparison() }, rowParams())

        column.addView(sectionLabel("نتيجة آخر فحص"))
        val metricsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val healthCard = metricCard("حالة الأرشيف", "لم يُفحص", 0xFFCBD8EC.toInt())
        operationHealth = healthCard.second
        val filesCard = metricCard("الملفات المفحوصة", "—", 0xFFFFFFFF.toInt())
        verifiedFiles = filesCard.second
        val bytesCard = metricCard("البيانات المتحققة", "—", 0xFFFFFFFF.toInt())
        verifiedBytes = bytesCard.second
        metricsRow.addView(healthCard.first, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginEnd = dp(4) })
        metricsRow.addView(filesCard.first, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        metricsRow.addView(bytesCard.first, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(4) })
        column.addView(metricsRow, LinearLayout.LayoutParams(-1, -2))
        column.addView(label("QAMZOR  •  QZRA Archive System", 10f, 0xFF8299BE.toInt(), false)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(2)) })
        return pageScroll(column)
    }

    // ───────────────────────────── Page 4: Operations ─────────────────────────────

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
                else -> canvas.drawCircle(12f, 12f, 8f, stroke)
            }
            canvas.restore()
        }
    }

    private fun metricCard(label: String, initialValue: String, valueColor: Int): Pair<View, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(7))
            background = rounded(0xFF1A2B49.toInt(), 14)
        }
        card.addView(TextView(this).apply {
            text = label
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(0xFFAFC2E1.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val value = TextView(this).apply {
            text = initialValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(valueColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(value)
        return card to value
    }

    private fun resetVerificationMetrics() {
        operationHealth.text = "جارٍ الفحص"
        operationHealth.setTextColor(0xFFFFD27A.toInt())
        verifiedFiles.text = "—"
        verifiedBytes.text = "—"
    }

    private fun refreshVerificationMetrics(valid: Boolean, files: Int, bytes: Long) {
        operationHealth.text = if (valid) "✓ سليم" else "غير سليم"
        operationHealth.setTextColor(if (valid) 0xFF68D391.toInt() else 0xFFFF7777.toInt())
        verifiedFiles.text = files.toString()
        verifiedBytes.text = formatBytes(bytes)
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FILES)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FOLDER)
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
        if (resultCode != RESULT_OK || data == null) return
        try {
            when (requestCode) {
                REQUEST_FILES -> addSelectedFiles(data)
                REQUEST_FOLDER -> addSelectedFolder(data)
                REQUEST_SAVE_ARCHIVE -> savePendingArchive(data.data)
                REQUEST_BROWSE -> browseSelectedArchive(data.data)
                REQUEST_VERIFY -> verifySelectedArchive(data.data)
                REQUEST_COMPARE -> compareSelectedArchives(data)
                REQUEST_EXTRACT -> {
                    val selected = data.data ?: error("لم يتم اختيار أرشيف")
                    pendingExtractionPaths = null
                    status.text = "جارٍ قراءة الأرشيف المحدد..."
                    val name = displayName(selected) ?: "archive.qzra"
                    runOnWorker("تعذرت قراءة الأرشيف") {
                        val archive = copyUriToCache(selected, "selected-archive-${System.currentTimeMillis()}.qzra")
                        try {
                            ArchiveEngine.browse(archive)
                            installOpenedArchive(archive, name, null, emptySet())
                            runOnUiThread { requestSelectedExtraction(null) }
                        } catch (error: IllegalArgumentException) {
                            if (needsArchivePassword(error)) {
                                runOnUiThread { promptArchivePassword(archive, name) { requestSelectedExtraction(null) } }
                            } else throw error
                        }
                    }
                }
                REQUEST_OUTPUT_TREE -> {
                    data.data?.let { takeTreePermission(it, data) }
                    extractToTree(data.data)
                }
            }
        } catch (error: Exception) {
            status.text = "فشلت العملية: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun pickArchiveComparison() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_COMPARE)
    }

    private fun compareSelectedArchives(data: Intent) {
        val uris = buildList {
            data.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
            if (isEmpty()) data.data?.let(::add)
        }.distinct()
        require(uris.size == 2) { "اختَر أرشيفين بالضبط للمقارنة في نافذة اختيار الملفات." }
        status.text = "جارٍ تجهيز الأرشيفين للتحقق والمقارنة ببصمات SHA-256..."
        runOnWorker("فشلت مقارنة الأرشيفين") {
            val left = copyUriToCache(uris[0], "qamzor-compare-left-${System.currentTimeMillis()}.qzra")
            val right = copyUriToCache(uris[1], "qamzor-compare-right-${System.currentTimeMillis()}.qzra")
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

    private fun verifySelectedArchive(uri: Uri?) {
        val selected = uri ?: error("لم يتم اختيار أرشيف")
        status.text = "جارٍ فحص جميع بيانات الأرشيف والتحقق من SHA-256..."
        resetVerificationMetrics()
        openedFolderPasswords.clear()
        runOnWorker("فشل فحص الأرشيف") {
            val archive = copyUriToCache(selected, "qamzor-verify-${System.currentTimeMillis()}.qzra")
            try {
                verifyArchiveFile(archive, null)
            } catch (error: IllegalArgumentException) {
                if (error.message.orEmpty().contains("password", true) || error.message.orEmpty().contains("encrypted", true)) {
                    runOnUiThread { promptPasswordForVerification(archive) }
                } else throw error
            }
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

    private fun addSelectedFiles(data: Intent) {
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (index in 0 until clip.itemCount) uris.add(clip.getItemAt(index).uri) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        require(uris.isNotEmpty()) { "لم يتم اختيار ملفات" }
        status.text = "جارٍ نسخ الملفات المحددة إلى قائمة الأرشفة..."
        runOnWorker("تعذرت إضافة الملفات") {
            val staging = getStagingDirectory()
            val added = ArrayList<File>()
            for ((index, uri) in uris.withIndex()) {
                val name = uniqueName(staging, safeName(displayName(uri) ?: "file-${index + 1}.bin"))
                added += copyUriTo(uri, File(staging, name))
            }
            synchronized(selectedSources) { selectedSources.addAll(added) }
            runOnUiThread {
                refreshSelectionSummary()
                status.text = "تمت إضافة ${added.size} ملف/ملفات. يمكنك إضافة مجلدات أخرى قبل إنشاء الأرشيف."
            }
        }
    }

    private fun addSelectedFolder(data: Intent) {
        val uri = data.data ?: error("لم يتم اختيار مجلد")
        takeTreePermission(uri, data)
        status.text = "جارٍ فتح مصدر المجلد وقراءة جميع محتوياته..."
        runOnWorker("تعذرت إضافة المجلد") {
            val documentTree = DocumentFile.fromTreeUri(this, uri) ?: error("لا يمكن فتح المجلد المحدد عبر موفر الملفات")
            require(documentTree.isDirectory) { "العنصر المحدد ليس مجلدًا" }
            val staging = getStagingDirectory()
            val rootName = uniqueName(staging, safeName(documentTree.name ?: "folder"))
            val localRoot = File(staging, rootName)
            val visitedDocumentIds = HashSet<String>()
            val copied = try {
                copyDocumentDirectory(documentTree, localRoot, visitedDocumentIds, 0)
            } catch (error: Exception) {
                localRoot.deleteRecursively()
                throw error
            }
            require(localRoot.isDirectory) { "لم يتم إنشاء جذر المجلد المحلي" }
            val counts = countFilesAndDirectories(localRoot)
            require(counts.second >= 1) { "تعذر إنشاء بنية المجلد المحدد" }
            synchronized(selectedSources) { selectedSources.add(localRoot) }
            runOnUiThread {
                refreshSelectionSummary()
                status.text = if (counts.first == 0) {
                    "تمت إضافة المجلد الفارغ: $rootName\nسيتم تضمين اسم المجلد وبنيته في أرشيف QZRA."
                } else {
                    "تمت إضافة المجلد كاملًا: $rootName\nالملفات: ${counts.first} • المجلدات: ${counts.second}\nالحجم المنسوخ: ${formatBytes(copied.second)}\nأصبح جاهزًا للتحويل إلى QZRA."
                }
            }
        }
    }

    /**
     * Copies a SAF tree into the app's private staging area. Some document providers
     * return null MIME types for ordinary files, making DocumentFile.isFile false.
     * Treat every non-directory document as a stream candidate and report a useful
     * error only when the provider cannot actually open it.
     */
    private fun copyDocumentDirectory(
        source: DocumentFile,
        destination: File,
        visitedDocumentIds: MutableSet<String>,
        depth: Int
    ): Pair<Int, Long> {
        require(depth <= 128) { "المجلد متداخل بعمق غير مدعوم (الحد 128 مستوى)" }
        val documentId = try { android.provider.DocumentsContract.getDocumentId(source.uri) }
            catch (_: Exception) { source.uri.toString() }
        require(visitedDocumentIds.add(documentId)) { "اكتُشف تكرار أو حلقة داخل شجرة المجلدات: ${source.name}" }
        require(destination.mkdirs() || destination.isDirectory) { "تعذر إنشاء المجلد المحلي: ${destination.name}" }

        var filesCopied = 0
        var bytesCopied = 0L
        val usedNames = HashSet<String>()
        val children = source.listFiles().sortedBy { (it.name ?: "").lowercase(Locale.ROOT) }
        for (child in children) {
            val childName = uniqueName(usedNames, safeName(child.name ?: displayName(child.uri) ?: "item"))
            val target = File(destination, childName)
            val isDirectory = child.isDirectory ||
                contentResolver.getType(child.uri) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
            if (isDirectory) {
                val nested = copyDocumentDirectory(child, target, visitedDocumentIds, depth + 1)
                filesCopied += nested.first
                bytesCopied += nested.second
            } else {
                val input = try { contentResolver.openInputStream(child.uri) } catch (error: Exception) {
                    throw IllegalArgumentException("تعذرت قراءة العنصر '$childName' من موفر الملفات: ${error.message ?: error.javaClass.simpleName}", error)
                } ?: error("موفر الملفات لم يعطِ محتوى قابلًا للقراءة للعنصر: $childName")
                var copied = 0L
                input.use { stream ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                        }
                    }
                }
                filesCopied++
                bytesCopied += copied
            }
        }
        return filesCopied to bytesCopied
    }

    // ───────────────────────────── Create flow: source tree, folder locks, archive creation ─────────────────────────────

    private fun convertSelectedToQzra() {
        if (backgroundBusy) {
            status.text = "هناك عملية قيد التنفيذ. انتظر حتى تنتهي."
            return
        }
        val sources = synchronized(selectedSources) { selectedSources.toList() }
        if (sources.isEmpty()) {
            status.text = "أضف ملفًا أو مجلدًا واحدًا على الأقل قبل إنشاء الأرشيف."
            return
        }
        val root = stagingDirectory
        val locks = LinkedHashMap<String, String>()
        if (root != null) for ((path, secret) in folderLocks) if (File(root, path).isDirectory) locks[path] = secret
        val protect = protectSwitch?.isChecked == true || locks.isNotEmpty()
        if (!protect) {
            createSelectedQzra(sources, null)
            return
        }
        val first = passwordField?.text?.toString().orEmpty()
        val second = confirmField?.text?.toString().orEmpty()
        if (protectSwitch?.isChecked != true) protectSwitch?.isChecked = true
        when {
            first.isEmpty() -> status.text = "أدخل كلمة مرور الأرشيف في بطاقة الحماية (مطلوبة أيضًا عند حماية مجلد)."
            first.length < 10 -> status.text = "كلمة مرور الأرشيف قصيرة؛ استخدم 10 أحرف على الأقل."
            first != second -> status.text = "كلمتا مرور الأرشيف غير متطابقتين."
            else -> createSelectedQzra(sources, first, hideNamesBox?.isChecked != false || locks.isNotEmpty(), locks)
        }
    }

    private fun promptFolderLock(path: String) {
        val existing = folderLocks.containsKey(path)
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
                    value.length < 10 -> { status.text = "كلمة مرور المجلد قصيرة؛ استخدم 10 أحرف على الأقل."; promptFolderLock(path) }
                    value != confirmation -> { status.text = "كلمتا مرور المجلد غير متطابقتين."; promptFolderLock(path) }
                    !existing && folderLocks.size >= 32 -> { status.text = "الحد الأقصى 32 مجلدًا محميًا بكلمة مرور مستقلة في الأرشيف الواحد." }
                    else -> {
                        folderLocks[path] = value
                        if (protectSwitch?.isChecked != true) protectSwitch?.isChecked = true
                        status.text = "تم تعيين كلمة مرور مستقلة للمجلد: $path\nأدخل كلمة مرور الأرشيف ثم اضغط «إنشاء أرشيف QZRA»."
                        refreshSelectionSummary()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
        if (existing) {
            builder.setNeutralButton("إزالة الحماية") { _, _ ->
                folderLocks.remove(path)
                status.text = "تمت إزالة حماية المجلد: $path"
                refreshSelectionSummary()
            }
        }
        builder.show()
    }

    private fun createSelectedQzra(
        sources: List<File>, password: String?, encryptMetadata: Boolean = false,
        folderPasswords: Map<String, String> = emptyMap()
    ) {
        status.text = "جارٍ إنشاء أرشيف QZRA${if (password == null) "" else " مشفّر"}..."
        resetVerificationMetrics()
        runOnWorker("فشل إنشاء أرشيف QZRA") {
            val output = File(cacheDir, "QAMZOR-${System.currentTimeMillis()}.qzra")
            val result = ArchiveEngine.convertToQzra(
                output, sources, password = password, encryptMetadata = encryptMetadata,
                folderPasswords = folderPasswords
            )
            val verified = ArchiveEngine.verify(output, password, folderPasswords)
            require(verified.valid && verified.filesChecked == result.files) { "تعذر التحقق من الأرشيف بعد الإنشاء" }
            val archiveEntries = ArchiveEngine.list(output, password, folderPasswords)
            require(archiveEntries.size == result.entries) { "عدد عناصر الأرشيف الناتج لا يطابق المصادر" }
            pendingArchive = output
            runOnUiThread {
                refreshVerificationMetrics(verified.valid, verified.filesChecked, verified.bytesChecked)
                val protectedCount = folderPasswords.size
                status.text = "اكتمل الإنشاء والتحقق ✓ (${verified.version}) • الملفات: ${result.files} • " +
                    "${formatBytes(result.originalBytes)} ← ${formatBytes(result.storedBytes)}" +
                    (if (protectedCount > 0) " • مجلدات محمية: $protectedCount" else "") + "\nاختر مكان حفظ ملف .qzra."
                val save = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, output.name)
                }
                startActivityForResult(save, REQUEST_SAVE_ARCHIVE)
            }
        }
    }

    private fun resetProtectionUi() {
        folderLocks.clear()
        expandedDirs.clear()
        passwordField?.text?.clear()
        confirmField?.text?.clear()
        protectSwitch?.isChecked = false
        hideNamesBox?.isChecked = true
    }

    private fun clearSelection() {
        if (backgroundBusy) {
            status.text = "هناك عملية ملفات قيد التنفيذ. امسح القائمة بعد انتهائها."
            return
        }
        synchronized(selectedSources) { selectedSources.clear() }
        stagingDirectory?.deleteRecursively()
        stagingDirectory = null
        folderLocks.clear()
        expandedDirs.clear()
        refreshSelectionSummary()
        status.text = "تم مسح قائمة الملفات والمجلدات."
    }

    private fun removeSource(source: File, relative: String) {
        if (backgroundBusy) {
            status.text = "هناك عملية ملفات قيد التنفيذ. حاول بعد انتهائها."
            return
        }
        synchronized(selectedSources) { selectedSources.remove(source) }
        source.deleteRecursively()
        for (key in folderLocks.keys.toList()) if (key == relative || key.startsWith("$relative/")) folderLocks.remove(key)
        for (key in expandedDirs.toList()) if (key == relative || key.startsWith("$relative/")) expandedDirs.remove(key)
        refreshSelectionSummary()
        status.text = "تمت إزالة ${source.name} من قائمة الأرشفة."
    }

    private fun refreshSelectionSummary() {
        val sources = synchronized(selectedSources) { selectedSources.toList() }
        val root = stagingDirectory
        runOnUiThread {
            sourcesListContainer.removeAllViews()
            if (sources.isEmpty() || root == null) {
                sourcesListContainer.addView(label("لم تتم إضافة ملفات أو مجلدات بعد.", 12f, 0xFF9FB4D3.toInt(), false)
                    .apply { setPadding(dp(4), dp(8), dp(4), dp(8)) })
            } else {
                for (source in sources) addSourceRows(source, root, 0)
            }
        }
    }

    private fun addSourceRows(file: File, root: File, depth: Int) {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val isDirectory = file.isDirectory
        val locked = isDirectory && folderLocks.containsKey(relative)
        val expanded = isDirectory && expandedDirs.contains(relative)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(8) + depth * dp(14), dp(3), dp(8), dp(3))
            background = rounded(if (depth == 0) 0xFF1D3152.toInt() else 0xFF182845.toInt(), 10)
            if (isDirectory) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (expanded) expandedDirs.remove(relative) else expandedDirs.add(relative)
                    refreshSelectionSummary()
                }
            }
        }
        row.addView(QamzorLineIconView(this, if (isDirectory) "folder" else "file", 0xFFBBD2FF.toInt()),
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(file.name, 12.5f, 0xFFF4F7FF.toInt(), true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setSingleLine(true)
        })
        texts.addView(label(
            when {
                locked -> "محمي بكلمة مرور مستقلة"
                isDirectory -> "مجلد • ${file.list()?.size ?: 0} عناصر مباشرة"
                else -> "ملف • ${formatBytes(file.length())}"
            }, 10f, if (locked) 0xFFFFD27A.toInt() else 0xFF9FB4D3.toInt(), false).apply { maxLines = 1 })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (isDirectory) {
            val lockButton = iconBadge(if (locked) "lock" else "unlock", if (locked) 0x44FFD27A else 0x334B73AA,
                if (locked) 0xFFFFD27A.toInt() else 0xFFBBD2FF.toInt(), 32).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "كلمة مرور مستقلة للمجلد ${file.name}"
                setOnClickListener { promptFolderLock(relative) }
            }
            row.addView(lockButton, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) })
        }
        if (depth == 0) {
            val remove = iconBadge("trash", 0x334E2937, 0xFFFFB6B6.toInt(), 32).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "حذف ${file.name} من القائمة"
                setOnClickListener { removeSource(file, relative) }
            }
            row.addView(remove, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) })
        }
        sourcesListContainer.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
        if (expanded) {
            val children = file.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                ?.take(300).orEmpty()
            for (child in children) addSourceRows(child, root, depth + 1)
        }
    }

    // ───────────────────────────── Open flow ─────────────────────────────

    private fun browseSelectedArchive(uri: Uri?) {
        val selected = uri
        if (selected == null) {
            status.text = "لم يتم اختيار أرشيف."
            return
        }
        status.text = "جارٍ فتح أرشيف QZRA..."
        val name = displayName(selected) ?: "archive.qzra"
        runOnWorker("تعذر فتح الأرشيف") {
            val archive = copyUriToCache(selected, "qamzor-opened-${System.currentTimeMillis()}.qzra")
            try {
                // The manifest is validated here; payload integrity is checked on preview/extract/verify.
                ArchiveEngine.browse(archive)
                installOpenedArchive(archive, name, null, emptySet())
                runOnUiThread {
                    status.text = "تم فتح الأرشيف. تُقرأ الملفات من داخله دون فكّه بالكامل."
                    selectTab(1)
                    showVirtualExplorer("")
                }
            } catch (error: IllegalArgumentException) {
                if (needsArchivePassword(error)) {
                    runOnUiThread { selectTab(1); promptArchivePassword(archive, name) }
                } else throw error
            }
        }
    }

    private fun promptArchivePassword(archive: File, name: String, afterAuthenticated: (() -> Unit)? = null) {
        val passwordField = dialogField("كلمة مرور الأرشيف")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(passwordField)
        }
        AlertDialog.Builder(this)
            .setTitle("أرشيف QZRA مشفّر")
            .setMessage("أدخل كلمة مرور الأرشيف لفتح الفهرس وعرض الأسماء.")
            .setView(panel)
            .setPositiveButton("فتح الأرشيف") { _, _ ->
                val password = passwordField.text.toString()
                passwordField.text?.clear()
                if (password.isEmpty()) {
                    status.text = "يجب إدخال كلمة المرور للأرشيف المشفّر."
                } else {
                    runOnWorker("تعذر فتح الأرشيف المشفّر") {
                        try {
                            ArchiveEngine.browse(archive, "", password)
                            val protectedPaths = ArchiveEngine.protectedFolders(archive, password).toSet()
                            installOpenedArchive(archive, name, password, protectedPaths)
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
                                    .setPositiveButton("إعادة المحاولة") { _, _ -> promptArchivePassword(archive, name, afterAuthenticated) }
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

    private fun requestSelectedExtraction(path: String?) {
        val archive = openedArchive ?: pendingExtraction ?: return
        pendingExtraction = archive
        pendingExtractionPaths = path?.let { setOf(it) }
        val description = if (path == null) "الأرشيف بالكامل" else "العنصر: $path"
        status.text = "اختر مجلد الوجهة لاستخراج $description."
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            ), REQUEST_OUTPUT_TREE
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(Locale.ROOT, bytes / 1024.0)} KB"
        else -> "${"%.2f".format(Locale.ROOT, bytes / (1024.0 * 1024.0))} MB"
    }

    private fun savePendingArchive(uri: Uri?) {
        val archive = pendingArchive ?: error("لا يوجد أرشيف بانتظار الحفظ")
        val destination = uri ?: error("لم يتم اختيار مكان الحفظ")
        status.text = "جارٍ حفظ الأرشيف..."
        runOnWorker("فشل حفظ الأرشيف") {
            val output = contentResolver.openOutputStream(destination, "w") ?: error("لا يمكن فتح مكان الحفظ")
            output.use { stream -> archive.inputStream().use { it.copyTo(stream) } }
            runOnUiThread {
                status.text = "تم حفظ الأرشيف بنجاح: ${archive.name}\nيمكنك الآن فحصه أو مشاركته."
                pendingArchive = null
                try { archive.delete() } catch (_: Exception) { /* best effort */ }
                synchronized(selectedSources) { selectedSources.clear() }
                stagingDirectory?.deleteRecursively()
                stagingDirectory = null
                resetProtectionUi()
                refreshSelectionSummary()
            }
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

    private fun getStagingDirectory(): File {
        val current = stagingDirectory
        if (current != null && current.isDirectory) return current
        return File(cacheDir, "qamzor-selected-sources").apply { deleteRecursively(); mkdirs() }.also { stagingDirectory = it }
    }

    private fun countFilesAndDirectories(root: File): Pair<Int, Int> {
        var files = 0
        var directories = 0
        root.walkTopDown().forEach { if (it.isDirectory) directories++ else if (it.isFile) files++ }
        return files to directories
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

    private fun uniqueName(directory: File, requested: String): String {
        val used = directory.list()?.map { it.lowercase(Locale.ROOT) }?.toMutableSet() ?: mutableSetOf()
        return uniqueName(used, requested)
    }

    private fun uniqueName(used: MutableSet<String>, requested: String): String {
        if (used.add(requested.lowercase(Locale.ROOT))) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = "${stem}_$suffix$extension"
            if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
            suffix++
        }
    }

    companion object {
        private val COLOR_BG = 0xFF0B1730.toInt()
        private val COLOR_BAR = 0xFF101D36.toInt()
        private val NAV_IDLE = 0xFF8FA4C8.toInt()
        private val TAB_TITLES = listOf("إنشاء أرشيف جديد", "فتح وتصفح أرشيف", "الأدوات", "سجل العمليات")
        private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "py", "kt", "java", "yaml", "yml", "toml", "ini", "properties", "sh", "sql", "log")
        private const val REQUEST_FILES = 20
        private const val REQUEST_FOLDER = 21
        private const val REQUEST_SAVE_ARCHIVE = 22
        private const val REQUEST_BROWSE = 23
        private const val REQUEST_EXTRACT = 24
        private const val REQUEST_OUTPUT_TREE = 25
        private const val REQUEST_VERIFY = 26
        private const val REQUEST_COMPARE = 27
    }
}
