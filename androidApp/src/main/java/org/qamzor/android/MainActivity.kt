package org.qamzor.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
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
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import org.qamzor.core.ArchiveEngine
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Android SAF front-end for the QAMZOR reference archive engine. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var selectionSummary: TextView
    private val selectedSources = ArrayList<File>()
    private val backgroundTaskLock = Any()
    @Volatile private var backgroundBusy = false
    private var stagingDirectory: File? = null
    private var pendingArchive: File? = null
    private var pendingExtraction: File? = null
    private var pendingExtractionPaths: Set<String>? = null
    private var openedArchive: File? = null
    private var openedArchivePassword: String? = null
    private var explorerDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    private fun buildInterface() {
        window.statusBarColor = 0xFF101D36.toInt()
        window.navigationBarColor = 0xFF101D36.toInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            background = rounded(0xFF0B1730.toInt(), 0)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(0xFF0B1730.toInt())
            addView(root)
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(18))
        }
        val mark = TextView(this).apply {
            text = "▣"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFBBD2FF.toInt())
            background = rounded(0xFF263F70.toInt(), 18)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brandText.addView(TextView(this).apply {
            text = "QAMZOR"
            textSize = 29f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF7FAFF.toInt())
        })
        brandText.addView(TextView(this).apply {
            text = "THE NEXT ARCHIVE EXPERIENCE"
            textSize = 10f
            setTextColor(0xFF9FB5D9.toInt())
            letterSpacing = .08f
        })
        brand.addView(mark)
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(brand)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(22))
            background = rounded(0xFF1C2D50.toInt(), 26)
        }
        hero.addView(TextView(this).apply {
            text = "مساحة الأرشيف الذكية"
            textSize = 14f
            setTextColor(0xFFB7C9E8.toInt())
        })
        hero.addView(TextView(this).apply {
            text = "إدارة ملفاتك بذكاء"
            textSize = 27f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(8), 0, dp(5))
        })
        hero.addView(TextView(this).apply {
            text = "إنشاء وفحص واستعراض وتحويل أرشيفات QZRA من مكان واحد."
            textSize = 15f
            setTextColor(0xFFD4E0F5.toInt())
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        root.addView(sectionLabel("الأدوات الرئيسية"))
        addTileRow(root,
            dashboardTile("اختيار ملفات", "إضافة ملفات إلى الأرشيف", "＋", 0xFF31588F.toInt()) { pickFiles() },
            dashboardTile("استعراض الأرشيف", "تصفح المحتويات داخليًا", "⌕", 0xFF31588F.toInt()) { pickArchive(REQUEST_BROWSE) })
        addTileRow(root,
            dashboardTile("تحويل إلى QZRA", "تجميع المحدد في أرشيف", "⇄", 0xFF31588F.toInt()) { convertSelectedToQzra() },
            dashboardTile("فحص السلامة", "التحقق الكامل من SHA-256", "✓", 0xFF31588F.toInt()) { pickArchive(REQUEST_VERIFY) })
        addTileRow(root,
            dashboardTile("استخراج الأرشيف", "استخراج كامل أو انتقائي", "⇩", 0xFF31588F.toInt()) { pickArchive(REQUEST_EXTRACT) },
            dashboardTile("إضافة مجلد", "ضم المجلدات الفرعية", "▱", 0xFF31588F.toInt()) { pickFolder() })

        root.addView(sectionLabel("مصادر التحويل"))
        selectionSummary = infoPanel("لم تتم إضافة ملفات أو مجلدات بعد.\nاختر ملفات أو مجلدات متعددة لتجميعها في أرشيف QZRA واحد.")
        root.addView(selectionSummary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val sourceActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sourceActions.addView(compactAction("إضافة ملفات", "＋") { pickFiles() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        sourceActions.addView(compactAction("مسح الاختيار", "×") { clearSelection() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(sourceActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root.addView(sectionLabel("مركز العمليات"))
        status = infoPanel("جاهز للعمل. اختر إحدى الأدوات للبدء.")
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(TextView(this).apply {
            text = "QAMZOR  •  QZRA Archive System"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF8299BE.toInt())
            setPadding(0, dp(22), 0, dp(4))
        })
        setContentView(scroll)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun infoPanel(message: String) = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(0xFFE0E9F8.toInt())
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(0xFF1A2A49.toInt(), 18)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(64)
    }

    private fun sectionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFFB9D0F7.toInt())
        setPadding(dp(2), dp(14), dp(2), dp(8))
    }

    private fun dashboardTile(title: String, description: String, symbol: String, color: Int, action: () -> Unit): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(color, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            minimumHeight = dp(142)
            contentDescription = "$title. $description"
        }
        tile.addView(TextView(this).apply {
            text = symbol
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.END
        })
        tile.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(8), 0, dp(5))
        })
        tile.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(0xFFD8E4F7.toInt())
        })
        return tile
    }

    private fun addTileRow(parent: LinearLayout, first: View, second: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun compactAction(label: String, symbol: String, action: () -> Unit): View = TextView(this).apply {
        text = "$symbol  $label"
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(0xFFF4F7FF.toInt())
        background = rounded(0xFF263D63.toInt(), 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
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
                REQUEST_EXTRACT -> {
                    val selected = data.data ?: error("لم يتم اختيار أرشيف")
                    pendingExtractionPaths = null
                    openedArchivePassword = null
                    status.text = "جارٍ قراءة الأرشيف المحدد..."
                    runOnWorker("تعذرت قراءة الأرشيف") {
                        val archive = copyUriToCache(selected, "selected-archive-${System.currentTimeMillis()}.qzra")
                        pendingExtraction = archive
                        openedArchive = archive
                        try {
                            ArchiveEngine.browse(archive)
                            openedArchivePassword = null
                            runOnUiThread { requestSelectedExtraction(null) }
                        } catch (error: IllegalArgumentException) {
                            if ((error.message.orEmpty().contains("encrypted", ignoreCase = true) && error.message.orEmpty().contains("password", ignoreCase = true))) {
                                runOnUiThread { promptArchivePassword(archive) }
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

    private fun verifySelectedArchive(uri: Uri?) {
        val selected = uri ?: error("لم يتم اختيار أرشيف")
        status.text = "جارٍ فحص جميع بيانات الأرشيف والتحقق من SHA-256..."
        runOnWorker("فشل فحص الأرشيف") {
            val archive = copyUriToCache(selected, "qamzor-verify-${System.currentTimeMillis()}.qzra")
            val result = ArchiveEngine.verify(archive)
            runOnUiThread {
                status.text = if (result.valid) {
                    "الأرشيف سليم ✓\nالملفات التي تم فحصها: ${result.filesChecked}\nالبيانات التي تم التحقق منها: ${formatBytes(result.bytesChecked)}"
                } else "لم يكتمل التحقق من سلامة الأرشيف."
            }
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
        status.text = "جارٍ نسخ المجلد بالكامل مع المجلدات الفرعية..."
        runOnWorker("تعذرت إضافة المجلد") {
            val documentTree = DocumentFile.fromTreeUri(this, uri) ?: error("لا يمكن فتح المجلد المحدد")
            require(documentTree.isDirectory) { "العنصر المحدد ليس مجلدًا" }
            val staging = getStagingDirectory()
            val rootName = uniqueName(staging, safeName(documentTree.name ?: "folder"))
            val localRoot = File(staging, rootName)
            copyDocumentDirectory(documentTree, localRoot)
            synchronized(selectedSources) { selectedSources.add(localRoot) }
            val counts = countFilesAndDirectories(localRoot)
            runOnUiThread {
                refreshSelectionSummary()
                status.text = "تمت إضافة المجلد: $rootName\nالملفات: ${counts.first}\nالمجلدات: ${counts.second}\nيمكنك إضافة عناصر أخرى أو إنشاء الأرشيف الآن."
            }
        }
    }

    private fun copyDocumentDirectory(source: DocumentFile, destination: File) {
        require(destination.mkdirs() || destination.isDirectory) { "تعذر إنشاء المجلد المحلي: ${destination.name}" }
        val usedNames = HashSet<String>()
        val children = source.listFiles().sortedBy { (it.name ?: "").lowercase(Locale.ROOT) }
        for (child in children) {
            val childName = uniqueName(usedNames, safeName(child.name ?: "item"))
            val target = File(destination, childName)
            when {
                child.isDirectory -> copyDocumentDirectory(child, target)
                child.isFile -> {
                    val input = contentResolver.openInputStream(child.uri) ?: error("تعذر قراءة الملف: $childName")
                    input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
                }
                else -> error("نوع عنصر غير مدعوم داخل المجلد: $childName")
            }
        }
    }

    private fun convertSelectedToQzra() {
        val sources = synchronized(selectedSources) { selectedSources.toList() }
        require(sources.isNotEmpty()) { "أضف ملفًا واحدًا على الأقل أو مجلدًا قبل التحويل إلى QZRA" }
        status.text = "جارٍ تحويل ${sources.size} مصدر إلى حاوية QZRA الأصلية..."
        runOnWorker("فشل تحويل العناصر إلى QZRA") {
            val output = File(cacheDir, "QAMZOR-${System.currentTimeMillis()}.qzra")
            val result = ArchiveEngine.convertToQzra(output, sources)
            val verified = ArchiveEngine.verify(output)
            require(verified.valid && verified.filesChecked == result.files) { "تعذر التحقق من الأرشيف بعد التحويل" }
            pendingArchive = output
            runOnUiThread {
                status.text = "اكتمل التحويل إلى صيغة QZRA والتحقق من سلامة البيانات ✓\nالعناصر: ${result.entries}\nالملفات: ${result.files}\nالحجم الأصلي: ${result.originalBytes} بايت\nالحجم داخل QZRA: ${result.storedBytes} بايت\nالملفات الأصلية لم تتغير. اختر مكان حفظ ملف .qzra."
                val save = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, output.name)
                }
                startActivityForResult(save, REQUEST_SAVE_ARCHIVE)
            }
        }
    }

    private fun clearSelection() {
        if (backgroundBusy) {
            status.text = "هناك عملية ملفات قيد التنفيذ. امسح القائمة بعد انتهائها."
            return
        }
        synchronized(selectedSources) { selectedSources.clear() }
        stagingDirectory?.deleteRecursively()
        stagingDirectory = null
        refreshSelectionSummary()
        status.text = "تم مسح قائمة الملفات والمجلدات."
    }

    private fun refreshSelectionSummary() {
        val sources = synchronized(selectedSources) { selectedSources.toList() }
        val names = sources.joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.name }
        runOnUiThread {
            selectionSummary.text = if (sources.isEmpty()) {
                "لم تتم إضافة ملفات أو مجلدات بعد.\nيمكنك اختيار ملفات ومجلدات متعددة وتحويلها إلى QZRA واحد."
            } else {
                "مصادر جاهزة للتحويل إلى QZRA: ${sources.size}\n$names\nسيتم حفظ البيانات الأصلية كما هي داخل الحاوية."
            }
        }
    }

    private fun browseSelectedArchive(uri: Uri?) {
        val selected = uri ?: error("لم يتم اختيار أرشيف")
        status.text = "جارٍ فتح فهرس أرشيف QZRA..."
        runOnWorker("تعذر فتح الأرشيف") {
            val archive = copyUriToCache(selected, "qamzor-opened-${System.currentTimeMillis()}.qzra")
            try {
                // Opening the virtual explorer reads and validates the manifest only.
                // Payload integrity is checked when a file is previewed or extracted.
                ArchiveEngine.browse(archive)
                openedArchive = archive
                openedArchivePassword = null
                pendingExtraction = archive
                runOnUiThread {
                    status.text = "تم فتح الأرشيف. تتم قراءة الملفات من داخله دون فك الأرشيف بالكامل."
                    showVirtualExplorer("")
                }
            } catch (error: IllegalArgumentException) {
                if ((error.message.orEmpty().contains("encrypted", ignoreCase = true) && error.message.orEmpty().contains("password", ignoreCase = true))) {
                    runOnUiThread { promptArchivePassword(archive) }
                } else throw error
            }
        }
    }

    private fun promptArchivePassword(archive: File) {
        val passwordField = EditText(this).apply {
            hint = "كلمة مرور الأرشيف"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(24, 18, 24, 18)
        }
        AlertDialog.Builder(this)
            .setTitle("أرشيف QZRA مشفّر")
            .setMessage("أدخل كلمة المرور لفك مصادقة الفهرس وقراءة الملفات بأمان.")
            .setView(passwordField)
            .setPositiveButton("فتح الأرشيف") { _, _ ->
                val password = passwordField.text.toString()
                if (password.isEmpty()) {
                    status.text = "يجب إدخال كلمة المرور للأرشيف المشفّر."
                } else {
                    runOnWorker("تعذر فتح الأرشيف المشفّر") {
                        try {
                            ArchiveEngine.browse(archive, "", password)
                            openedArchive = archive
                            openedArchivePassword = password
                            pendingExtraction = archive
                            runOnUiThread {
                                status.text = "تمت مصادقة الأرشيف المشفّر بنجاح ✓"
                                showVirtualExplorer("")
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setTitle("تعذر فتح الأرشيف")
                                    .setMessage(error.message ?: "كلمة المرور غير صحيحة أو الأرشيف تالف")
                                    .setPositiveButton("إعادة المحاولة") { _, _ -> promptArchivePassword(archive) }
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

    private fun showVirtualExplorer(path: String) {
        val archive = openedArchive ?: return
        runOnWorker("تعذر قراءة مجلد الأرشيف") {
            val children = ArchiveEngine.browse(archive, path, openedArchivePassword)
            runOnUiThread {
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 8, 12, 8)
                }
                val breadcrumb = TextView(this).apply {
                    text = if (path.isBlank()) "المسار: /" else "المسار: /$path/"
                    textSize = 14f
                    setTextColor(0xFF2455A4.toInt())
                    setPadding(8, 8, 8, 12)
                }
                container.addView(breadcrumb)
                val summary = TextView(this).apply {
                    text = "${children.size} عنصر في هذا المجلد\nالملفات تُقرأ مباشرة من كتل الأرشيف، ولا تُفك إلى مجلد خارجي عند معاينتها."
                    textSize = 12f
                    setTextColor(0xFF526078.toInt())
                    setPadding(8, 4, 8, 12)
                }
                container.addView(summary)
                if (path.isNotBlank()) {
                    val up = Button(this).apply {
                        text = "⬆ المجلد السابق"
                        isAllCaps = false
                        setOnClickListener {
                            explorerDialog?.dismiss()
                            showVirtualExplorer(path.substringBeforeLast('/', ""))
                        }
                    }
                    container.addView(up)
                }
                if (children.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = "هذا المجلد فارغ."
                        textSize = 15f
                        setPadding(18, 24, 18, 24)
                    })
                }
                for (item in children) {
                    val row = TextView(this).apply {
                        text = (if (item.type == "directory") "📁  " else "📄  ") + item.name +
                            if (item.type == "directory") "  ›" else "\n${formatBytes(item.size)}"
                        textSize = 15f
                        setTextColor(0xFF202A3A.toInt())
                        setPadding(16, 14, 16, 14)
                        setBackgroundColor(0xFFFFFFFF.toInt())
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            if (item.type == "directory") {
                                explorerDialog?.dismiss()
                                showVirtualExplorer(item.path)
                            } else showFileActions(item.path, item.size)
                        }
                        setOnLongClickListener {
                            if (item.type == "directory") {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle(item.name)
                                    .setItems(arrayOf("فتح المجلد", "استخراج هذا المجلد فقط")) { _, which ->
                                        if (which == 0) {
                                            explorerDialog?.dismiss()
                                            showVirtualExplorer(item.path)
                                        } else requestSelectedExtraction(item.path)
                                    }.show()
                            } else showFileActions(item.path, item.size)
                            true
                        }
                    }
                    container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5 })
                }
                val scroll = ScrollView(this).apply { addView(container) }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("مستكشف QZRA الافتراضي")
                    .setView(scroll)
                    .setPositiveButton("إغلاق", null)
                    .setNeutralButton("استخراج الكل") { _, _ -> requestSelectedExtraction(null) }
                    .create()
                explorerDialog = dialog
                dialog.setOnDismissListener { if (explorerDialog === dialog) explorerDialog = null }
                dialog.show()
            }
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
            val preview = ArchiveEngine.preview(archive, path, limit, openedArchivePassword)
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
                synchronized(selectedSources) { selectedSources.clear() }
                stagingDirectory?.deleteRecursively()
                stagingDirectory = null
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
                val selection = selectedPaths ?: ArchiveEngine.list(archive, openedArchivePassword).map { it.path }.toSet()
                val extracted = ArchiveEngine.extractSelected(archive, temporary, selection, password = openedArchivePassword)
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
                try { block() }
                catch (error: Exception) {
                    runOnUiThread { status.text = "$errorPrefix: ${error.message ?: error.javaClass.simpleName}" }
                } finally {
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
        private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "py", "kt", "java", "yaml", "yml", "toml", "ini", "properties", "sh", "sql", "log")
        private const val REQUEST_FILES = 20
        private const val REQUEST_FOLDER = 21
        private const val REQUEST_SAVE_ARCHIVE = 22
        private const val REQUEST_BROWSE = 23
        private const val REQUEST_EXTRACT = 24
        private const val REQUEST_OUTPUT_TREE = 25
        private const val REQUEST_VERIFY = 26
    }
}
