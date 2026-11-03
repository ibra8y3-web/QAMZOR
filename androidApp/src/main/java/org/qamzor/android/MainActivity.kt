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
    private var protectArchiveWithPassword = false
    private var hideArchiveMetadata = false
    private val operationHistory = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val openedFolderPasswords: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())
    @Volatile private var activeOperationLabel: String? = null
    @Volatile private var activeOperationStartedAt: Long? = null
    private var protectArchiveCheckBox: CheckBox? = null
    private var hideMetadataCheckBox: CheckBox? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreOperationHistory()
        buildInterface()
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
    }

    private fun buildInterface() {
        window.statusBarColor = 0xFF101D36.toInt()
        window.navigationBarColor = 0xFF101D36.toInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            background = rounded(0xFF0B1730.toInt(), 0)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(0xFF0B1730.toInt())
            addView(root)
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(14))
        }
        val mark = TextView(this).apply {
            text = "▣"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFBBD2FF.toInt())
            background = rounded(0xFF263F70.toInt(), 15)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        brandText.addView(TextView(this).apply {
            text = "QAMZOR"
            textSize = 27f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF7FAFF.toInt())
        })
        brandText.addView(TextView(this).apply {
            text = "THE NEXT ARCHIVE EXPERIENCE"
            textSize = 9f
            setTextColor(0xFF9FB5D9.toInt())
            letterSpacing = .07f
        })
        brand.addView(mark)
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(brand)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(0xFF1C2D50.toInt(), 18)
        }
        hero.addView(TextView(this).apply {
            text = "مساحة الأرشيف الذكية"
            textSize = 12f
            setTextColor(0xFFB7C9E8.toInt())
        })
        hero.addView(TextView(this).apply {
            text = "إدارة ملفاتك بذكاء"
            textSize = 23f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, dp(5), 0, dp(4))
        })
        hero.addView(TextView(this).apply {
            text = "إنشاء وفحص واستعراض وتحويل أرشيفات QZRA من مكان واحد."
            textSize = 13f
            setTextColor(0xFFD4E0F5.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        root.addView(sectionLabel("الأدوات الرئيسية"))
        addTileRow(root,
            dashboardTile("إضافة ملفات", "تحديد ملفات متعددة", "file") { pickFiles() },
            dashboardTile("استعراض الأرشيف", "تصفح المحتويات", "folder_search") { pickArchive(REQUEST_BROWSE) })
        addTileRow(root,
            dashboardTile("تحويل إلى QZRA", "صيغة البرنامج", "convert") { convertSelectedToQzra() },
            dashboardTile("فحص السلامة", "تحقق SHA-256", "shield_check") { pickArchive(REQUEST_VERIFY) })
        addTileRow(root,
            dashboardTile("استخراج الأرشيف", "كامل أو انتقائي", "extract") { pickArchive(REQUEST_EXTRACT) },
            dashboardTile("إضافة مجلد", "المجلدات الفرعية", "folder_add") { pickFolder() })
        addTileRow(root,
            dashboardTile("مقارنة أرشيفين", "SHA-256 للمحتوى", "compare") { pickArchiveComparison() },
            dashboardTile("مركز العمليات", "الحالة وسجل التنفيذ", "activity") { showOperationCenter() })

        root.addView(sectionLabel("مصادر التحويل"))
        val sourcesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xFF142441.toInt(), 16)
        }
        val sourcesHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourcesHeader.addView(TextView(this).apply {
            text = "العناصر المحددة"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFE7EFFD.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sourcesHeader.addView(TextView(this).apply {
            text = "تُحفظ كما هي داخل الحاوية"
            textSize = 10f
            setTextColor(0xFF9FB4D3.toInt())
        })
        sourcesPanel.addView(sourcesHeader)
        sourcesListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        sourcesPanel.addView(sourcesListContainer)
        sourcesPanel.addView(TextView(this).apply {
            text = "سيتم حفظ البيانات الأصلية كما هي داخل الحاوية. لا يتم تعديل ملفات المصدر."
            textSize = 11f
            setTextColor(0xFF91A8CA.toInt())
            setPadding(dp(2), dp(7), dp(2), dp(2))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        protectArchiveCheckBox = CheckBox(this).apply {
            text = "حماية أرشيف QZRA بكلمة مرور (اختياري)"
            textSize = 12f
            setTextColor(0xFFD8E5F8.toInt())
            buttonTintList = ColorStateList.valueOf(0xFF8FB4F4.toInt())
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                protectArchiveWithPassword = checked
                hideMetadataCheckBox?.isEnabled = checked
                if (!checked) hideMetadataCheckBox?.isChecked = false
            }
        }
        sourcesPanel.addView(protectArchiveCheckBox)
        hideMetadataCheckBox = CheckBox(this).apply {
            text = "إخفاء أسماء الملفات والبيانات — QZRA 1.3"
            textSize = 12f
            setTextColor(0xFFD8E5F8.toInt())
            buttonTintList = ColorStateList.valueOf(0xFF8FB4F4.toInt())
            isEnabled = protectArchiveWithPassword
            setOnCheckedChangeListener { _, checked -> hideArchiveMetadata = checked }
        }
        sourcesPanel.addView(hideMetadataCheckBox)
        root.addView(sourcesPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val sourceActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceActions.addView(compactAction("＋", "إضافة ملفات") { pickFiles() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        sourceActions.addView(compactAction("×", "مسح الاختيار") { clearSelection() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(sourceActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(sectionLabel("مركز العمليات"))
        status = infoPanel("جاهز للعمل. اختر إحدى الأدوات للبدء.")
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val healthCard = metricCard("حالة الأرشيف", "لم يُفحص", 0xFFCBD8EC.toInt())
        operationHealth = healthCard.second
        val filesCard = metricCard("الملفات المفحوصة", "—", 0xFFFFFFFF.toInt())
        verifiedFiles = filesCard.second
        val bytesCard = metricCard("البيانات المتحققة", "—", 0xFFFFFFFF.toInt())
        verifiedBytes = bytesCard.second
        metricsRow.addView(healthCard.first, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(4) })
        metricsRow.addView(filesCard.first, LinearLayout.LayoutParams(0, dp(76), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        metricsRow.addView(bytesCard.first, LinearLayout.LayoutParams(0, dp(76), 1f).apply { marginStart = dp(4) })
        root.addView(metricsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "QAMZOR  •  QZRA Archive System"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFF8299BE.toInt())
            setPadding(0, dp(14), 0, dp(2))
        })
        setContentView(scroll)
        refreshSelectionSummary()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun infoPanel(message: String) = TextView(this).apply {
        text = message
        textSize = 13f
        setTextColor(0xFFE0E9F8.toInt())
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(0xFF1A2A49.toInt(), 14)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(50)
    }

    private fun sectionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFFB9D0F7.toInt())
        setPadding(dp(2), dp(12), dp(2), dp(7))
    }

    private fun iconBadge(kind: String, backgroundColor: Int, tint: Int, size: Int): View = FrameLayout(this).apply {
        background = rounded(backgroundColor, 11)
        addView(QamzorLineIconView(this@MainActivity, kind, tint),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Small Lucide-inspired outline icons drawn locally; no external icon dependency. */
    private inner class QamzorLineIconView(context: Context, private val kind: String, tint: Int) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val shape = Path()

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
                else -> canvas.drawCircle(12f, 12f, 8f, stroke)
            }
            canvas.restore()
        }
    }

    private fun dashboardTile(title: String, description: String, symbol: String, action: () -> Unit): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(0xFF2D4B78.toInt(), 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            contentDescription = "$title. $description"
        }
        tile.addView(iconBadge(symbol, 0x554B73AA, 0xFFF7FAFF.toInt(), 36))
        tile.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, dp(1))
        })
        tile.addView(TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(0xFFD0DDF1.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        return tile
    }

    private fun addTileRow(parent: LinearLayout, first: View, second: View) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        row.addView(first, LinearLayout.LayoutParams(0, dp(122), 1f).apply { marginEnd = dp(6) })
        row.addView(second, LinearLayout.LayoutParams(0, dp(122), 1f).apply { marginStart = dp(6) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun compactAction(symbol: String, label: String, action: () -> Unit): View = TextView(this).apply {
        text = "$symbol  $label"
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(0xFFF4F7FF.toInt())
        background = rounded(0xFF263D63.toInt(), 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
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
                            if ((error.message.orEmpty().contains("encrypted", ignoreCase = true) && error.message.orEmpty().contains("password", ignoreCase = true)) || error.message.orEmpty().contains("QZRA 1.3", ignoreCase = true)) {
                                runOnUiThread { promptArchivePassword(archive) { requestSelectedExtraction(null) } }
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

    private fun showOperationCenter() {
        val snapshot = synchronized(operationHistory) { operationHistory.toList().takeLast(60) }
        val current = activeOperationLabel?.let {
            val elapsed = activeOperationStartedAt?.let { start -> ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0) } ?: 0
            "العملية النشطة: $it\nالمدة: ${elapsed} ثانية\n\n"
        } ?: "لا توجد عملية نشطة الآن.\n\n"
        val completed = snapshot.count { it.contains("اكتملت") }
        val failed = snapshot.count { it.contains("فشلت") }
        val interrupted = snapshot.count { it.contains("توقفت العملية") }
        val body = current + "إجمالي السجل: ${snapshot.size} | ناجحة: $completed | فاشلة: $failed | متوقفة: $interrupted\n\n" +
            if (snapshot.isEmpty()) "لا توجد عمليات مسجلة بعد." else snapshot.asReversed().joinToString("\n")
        AlertDialog.Builder(this).setTitle("مركز عمليات QAMZOR")
            .setMessage(body)
            .setPositiveButton("تحديث") { _, _ -> showOperationCenter() }
            .setNeutralButton("مسح السجل") { _, _ ->
                synchronized(operationHistory) {
                    operationHistory.clear()
                    operationPreferences().edit().remove("events").apply()
                }
                showOperationCenter()
            }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun verifySelectedArchive(uri: Uri?) {
        val selected = uri ?: error("لم يتم اختيار أرشيف")
        status.text = "جارٍ فحص جميع بيانات الأرشيف والتحقق من SHA-256..."
        resetVerificationMetrics()
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

    private fun convertSelectedToQzra() {
        val sources = synchronized(selectedSources) { selectedSources.toList() }
        require(sources.isNotEmpty()) { "أضف ملفًا واحدًا على الأقل أو مجلدًا قبل التحويل إلى QZRA" }
        if (protectArchiveWithPassword) {
            promptNewArchivePassword(sources)
        } else {
            createSelectedQzra(sources, null)
        }
    }

    private fun promptNewArchivePassword(sources: List<File>) {
        val password = EditText(this).apply {
            hint = "كلمة المرور الرئيسية (10 أحرف على الأقل)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val confirm = EditText(this).apply {
            hint = "تأكيد كلمة المرور الرئيسية"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val scopePath = EditText(this).apply {
            hint = "مسار مجلد للحماية المستقلة (اختياري، مثل Project/Vault)"
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val scopePassword = EditText(this).apply {
            hint = "كلمة مرور المجلد المستقلة (اختياري)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val scopeConfirm = EditText(this).apply {
            hint = "تأكيد كلمة مرور المجلد"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val hideMetadataOption = CheckBox(this).apply {
            text = "إخفاء أسماء الملفات والبيانات (QZRA 1.3)"
            textSize = 12f
            setTextColor(0xFFD8E5F8.toInt())
            buttonTintList = ColorStateList.valueOf(0xFF8FB4F4.toInt())
            isChecked = hideArchiveMetadata
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(2))
            addView(password); addView(confirm)
            addView(hideMetadataOption)
            addView(TextView(this@MainActivity).apply {
                text = "لحماية مجلد مستقل، أدخل مساره كما سيظهر داخل الأرشيف وكلمة مرور مختلفة عنه."
                textSize = 11f; setTextColor(0xFF9FB4D3.toInt()); setPadding(0, dp(8), 0, dp(2))
            })
            addView(scopePath); addView(scopePassword); addView(scopeConfirm)
        }
        AlertDialog.Builder(this)
            .setTitle("حماية أرشيف QZRA")
            .setMessage("تشفير AES-256-GCM. عند تفعيل QZRA 1.3 تُخفى أسماء ومسارات الملفات وأحجامها داخل Manifest مشفّر. كلمات المرور لا تُحفظ؛ احتفظ بها منفصلة.")
            .setView(fields)
            .setPositiveButton("إنشاء أرشيف محمي") { _, _ ->
                val first = password.text.toString()
                val second = confirm.text.toString()
                val path = scopePath.text.toString().trim().trim('/')
                val folderFirst = scopePassword.text.toString()
                val folderSecond = scopeConfirm.text.toString()
                when {
                    first.length < 10 -> status.text = "كلمة المرور الرئيسية قصيرة؛ استخدم 10 أحرف على الأقل."
                    first != second -> status.text = "كلمتا المرور الرئيسيتان غير متطابقتين."
                    path.isNotEmpty() && (folderFirst.length < 10 || folderFirst != folderSecond) -> status.text = "أدخل كلمة مرور مستقلة للمجلد من 10 أحرف على الأقل وأكدها بشكل صحيح."
                    path.isEmpty() && (folderFirst.isNotEmpty() || folderSecond.isNotEmpty()) -> status.text = "أدخل مسار المجلد المحمي أيضًا."
                    else -> createSelectedQzra(sources, first, hideMetadataOption.isChecked, if (path.isNotEmpty()) mapOf(path to folderFirst) else emptyMap())
                }
                password.text?.clear(); confirm.text?.clear()
                scopePassword.text?.clear(); scopeConfirm.text?.clear()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun createSelectedQzra(
        sources: List<File>, password: String?, encryptMetadata: Boolean = false,
        folderPasswords: Map<String, String> = emptyMap()
    ) {
        status.text = "جارٍ تحويل ${sources.size} مصدر إلى حاوية QZRA${if (password == null) "" else if (encryptMetadata || folderPasswords.isNotEmpty()) " 1.3 مشفّرة بالكامل" else " مشفّرة"}..."
        resetVerificationMetrics()
        runOnWorker("فشل تحويل العناصر إلى QZRA") {
            val output = File(cacheDir, "QAMZOR-${System.currentTimeMillis()}.qzra")
            val result = ArchiveEngine.convertToQzra(
                output, sources, password = password, encryptMetadata = encryptMetadata,
                folderPasswords = folderPasswords
            )
            val verified = ArchiveEngine.verify(output, password, folderPasswords)
            require(verified.valid && verified.filesChecked == result.files) { "تعذر التحقق من الأرشيف بعد التحويل" }
            val archiveEntries = ArchiveEngine.list(output, password, folderPasswords)
            require(archiveEntries.size == result.entries) { "عدد عناصر الأرشيف الناتج لا يطابق المصادر" }
            pendingArchive = output
            runOnUiThread {
                openedFolderPasswords.clear(); openedFolderPasswords.putAll(folderPasswords)
                refreshVerificationMetrics(verified.valid, verified.filesChecked, verified.bytesChecked)
                status.text = "اكتمل التحويل والتحقق ✓\nالإصدار: ${verified.version} • العناصر: ${result.entries} • الملفات: ${result.files} • الحجم: ${formatBytes(result.originalBytes)} ← ${formatBytes(result.storedBytes)}. اختر مكان حفظ ملف .qzra."
                val save = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
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
        runOnUiThread {
            sourcesListContainer.removeAllViews()
            if (sources.isEmpty()) {
                sourcesListContainer.addView(TextView(this).apply {
                    text = "لم تتم إضافة ملفات أو مجلدات بعد. أضف مصادر لتحويلها إلى أرشيف واحد."
                    textSize = 12f
                    setTextColor(0xFF9FB4D3.toInt())
                    setPadding(dp(4), dp(9), dp(4), dp(9))
                })
            } else {
                sources.forEach { source ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_RTL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        background = rounded(0xFF1D3152.toInt(), 12)
                    }
                    row.addView(
                        iconBadge(if (source.isDirectory) "folder" else "file", 0x334B73AA, 0xFFBBD2FF.toInt(), 36),
                        LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
                    )
                    val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
                    labels.addView(TextView(this).apply {
                        text = source.name
                        textSize = 12.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(0xFFF4F7FF.toInt())
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setSingleLine(true)
                    })
                    labels.addView(TextView(this).apply {
                        text = if (source.isDirectory) "مجلد كامل • ${source.list()?.size ?: 0} عناصر مباشرة"
                        else "ملف • ${formatBytes(source.length())}"
                        textSize = 10f
                        setTextColor(0xFF9FB4D3.toInt())
                        maxLines = 1
                    })
                    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    val remove = iconBadge("trash", 0x334E2937, 0xFFFFB6B6.toInt(), 34).apply {
                        isClickable = true
                        isFocusable = true
                        contentDescription = "حذف ${source.name} من قائمة التحويل"
                        setOnClickListener {
                            if (backgroundBusy) {
                                status.text = "هناك عملية ملفات قيد التنفيذ. حاول بعد انتهائها."
                            } else {
                                synchronized(selectedSources) { selectedSources.remove(source) }
                                source.deleteRecursively()
                                refreshSelectionSummary()
                                status.text = "تمت إزالة ${source.name} من مصادر التحويل."
                            }
                        }
                    }
                    row.addView(remove, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) })
                    sourcesListContainer.addView(row, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) })
                }
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
                openedFolderPasswords.clear()
                pendingExtraction = archive
                runOnUiThread {
                    status.text = "تم فتح الأرشيف. تتم قراءة الملفات من داخله دون فك الأرشيف بالكامل."
                    showVirtualExplorer("")
                }
            } catch (error: IllegalArgumentException) {
                if ((error.message.orEmpty().contains("encrypted", ignoreCase = true) && error.message.orEmpty().contains("password", ignoreCase = true)) || error.message.orEmpty().contains("QZRA 1.3", ignoreCase = true)) {
                    runOnUiThread { promptArchivePassword(archive) }
                } else throw error
            }
        }
    }

    private fun promptArchivePassword(archive: File, afterAuthenticated: (() -> Unit)? = null) {
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
                            openedFolderPasswords.clear()
                            pendingExtraction = archive
                            runOnUiThread {
                                status.text = "تمت مصادقة الأرشيف المشفّر بنجاح ✓"
                                if (afterAuthenticated != null) afterAuthenticated() else showVirtualExplorer("")
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setTitle("تعذر فتح الأرشيف")
                                    .setMessage(error.message ?: "كلمة المرور غير صحيحة أو الأرشيف تالف")
                                    .setPositiveButton("إعادة المحاولة") { _, _ -> promptArchivePassword(archive, afterAuthenticated) }
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
                synchronized(selectedSources) { selectedSources.clear() }
                stagingDirectory?.deleteRecursively()
                stagingDirectory = null
                protectArchiveWithPassword = false
                hideArchiveMetadata = false
                protectArchiveCheckBox?.isChecked = false
                hideMetadataCheckBox?.isChecked = false
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
