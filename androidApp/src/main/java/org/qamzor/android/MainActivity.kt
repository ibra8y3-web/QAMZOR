package org.qamzor.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
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
import android.content.ClipData
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.Observer
import org.qamzor.core.ArchiveEngine
import org.qamzor.core.ArchiveVolumes
import org.qamzor.core.InPlaceVault
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/** Android SAF front-end for the QAMZOR reference archive engine. */
class MainActivity : Activity() {
    private lateinit var status: TextView
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
    private lateinit var pageHost: FrameLayout
    private val navBoxes = ArrayList<View>()
    private val navIcons = ArrayList<QamzorLineIconView>()
    private val navLabels = ArrayList<TextView>()
    private var currentTab = 0
    private val backgroundTaskLock = Any()
    private val fileListingExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var fileRenderGeneration = 0
    @Volatile private var backgroundBusy = false
    private var pendingExtraction: File? = null
    private var pendingExtractionPaths: Set<String>? = null
    private var openedArchive: File? = null
    private var openedArchiveLabel: String = ""
    private var openedArchivePassword: String? = null
    private var openedProtected: Set<String> = emptySet()
    private var explorerPath: String = ""
    private val operationHistory = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val openedFolderPasswords: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())
    // File browser state (page 0)
    private lateinit var filesList: LinearLayout
    private lateinit var filesScroll: ScrollView
    private lateinit var filesPathText: TextView
    private lateinit var filesRootsRow: LinearLayout
    private lateinit var filesPermissionCard: View
    private lateinit var filesSearchInput: EditText
    private lateinit var filesSortSpinner: Spinner
    private lateinit var archiveSearchInput: EditText
    private lateinit var archiveSortSpinner: Spinner
    private lateinit var filesSelectionBar: View
    private lateinit var filesSelectionLabel: TextView
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private val selectedPaths = LinkedHashSet<String>()
    private var filesLimit = FILES_PAGE_SIZE
    private var filesSearchQuery = ""
    private var filesSortMode = 0
    private var archiveSearchQuery = ""
    private var archiveSortMode = 0
    private var archiveItems: List<ArchiveEngine.BrowseItem> = emptyList()
    private var openedArchiveInfo: ArchiveEngine.ArchiveInfo? = null
    private var pendingSignatureArchive: File? = null
    @Volatile private var activeOperationLabel: String? = null
    @Volatile private var activeOperationStartedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && settingsPreferences().getBoolean("cleanup_temp_on_start", true)) purgeStaleCache()
        restoreOperationHistory()
        buildInterface()
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    /** Plain-text working copies never outlive the process that made them. */
    private fun purgeStaleCache() {
        Thread({
            try {
                if (hasActiveBackgroundUseOfCache()) return@Thread
                val cutoff = System.currentTimeMillis() - TEMP_CACHE_RETENTION_MS
                cacheDir.listFiles()?.filter(::isQamzorTemporary)?.forEach { file ->
                    if (newestModified(file) <= cutoff) file.deleteRecursively()
                }
            } catch (_: Exception) { /* best effort; never block startup on cache maintenance */ }
        }, "QAMZOR-StaleCacheCleanup").start()
    }

    /** Do not remove cache-backed SAF copies while a durable worker still needs them. */
    private fun hasActiveBackgroundUseOfCache(): Boolean {
        val cacheRoot = cacheDir.canonicalPath + File.separator
        val infos = WorkManager.getInstance(this).getWorkInfosByTag(QamzorBackgroundWork.TAG_ALL)
            .get(5, java.util.concurrent.TimeUnit.SECONDS)
        return infos.any { info ->
            if (info.state !in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) return@any false
            val paths = ArrayList<String>()
            listOf("archive", "source", "destination", "store").forEach { key ->
                info.inputData.getString(key)?.let(paths::add)
            }
            info.inputData.getString("descriptor_path")?.let { descriptorPath ->
                runCatching {
                    val json = JSONObject(File(descriptorPath).readText(Charsets.UTF_8))
                    listOf("archive", "source", "destination", "store").forEach { key -> json.optString(key).takeIf { it.isNotBlank() }?.let(paths::add) }
                    json.optJSONArray("sources")?.let { array -> for (index in 0 until array.length()) paths.add(array.getString(index)) }
                }
            }
            paths.any { raw -> runCatching { File(raw).canonicalPath.startsWith(cacheRoot) }.getOrDefault(false) }
        }
    }

    private fun isQamzorTemporary(file: File): Boolean =
        file.name == "qamzor-selected-sources" || file.name.startsWith("qamzor-") ||
            file.name.startsWith("QAMZOR-") || file.name.startsWith("selected-archive-")

    private fun newestModified(file: File): Long {
        if (!file.isDirectory) return file.lastModified()
        val children = file.listFiles() ?: return file.lastModified()
        return children.maxOfOrNull(::newestModified) ?: file.lastModified()
    }

    /** Double-tapping a .qzra file in any file manager opens it here. */
    private fun handleViewIntent(launch: Intent?) {
        val uri = launch?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        selectTab(1)
        browseSelectedArchive(uri)
    }

    override fun onBackPressed() {
        if (currentTab == 0 && selectedPaths.isNotEmpty()) {
            clearFileSelection()
        } else if (currentTab == 0 && goUp()) {
            // moved to the parent folder
        } else if (currentTab == 1 && openedArchive != null && explorerPath.isNotEmpty()) {
            showVirtualExplorer(explorerPath.substringBeforeLast('/', ""))
        } else if (currentTab != 0) {
            selectTab(0)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun operationPreferences() = getSharedPreferences("qamzor-operation-center", Context.MODE_PRIVATE)
    private fun filesPreferences() = getSharedPreferences("qamzor-files-browser", Context.MODE_PRIVATE)
    private fun archivePreferences() = getSharedPreferences("qamzor-archive-browser", Context.MODE_PRIVATE)
    private fun settingsPreferences() = getSharedPreferences("qamzor-settings", Context.MODE_PRIVATE)
    private fun openedFolderPasswordsSnapshot(): Map<String, String> =
        synchronized(openedFolderPasswords) { LinkedHashMap(openedFolderPasswords) }

    private fun restoreOperationHistory() {
        val preferences = operationPreferences()
        val saved = preferences.getString("events", "").orEmpty()
        synchronized(operationHistory) {
            operationHistory.clear()
            val maxEvents = settingsPreferences().getInt("operation_history_limit", 100).coerceIn(20, 500)
        if (saved.isNotEmpty()) operationHistory.addAll(saved.lineSequence().filter { it.isNotBlank() }.toList().takeLast(maxEvents))
            val previousPid = preferences.getInt("active_pid", -1)
            val previousLabel = preferences.getString("active_label", null)
            if (previousPid > 0 && previousPid != android.os.Process.myPid()) {
                val whenText = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
                operationHistory.add("[$whenText] توقفت العملية بسبب إغلاق التطبيق أو إنهاء النظام — ${previousLabel ?: "عملية ملفات"}")
                while (operationHistory.size > settingsPreferences().getInt("operation_history_limit", 100).coerceIn(20, 500)) operationHistory.removeAt(0)
                preferences.edit().putString("events", operationHistory.joinToString("\n"))
                    .remove("active_pid").remove("active_label").remove("active_started_at").apply()
            }
        }
    }

    private fun recordOperationEvent(message: String) {
        synchronized(operationHistory) {
            operationHistory.add(message)
            while (operationHistory.size > settingsPreferences().getInt("operation_history_limit", 100).coerceIn(20, 500)) operationHistory.removeAt(0)
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

        pageHost = FrameLayout(this)
        pages.add(buildFilesPage())
        pages.add(buildOpenPage())
        pages.add(buildToolsPage())
        pages.add(buildOperationsPage())
        pages.add(buildSettingsPage())
        for (page in pages) pageHost.addView(page, FrameLayout.LayoutParams(-1, -1))
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(status, LinearLayout.LayoutParams(-1, -2))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(COLOR_BAR)
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        val specs = listOf("folder" to "الملفات", "folder_search" to "الأرشيف", "tools" to "أدوات", "activity" to "العمليات", "settings" to "الإعدادات")
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
        renderFiles()
        refreshOperationLog()
    }

    private fun replacePage(index: Int, page: View) {
        val previous = pages[index]
        val visibility = previous.visibility
        pageHost.removeView(previous)
        pages[index] = page
        page.visibility = visibility
        pageHost.addView(page, FrameLayout.LayoutParams(-1, -1))
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

    private fun sortAdapter(options: List<String>): ArrayAdapter<String> = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, options
    ) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(0xFFF4F7FF.toInt())
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(dp(10), 0, dp(10), 0)
            }
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(0xFF17233B.toInt())
                textSize = 13f
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
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

    // ───────────────────────────── Page 1: Phone files (browse everything, archive/encrypt in place) ─────────────────────────────

    private fun buildFilesPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(COLOR_BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }

        val permission = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = rounded(0xFF3A2D12.toInt(), 14)
            visibility = View.GONE
        }
        permission.addView(label("يحتاج QAMZOR صلاحية «الوصول إلى كل الملفات» ليعرض ملفات الهاتف ويؤرشف الأصل نفسه (مثل RAR).", 12f, 0xFFFFE2A8.toInt(), true))
        permission.addView(actionButton("shield_check", "منح الصلاحية", true) { requestStorageAccess() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        filesPermissionCard = permission
        header.addView(permission, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        filesRootsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        header.addView(filesRootsRow, LinearLayout.LayoutParams(-1, -2))

        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        filesPathText = label("", 11f, 0xFF9FB4D3.toInt(), false).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.START
        }
        pathRow.addView(filesPathText, LinearLayout.LayoutParams(0, -2, 1f))
        pathRow.addView(label("↑ للأعلى", 12f, 0xFFBBD2FF.toInt(), true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener { if (!goUp()) status.text = "أنت في جذر التخزين." }
        })
        header.addView(pathRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        filesSearchInput = inputField("بحث في أسماء الملفات والامتدادات...", false).apply {
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_search, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filesSearchQuery = s?.toString()?.trim().orEmpty()
                    filesLimit = FILES_PAGE_SIZE
                    renderFiles()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        header.addView(filesSearchInput, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })
        filesSortSpinner = Spinner(this).apply {
            adapter = sortAdapter(FILE_SORT_OPTIONS)
            background = rounded(0xFF1D3152.toInt(), 10)
            filesSortMode = filesPreferences().getInt("sort", 0).coerceIn(FILE_SORT_OPTIONS.indices)
            setSelection(filesSortMode)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (filesSortMode == position) return
                    filesSortMode = position
                    filesPreferences().edit().putInt("sort", position).apply()
                    renderFiles()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        header.addView(filesSortSpinner, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = rounded(0xFF1B3358.toInt(), 14)
            visibility = View.GONE
        }
        filesSelectionLabel = label("", 12.5f, 0xFFE7EFFD.toInt(), true)
        bar.addView(filesSelectionLabel)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        buttons.addView(actionButton("create", "أرشفة", false) { confirmSeal(false) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        buttons.addView(actionButton("lock", "تشفير", true) { confirmSeal(true) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        buttons.addView(actionButton("close", "إلغاء", false) { clearFileSelection() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        bar.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        filesSelectionBar = bar
        header.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        page.addView(header, LinearLayout.LayoutParams(-1, -2))

        filesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(6), dp(14), dp(18))
        }
        filesScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(filesList)
        }
        page.addView(filesScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
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
        val signatureRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        signatureRow.addView(actionButton("share", "توقيع ومشاركة", true) { signAndShareOpenedArchive() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        signatureRow.addView(actionButton("shield_check", "التحقق من التوقيع", false) { verifyOpenedArchiveSignature() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        column.addView(signatureRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        column.addView(actionButton("cloud", "رفع نسخة الأرشيف إلى WebDAV", false) { pushOpenedArchiveToCloud() },
            LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(7) })
        column.addView(actionButton("usb", "نسخ الأرشيف إلى USB / SD", false) { chooseExternalExportTree() },
            LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        archiveInfoText = label("", 12.5f, 0xFFE7EFFD.toInt(), true).apply { setPadding(dp(2), dp(12), dp(2), dp(0)) }
        explorerPathText = label("", 11f, 0xFF9FB4D3.toInt(), false).apply { setPadding(dp(2), dp(2), dp(2), dp(8)) }
        column.addView(archiveInfoText)
        column.addView(explorerPathText)
        archiveSearchInput = inputField("بحث داخل هذا المجلد في الأرشيف...", false).apply {
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_search, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    archiveSearchQuery = s?.toString()?.trim().orEmpty()
                    renderExplorerContents()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        column.addView(archiveSearchInput, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(5) })
        archiveSortSpinner = Spinner(this).apply {
            adapter = sortAdapter(ARCHIVE_SORT_OPTIONS)
            background = rounded(0xFF1D3152.toInt(), 10)
            archiveSortMode = archivePreferences().getInt("sort", 0).coerceIn(ARCHIVE_SORT_OPTIONS.indices)
            setSelection(archiveSortMode)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (archiveSortMode == position) return
                    archiveSortMode = position
                    archivePreferences().edit().putInt("sort", position).apply()
                    renderExplorerContents()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        column.addView(archiveSortSpinner, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(8) })
        explorerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(explorerContainer, LinearLayout.LayoutParams(-1, -2))
        return pageScroll(column)
    }

    private fun showSavedPasswordPicker(onSelected: (String) -> Unit) {
        val entries = QamzorPasswordVault.list(this)
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle("خزنة كلمات المرور فارغة")
                .setMessage("يمكنك إضافة كلمة مرور من إعدادات QAMZOR ثم اختيارها هنا.")
                .setPositiveButton("إدارة الخزنة") { _, _ -> managePasswordVault() }
                .setNegativeButton("إغلاق", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("اختيار كلمة مرور محفوظة")
            .setItems(entries.map { it.label }.toTypedArray()) { _, index ->
                try { onSelected(QamzorPasswordVault.reveal(this, entries[index].id)) }
                catch (error: Exception) { status.text = "تعذر فك كلمة المرور المحفوظة: ${error.message}" }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun managePasswordVault() {
        val entries = QamzorPasswordVault.list(this)
        val labels = entries.map { entry ->
            "${entry.label}\n${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.updatedAt))}"
        }.toMutableList()
        labels.add("＋ إضافة كلمة مرور جديدة")
        AlertDialog.Builder(this).setTitle("خزنة كلمات المرور المشفرة")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == entries.size) addVaultPassword()
                else showVaultPasswordActions(entries[index])
            }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun addVaultPassword() {
        val name = dialogField("اسم أو وصف كلمة المرور").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val secret = dialogField("كلمة المرور")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(name); addView(secret)
            addView(label("تُشفّر القيمة بمفتاح Android Keystore على هذا الجهاز. لا تتم مزامنتها أو رفعها تلقائيًا.", 10f, 0xFF9FB4D3.toInt(), false))
        }
        AlertDialog.Builder(this).setTitle("إضافة كلمة مرور")
            .setView(panel)
            .setPositiveButton("حفظ مشفرًا") { _, _ ->
                try {
                    QamzorPasswordVault.save(this, name.text.toString(), secret.text.toString())
                    secret.text?.clear()
                    status.text = "تم حفظ كلمة المرور داخل الخزنة المشفرة."
                    managePasswordVault()
                } catch (error: Exception) { status.text = "تعذر حفظ كلمة المرور: ${error.message}" }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showVaultPasswordActions(entry: QamzorPasswordVault.Entry) {
        AlertDialog.Builder(this).setTitle(entry.label)
            .setItems(arrayOf("عرض كلمة المرور", "حذف من الخزنة")) { _, choice ->
                if (choice == 0) {
                    try {
                        val value = QamzorPasswordVault.reveal(this, entry.id)
                        val field = dialogField("كلمة المرور").apply {
                            setText(value); setSelection(text.length)
                            inputType = android.text.InputType.TYPE_CLASS_TEXT
                        }
                        AlertDialog.Builder(this).setTitle(entry.label).setView(field)
                            .setPositiveButton("إغلاق") { _, _ -> field.text?.clear() }.show()
                    } catch (error: Exception) { status.text = "تعذر عرض كلمة المرور: ${error.message}" }
                } else AlertDialog.Builder(this).setTitle("حذف كلمة المرور")
                    .setMessage("سيتم حذف السر المشفر وبياناته المحلية نهائيًا.")
                    .setPositiveButton("حذف") { _, _ ->
                        QamzorPasswordVault.remove(this, entry.id)
                        status.text = "تم حذف كلمة المرور من الخزنة."
                        managePasswordVault()
                    }.setNegativeButton("إلغاء", null).show()
            }.setNegativeButton("رجوع") { _, _ -> managePasswordVault() }.show()
    }

    private fun showCloudAccounts() {
        val accounts = QamzorCloudAccounts.list(this)
        val labels = accounts.map { "${it.name}\n${it.collectionUrl}" }.toMutableList()
        labels.add("＋ إضافة حساب WebDAV جديد")
        AlertDialog.Builder(this)
            .setTitle("حسابات التخزين السحابي")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == accounts.size) addWebDavAccount() else showCloudAccountActions(accounts[index])
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun addWebDavAccount() {
        val name = dialogField("اسم الحساب").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val url = dialogField("رابط WebDAV يبدأ بـ https://").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val username = dialogField("اسم المستخدم").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val password = dialogField("كلمة مرور / App Password").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(name); addView(url); addView(username); addView(password)
            addView(label("استخدم رابط مجلد WebDAV مخصصًا للتطبيق. يجب أن يكون HTTPS، ولا تُضمّن بيانات الدخول داخل الرابط.", 10f, 0xFF9FB4D3.toInt(), false))
        }
        AlertDialog.Builder(this).setTitle("إضافة حساب WebDAV")
            .setView(panel)
            .setPositiveButton("اختبار وحفظ", null)
            .setNegativeButton("إلغاء", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val accountName = name.text.toString().trim()
                        val accountUrl = url.text.toString().trim()
                        val accountUser = username.text.toString().trim()
                        val accountPassword = password.text.toString()
                        if (accountName.isBlank() || accountUrl.isBlank() || accountUser.isBlank() || accountPassword.isEmpty()) {
                            status.text = "أكمل جميع بيانات الحساب أولًا."
                            return@setOnClickListener
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        status.text = "جارٍ اختبار اتصال WebDAV قبل حفظ بيانات الدخول..."
                        Thread({
                            try {
                                val probe = QamzorCloudAccount("00000000000000000000000000000000", accountName, accountUrl, accountUser)
                                val httpStatus = QamzorWebDavClient(probe, accountPassword).testConnection()
                                val saved = QamzorCloudAccounts.save(this, accountName, accountUrl, accountUser, accountPassword)
                                runOnUiThread {
                                    dialog.dismiss()
                                    status.text = "تم التحقق من الاتصال وحفظ الحساب المشفر ($httpStatus)."
                                    showCloudAccountActions(saved)
                                }
                            } catch (error: Exception) {
                                runOnUiThread {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    status.text = "لم يُحفظ الحساب لأن الاختبار فشل: ${error.message ?: error.javaClass.simpleName}"
                                }
                            }
                        }, "QAMZOR-WebDAV-Test").start()
                    }
                }
                dialog.show()
            }
    }

    private fun showCloudAccountActions(account: QamzorCloudAccount) {
        AlertDialog.Builder(this).setTitle(account.name)
            .setItems(arrayOf("اختبار الاتصال", "رفع الأرشيف المفتوح والتحقق منه", "استعراض أرشيفات السحابة وتنزيلها", "مزامنة مجلد QZRA مع السحابة", "حذف الحساب من هذا الجهاز")) { _, choice ->
                when (choice) {
                    0 -> runOnWorker("فشل اختبار اتصال WebDAV") {
                        val passwordBytes = AndroidSecretVault.get(this, "cloud-${account.id}")
                        val password = try { String(passwordBytes, Charsets.UTF_8) } finally { passwordBytes.fill(0) }
                        val code = QamzorWebDavClient(account, password).testConnection()
                        runOnUiThread { status.text = "اتصال WebDAV ناجح (HTTP $code)." }
                    }
                    1 -> enqueueCloudUpload(account)
                    2 -> showRemoteCloudArchives(account)
                    3 -> enqueueCloudSync(account)
                    4 -> AlertDialog.Builder(this).setTitle("حذف الحساب")
                        .setMessage("سيتم حذف بيانات الحساب وكلمة مروره المشفرة من هذا الجهاز. لن تُحذف الملفات الموجودة على الخادم.")
                        .setPositiveButton("حذف") { _, _ ->
                            QamzorCloudAccounts.remove(this, account.id)
                            status.text = "تم حذف بيانات الحساب المحلية."
                        }.setNegativeButton("إلغاء", null).show()
                }
            }.setNegativeButton("رجوع") { _, _ -> showCloudAccounts() }.show()
    }

    private fun pushOpenedArchiveToCloud() {
        val archive = openedArchive
        if (archive == null || !archive.isFile) { status.text = "افتح أرشيف QZRA أولًا."; return }
        val accounts = QamzorCloudAccounts.list(this)
        if (accounts.isEmpty()) {
            AlertDialog.Builder(this).setTitle("لا توجد حسابات سحابية")
                .setMessage("أضف حساب WebDAV واختبر الاتصال أولًا، ثم ارفع الأرشيف.")
                .setPositiveButton("إضافة حساب") { _, _ -> addWebDavAccount() }
                .setNegativeButton("إلغاء", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("اختر وجهة الرفع")
            .setItems(accounts.map { it.name }.toTypedArray()) { _, index -> enqueueCloudUpload(accounts[index]) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showRemoteCloudArchives(account: QamzorCloudAccount) {
        runOnWorker("تعذر استعراض أرشيفات WebDAV") {
            val passwordBytes = AndroidSecretVault.get(this, "cloud-${account.id}")
            val password = try { String(passwordBytes, Charsets.UTF_8) } finally { passwordBytes.fill(0) }
            val remote = QamzorWebDavClient(account, password).listArchives()
            runOnUiThread {
                if (remote.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("أرشيفات WebDAV")
                        .setMessage("لم يتم العثور على أرشيف QZRA منشور مع ملف تحقق QAMZOR صالح في هذا المجلد.")
                        .setPositiveButton("حسنًا", null).show()
                    return@runOnUiThread
                }
                val labels = remote.map { item ->
                    "${item.name}\n${formatBytes(item.size)} • ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.uploadedAt))}"
                }
                AlertDialog.Builder(this).setTitle("أرشيفات السحابة (${remote.size})")
                    .setItems(labels.toTypedArray()) { _, index -> enqueueCloudDownload(account, remote[index]) }
                    .setNegativeButton("إغلاق", null).show()
            }
        }
    }

    private fun enqueueCloudSync(account: QamzorCloudAccount) {
        val folder = currentDir
        if (!folder.isDirectory || !folder.canRead() || !folder.canWrite()) {
            status.text = "افتح مجلدًا محليًا قابلًا للقراءة والكتابة من تبويب الملفات أولًا."
            return
        }
        AlertDialog.Builder(this).setTitle("مزامنة أرشيفات QZRA")
            .setMessage("المجلد: ${folder.absolutePath}\n\nترفع الملفات المحلية غير الموجودة على WebDAV، وتنزل الملفات السحابية غير الموجودة محليًا، وتتخطى النسخ المتطابقة. إذا اختلف ملفان بالاسم نفسه، لا يستبدل QAMZOR أي نسخة ويسجل تعارضًا للمراجعة. لا تُحذف ملفات من أي جهة.")
            .setPositiveButton("بدء المزامنة") { _, _ ->
                try {
                    val id = QamzorBackgroundWork.enqueueCloudSync(this, account.id, folder)
                    recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] أُضيفت مزامنة QZRA آمنة — ${folder.name} — $id")
                    status.text = "بدأت مزامنة أرشيفات QZRA مع ${account.name}."
                    selectTab(3)
                } catch (error: Exception) { status.text = "تعذر بدء المزامنة: ${error.message}" }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun enqueueCloudDownload(account: QamzorCloudAccount, remote: QamzorRemoteArchive) {
        try {
            val directory = File(getExternalFilesDir(null) ?: filesDir, "QAMZOR/CloudDownloads").apply { mkdirs() }
            val baseName = remote.name.removeSuffix(".qzra").removeSuffix(".QZRA")
            var destination = File(directory, remote.name)
            if (destination.exists()) destination = File(directory, "$baseName-${System.currentTimeMillis()}.qzra")
            val id = QamzorBackgroundWork.enqueueCloudPull(this, account.id, remote.name, destination)
            recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] أُضيف تنزيل سحابي متحقق — ${remote.name} — $id")
            status.text = "بدأ تنزيل ${remote.name}. لن يُنشر الملف محليًا إلا بعد مطابقة الحجم وبصمة SHA-256."
            selectTab(3)
        } catch (error: Exception) {
            status.text = "تعذر بدء التنزيل: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun enqueueCloudUpload(account: QamzorCloudAccount) {
        val archive = openedArchive
        if (archive == null || !archive.isFile) { status.text = "افتح الأرشيف المراد رفعه أولًا."; return }
        try {
            val workId = QamzorBackgroundWork.enqueueCloudPush(this, archive, account.id)
            recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] أُضيف رفع سحابي إلى ${account.name} — $workId")
            status.text = "أُضيف الرفع إلى العمليات الخلفية. سيُتحقق من SHA-256 بعد الرفع."
            selectTab(3)
        } catch (error: Exception) { status.text = "تعذر بدء الرفع: ${error.message ?: error.javaClass.simpleName}" }
    }

    private fun chooseExternalStorageTree() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, REQUEST_STORAGE_TREE)
    }

    private fun openLinkedExternalStorage() {
        val raw = settingsPreferences().getString("external_storage_tree_uri", null)
        if (raw.isNullOrBlank()) {
            AlertDialog.Builder(this).setTitle("لم يتم ربط وحدة تخزين")
                .setMessage("اربط USB أو بطاقة SD أولًا، ثم افتحها من هنا.")
                .setPositiveButton("ربط وحدة التخزين") { _, _ -> chooseExternalStorageTree() }
                .setNegativeButton("إلغاء", null).show()
            return
        }
        try {
            val uri = Uri.parse(raw)
            val root = DocumentFile.fromTreeUri(this, uri) ?: error("تعذر فتح وحدة التخزين")
            if (!root.canRead()) error("إذن القراءة غير متاح. أعد ربط وحدة التخزين.")
            showSafDirectory(root)
        } catch (error: Exception) {
            settingsPreferences().edit().remove("external_storage_tree_uri").apply()
            status.text = "تعذر استخدام إذن وحدة التخزين: ${error.message}. أعد ربطها."
        }
    }

    private fun showSafDirectory(directory: DocumentFile) {
        Thread({
            try {
                val children = directory.listFiles().sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase(Locale.ROOT).orEmpty() }))
                val shown = children.take(500)
                runOnUiThread {
                    val labels = shown.map { item ->
                        (if (item.isDirectory) "📁 " else "📄 ") + (item.name ?: "بدون اسم") +
                            if (item.isFile) " • ${formatBytes(item.length())}" else ""
                    }
                    AlertDialog.Builder(this).setTitle(directory.name ?: "وحدة التخزين")
                        .setItems(labels.toTypedArray()) { _, index ->
                            val selected = shown[index]
                            if (selected.isDirectory) showSafDirectory(selected)
                            else if (selected.name?.endsWith(".qzra", true) == true) browseSelectedArchive(selected.uri)
                            else status.text = "تم العثور على الملف، لكن فتحه من هذا المتصفح متاح حاليًا لأرشيفات QZRA فقط."
                        }
                        .setPositiveButton("تحديث", null)
                        .setNegativeButton("إغلاق", null)
                        .setOnCancelListener { }
                        .show()
                    if (children.size > shown.size) status.text = "تم عرض أول 500 عنصر. افتح مجلدًا فرعيًا للوصول إلى بقية الملفات."
                    else status.text = "${children.size} عنصر في ${directory.name ?: "وحدة التخزين"}."
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "تعذرت قراءة وحدة التخزين: ${error.message}" }
            }
        }, "QAMZOR-SAF-Browser").start()
    }

    private fun unlinkExternalStorage() {
        val raw = settingsPreferences().getString("external_storage_tree_uri", null)
        if (raw.isNullOrBlank()) { status.text = "لا يوجد إذن وحدة تخزين محفوظ."; return }
        try {
            val uri = Uri.parse(raw)
            contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let { permission ->
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (flags != 0) contentResolver.releasePersistableUriPermission(uri, flags)
            }
        } catch (_: Exception) { }
        settingsPreferences().edit().remove("external_storage_tree_uri").apply()
        status.text = "تم إلغاء ربط وحدة التخزين دون حذف أي ملفات."
    }

    private fun chooseExternalExportTree() {
        val archive = openedArchive
        if (archive == null || !archive.isFile) { status.text = "افتح أرشيف QZRA أولًا."; return }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ), REQUEST_EXPORT_TREE)
    }

    private fun exportOpenedArchiveToTree(treeUri: Uri) {
        val archive = openedArchive ?: return
        runOnWorker("تعذر نسخ الأرشيف إلى USB أو SD") {
            val root = DocumentFile.fromTreeUri(this, treeUri) ?: error("تعذر فتح مجلد الوجهة")
            require(root.canWrite()) { "مجلد الوجهة لا يسمح بالكتابة" }
            val companions = listOf(archive, File(archive.parentFile, archive.name + ".qzsig"), File(archive.parentFile, archive.name + ".public.pem"))
                .filter { it.isFile }
            val written = ArrayList<DocumentFile>()
            try {
                companions.forEach { source ->
                    val base = source.name
                    var name = base
                    var suffix = 2
                    while (root.findFile(name) != null) { name = "${base.substringBeforeLast('.', base)}_$suffix${base.substringAfterLast('.', "").let { ext -> if (ext == base) "" else ".$ext" }}"; suffix++ }
                    val target = root.createFile(if (source.extension.equals("qzra", true)) "application/vnd.qamzor.qzra" else "application/octet-stream", name)
                        ?: error("تعذر إنشاء الملف على وحدة التخزين: $name")
                    written.add(target)
                    contentResolver.openOutputStream(target.uri, "w").use { output ->
                        requireNotNull(output) { "تعذر الكتابة إلى $name" }
                        source.inputStream().use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
                    val expected = digestFile(source)
                    val actual = contentResolver.openInputStream(target.uri).use { input ->
                        requireNotNull(input) { "تعذر إعادة قراءة $name" }
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(1024 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
                        md.digest().joinToString("") { "%02x".format(it) }
                    }
                    require(expected == actual) { "فشل التحقق من النسخة المنسوخة: $name" }
                }
                runOnUiThread { status.text = "تم نسخ ${written.size} ملف والتحقق من بصمات SHA-256 على وحدة التخزين الخارجية." }
            } catch (error: Exception) {
                written.forEach { it.delete() }
                throw error
            }
        }
    }

    private fun digestFile(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signingKeyMaterial(): Pair<ByteArray, ByteArray>? {
        val bytes = try { AndroidSecretVault.get(this, "qamzor-signing-key-v1") } catch (_: Exception) { return null }
        return try {
            val stored = JSONObject(String(bytes, Charsets.UTF_8))
            android.util.Base64.decode(stored.getString("private"), android.util.Base64.DEFAULT) to
                android.util.Base64.decode(stored.getString("public"), android.util.Base64.DEFAULT)
        } finally { bytes.fill(0) }
    }

    private fun createLocalSigningKey(): String {
        val pair = org.qamzor.core.ArchiveSignatures.generateKeyPair()
        try {
            val payload = JSONObject()
                .put("private", android.util.Base64.encodeToString(pair.privateKeyPkcs8, android.util.Base64.NO_WRAP))
                .put("public", android.util.Base64.encodeToString(pair.publicKeyX509, android.util.Base64.NO_WRAP))
                .toString().toByteArray(Charsets.UTF_8)
            AndroidSecretVault.put(this, "qamzor-signing-key-v1", payload)
            return signingFingerprint(pair.publicKeyX509)
        } finally { pair.privateKeyPkcs8.fill(0) }
    }

    private fun signingFingerprint(publicKey: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(publicKey).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    private fun showSigningKeyManager() {
        val material = signingKeyMaterial()
        if (material == null) {
            AlertDialog.Builder(this).setTitle("مفتاح توقيع QAMZOR")
                .setMessage("لا يوجد مفتاح Ed25519 محلي بعد. إنشاء المفتاح يحفظ الخاص مشفرًا بمفتاح Android Keystore. المفتاح العام فقط يخرج مع الأرشيف عند المشاركة.")
                .setPositiveButton("إنشاء مفتاح") { _, _ ->
                    runOnWorker("تعذر إنشاء مفتاح التوقيع") {
                        val fingerprint = createLocalSigningKey()
                        runOnUiThread { status.text = "تم إنشاء مفتاح Ed25519. بصمة المفتاح: $fingerprint" }
                    }
                }.setNegativeButton("إغلاق", null).show()
            return
        }
        val (privateKey, publicKey) = material
        privateKey.fill(0)
        val fingerprint = signingFingerprint(publicKey)
        publicKey.fill(0)
        AlertDialog.Builder(this).setTitle("مفتاح Ed25519 المحلي")
            .setMessage("بصمة المفتاح العام:\n$fingerprint\n\nتحقق من البصمة عبر قناة مستقلة قبل الوثوق بتوقيع مستلم. تدوير المفتاح لا يبطل التواقيع السابقة التي تحتفظ بملف المفتاح العام الصحيح، لكنه يغيّر هوية التوقيع لهذا الجهاز.")
            .setItems(arrayOf("نسخ بصمة المفتاح العام", "تدوير المفتاح وإنشاء هوية جديدة")) { _, choice ->
                if (choice == 0) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("QAMZOR Ed25519 fingerprint", fingerprint))
                    status.text = "تم نسخ بصمة المفتاح العام."
                } else AlertDialog.Builder(this).setTitle("تأكيد تدوير المفتاح")
                    .setMessage("سيتم حذف المفتاح الخاص الحالي من هذا الجهاز نهائيًا وإنشاء مفتاح جديد. احتفظ بالمفاتيح العامة القديمة للتحقق من التواقيع السابقة.")
                    .setPositiveButton("تدوير المفتاح") { _, _ ->
                        runOnWorker("تعذر تدوير مفتاح التوقيع") {
                            AndroidSecretVault.remove(this, "qamzor-signing-key-v1")
                            val next = createLocalSigningKey()
                            runOnUiThread { status.text = "تم تدوير المفتاح. البصمة الجديدة: $next" }
                        }
                    }.setNegativeButton("إلغاء", null).show()
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun signAndShareOpenedArchive() {
        val archive = openedArchive
        if (archive == null || !archive.isFile) {
            status.text = "افتح أرشيف QZRA أولًا."
            return
        }
        val signingReference = "qamzor-signing-key-v1"
        runOnWorkerAfter("تعذر توقيع الأرشيف", {
            val storedBytes = try { AndroidSecretVault.get(this, signingReference) } catch (_: Exception) { null }
            val stored = storedBytes?.let { bytes -> try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) } }
            val privateKey: ByteArray
            val publicKey: ByteArray
            if (stored == null) {
                val generated = org.qamzor.core.ArchiveSignatures.generateKeyPair()
                privateKey = generated.privateKeyPkcs8
                publicKey = generated.publicKeyX509
                val payload = JSONObject()
                    .put("private", android.util.Base64.encodeToString(privateKey, android.util.Base64.NO_WRAP))
                    .put("public", android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP))
                AndroidSecretVault.put(this, signingReference, payload.toString().toByteArray(Charsets.UTF_8))
            } else {
                privateKey = android.util.Base64.decode(stored.getString("private"), android.util.Base64.DEFAULT)
                publicKey = android.util.Base64.decode(stored.getString("public"), android.util.Base64.DEFAULT)
            }
            val signature = File(archive.parentFile, archive.name + ".qzsig")
            val public = File(archive.parentFile, archive.name + ".public.pem")
            try {
                org.qamzor.core.ArchiveSignatures.signArchive(archive, privateKey, publicKey, signature)
                public.writeText(org.qamzor.core.ArchiveSignatures.publicKeyPem(publicKey), Charsets.US_ASCII)
            } finally {
                privateKey.fill(0)
            }
        }) {
            try {
                val signature = File(archive.parentFile, archive.name + ".qzsig")
                val public = File(archive.parentFile, archive.name + ".public.pem")
                val authority = "$packageName.files"
                val uris = arrayListOf(archive, signature, public).map { FileProvider.getUriForFile(this, authority, it) }
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/octet-stream"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    clipData = ClipData.newUri(contentResolver, "QAMZOR signed archive", uris.first()).apply {
                        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                status.text = "تم إنشاء توقيع Ed25519 وبصمة الملف. أرسل الأرشيف وملف .qzsig والمفتاح العام معًا."
                startActivity(Intent.createChooser(share, "مشاركة أرشيف QAMZOR الموقّع"))
            } catch (error: Exception) {
                status.text = "تم إنشاء التوقيع لكن تعذرت المشاركة: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun verifyOpenedArchiveSignature() {
        val archive = openedArchive
        if (archive == null || !archive.isFile) { status.text = "افتح الأرشيف أولًا."; return }
        val signature = File(archive.parentFile, archive.name + ".qzsig")
        if (!signature.isFile) {
            status.text = "ملف التوقيع غير موجود بجانب الأرشيف (.qzsig)."
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        pendingSignatureArchive = archive
        startActivityForResult(intent, REQUEST_PUBLIC_KEY)
    }

    private fun verifySignatureWithPublicKey(archive: File, publicKeyFile: File) {
        val signature = File(archive.parentFile, archive.name + ".qzsig")
        runOnWorker("تعذر التحقق من التوقيع الرقمي") {
            val publicKey = org.qamzor.core.ArchiveSignatures.parsePublicKey(publicKeyFile)
            val result = org.qamzor.core.ArchiveSignatures.verifyArchive(archive, publicKey, signature)
            runOnUiThread {
                status.text = "التوقيع صحيح ✓\nEd25519 • SHA-256: ${result.archiveSha256}\nالحجم: ${formatBytes(result.archiveSize)}"
                AlertDialog.Builder(this).setTitle("تم التحقق من التوقيع الرقمي")
                    .setMessage("التوقيع صحيح والملف مطابق للبصمة المشمولة في التوقيع. للتأكد من هوية المرسل، قارن بصمة المفتاح العام هذه ببصمة موثوقة حصلت عليها من قناة مستقلة.\n\nبصمة المفتاح العام: ${result.publicKeySha256}")
                    .setPositiveButton("حسنًا", null).show()
            }
        }
    }

    private fun renderEmptyExplorer() {
        archiveItems = emptyList()
        openedArchiveInfo = null
        if (::archiveSearchInput.isInitialized) archiveSearchInput.visibility = View.GONE
        if (::archiveSortSpinner.isInitialized) archiveSortSpinner.visibility = View.GONE
        if (::archiveSearchInput.isInitialized && archiveSearchInput.text.isNotEmpty()) archiveSearchInput.text.clear()
        archiveSearchQuery = ""
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
        archiveItems = children
        renderExplorerContents()
    }

    private fun renderExplorerContents() {
        if (!::explorerContainer.isInitialized || openedArchive == null) return
        archiveSearchInput.visibility = View.VISIBLE
        archiveSortSpinner.visibility = View.VISIBLE
        explorerContainer.removeAllViews()
        val info = openedArchiveInfo
        archiveInfoText.text = if (info == null) openedArchiveLabel.ifEmpty { "أرشيف QZRA" } else {
            val security = if (info.encrypted) "مشفّر" else "دون تشفير"
            val privacy = if (info.encryptedMetadata) " • بيانات مخفية" else ""
            val protected = if (info.protectedFolders > 0) " • ${info.protectedFolders} مجلد محمي" else ""
            "${openedArchiveLabel.ifEmpty { "أرشيف QZRA" }} • QZRA ${info.version} • $security$privacy\n" +
                "${info.files} ملف • ${info.directories} مجلد$protected • ${formatBytes(info.uncompressedBytes)}"
        }
        val query = archiveSearchQuery.lowercase(Locale.ROOT)
        val filtered = archiveItems.filter { item ->
            query.isEmpty() || item.name.lowercase(Locale.ROOT).contains(query) || item.path.lowercase(Locale.ROOT).contains(query)
        }
        val directoriesFirst = if (settingsPreferences().getBoolean("directories_first", true))
            compareBy<ArchiveEngine.BrowseItem> { if (it.type == "directory") 0 else 1 }
        else compareBy<ArchiveEngine.BrowseItem> { 0 }
        val sorted = when (archiveSortMode) {
            1 -> filtered.sortedWith(directoriesFirst.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
            2 -> filtered.sortedWith(directoriesFirst.thenByDescending { it.name.lowercase(Locale.ROOT) }.thenByDescending { it.name })
            3 -> filtered.sortedWith(directoriesFirst.thenByDescending { if (it.type == "file") it.size else -1L }.thenBy { it.name.lowercase(Locale.ROOT) })
            4 -> filtered.sortedWith(directoriesFirst.thenBy { if (it.type == "file") it.size else Long.MAX_VALUE }.thenBy { it.name.lowercase(Locale.ROOT) })
            5 -> filtered.sortedWith(directoriesFirst.thenBy { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) })
            else -> filtered.sortedWith(directoriesFirst.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
        }
        explorerPathText.text = (if (explorerPath.isBlank()) "/" else "/$explorerPath") +
            " • ${sorted.size}" + if (query.isNotEmpty()) "/${archiveItems.size} نتيجة" else " عنصر"
        if (explorerPath.isNotBlank()) {
            explorerContainer.addView(
                explorerRow("folder", "..  المجلد السابق", "", false, { showVirtualExplorer(explorerPath.substringBeforeLast('/', "")) }),
                LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(5) })
        }
        if (sorted.isEmpty()) {
            val message = if (query.isNotEmpty()) "لا توجد ملفات تطابق البحث داخل هذا المجلد." else "هذا المجلد فارغ."
            explorerContainer.addView(label(message, 12f, 0xFF9FB4D3.toInt(), false).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
        }
        for (item in sorted) {
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
            val children = ArchiveEngine.browse(archive, path, openedArchivePassword, openedFolderPasswordsSnapshot())
            runOnUiThread { renderExplorer(path, children) }
        }
    }

    private fun closeOpenedArchive() {
        if (backgroundBusy) {
            status.text = "هناك عملية تستخدم الأرشيف حاليًا. انتظر حتى تنتهي ثم أغلقه."
            return
        }
        if (openedArchive == null) {
            status.text = "لا يوجد أرشيف مفتوح."
            return
        }
        deleteIfCached(openedArchive)
        openedArchive = null
        pendingExtraction = null
        pendingExtractionPaths = null
        openedArchivePassword = null
        openedArchiveInfo = null
        archiveItems = emptyList()
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
        val scopes = openedProtected.filterNot { openedFolderPasswords.containsKey(it) }
        promptScopedFolderPasswords(archive, openedArchivePassword, scopes, 0, LinkedHashMap(),
            onComplete = { newlyEntered ->
                openedFolderPasswords.putAll(newlyEntered)
                runOnWorker("فشل فحص الأرشيف") { verifyArchiveFile(archive, openedArchivePassword, openedFolderPasswordsSnapshot()) }
            })
    }

    private fun installOpenedArchive(archive: File, name: String, password: String?, protectedPaths: Set<String>) {
        val previous = openedArchive
        openedArchive = archive
        pendingExtraction = archive
        openedArchiveLabel = name
        openedArchivePassword = password
        openedArchiveInfo = ArchiveEngine.inspect(archive, password)
        openedProtected = protectedPaths
        openedFolderPasswords.clear()
        explorerPath = ""
        if (previous != null && previous != archive) deleteIfCached(previous)
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
        column.addView(sectionLabel("الإصدارات والتوافق"))
        column.addView(toolRow("info", "QAMZOR 0.9.0", "قراءة تنسيقات QZRA من 1.0 إلى 1.3 • تقسيم الحِزم مع SHA-256") {
            AlertDialog.Builder(this)
                .setTitle("إصدارات QAMZOR وتنسيق QZRA")
                .setMessage("إصدار التطبيق: 0.9.0\n\nتنسيقات الأرشيف المدعومة: QZRA 1.0 و1.1 و1.2 و1.3.\n\n1.0: قراءة الأرشيفات القديمة.\n1.1: ضغط مجزأ وإزالة تكرار الكتل.\n1.2: تشفير AES-256-GCM والتحقق من الفهرس.\n1.3: تشفير بيانات الفهرس وإخفاء أسماء الملفات ودعم نطاقات المجلدات المحمية.\n\nحِزم الأرشيف: ملف .qzset مع أجزاء مرقمة وبصمات SHA-256 لكل جزء وللأرشيف الكامل. لا يوجد تحديث تلقائي مزيف؛ رقم الإصدار هنا هو الإصدار المثبت فعليًا.")
                .setPositiveButton("حسنًا", null)
                .show()
        }, rowParams())
        column.addView(label("QAMZOR  •  QZRA Archive System", 10f, 0xFF8299BE.toInt(), false)
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(2)) })
        return pageScroll(column)
    }

    // ───────────────────────────── Page 4: Operations ─────────────────────────────

    private fun buildOperationsPage(): View {
        val column = pageColumn()
        column.addView(toolRow("activity", "إدارة العمليات الخلفية", "متابعة التقدم والسرعة والوقت المتبقي، وإيقاف مؤقت أو استئناف أو إلغاء المهمة") {
            showBackgroundJobs()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(8) })
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

    private fun showBackgroundJobs() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle("عمليات QAMZOR الخلفية")
            .setView(scroll)
            .setPositiveButton("إغلاق", null)
            .create()
        val live = WorkManager.getInstance(this).getWorkInfosByTagLiveData(QamzorBackgroundWork.TAG_ALL)
        val observer = Observer<List<WorkInfo>> { workInfos ->
            content.removeAllViews()
            val jobs = workInfos.orEmpty().asReversed().take(30)
            if (jobs.isEmpty()) {
                content.addView(label("لا توجد مهام خلفية محفوظة حاليًا. أنشئ أرشيفًا أو نسخة احتياطية لعرض التقدم هنا.", 12f, 0xFF9FB4D3.toInt(), false))
            }
            jobs.forEach { info ->
                val percent = info.progress.getInt("progress", if (info.state == WorkInfo.State.SUCCEEDED) 100 else 0).coerceIn(0, 100)
                val phase = info.progress.getString("phase") ?: info.state.name
                val done = info.progress.getLong("completed_bytes", 0L)
                val total = info.progress.getLong("total_bytes", 0L)
                val speed = info.progress.getLong("speed_bytes_per_second", 0L)
                val eta = info.progress.getLong("eta_seconds", -1L)
                val files = info.progress.getInt("completed_files", -1)
                val totalFiles = info.progress.getInt("total_files", -1)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    background = rounded(0xFF1A2B49.toInt(), 12)
                }
                val details = buildString {
                    append("${info.state.name} • $percent%\n$phase")
                    if (total > 0) append("\n${formatBytes(done)} / ${formatBytes(total)}")
                    if (speed > 0) append(" • ${formatBytes(speed)}/ث")
                    if (eta >= 0) append(" • متبقي ${eta} ث")
                    if (files >= 0 && totalFiles >= 0) append("\nالملفات: $files / $totalFiles")
                    val uploaded = info.outputData.getInt("uploaded", info.progress.getInt("uploaded", -1))
                    val downloaded = info.outputData.getInt("downloaded", info.progress.getInt("downloaded", -1))
                    val unchanged = info.outputData.getInt("unchanged", info.progress.getInt("unchanged", -1))
                    if (uploaded >= 0 || downloaded >= 0) append("\nرفع: ${uploaded.coerceAtLeast(0)} • تنزيل: ${downloaded.coerceAtLeast(0)} • مطابق: ${unchanged.coerceAtLeast(0)}")
                    val conflicts = info.outputData.getString("conflicts") ?: info.progress.getString("conflicts")
                    if (!conflicts.isNullOrBlank()) append("\nتعارضات تحتاج مراجعة: $conflicts")
                    info.outputData.getString("local_path")?.let { append("\nحُفظ محليًا: $it") }
                    info.outputData.getString("error")?.let { append("\nالخطأ: $it") }
                }
                card.addView(label(details, 11.5f, 0xFFE7EFFD.toInt(), true))
                val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = percent
                }
                card.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(7) })
                card.addView(actionButton("settings", "إدارة المهمة", false) { showBackgroundJobActions(info) },
                    LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(7) })
                content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        dialog.setOnDismissListener { live.removeObserver(observer) }
        dialog.show()
        live.observeForever(observer)
    }

    private fun showBackgroundJobActions(info: WorkInfo) {
        val state = info.state
        val active = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
        val paused = info.progress.getString("phase") == "paused"
        val actions = when {
            !active -> arrayOf("عرض التفاصيل")
            paused -> arrayOf("استئناف", "إلغاء المهمة")
            else -> arrayOf("إيقاف مؤقت", "إلغاء المهمة")
        }
        AlertDialog.Builder(this).setTitle("المهمة ${info.id}")
            .setItems(actions) { _, which ->
                if (!active) {
                    val message = buildString {
                        append("الحالة: ${info.state}\nالمحاولات: ${info.runAttemptCount}\n")
                        append(info.outputData.getString("error") ?: info.outputData.getString("phase") ?: info.progress.getString("phase") ?: "لا توجد تفاصيل إضافية.")
                        info.outputData.getString("local_path")?.let { append("\nالمسار المحلي: $it") }
                        info.outputData.getString("conflicts")?.takeIf { it.isNotBlank() }?.let { append("\nتعارضات: $it") }
                    }
                    AlertDialog.Builder(this).setMessage(message).setPositiveButton("حسنًا", null).show()
                } else if (paused) {
                    QamzorBackgroundWork.resume(this, info.id)
                    status.text = "تم طلب استئناف المهمة الخلفية."
                } else if (which == 0) {
                    QamzorBackgroundWork.pause(this, info.id)
                    status.text = "تم طلب إيقاف المهمة مؤقتًا عند نقطة آمنة."
                } else {
                    AlertDialog.Builder(this).setTitle("إلغاء المهمة")
                        .setMessage("سيتم إيقاف المهمة عند أقرب نقطة آمنة. لن تُحذف ملفات المصدر.")
                        .setPositiveButton("إلغاء المهمة") { _, _ ->
                            QamzorBackgroundWork.cancel(this, info.id)
                            status.text = "تم إرسال طلب الإلغاء."
                        }.setNegativeButton("رجوع", null).show()
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun backupStoreDirectory(): File = File(noBackupFilesDir, "QAMZOR/Backups").apply { mkdirs() }

    private fun scheduleAutomaticBackup() {
        val source = currentDir
        if (!source.exists() || !source.canRead()) {
            status.text = "اختر مجلدًا أو ملفًا يمكن قراءته أولًا من تبويب الملفات."
            return
        }
        val options = Spinner(this).apply {
            adapter = sortAdapter(listOf("يوميًا", "كل 3 أيام", "أسبوعيًا"))
            setSelection(0)
        }
        val password = dialogField("كلمة مرور اختيارية لتشفير النسخ الاحتياطية")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("المصدر: ${source.absolutePath}", 11f, 0xFF53627A.toInt(), false))
            addView(label("مكان حفظ النسخ: ${backupStoreDirectory().absolutePath}", 10f, 0xFF53627A.toInt(), false))
            addView(options, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
            addView(password)
            addView(label("عند إدخال كلمة مرور تُشفّر كل نسخة بهذه الكلمة. احتفظ بها؛ لا يمكن استعادة النسخ بدونها. ينفذ Android الجدولة وفق قيود البطارية والتخزين.", 10f, 0xFF8A5A22.toInt(), false))
        }
        AlertDialog.Builder(this).setTitle("نسخ احتياطي تلقائي")
            .setView(panel)
            .setPositiveButton("جدولة") { _, _ ->
                val days = listOf(1L, 3L, 7L)[options.selectedItemPosition]
                try {
                    val secret = password.text.toString().ifEmpty { null }
                    val id = QamzorBackgroundWork.scheduleSnapshot(this, source, backupStoreDirectory(), days, secret)
                    password.text?.clear()
                    recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] تمت جدولة نسخة احتياطية كل ${days} يوم — $id")
                    status.text = if (secret == null) "تمت الجدولة بدون تشفير النسخة. يمكنك إضافة كلمة مرور للنسخ القادمة من جديد."
                        else "تمت جدولة النسخ الاحتياطي المشفر. كلمة المرور محفوظة داخل مخزن Android المشفر لتشغيل المهام التلقائية."
                } catch (error: Exception) { status.text = "تعذر جدولة النسخة: ${error.message}" }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun createBackupNow() {
        val source = currentDir
        if (!source.exists() || !source.canRead()) { status.text = "اختر مصدرًا صالحًا من متصفح الملفات."; return }
        val password = dialogField("كلمة مرور اختيارية لتشفير النسخة")
        AlertDialog.Builder(this).setTitle("إنشاء نسخة احتياطية")
            .setMessage("يمكن ترك كلمة المرور فارغة لإنشاء نسخة غير مشفرة. النسخة ستُفحص قبل تسجيلها في سجل الإصدارات.")
            .setView(password)
            .setPositiveButton("بدء النسخ") { _, _ ->
                try {
                    val secret = password.text.toString().ifEmpty { null }
                    val id = QamzorBackgroundWork.enqueueSnapshot(this, source, backupStoreDirectory(), secret)
                    password.text?.clear()
                    recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] أُضيفت نسخة احتياطية فورية — $id")
                    status.text = "بدأت إضافة النسخة إلى قائمة العمليات الخلفية."
                } catch (error: Exception) { status.text = "تعذر بدء النسخة: ${error.message}" }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showSnapshotHistory() {
        Thread({
            try {
                val snapshots = org.qamzor.core.SnapshotArchiveStore(backupStoreDirectory()).listSnapshots()
                runOnUiThread {
                    if (snapshots.isEmpty()) {
                        AlertDialog.Builder(this).setTitle("سجل إصدارات النسخ الاحتياطي")
                            .setMessage("لا توجد نسخ بعد. أنشئ نسخة الآن أو فعّل الجدولة التلقائية.")
                            .setPositiveButton("حسنًا", null).show()
                        return@runOnUiThread
                    }
                    val labels = snapshots.map { snap ->
                        "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snap.createdAtEpochMillis))} • ${snap.sourceName}\n${snap.files} ملف • ${formatBytes(snap.originalBytes)} • ${snap.archiveName}"
                    }
                    AlertDialog.Builder(this).setTitle("سجل إصدارات الملفات")
                        .setItems(labels.toTypedArray()) { _, index -> showSnapshotActions(snapshots[index].id) }
                        .setNegativeButton("إغلاق", null).show()
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "تعذر قراءة سجل النسخ: ${error.message ?: error.javaClass.simpleName}" }
            }
        }, "QAMZOR-Snapshots").start()
    }

    private fun showSnapshotActions(snapshotId: String) {
        val password = dialogField("كلمة مرور النسخة (اتركها فارغة إذا كانت غير مشفرة)")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(password)
            addView(actionButton("key", "اختيار كلمة مرور محفوظة", false) {
                showSavedPasswordPicker { value -> password.setText(value); password.setSelection(password.text.length) }
            })
            addView(label("إذا كانت النسخة مشفرة، يجب إدخال كلمة المرور الصحيحة لفحص المحتوى أو استعادته. يتم تمرير السر مشفرًا إلى المهمة الخلفية.", 10f, 0xFF9FB4D3.toInt(), false))
        }
        AlertDialog.Builder(this).setTitle("نسخة احتياطية")
            .setView(panel)
            .setPositiveButton("التحقق من سلامة النسخة") { _, _ ->
                try {
                    val id = QamzorBackgroundWork.enqueueSnapshotVerify(this, backupStoreDirectory(), snapshotId, password.text.toString().ifEmpty { null })
                    password.text?.clear()
                    status.text = "أُضيف فحص النسخة إلى العمليات الخلفية — $id"
                    selectTab(3)
                } catch (error: Exception) { status.text = "تعذر إضافة الفحص: ${error.message}" }
            }
            .setNeutralButton("استعادة إلى مجلد منفصل") { _, _ ->
                try {
                    val destination = File(getExternalFilesDir(null) ?: filesDir, "QAMZOR/Restored/$snapshotId")
                    val id = QamzorBackgroundWork.enqueueSnapshotRestore(this, backupStoreDirectory(), snapshotId, destination,
                        password.text.toString().ifEmpty { null })
                    password.text?.clear()
                    status.text = "أُضيفت الاستعادة إلى: ${destination.absolutePath} — $id"
                    selectTab(3)
                } catch (error: Exception) { status.text = "تعذرت إضافة الاستعادة: ${error.message}" }
            }
            .setNegativeButton("إلغاء") { _, _ -> password.text?.clear() }
            .setOnCancelListener { password.text?.clear() }
            .show()
    }

    // ───────────────────────────── Settings: persisted preferences that affect real behavior ─────────────────────────────

    private fun buildSettingsPage(): View {
        val column = pageColumn()
        column.addView(sectionLabel("إعدادات مدير الملفات"))
        column.addView(settingSwitch(
            "show_hidden_files", "إظهار الملفات المخفية", "عرض الملفات والمجلدات التي تبدأ أسماؤها بنقطة.", false
        ) { renderFiles() })
        column.addView(settingSwitch(
            "directories_first", "عرض المجلدات أولًا", "تطبيق هذا الخيار على متصفح الهاتف والأرشيف.", true
        ) { renderFiles(); renderExplorerContents() })
        column.addView(settingSwitch(
            "delete_original_after_seal", "حذف الأصل بعد الأرشفة المتحقق منها", "عند إيقاف الخيار، يحتفظ QAMZOR بالمصدر وينشئ الأرشيف بجانبه.", true
        ) {})
        column.addView(settingSwitch(
            "confirm_destructive_actions", "التأكيد قبل العمليات المؤثرة", "إظهار تأكيد قبل الأرشفة أو الاستعادة التي تحذف ملف الأرشيف.", true
        ) {})
        column.addView(settingSwitch(
            "cleanup_temp_on_start", "تنظيف الملفات المؤقتة القديمة عند بدء التطبيق", "يحذف ملفات QAMZOR الخاصة التي لم تتغير منذ 24 ساعة على الأقل.", true
        ) {})

        column.addView(sectionLabel("سجل العمليات"))
        val history = Spinner(this).apply {
            adapter = sortAdapter(listOf("20 سجلًا", "50 سجلًا", "100 سجل", "200 سجل", "500 سجل"))
            background = rounded(0xFF1D3152.toInt(), 10)
            val stored = settingsPreferences().getInt("operation_history_limit", 100)
            val values = listOf(20, 50, 100, 200, 500)
            setSelection(values.indexOf(stored).takeIf { it >= 0 } ?: 2)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    settingsPreferences().edit().putInt("operation_history_limit", values[position]).apply()
                    synchronized(operationHistory) {
                        while (operationHistory.size > values[position]) operationHistory.removeAt(0)
                        operationPreferences().edit().putString("events", operationHistory.joinToString("\n")).apply()
                    }
                    refreshOperationLog()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        column.addView(history, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })

        column.addView(sectionLabel("الصيانة والخصوصية"))
        column.addView(toolRow("trash", "تنظيف الملفات المؤقتة القديمة الآن", "حذف ملفات QAMZOR الخاصة التي مر على آخر تعديل لها 24 ساعة على الأقل") {
            AlertDialog.Builder(this)
                .setTitle("تنظيف الملفات المؤقتة")
                .setMessage("سيتم حذف ملفات QAMZOR المؤقتة التي لم تتغير منذ 24 ساعة على الأقل. لن يتم حذف الملفات الحديثة أو الأرشيف المفتوح أو أي ملف من مجلداتك الأصلية.")
                .setPositiveButton("تنظيف") { _, _ ->
                    if (backgroundBusy) {
                        status.text = "لا يمكن تنظيف الملفات المؤقتة أثناء وجود عملية نشطة."
                    } else {
                        runOnWorker("تعذر تنظيف الملفات المؤقتة") {
                            val removed = cleanTemporaryCache()
                            runOnUiThread {
                                status.text = if (removed == 0) "لا توجد ملفات مؤقتة قديمة تحتاج إلى التنظيف." else "اكتمل التنظيف: تمت إزالة ${removed} عنصر مؤقت قديم."
                            }
                        }
                    }
                }
                .setNegativeButton("إلغاء", null).show()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        column.addView(toolRow("refresh", "إعادة ضبط إعدادات العرض", "إرجاع خيارات العرض إلى القيم الافتراضية دون المساس بالملفات") {
            AlertDialog.Builder(this)
                .setTitle("إعادة ضبط الإعدادات")
                .setMessage("سيتم إعادة إعدادات التطبيق فقط. لن تُحذف الأرشيفات أو الملفات أو سجلات العمليات.")
                .setPositiveButton("إعادة الضبط") { _, _ ->
                    settingsPreferences().edit().clear().apply()
                    filesPreferences().edit().clear().apply()
                    archivePreferences().edit().clear().apply()
                    filesSearchQuery = ""; archiveSearchQuery = ""
                    filesSortMode = 0; archiveSortMode = 0; filesLimit = FILES_PAGE_SIZE
                    if (::filesSearchInput.isInitialized) filesSearchInput.setText("")
                    if (::archiveSearchInput.isInitialized) archiveSearchInput.setText("")
                    if (::filesSortSpinner.isInitialized) filesSortSpinner.setSelection(0)
                    if (::archiveSortSpinner.isInitialized) archiveSortSpinner.setSelection(0)
                    renderFiles(); renderExplorerContents()
                    replacePage(4, buildSettingsPage())
                    status.text = "تمت إعادة ضبط الإعدادات وعرض القيم الافتراضية."
                }
                .setNegativeButton("إلغاء", null).show()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })

        column.addView(sectionLabel("النسخ الاحتياطي وسجل الإصدارات"))
        column.addView(toolRow("backup", "إنشاء نسخة احتياطية الآن", "نسخة QZRA منفصلة تعمل في الخلفية مع التحقق من SHA-256") {
            createBackupNow()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        column.addView(toolRow("calendar", "جدولة نسخ احتياطي تلقائي", "يومي أو كل 3 أيام أو أسبوعي • يعمل بعد إغلاق التطبيق وإعادة تشغيل الهاتف") {
            scheduleAutomaticBackup()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        column.addView(toolRow("close", "إيقاف كل جداول النسخ التلقائي", "إلغاء المهام الدورية وحذف مراجع كلمات مرور النسخ المشفرة من مخزن الأسرار") {
            AlertDialog.Builder(this).setTitle("إيقاف النسخ التلقائي")
                .setMessage("سيتم إلغاء جميع جداول النسخ الدورية لهذا التطبيق. لن تُحذف النسخ الاحتياطية التي أُنشئت بالفعل.")
                .setPositiveButton("إيقاف الجداول") { _, _ ->
                    QamzorBackgroundWork.cancelAutomaticSnapshots(this)
                    status.text = "تم طلب إلغاء جداول النسخ التلقائي. النسخ المحفوظة لم تتأثر."
                }.setNegativeButton("رجوع", null).show()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        column.addView(label("عدد النسخ المحفوظة قبل حذف الإصدارات الأقدم", 11f, 0xFF9FB4D3.toInt(), false))
        val retention = Spinner(this).apply {
            adapter = sortAdapter(listOf("10 نسخ", "20 نسخة", "30 نسخة", "50 نسخة", "100 نسخة"))
            val values = listOf(10, 20, 30, 50, 100)
            setSelection(values.indexOf(settingsPreferences().getInt("backup_retention", 30)).takeIf { it >= 0 } ?: 2)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    settingsPreferences().edit().putInt("backup_retention", values[position]).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        column.addView(retention, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        column.addView(toolRow("history", "سجل الإصدارات والاستعادة", "استعراض النسخ المحفوظة وفحصها أو استعادة إصدار سابق إلى مجلد منفصل") {
            showSnapshotHistory()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })

        column.addView(sectionLabel("إدارة كلمات المرور والمفاتيح"))
        column.addView(toolRow("key", "خزنة كلمات مرور الأرشيف", "حفظ كلمات مرور الأرشيف والمجلدات المحمية بشكل مشفر عبر Android Keystore، مع اختيارها عند الفتح والاستعادة") {
            managePasswordVault()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })

        column.addView(sectionLabel("الأمان ومفاتيح التوقيع"))
        column.addView(toolRow("key", "إدارة مفاتيح التوقيع الرقمي", "عرض بصمة Ed25519 ونسخها أو تدوير المفتاح المحلي المحمي بـ Android Keystore") {
            showSigningKeyManager()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })

        column.addView(sectionLabel("التخزين السحابي والحسابات"))
        column.addView(toolRow("cloud", "إدارة حسابات WebDAV", "إضافة اتصال HTTPS آمن، اختباره، رفع الأرشيف والتحقق من بصمته بعد الرفع") {
            showCloudAccounts()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        column.addView(label("Android يدعم WebDAV عبر HTTPS: اختبار الحساب، رفع الأرشيف، استعراض الأرشيفات المنشورة، التنزيل مع SHA-256، ومزامنة ثنائية الاتجاه لأرشيفات QZRA دون حذف أو استبدال عند التعارض. موصلات Drive وDropbox وOneDrive وS3 موجودة في Python، لكنها ليست بعد تسجيل دخول OAuth أصليًا في Android.", 10f, 0xFF9FB4D3.toInt(), false))

        column.addView(sectionLabel("وحدات USB وبطاقات SD"))
        column.addView(toolRow("usb", "ربط وحدة USB أو بطاقة SD", "اختيار مجلد التخزين عبر Android Storage Access Framework وحفظ الإذن") {
            chooseExternalStorageTree()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        column.addView(toolRow("folder", "فتح وحدة التخزين المرتبطة", "استعراض الملفات والأرشيفات من وحدة التخزين التي منحت التطبيق إذن الوصول إليها") {
            openLinkedExternalStorage()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        column.addView(toolRow("upload", "إلغاء ربط وحدة التخزين", "إزالة إذن الوصول المحفوظ دون حذف أي ملفات على الوحدة") {
            unlinkExternalStorage()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })

        column.addView(sectionLabel("حول QAMZOR"))
        column.addView(toolRow("info", "معلومات التطبيق والتوافق", "الإصدار المثبت، صيغة QZRA، وحدود دعم المنصات") {
            AlertDialog.Builder(this)
                .setTitle("QAMZOR 0.9.0")
                .setMessage("التطبيق: QAMZOR 0.9.0\nصيغة الأرشيف الأصلية: QZRA 1.0–1.3\nالتشفير: AES-256-GCM مع اشتقاق مفاتيح scrypt\nالتحقق: SHA-256 على بيانات الملفات\nالأجزاء: ملف .qzset مع التحقق من بصمات الأجزاء والأرشيف الكامل\n\nملاحظة التوافق: لا تعرض هذه النسخة دعم ZIP أو 7z أو RAR أو TAR في متصفح Android الحالي. وجود موصلات سحابية أو أدوات إضافية في Python لا يعني أنها مرتبطة بواجهة Android.")
                .setPositiveButton("حسنًا", null).show()
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(7) })
        return pageScroll(column)
    }

    private fun settingSwitch(key: String, title: String, summary: String, defaultValue: Boolean, changed: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xFF1D3152.toInt(), 12)
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(label(title, 13f, 0xFFF4F7FF.toInt(), true))
        textColumn.addView(label(summary, 10.5f, 0xFF9FB4D3.toInt(), false))
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = settingsPreferences().getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, checked ->
                settingsPreferences().edit().putBoolean(key, checked).apply()
                changed(checked)
            }
            contentDescription = title
        }
        row.addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        return row.apply { setPaddingRelative(dp(12), dp(8), dp(12), dp(8)) }
    }

    private fun cleanTemporaryCache(): Int {
        if (hasActiveBackgroundUseOfCache()) {
            throw IllegalStateException("هناك مهمة خلفية تستخدم ملفات مؤقتة؛ أوقف التنظيف حتى تنتهي المهمة.")
        }
        val activePath = openedArchive?.let { try { it.canonicalPath } catch (_: Exception) { null } }
        var removed = 0
        val cutoff = System.currentTimeMillis() - TEMP_CACHE_RETENTION_MS
        cacheDir.listFiles()?.filter(::isQamzorTemporary)?.forEach { file ->
            val canonical = try { file.canonicalPath } catch (_: Exception) { "" }
            val containsOpenArchive = activePath != null && (canonical == activePath || activePath.startsWith(canonical + File.separator))
            if (!containsOpenArchive && file.exists() && newestModified(file) <= cutoff && file.deleteRecursively()) removed++
        }
        return removed
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

    // ───────────────────────────── Phone files: browse, select, archive / encrypt in place ─────────────────────────────

    override fun onResume() {
        super.onResume()
        // Also covers returning from the "All files access" settings screen.
        renderFiles()
    }

    override fun onDestroy() {
        fileRenderGeneration++
        fileListingExecutor.shutdownNow()
        openedFolderPasswords.clear()
        openedArchivePassword = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderFiles()
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }

    private fun storageRoots(): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>()
        result.add("الذاكرة الداخلية" to Environment.getExternalStorageDirectory())
        try {
            for (dir in getExternalFilesDirs(null)) {
                if (dir == null) continue
                val path = dir.absolutePath
                val index = path.indexOf("/Android/")
                if (index > 0) {
                    val root = File(path.substring(0, index))
                    if (result.none { it.second.absolutePath == root.absolutePath }) result.add("بطاقة SD" to root)
                }
            }
        } catch (_: Exception) { /* removable storage is optional */ }
        return result
    }

    private fun isStorageRoot(file: File): Boolean = storageRoots().any { it.second.absolutePath == file.absolutePath }

    private fun isProtectedLocation(file: File): Boolean {
        val path = file.absolutePath
        if (isStorageRoot(file)) return true
        return storageRoots().any { path == it.second.absolutePath + "/Android" }
    }

    private fun openDirectory(dir: File) {
        if (!dir.isDirectory) return
        currentDir = dir
        selectedPaths.clear()
        filesLimit = FILES_PAGE_SIZE
        renderFiles()
        filesScroll.scrollTo(0, 0)
    }

    private fun goUp(): Boolean {
        val parent = currentDir.absoluteFile.parentFile
        if (parent == null || isStorageRoot(currentDir)) return false
        openDirectory(parent)
        return true
    }

    private fun listDirectory(dir: File): List<File> {
        val includeHidden = settingsPreferences().getBoolean("show_hidden_files", false)
        val children = dir.listFiles() ?: return emptyList()
        return children.filter {
            val name = it.name
            !(name.startsWith(".qamzor-") || name.startsWith("qamzor-payload-") ||
                (name.startsWith(".") && name.endsWith(".tmp") && name.contains(".qzra."))) &&
                (includeHidden || !name.startsWith("."))
        }
    }

    private fun sortPhoneFiles(entries: List<File>, sortMode: Int): List<File> {
        val directoriesFirst = if (settingsPreferences().getBoolean("directories_first", true))
            compareBy<File> { if (it.isDirectory) 0 else 1 }
        else compareBy<File> { 0 }
        return when (sortMode) {
            1 -> entries.sortedWith(directoriesFirst.thenByDescending { it.lastModified() }.thenBy { it.name.lowercase(Locale.ROOT) })
            2 -> entries.sortedWith(directoriesFirst.thenBy { it.lastModified() }.thenBy { it.name.lowercase(Locale.ROOT) })
            3 -> entries.sortedWith(directoriesFirst.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
            4 -> entries.sortedWith(directoriesFirst.thenByDescending { it.name.lowercase(Locale.ROOT) }.thenByDescending { it.name })
            5 -> entries.sortedWith(directoriesFirst.thenByDescending { if (it.isFile) it.length() else -1L }.thenBy { it.name.lowercase(Locale.ROOT) })
            6 -> entries.sortedWith(directoriesFirst.thenBy { if (it.isFile) it.length() else Long.MAX_VALUE }.thenBy { it.name.lowercase(Locale.ROOT) })
            7 -> entries.sortedWith(directoriesFirst.thenBy { it.extension.lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) })
            else -> entries.sortedWith(directoriesFirst.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
        }
    }

    private fun renderFiles() {
        if (!::filesList.isInitialized) return
        val allowed = hasStorageAccess()
        filesPermissionCard.visibility = if (allowed) View.GONE else View.VISIBLE
        filesRootsRow.removeAllViews()
        for ((title, root) in storageRoots()) {
            val chip = label(title, 12f, 0xFFF4F7FF.toInt(), true).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = rounded(0xFF263D63.toInt(), 10)
                isClickable = true
                setOnClickListener { openDirectory(root) }
            }
            filesRootsRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        filesPathText.text = currentDir.absolutePath
        refreshFileSelectionBar()
        filesList.removeAllViews()
        if (!allowed) {
            filesList.addView(label("امنح الصلاحية ليظهر محتوى الهاتف هنا.", 12f, 0xFF9FB4D3.toInt(), false)
                .apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
            return
        }
        val query = filesSearchQuery.lowercase(Locale.ROOT)
        val directory = currentDir
        val sortMode = filesSortMode
        val limit = filesLimit
        val generation = ++fileRenderGeneration
        filesList.addView(label("جارٍ قراءة محتويات المجلد…", 12f, 0xFF9FB4D3.toInt(), false)
            .apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
        fileListingExecutor.execute {
            val sorted = try {
                sortPhoneFiles(listDirectory(directory).filter { file ->
                    query.isEmpty() || file.name.lowercase(Locale.ROOT).contains(query) ||
                        file.extension.lowercase(Locale.ROOT).contains(query)
                }, sortMode)
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation == fileRenderGeneration) {
                        filesList.removeAllViews()
                        filesList.addView(label("تعذرت قراءة المجلد: ${error.message ?: "خطأ غير معروف"}", 12f, 0xFFFFB4A9.toInt(), false)
                            .apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
                    }
                }
                return@execute
            }
            runOnUiThread {
                if (generation != fileRenderGeneration || directory.absolutePath != currentDir.absolutePath) return@runOnUiThread
                filesList.removeAllViews()
                if (sorted.isEmpty()) {
                    val message = if (query.isNotEmpty()) "لا توجد ملفات أو مجلدات تطابق «$filesSearchQuery» في هذا المجلد." else "المجلد فارغ أو لا يمكن قراءته."
                    filesList.addView(label(message, 12f, 0xFF9FB4D3.toInt(), false)
                        .apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
                    return@runOnUiThread
                }
                for (entry in sorted.take(limit)) {
                    filesList.addView(fileRow(entry), LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) })
                }
                if (sorted.size > limit) {
                    val more = label("عرض المزيد (${sorted.size - limit} عنصر متبقٍ)", 12.5f, 0xFFBBD2FF.toInt(), true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(0xFF182845.toInt(), 12)
                        isClickable = true
                        setOnClickListener { filesLimit += FILES_PAGE_SIZE; renderFiles() }
                    }
                    filesList.addView(more, LinearLayout.LayoutParams(-1, dp(44)))
                }
            }
        }
    }

    private fun fileRow(file: File): View {
        val isDirectory = file.isDirectory
        val isArchive = InPlaceVault.isQzra(file)
        val selected = selectedPaths.contains(file.absolutePath)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(10), dp(4), dp(10), dp(4))
            background = rounded(if (selected) 0xFF2F4F86.toInt() else 0xFF1D3152.toInt(), 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onFileTapped(file) }
            setOnLongClickListener { toggleFileSelection(file); true }
        }
        if (isArchive) {
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_qamzor)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        } else {
            row.addView(QamzorLineIconView(this, if (isDirectory) "folder" else "file", 0xFFBBD2FF.toInt()),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        }
        val subtitle = when {
            isDirectory -> "مجلد • ${formatModified(file.lastModified())}"
            isArchive -> (if (InPlaceVault.isEncrypted(file)) "أرشيف QZRA مشفّر بكلمة مرور" else "أرشيف QZRA") + " • " + formatBytes(file.length()) + " • " + formatModified(file.lastModified())
            file.extension.equals("qzset", ignoreCase = true) -> "ملف تجميع حِزم QZRA • ${formatBytes(file.length())} • ${formatModified(file.lastModified())}"
            else -> formatBytes(file.length()) + " • " + formatModified(file.lastModified())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(file.name, 13f, 0xFFF4F7FF.toInt(), true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setSingleLine(true)
        })
        texts.addView(label(subtitle, 10.5f, 0xFF9FB4D3.toInt(), false).apply { maxLines = 1 })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (isArchive && InPlaceVault.isEncrypted(file)) {
            row.addView(QamzorLineIconView(this, "lock", 0xFFFFD27A.toInt()),
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) })
        }
        if (selected) row.addView(label("✓", 17f, 0xFF68D391.toInt(), true).apply { setPadding(dp(8), 0, dp(4), 0) })
        return row
    }

    private fun onFileTapped(file: File) {
        when {
            selectedPaths.isNotEmpty() -> toggleFileSelection(file)
            file.isDirectory -> openDirectory(file)
            InPlaceVault.isQzra(file) -> showArchiveActions(file)
            file.extension.equals("qzset", ignoreCase = true) -> showSplitSetActions(file)
            else -> toggleFileSelection(file)
        }
    }

    private fun toggleFileSelection(file: File) {
        val key = file.absolutePath
        if (!selectedPaths.remove(key)) selectedPaths.add(key)
        renderFiles()
    }

    private fun clearFileSelection() {
        selectedPaths.clear()
        renderFiles()
    }

    private fun refreshFileSelectionBar() {
        filesSelectionBar.visibility = if (selectedPaths.isEmpty()) View.GONE else View.VISIBLE
        val deleteOriginal = settingsPreferences().getBoolean("delete_original_after_seal", true)
        filesSelectionLabel.text = "المحدد: ${selectedPaths.size} عنصر — " +
            if (deleteOriginal) "سيُحذف الأصل بعد التحقق" else "سيُحتفظ بالأصل"
    }

    private fun selectedFiles(): List<File> = selectedPaths.map { File(it) }.filter { it.exists() }

    private data class ProtectedFolderCandidate(val source: File, val archivePath: String) {
        val label: String get() = archivePath
    }

    private fun confirmSeal(encrypt: Boolean) {
        val items = selectedFiles()
        if (items.isEmpty()) return
        if (backgroundBusy) {
            status.text = "هناك عملية قيد التنفيذ. انتظر حتى تنتهي."
            return
        }
        val blocked = items.firstOrNull { isProtectedLocation(it) }
        if (blocked != null) {
            status.text = "لا يمكن أرشفة «${blocked.name}»: جذر التخزين أو مجلد النظام Android."
            return
        }
        val confirm = settingsPreferences().getBoolean("confirm_destructive_actions", true)
        val deleteOriginal = settingsPreferences().getBoolean("delete_original_after_seal", true)
        if (encrypt) {
            if (confirm) {
                AlertDialog.Builder(this)
                    .setTitle("تأكيد التشفير")
                    .setMessage(if (deleteOriginal) "سيُنشأ أرشيف مشفّر ويُحذف الأصل بعد نجاح التحقق الكامل. يمكنك تعطيل حذف الأصل من الإعدادات." else "سيُنشأ أرشيف مشفّر مع الاحتفاظ بالأصل.")
                    .setPositiveButton("متابعة") { _, _ -> promptSealPassword(items) }
                    .setNegativeButton("إلغاء", null).show()
            } else promptSealPassword(items)
        } else {
            val start = { startSeal(items, null) }
            if (!confirm) start()
            else AlertDialog.Builder(this)
                .setTitle("أرشفة ${items.size} عنصر")
                .setMessage((if (deleteOriginal) "سيُنشأ ملف .qzra بجانب كل عنصر ثم يُحذف الأصل بعد التحقق الكامل." else "سيُنشأ ملف .qzra بجانب كل عنصر مع الاحتفاظ بالأصل.") + "\n\nتنبيه: بدون كلمة مرور الأرشيف غير مشفّر. استخدم «تشفير» لحماية حقيقية.")
                .setPositiveButton("أرشفة") { _, _ -> start() }
                .setNegativeButton("إلغاء", null).show()
        }
    }

    private fun promptSealPassword(items: List<File>) {
        val first = dialogField("كلمة المرور ($MIN_PASSWORD_LENGTH أحرف على الأقل)")
        val second = dialogField("تأكيد كلمة المرور")
        val saveMaster = CheckBox(this).apply {
            text = "حفظ كلمة المرور الرئيسية في الخزنة المشفرة"
            setTextColor(0xFFDCE7F8.toInt())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(first)
            addView(actionButton("key", "اختيار كلمة مرور محفوظة", false) {
                showSavedPasswordPicker { value ->
                    first.setText(value); second.setText(value)
                    first.setSelection(first.text.length); second.setSelection(second.text.length)
                }
            })
            addView(second)
            addView(saveMaster)
        }
        AlertDialog.Builder(this)
            .setTitle("تشفير ${items.size} عنصر")
            .setMessage("سيُنشأ أرشيف QZRA 1.3 بتشفير AES-256. يمكنك تعيين كلمات مرور مستقلة لما يصل إلى 32 مجلدًا داخليًا.")
            .setView(panel)
            .setPositiveButton("متابعة") { _, _ ->
                val value = first.text.toString()
                val confirmation = second.text.toString()
                first.text?.clear(); second.text?.clear()
                when {
                    value.length < MIN_PASSWORD_LENGTH -> { status.text = "كلمة المرور قصيرة؛ استخدم $MIN_PASSWORD_LENGTH أحرف على الأقل."; promptSealPassword(items) }
                    value != confirmation -> { status.text = "كلمتا المرور غير متطابقتين."; promptSealPassword(items) }
                    else -> {
                        if (saveMaster.isChecked) runCatching { QamzorPasswordVault.save(this, "كلمة مرور QZRA رئيسية", value) }
                            .onFailure { recordOperationEvent("تعذر حفظ كلمة المرور الرئيسية: ${it.message}") }
                        promptProtectedFolderSelection(items, value)
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun protectedFolderCandidates(items: List<File>): List<ProtectedFolderCandidate> {
        val result = ArrayList<ProtectedFolderCandidate>(32)
        val seen = HashSet<String>()
        for (source in items.filter { it.isDirectory }) {
            if (result.size >= 32) break
            val base = source.name
            val baseKey = source.absolutePath + "|" + base
            if (seen.add(baseKey)) result += ProtectedFolderCandidate(source, base)
            for (folder in source.walkTopDown().drop(1)) {
                if (result.size >= 32) break
                if (!folder.isDirectory || java.nio.file.Files.isSymbolicLink(folder.toPath())) continue
                val relative = source.toPath().relativize(folder.toPath()).toString().replace(File.separatorChar, '/')
                val archivePath = "$base/$relative"
                if (seen.add(source.absolutePath + "|" + archivePath)) result += ProtectedFolderCandidate(source, archivePath)
            }
        }
        return result
    }

    private fun promptProtectedFolderSelection(items: List<File>, masterPassword: String) {
        val candidates = protectedFolderCandidates(items)
        if (candidates.isEmpty()) {
            startSeal(items, masterPassword)
            return
        }
        val labels = candidates.map { it.label }.toTypedArray()
        val selected = BooleanArray(candidates.size)
        AlertDialog.Builder(this)
            .setTitle("مجلدات بكلمات مرور مستقلة")
            .setMessage("اختياري: حدد المجلدات التي تريد حمايتها بكلمات مرور منفصلة عن كلمة مرور الأرشيف. الحد الأقصى 32 مجلدًا.")
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("تعيين كلمات المرور") { _, _ ->
                val chosen = candidates.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) startSeal(items, masterPassword)
                else promptNextSealFolderPassword(items, masterPassword, chosen, 0, LinkedHashMap())
            }
            .setNegativeButton("تخطي") { _, _ -> startSeal(items, masterPassword) }
            .show()
    }

    private fun promptNextSealFolderPassword(
        items: List<File>, masterPassword: String, chosen: List<ProtectedFolderCandidate>, index: Int,
        result: MutableMap<String, MutableMap<String, String>>
    ) {
        if (index >= chosen.size) {
            startSeal(items, masterPassword, result.mapValues { it.value.toMap() })
            return
        }
        val candidate = chosen[index]
        val field = dialogField("كلمة مرور مستقلة لـ ${candidate.archivePath}")
        val savePassword = CheckBox(this).apply {
            text = "حفظ كلمة المرور في الخزنة المشفرة لهذا الجهاز"
            setTextColor(0xFFDCE7F8.toInt())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(field)
            addView(actionButton("key", "اختيار كلمة مرور محفوظة", false) {
                showSavedPasswordPicker { value -> field.setText(value); field.setSelection(field.text.length) }
            })
            addView(savePassword)
        }
        AlertDialog.Builder(this)
            .setTitle("حماية مجلد ${index + 1}/${chosen.size}")
            .setMessage("كلمة المرور هذه مستقلة عن كلمة مرور الأرشيف. احفظها في مكان آمن؛ لن تتمكن من فتح النطاق بدونها.")
            .setView(panel)
            .setPositiveButton("التالي") { _, _ ->
                val value = field.text.toString()
                field.text?.clear()
                if (value.isEmpty()) {
                    status.text = "لا يمكن أن تكون كلمة مرور المجلد فارغة."
                    promptNextSealFolderPassword(items, masterPassword, chosen, index, result)
                } else {
                    result.getOrPut(candidate.source.absolutePath) { LinkedHashMap() }[candidate.archivePath] = value
                    if (savePassword.isChecked) runCatching { QamzorPasswordVault.save(this, "مجلد: ${candidate.archivePath.take(100)}", value) }
                        .onFailure { recordOperationEvent("تعذر حفظ كلمة مرور المجلد: ${it.message}") }
                    promptNextSealFolderPassword(items, masterPassword, chosen, index + 1, result)
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun startSeal(
        items: List<File>, password: String?, folderPasswordsBySource: Map<String, Map<String, String>> = emptyMap()
    ) {
        val deleteOriginal = settingsPreferences().getBoolean("delete_original_after_seal", true)
        try {
            val workId = QamzorBackgroundWork.enqueueSeal(this, items, password, folderPasswordsBySource, deleteOriginal)
            recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] أُضيفت مهمة أرشفة خلفية — $workId")
            status.text = "تمت إضافة الأرشفة إلى مركز العمليات. ستستمر في الخلفية بعد مغادرة الشاشة."
            selectTab(3)
            showBackgroundJobs()
        } catch (error: Exception) {
            status.text = "تعذر إنشاء مهمة الأرشفة: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    /** Ask Android's media index to forget removed files / see new ones (Gallery, Music). Best effort. */
    private fun scanMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            android.media.MediaScannerConnection.scanFile(applicationContext, paths.toTypedArray(), null, null)
        } catch (_: Exception) { /* best effort */ }
    }

    private fun pathsUnder(root: File): List<String> =
        if (root.isFile) listOf(root.absolutePath)
        else root.walkTopDown().filter { it.isFile }.take(3000).map { it.absolutePath }.toList()

    private fun showArchiveActions(file: File) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(arrayOf("فتح وتصفح داخل QAMZOR", "استعادة الأصل (فك الأرشفة)", "فحص السلامة", "تقسيم الأرشيف إلى حِزم")) { _, which ->
                when (which) {
                    0 -> openArchiveFile(file)
                    1 -> confirmRestore(file)
                    2 -> verifyArchivePath(file)
                    else -> promptSplitArchive(file)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun promptSplitArchive(file: File) {
        val sizeField = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("100")
            hint = "حجم الجزء بالميجابايت"
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(label("قسّم الأرشيف إلى ملفات أجزاء مستقلة. الحد الأدنى 1 ميجابايت، وسيُنشأ ملف .qzset لفحص الأجزاء وإعادة تجميعها.", 12f, 0xFF53627A.toInt(), false))
            addView(sizeField, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("تقسيم أرشيف QZRA إلى حِزم")
            .setView(panel)
            .setPositiveButton("بدء التقسيم") { _, _ ->
                val mib = sizeField.text.toString().toLongOrNull()
                if (mib == null || mib !in 1L..2048L) {
                    status.text = "أدخل حجمًا صحيحًا من 1 إلى 2048 ميجابايت."
                    return@setPositiveButton
                }
                runOnWorker("فشل تقسيم الأرشيف إلى حِزم") {
                    val result = ArchiveVolumes.split(file, file.parentFile ?: error("لا يوجد مجلد للأرشيف"), mib * 1024L * 1024L)
                    recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] اكتملت قسمة ${file.name} إلى ${result.partCount} حزمة مع التحقق من SHA-256")
                    runOnUiThread {
                        status.text = "اكتمل تقسيم الأرشيف ✓ • ${result.partCount} حزمة • ${formatBytes(result.archiveSize)}"
                        AlertDialog.Builder(this)
                            .setTitle("اكتمل تقسيم الأرشيف")
                            .setMessage("ملف التجميع: ${result.setFile.name}\nمجلد الأجزاء: ${result.partsDirectory.name}\nعدد الأجزاء: ${result.partCount}\nالحجم الأصلي: ${formatBytes(result.archiveSize)}\nSHA-256: ${result.archiveSha256}")
                            .setPositiveButton("حسنًا", null)
                            .show()
                        renderFiles()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSplitSetActions(setFile: File) {
        val expectedName = setFile.name.removeSuffix(".qzset").let { if (it.endsWith(".qzra", true)) it else "$it.qzra" }
        val parent = setFile.parentFile ?: return
        val destination = File(parent, uniqueName(parent, expectedName))
        AlertDialog.Builder(this)
            .setTitle("إعادة تجميع حِزم QZRA")
            .setMessage("سيتم قراءة ملف الوصف ${setFile.name}، وفحص ترتيب كل جزء وحجمه وبصمة SHA-256، ثم التحقق من بصمة الأرشيف الكامل.\n\nالملف الناتج: ${destination.name}\nلن تُحذف الحِزم الأصلية.")
            .setPositiveButton("إعادة التجميع والتحقق") { _, _ ->
                runOnWorker("فشل إعادة تجميع الحِزم") {
                    val result = ArchiveVolumes.join(setFile, destination)
                    recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] اكتملت إعادة تجميع ${setFile.name} والتحقق من ${result.partCount} حزمة")
                    runOnUiThread {
                        status.text = "اكتملت إعادة التجميع والتحقق ✓ • ${result.archive.name}"
                        AlertDialog.Builder(this)
                            .setTitle("تمت إعادة التجميع والتحقق ✓")
                            .setMessage("الملف: ${result.archive.name}\nالأجزاء التي تم فحصها: ${result.partCount}\nالحجم: ${formatBytes(result.archiveSize)}\nSHA-256: ${result.archiveSha256}\n\nتم الاحتفاظ بملفات الأجزاء الأصلية.")
                            .setPositiveButton("حسنًا", null)
                            .show()
                        renderFiles()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
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
                "settings" -> {
                    canvas.drawCircle(12f, 12f, 3.2f, stroke)
                    canvas.drawCircle(12f, 12f, 8f, stroke)
                    for (angle in 0..7) {
                        val radians = Math.toRadians(angle * 45.0)
                        val x1 = 12f + (8f * kotlin.math.cos(radians)).toFloat()
                        val y1 = 12f + (8f * kotlin.math.sin(radians)).toFloat()
                        val x2 = 12f + (10f * kotlin.math.cos(radians)).toFloat()
                        val y2 = 12f + (10f * kotlin.math.sin(radians)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, stroke)
                    }
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
                REQUEST_BROWSE -> browseSelectedArchive(data.data)
                REQUEST_VERIFY -> verifySelectedArchive(data.data)
                REQUEST_COMPARE -> compareSelectedArchives(data)
                REQUEST_EXTRACT -> {
                    val selected = data.data ?: error("لم يتم اختيار أرشيف")
                    pendingExtractionPaths = null
                    status.text = "جارٍ قراءة الأرشيف المحدد..."
                    val name = displayName(selected) ?: "archive.qzra"
                    runOnWorker("تعذرت قراءة الأرشيف") {
                        val session = File(cacheDir, "qamzor-extract-open-${System.nanoTime()}").apply { mkdirs() }
                        val archive = File(session, safeName(name))
                        try {
                            copyUriTo(selected, archive)
                            try {
                                ArchiveEngine.browse(archive)
                                installOpenedArchive(archive, name, null, emptySet())
                                runOnUiThread { requestSelectedExtraction(null) }
                            } catch (error: Exception) {
                                if (needsArchivePassword(error)) {
                                    runOnUiThread { promptArchivePassword(archive, name) { requestSelectedExtraction(null) } }
                                } else throw error
                            }
                        } catch (error: Exception) {
                            session.deleteRecursively()
                            throw error
                        }
                    }
                }
                REQUEST_OUTPUT_TREE -> {
                    data.data?.let { takeTreePermission(it, data) }
                    extractToTree(data.data)
                }
                REQUEST_STORAGE_TREE -> {
                    val uri = data.data ?: error("لم يتم اختيار وحدة التخزين")
                    takeTreePermission(uri, data)
                    settingsPreferences().edit().putString("external_storage_tree_uri", uri.toString()).apply()
                    status.text = "تم ربط وحدة التخزين وحفظ إذن الوصول."
                    openLinkedExternalStorage()
                }
                REQUEST_EXPORT_TREE -> {
                    val uri = data.data ?: error("لم يتم اختيار مجلد الوجهة")
                    takeTreePermission(uri, data)
                    exportOpenedArchiveToTree(uri)
                }
                REQUEST_PUBLIC_KEY -> {
                    val uri = data.data ?: error("لم يتم اختيار ملف المفتاح العام")
                    val archive = pendingSignatureArchive ?: error("لا يوجد أرشيف بانتظار التحقق")
                    pendingSignatureArchive = null
                    val keyFile = copyUriToCache(uri, "qamzor-public-${System.nanoTime()}.pem")
                    verifySignatureWithPublicKey(archive, keyFile)
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
        require(uris.size == 2) { "اختَر أرشيفين بالضبط للمقارنة." }
        val session = File(cacheDir, "qamzor-compare-${System.nanoTime()}").apply { mkdirs() }
        status.text = "جارٍ تجهيز الأرشيفين للمقارنة والتحقق..."
        runOnWorker("فشلت مقارنة الأرشيفين") {
            try {
                val left = copyUriTo(uris[0], File(session, "left.qzra"))
                val right = copyUriTo(uris[1], File(session, "right.qzra"))
                try {
                    val result = ArchiveEngine.compareContent(left, right)
                    session.deleteRecursively()
                    showCompareResult(result)
                } catch (error: Exception) {
                    if (needsArchivePassword(error) || lockedFolderPath(error.message.orEmpty()) != null) {
                        runOnUiThread { promptComparePasswords(left, right, session) }
                    } else throw error
                }
            } catch (error: Exception) {
                if (error !is IllegalArgumentException || (!needsArchivePassword(error) && lockedFolderPath(error.message.orEmpty()) == null)) {
                    session.deleteRecursively()
                }
                throw error
            }
        }
    }

    private fun promptComparePasswords(left: File, right: File, session: File) {
        val first = dialogField("كلمة مرور ${left.name}")
        val second = dialogField("كلمة مرور ${right.name}")
        val saveFirst = CheckBox(this).apply { text = "حفظ الأولى في الخزنة المشفرة"; setTextColor(0xFFDCE7F8.toInt()) }
        val saveSecond = CheckBox(this).apply { text = "حفظ الثانية في الخزنة المشفرة"; setTextColor(0xFFDCE7F8.toInt()) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(first)
            addView(actionButton("key", "اختيار كلمة محفوظة للأرشيف الأول", false) {
                showSavedPasswordPicker { value -> first.setText(value); first.setSelection(first.text.length) }
            })
            addView(saveFirst)
            addView(second)
            addView(actionButton("key", "اختيار كلمة محفوظة للأرشيف الثاني", false) {
                showSavedPasswordPicker { value -> second.setText(value); second.setSelection(second.text.length) }
            })
            addView(saveSecond)
        }
        AlertDialog.Builder(this)
            .setTitle("مقارنة أرشيفات محمية")
            .setMessage("أدخل كلمة مرور كل أرشيف بصورة مستقلة. يمكن ترك الحقل فارغًا للأرشيف غير المشفر.")
            .setView(panel)
            .setPositiveButton("متابعة") { _, _ ->
                val leftPassword = first.text.toString().ifEmpty { null }
                val rightPassword = second.text.toString().ifEmpty { null }
                val keepLeft = saveFirst.isChecked
                val keepRight = saveSecond.isChecked
                first.text?.clear(); second.text?.clear()
                prepareCompareFolderPasswords(left, right, leftPassword, rightPassword, session, keepLeft, keepRight)
            }
            .setNegativeButton("إلغاء") { _, _ -> session.deleteRecursively() }
            .setOnCancelListener { session.deleteRecursively() }
            .show()
    }

    private fun prepareCompareFolderPasswords(
        left: File, right: File, leftPassword: String?, rightPassword: String?, session: File,
        saveLeftPassword: Boolean = false, saveRightPassword: Boolean = false
    ) {
        runOnWorker("تعذر قراءة إعدادات حماية الأرشيفين") {
            try {
                val leftScopes = ArchiveEngine.protectedFolders(left, leftPassword)
                val rightScopes = ArchiveEngine.protectedFolders(right, rightPassword)
                runOnUiThread {
                    promptScopedFolderPasswords(left, leftPassword, leftScopes, 0, LinkedHashMap(),
                        onComplete = { leftFolders ->
                            promptScopedFolderPasswords(right, rightPassword, rightScopes, 0, LinkedHashMap(),
                                onComplete = { rightFolders ->
                                    runOnWorker("فشلت مقارنة الأرشيفين") {
                                        try {
                                            val result = ArchiveEngine.compareContent(
                                                left, right, leftPassword, rightPassword, leftFolders, rightFolders
                                            )
                                            if (saveLeftPassword && !leftPassword.isNullOrEmpty()) runCatching { QamzorPasswordVault.save(this, "أرشيف: ${left.name.take(90)}", leftPassword) }
                                                .onFailure { recordOperationEvent("تعذر حفظ كلمة مرور الأرشيف الأول: ${it.message}") }
                                            if (saveRightPassword && !rightPassword.isNullOrEmpty()) runCatching { QamzorPasswordVault.save(this, "أرشيف: ${right.name.take(90)}", rightPassword) }
                                                .onFailure { recordOperationEvent("تعذر حفظ كلمة مرور الأرشيف الثاني: ${it.message}") }
                                            session.deleteRecursively()
                                            showCompareResult(result)
                                        } catch (error: Exception) {
                                            runOnUiThread {
                                                AlertDialog.Builder(this)
                                                    .setTitle("تعذرت المقارنة")
                                                    .setMessage(error.message ?: "كلمة مرور غير صحيحة أو بيانات تالفة.")
                                                    .setPositiveButton("إعادة المحاولة") { _, _ ->
                                                        promptComparePasswords(left, right, session)
                                                    }
                                                    .setNegativeButton("إغلاق") { _, _ -> session.deleteRecursively() }
                                                    .show()
                                            }
                                        }
                                    }
                                },
                                onCancel = { session.deleteRecursively() }
                            )
                        },
                        onCancel = { session.deleteRecursively() }
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("تعذر فتح أحد الأرشيفين")
                        .setMessage(error.message ?: "تحقق من كلمات المرور وسلامة الأرشيفات.")
                        .setPositiveButton("إعادة المحاولة") { _, _ -> promptComparePasswords(left, right, session) }
                        .setNegativeButton("إغلاق") { _, _ -> session.deleteRecursively() }
                        .show()
                }
            }
        }
    }

    private fun showCompareResult(result: ArchiveEngine.CompareResult) {
        val message = "الإصدار الأيسر: ${result.leftVersion} • الأيمن: ${result.rightVersion}\n" +
            "التحقق من محتوى الأرشيفين: ${if (result.contentVerified) "ناجح" else "غير مكتمل"}\n" +
            "إضافة: ${result.added.size} | حذف: ${result.removed.size}\n" +
            "تغيير: ${result.changed.size} | متطابق: ${result.unchanged.size}\n\n" +
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
        val session = File(cacheDir, "qamzor-verify-${System.nanoTime()}").apply { mkdirs() }
        val archive = File(session, safeName(displayName(selected) ?: "archive.qzra"))
        status.text = "جارٍ فحص الأرشيف والتحقق من SHA-256..."
        resetVerificationMetrics()
        runOnWorkerAfter("تجهيز الأرشيف للفحص", {
            try {
                copyUriTo(selected, archive)
            } catch (error: Exception) {
                session.deleteRecursively()
                throw error
            }
        }) {
            verifyWithPasswordFlow(archive, null, session)
        }
    }

    private fun verifyArchivePath(file: File) {
        resetVerificationMetrics()
        if (InPlaceVault.isEncrypted(file)) promptPasswordForVerification(file, null)
        else verifyWithPasswordFlow(file, null, null)
    }

    private fun promptPasswordForVerification(archive: File, session: File?) {
        val field = dialogField("كلمة مرور الأرشيف")
        AlertDialog.Builder(this)
            .setTitle("فحص أرشيف مشفّر")
            .setMessage("أدخل كلمة مرور الأرشيف للتحقق من جميع الملفات ومجلداتها المحمية.")
            .setView(field)
            .setPositiveButton("متابعة الفحص") { _, _ ->
                val password = field.text.toString()
                field.text?.clear()
                if (password.isEmpty()) {
                    status.text = "أدخل كلمة مرور الأرشيف أولًا."
                    promptPasswordForVerification(archive, session)
                } else verifyWithPasswordFlow(archive, password, session)
            }
            .setNegativeButton("إلغاء") { _, _ -> session?.deleteRecursively() }
            .setOnCancelListener { session?.deleteRecursively() }
            .show()
    }

    private fun verifyWithPasswordFlow(archive: File, password: String?, session: File?) {
        runOnWorker("تعذر قراءة إعدادات الأرشيف") {
            try {
                val scopes = ArchiveEngine.protectedFolders(archive, password)
                runOnUiThread {
                    promptScopedFolderPasswords(archive, password, scopes, 0, LinkedHashMap(),
                        onComplete = { folders ->
                            try {
                                QamzorBackgroundWork.enqueueVerify(this, archive, password, folders)
                                status.text = "أُضيف فحص SHA-256 إلى العمليات الخلفية. يمكنك متابعة التقدم من تبويب العمليات."
                                operationHealth.text = "قيد الانتظار"
                                selectTab(3)
                                showBackgroundJobs()
                            } catch (error: Exception) {
                                status.text = "تعذر جدولة الفحص: ${error.message ?: error.javaClass.simpleName}"
                            }
                        },
                        onCancel = { session?.deleteRecursively() }
                    )
                }
            } catch (error: Exception) {
                if (needsArchivePassword(error) && password == null) {
                    runOnUiThread { promptPasswordForVerification(archive, session) }
                } else {
                    session?.deleteRecursively()
                    runOnUiThread {
                        AlertDialog.Builder(this).setTitle("تعذر فحص الأرشيف")
                            .setMessage(error.message ?: "تعذر قراءة الأرشيف أو كلمة المرور.")
                            .setPositiveButton("إعادة المحاولة") { _, _ -> promptPasswordForVerification(archive, session) }
                            .setNegativeButton("إغلاق") { _, _ -> session?.deleteRecursively() }.show()
                    }
                }
            }
        }
    }

    private fun verifyArchiveFile(
        archive: File, password: String?, folderPasswords: Map<String, String> = openedFolderPasswordsSnapshot()
    ) {
        val result = ArchiveEngine.verify(archive, password, folderPasswords)
        runOnUiThread {
            refreshVerificationMetrics(result.valid, result.filesChecked, result.bytesChecked)
            status.text = if (result.valid) {
                "اكتمل فحص الأرشيف والتحقق من SHA-256 ✓\nالإصدار: ${result.version}"
            } else "لم يكتمل التحقق من سلامة الأرشيف."
        }
    }

    private fun confirmRestore(file: File) {
        val encrypted = InPlaceVault.isEncrypted(file)
        val field = if (encrypted) dialogField("كلمة مرور الأرشيف") else null
        val saveMaster = CheckBox(this).apply {
            text = "حفظ كلمة مرور الأرشيف في الخزنة المشفرة"
            setTextColor(0xFFDCE7F8.toInt())
        }
        val deleteAsk = settingsPreferences().getBoolean("confirm_destructive_actions", true)
        val builder = AlertDialog.Builder(this)
            .setTitle("استعادة ${file.name}")
            .setMessage("سيُستخرج الأصل بعد التحقق من كل ملف. قد يطلب QAMZOR كلمة مرور مستقلة لكل مجلد محمي.")
        if (field != null) {
            builder.setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(2))
                addView(field)
                addView(actionButton("key", "اختيار كلمة محفوظة", false) {
                    showSavedPasswordPicker { value -> field.setText(value); field.setSelection(field.text.length) }
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
                addView(saveMaster)
            })
        }
        builder.setPositiveButton("استعادة وحذف الأرشيف") { _, _ ->
            val password = field?.text?.toString()
            field?.text?.clear()
            if (deleteAsk) {
                AlertDialog.Builder(this)
                    .setTitle("تأكيد حذف الأرشيف")
                    .setMessage("سيتم حذف ملف الأرشيف فقط بعد اكتمال الاستعادة والتحقق. إذا تعذر الحذف ستظهر رسالة بذلك وسيبقى الأرشيف موجودًا.")
                    .setPositiveButton("تأكيد الاستعادة") { _, _ -> beginRestorePasswordFlow(file, password, true, saveMaster.isChecked) }
                    .setNegativeButton("إلغاء", null).show()
            } else beginRestorePasswordFlow(file, password, true, saveMaster.isChecked)
        }
        builder.setNeutralButton("استعادة مع إبقاء الأرشيف") { _, _ ->
            val password = field?.text?.toString()
            field?.text?.clear()
            beginRestorePasswordFlow(file, password, false, saveMaster.isChecked)
        }
        builder.setNegativeButton("إلغاء", null)
        builder.show()
    }

    private fun beginRestorePasswordFlow(file: File, password: String?, removeArchive: Boolean, saveMasterPassword: Boolean = false) {
        if (InPlaceVault.isEncrypted(file) && password.isNullOrEmpty()) {
            status.text = "أدخل كلمة مرور الأرشيف أولًا."
            return
        }
        runOnWorker("تعذر قراءة إعدادات حماية المجلدات") {
            try {
                val scopes = ArchiveEngine.protectedFolders(file, password)
                runOnUiThread {
                    promptScopedFolderPasswords(file, password, scopes, 0, LinkedHashMap(),
                        onComplete = { folderPasswords ->
                            if (saveMasterPassword && !password.isNullOrEmpty()) {
                                runCatching { QamzorPasswordVault.save(this, "أرشيف: ${file.name.take(90)}", password) }
                                    .onFailure { recordOperationEvent("تعذر حفظ كلمة مرور الأرشيف في الخزنة: ${it.message}") }
                            }
                            startRestore(file, password, removeArchive, folderPasswords)
                        })
                }
            } catch (error: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("تعذر فتح الأرشيف للاستعادة")
                        .setMessage(error.message ?: "كلمة المرور غير صحيحة أو الأرشيف تالف.")
                        .setPositiveButton("إعادة إدخال كلمة المرور") { _, _ -> confirmRestore(file) }
                        .setNegativeButton("إغلاق", null).show()
                }
            }
        }
    }

    private fun promptScopedFolderPasswords(
        archive: File, masterPassword: String?, paths: List<String>, index: Int,
        collected: MutableMap<String, String>, onComplete: (Map<String, String>) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        if (index >= paths.size) {
            onComplete(collected.toMap())
            return
        }
        val path = paths[index]
        val field = dialogField("كلمة مرور المجلد: $path")
        val savePassword = CheckBox(this).apply {
            text = "حفظ كلمة المرور في الخزنة المشفرة لهذا الجهاز"
            setTextColor(0xFFDCE7F8.toInt())
            isChecked = false
        }
        val passwordPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(field)
            addView(actionButton("key", "اختيار كلمة محفوظة", false) {
                showSavedPasswordPicker { value -> field.setText(value); field.setSelection(field.text.length) }
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            addView(savePassword)
        }
        AlertDialog.Builder(this)
            .setTitle("مجلد محمي ${index + 1}/${paths.size}")
            .setMessage("أدخل كلمة المرور المستقلة لهذا المجلد. تحفظ فقط عند تفعيل الخيار أدناه.")
            .setView(passwordPanel)
            .setPositiveButton("تحقق وتابع") { _, _ ->
                val value = field.text.toString()
                field.text?.clear()
                if (value.isEmpty()) {
                    status.text = "أدخل كلمة مرور المجلد."
                    promptScopedFolderPasswords(archive, masterPassword, paths, index, collected, onComplete, onCancel)
                } else runOnWorker("تعذر التحقق من كلمة مرور المجلد") {
                    try {
                        val supplied = HashMap(collected)
                        supplied[path] = value
                        ArchiveEngine.list(archive, masterPassword, supplied)
                        collected[path] = value
                        if (savePassword.isChecked) runCatching { QamzorPasswordVault.save(this, "مجلد: ${path.take(100)}", value) }
                            .onFailure { recordOperationEvent("تعذر حفظ كلمة مرور المجلد في الخزنة: ${it.message}") }
                        runOnUiThread { promptScopedFolderPasswords(archive, masterPassword, paths, index + 1, collected, onComplete, onCancel) }
                    } catch (error: Exception) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("كلمة المرور غير صحيحة")
                                .setMessage(error.message ?: "تعذر فتح المجلد المحمي.")
                                .setPositiveButton("إعادة المحاولة") { _, _ ->
                                    promptScopedFolderPasswords(archive, masterPassword, paths, index, collected, onComplete, onCancel)
                                }
                                .setNegativeButton("إلغاء") { _, _ -> onCancel() }.show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun startRestore(
        file: File, password: String?, removeArchive: Boolean, folderPasswords: Map<String, String> = emptyMap()
    ) {
        if (password != null && password.isEmpty()) {
            status.text = "أدخل كلمة المرور أولًا."
            return
        }
        try {
            val workId = QamzorBackgroundWork.enqueueRestore(this, file, password, folderPasswords, removeArchive)
            recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] أُضيفت مهمة استعادة خلفية — $workId")
            status.text = "تمت إضافة الاستعادة إلى مركز العمليات. سيبقى الأرشيف الأصلي إذا فشل التحقق أو الحذف."
            selectTab(3)
            showBackgroundJobs()
        } catch (error: Exception) {
            status.text = "تعذر بدء الاستعادة: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    /** Opens an archive straight from storage (no copy). Asks for the password first when encrypted. */
    private fun openArchiveFile(file: File) {
        status.text = "جارٍ فتح ${file.name}..."
        runOnWorker("تعذر فتح الأرشيف") {
            try {
                ArchiveEngine.browse(file)
                installOpenedArchive(file, file.name, null, emptySet())
                runOnUiThread {
                    status.text = "تم فتح الأرشيف. تُقرأ الملفات من داخله دون فكّه بالكامل."
                    selectTab(1)
                    showVirtualExplorer("")
                }
            } catch (error: IllegalArgumentException) {
                if (needsArchivePassword(error)) {
                    runOnUiThread { selectTab(1); promptArchivePassword(file, file.name) }
                } else throw error
            }
        }
    }

    private fun isCachedFile(file: File): Boolean = try {
        file.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)
    } catch (_: Exception) { true }

    /** Only app-private cache copies may be deleted; never a user's real archive. */
    private fun deleteIfCached(file: File?) {
        if (file == null) return
        try {
            if (isCachedFile(file)) {
                val parent = file.parentFile
                file.delete()
                // Remove only QAMZOR-created per-operation folders; never climb to cacheDir.
                if (parent != null && parent != cacheDir && parent.name.startsWith("qamzor-")) parent.deleteRecursively()
            }
        } catch (_: Exception) { /* best effort */ }
    }

    /**
     * Extract from an archive that lives in user storage: the result is placed next to the archive
     * (no folder picker). Whole archive = restore the original; one item = extract it beside the archive.
     */
    private fun restoreOpenedInPlace(archive: File, path: String?) {
        val password = openedArchivePassword
        if (path != null) {
            val missingScopes = openedProtected.filterNot { openedFolderPasswords.containsKey(it) }
            promptScopedFolderPasswords(archive, password, missingScopes, 0, LinkedHashMap(),
                onComplete = { newlyEntered ->
                    openedFolderPasswords.putAll(newlyEntered)
                    val folderPasswords = openedFolderPasswordsSnapshot()
                    runOnWorker("فشل استخراج العنصر") {
                        val extracted = InPlaceVault.restoreItem(archive, path, password, folderPasswords)
                        scanMedia(pathsUnder(extracted))
                        runOnUiThread {
                            renderFiles()
                            status.text = "تم استخراج «${extracted.name}» بجانب الأرشيف والتحقق منه ✓ (الأرشيف باقٍ كما هو)."
                        }
                    }
                })
            return
        }
        val runWithPasswords: (Map<String, String>) -> Unit = { folderPasswords ->
            val run = { remove: Boolean ->
                runOnWorker("فشلت الاستعادة") {
                    val restored = InPlaceVault.restore(archive, password, remove, folderPasswords)
                    scanMedia(restored.flatMap { pathsUnder(it) })
                    runOnUiThread {
                        if (remove) { closeOpenedArchive(); selectTab(0) }
                        renderFiles()
                        status.text = "تمت الاستعادة والتحقق ✓: ${restored.joinToString { it.name }}" +
                            if (remove) "\nتم حذف الأرشيف بعد نجاح الاستعادة." else "\nتم الاحتفاظ بالأرشيف."
                    }
                }
            }
            AlertDialog.Builder(this)
                .setTitle("استعادة ${archive.name}")
                .setMessage("سيُستخرج الأصل بعد التحقق من جميع الملفات. كلمات مرور المجلدات المحمية مطلوبة قبل بدء الاستعادة.")
                .setPositiveButton("استعادة وحذف الأرشيف") { _, _ ->
                    val action = { run(true) }
                    if (settingsPreferences().getBoolean("confirm_destructive_actions", true)) {
                        AlertDialog.Builder(this).setTitle("تأكيد حذف الأرشيف")
                            .setMessage("لن يُحذف الأرشيف إلا بعد نجاح الاستعادة. إذا تعذر الحذف فسيبقى موجودًا وسيظهر الخطأ.")
                            .setPositiveButton("تأكيد", { _, _ -> action() }).setNegativeButton("إلغاء", null).show()
                    } else action()
                }
                .setNeutralButton("استعادة مع إبقاء الأرشيف") { _, _ -> run(false) }
                .setNegativeButton("إلغاء", null).show()
        }
        val missingScopes = openedProtected.filterNot { openedFolderPasswords.containsKey(it) }
        promptScopedFolderPasswords(archive, password, missingScopes, 0, LinkedHashMap(),
            onComplete = { newlyEntered ->
                openedFolderPasswords.putAll(newlyEntered)
                runWithPasswords(openedFolderPasswordsSnapshot())
            })
    }

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
        val savePassword = CheckBox(this).apply {
            text = "حفظ كلمة المرور في الخزنة المشفرة لهذا الجهاز"
            setTextColor(0xFFDCE7F8.toInt())
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(passwordField)
            addView(actionButton("key", "اختيار كلمة محفوظة", false) {
                showSavedPasswordPicker { value -> passwordField.setText(value); passwordField.setSelection(passwordField.text.length) }
            }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            addView(savePassword)
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
                            if (savePassword.isChecked) runCatching { QamzorPasswordVault.save(this, "أرشيف: ${name.take(90)}", password) }
                                .onFailure { recordOperationEvent("تعذر حفظ كلمة مرور الأرشيف: ${it.message}") }
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
                ArchiveEngine.preview(archive, path, limit, openedArchivePassword, openedFolderPasswordsSnapshot())
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
        val opened = openedArchive
        if (opened != null && !isCachedFile(opened) && hasStorageAccess()) {
            restoreOpenedInPlace(opened, path)
            return
        }
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

    private fun formatModified(timestamp: Long): String = if (timestamp <= 0L) "تاريخ غير معروف" else
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault())
            .format(java.util.Date(timestamp))

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(Locale.ROOT, bytes / 1024.0)} KB"
        else -> "${"%.2f".format(Locale.ROOT, bytes / (1024.0 * 1024.0))} MB"
    }

    private fun extractToTree(treeUri: Uri?) {
        val archive = pendingExtraction ?: error("لم يتم تحديد أرشيف")
        val destination = treeUri ?: error("لم يتم اختيار مجلد الوجهة")
        val selectedPaths = pendingExtractionPaths
        val masterPassword = openedArchivePassword
        val currentPasswords = openedFolderPasswordsSnapshot()
        runOnWorker("تعذر قراءة إعدادات حماية الأرشيف") {
            val scopes = ArchiveEngine.protectedFolders(archive, masterPassword)
            val missing = scopes.filterNot { currentPasswords.containsKey(it) }
            runOnUiThread {
                promptScopedFolderPasswords(archive, masterPassword, missing, 0, LinkedHashMap(),
                    onComplete = { newlyEntered ->
                        openedFolderPasswords.putAll(newlyEntered)
                        performExtractToTree(archive, destination, selectedPaths, masterPassword, openedFolderPasswordsSnapshot())
                    })
            }
        }
    }

    private fun performExtractToTree(
        archive: File, destination: Uri, selectedPaths: Set<String>?, password: String?, folderPasswords: Map<String, String>
    ) {
        status.text = if (selectedPaths == null) "جارٍ استخراج الأرشيف بالكامل..." else "جارٍ استخراج العنصر المحدد فقط..."
        runOnWorker("فشل استخراج الأرشيف") {
            val temporary = File(cacheDir, "qamzor-extract-${System.nanoTime()}").apply { mkdirs() }
            val createdByThisOperation = ArrayList<DocumentFile>()
            try {
                val selection = selectedPaths ?: ArchiveEngine.list(archive, password, folderPasswords).map { it.path }.toSet()
                val extracted = ArchiveEngine.extractSelected(
                    archive, temporary, selection, password = password, folderPasswords = folderPasswords
                )
                val tree = DocumentFile.fromTreeUri(this, destination) ?: error("لا يمكن الوصول إلى مجلد الوجهة")
                fun ensureTracked(parent: DocumentFile, name: String): DocumentFile {
                    val existing = parent.findFile(name)
                    if (existing != null) {
                        require(existing.isDirectory) { "يوجد ملف بنفس اسم المجلد: $name" }
                        return existing
                    }
                    val created = parent.createDirectory(name) ?: error("تعذر إنشاء المجلد: $name")
                    createdByThisOperation += created
                    return created
                }
                val directories = extracted.filter { it.isDirectory }.sortedBy { it.toPath().nameCount }
                for (directory in directories) {
                    val relative = temporary.toPath().relativize(directory.toPath()).toString().replace(File.separatorChar, '/')
                    var parent = tree
                    for (part in relative.split('/').filter { it.isNotEmpty() }) parent = ensureTracked(parent, part)
                }
                val files = extracted.filter { it.isFile }
                for (file in files) {
                    val relative = temporary.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    val parts = relative.split('/').filter { it.isNotEmpty() }
                    require(parts.isNotEmpty()) { "مسار ملف غير صالح داخل الأرشيف" }
                    var parent = tree
                    for (part in parts.dropLast(1)) parent = ensureTracked(parent, part)
                    require(parent.findFile(parts.last()) == null) { "المجلد الوجهة يحتوي بالفعل على ${parts.last()}" }
                    val mime = when (file.extension.lowercase(Locale.ROOT)) {
                        "txt", "md", "csv", "json", "xml", "html", "log" -> "text/plain"
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "pdf" -> "application/pdf"
                        else -> "application/octet-stream"
                    }
                    val target = parent.createFile(mime, parts.last()) ?: error("تعذر إنشاء الملف ${parts.last()}")
                    createdByThisOperation += target
                    val output = contentResolver.openOutputStream(target.uri, "w") ?: error("تعذر فتح الملف الناتج")
                    output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                }
                runOnUiThread {
                    val label = if (selectedPaths == null) "الأرشيف بالكامل" else selectedPaths.joinToString()
                    status.text = "اكتمل الاستخراج والتحقق من الملفات المحددة ✓\nالنطاق: $label\nالملفات: ${files.size}\nالمجلدات: ${directories.size}\nالوجهة: ${tree.name}"
                    pendingExtraction = null
                    pendingExtractionPaths = null
                }
            } catch (error: Exception) {
                createdByThisOperation.asReversed().forEach { try { it.delete() } catch (_: Exception) { } }
                throw error
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
                    val supplied = HashMap(openedFolderPasswordsSnapshot())
                    supplied[path] = value
                    try {
                        ArchiveEngine.list(archive, masterPassword, supplied)
                        openedFolderPasswords[path] = value
                        runOnUiThread { retry() }
                    } catch (error: Exception) {
                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("كلمة المرور غير صحيحة")
                                .setMessage(error.message ?: "تعذر فتح المجلد المحمي.")
                                .setPositiveButton("إعادة المحاولة") { _, _ -> promptFolderPassword(path, archive, masterPassword, retry) }
                                .setNegativeButton("إلغاء", null).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun takeTreePermission(uri: Uri, data: Intent) {
        val requested = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val flags = if (requested == 0) Intent.FLAG_GRANT_READ_URI_PERMISSION else requested
        try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) { /* Some providers grant transient access only. */ }
    }

    private fun runOnWorker(errorPrefix: String, block: () -> Unit) =
        launchWorker(errorPrefix, block, null)

    private fun runOnWorkerAfter(errorPrefix: String, block: () -> Unit, onSuccess: () -> Unit) =
        launchWorker(errorPrefix, block, onSuccess)

    private fun launchWorker(errorPrefix: String, block: () -> Unit, onSuccess: (() -> Unit)?) {
        synchronized(backgroundTaskLock) {
            if (backgroundBusy) {
                runOnUiThread { status.text = "هناك عملية أخرى قيد التنفيذ. انتظر حتى تنتهي قبل بدء: $errorPrefix" }
                return
            }
            backgroundBusy = true
        }
        Thread({
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
            var succeeded = false
            try {
                block()
                succeeded = true
                recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] اكتملت — $errorPrefix")
            } catch (error: Exception) {
                recordOperationEvent("[${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}] فشلت — $errorPrefix: ${error.message ?: error.javaClass.simpleName}")
                runOnUiThread { status.text = "$errorPrefix: ${error.message ?: error.javaClass.simpleName}" }
            } finally {
                operationPreferences().edit()
                    .remove("active_pid").remove("active_label").remove("active_started_at").apply()
                activeOperationLabel = null
                activeOperationStartedAt = null
                synchronized(backgroundTaskLock) { backgroundBusy = false }
                if (succeeded && onSuccess != null) runOnUiThread { onSuccess() }
            }
        }, "QAMZOR-$errorPrefix").start()
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
        private val TAB_TITLES = listOf("ملفات الهاتف", "الأرشيف المفتوح", "الأدوات", "سجل العمليات", "الإعدادات")
        private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "py", "kt", "java", "yaml", "yml", "toml", "ini", "properties", "sh", "sql", "log")
        private val FILE_SORT_OPTIONS = listOf(
            "المجلدات أولًا • الاسم", "الأحدث تعديلًا", "الأقدم تعديلًا", "الاسم: أ → ي",
            "الاسم: ي → أ", "الأكبر حجمًا", "الأصغر حجمًا", "نوع الملف"
        )
        private val ARCHIVE_SORT_OPTIONS = listOf(
            "المجلدات أولًا • الاسم", "الاسم: أ → ي", "الاسم: ي → أ", "الأكبر حجمًا", "الأصغر حجمًا", "نوع الملف"
        )
        private const val FILES_PAGE_SIZE = 400
        private const val TEMP_CACHE_RETENTION_MS = 24L * 60L * 60L * 1000L
        private const val MIN_PASSWORD_LENGTH = 10
        private const val REQUEST_STORAGE_PERMISSION = 30
        private const val REQUEST_BROWSE = 23
        private const val REQUEST_EXTRACT = 24
        private const val REQUEST_OUTPUT_TREE = 25
        private const val REQUEST_VERIFY = 26
        private const val REQUEST_COMPARE = 27
        private const val REQUEST_PUBLIC_KEY = 28
        private const val REQUEST_STORAGE_TREE = 29
        private const val REQUEST_EXPORT_TREE = 31
    }
}
