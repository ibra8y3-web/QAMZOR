package org.qamzor.android

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import org.qamzor.core.ArchiveEngine
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var pendingArchive: File? = null
    private var pendingExtraction: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 34, 28, 28)
            setBackgroundColor(0xFFF6F7FB.toInt())
        }
        val title = TextView(this).apply {
            text = "QAMZOR"
            textSize = 30f
            setTextColor(0xFF18233A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 8)
        }
        val subtitle = TextView(this).apply {
            text = "أدوات إنشاء وقراءة أرشيفات QZRA"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF526078.toInt())
            setPadding(0, 0, 0, 24)
        }
        status = TextView(this).apply {
            text = "اختر عملية للبدء. تتم عمليات الملفات من خلال نافذة اختيار ملفات النظام."
            textSize = 14f
            setTextColor(0xFF25324A.toInt())
            setPadding(16, 18, 16, 18)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(actionButton("إنشاء أرشيف QZRA", "اختر ملفًا أو أكثر لإنشاء أرشيف") { pickSources() })
        root.addView(actionButton("فحص أرشيف والتحقق من سلامته", "اختر ملف .qzra للفحص") { pickArchive(REQUEST_VERIFY) })
        root.addView(actionButton("استخراج أرشيف", "اختر ملف .qzra ثم مجلد الحفظ") { pickArchive(REQUEST_EXTRACT) })
        root.addView(status, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 18 })
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun actionButton(label: String, description: String, action: () -> Unit): Button = Button(this).apply {
        text = "$label\n$description"
        isAllCaps = false
        textSize = 15f
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 }
    }

    private fun pickSources() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_SOURCES)
    }

    private fun pickArchive(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("SAF callbacks are kept compatible with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        try {
            when (requestCode) {
                REQUEST_SOURCES -> createFromSelection(data)
                REQUEST_SAVE_ARCHIVE -> savePendingArchive(data.data)
                REQUEST_VERIFY -> verifySelectedArchive(data.data)
                REQUEST_EXTRACT -> {
                    val selected = data.data ?: error("No archive selected")
                    status.text = "جارٍ قراءة الأرشيف المحدد..."
                    Thread {
                        try {
                            pendingExtraction = copyUriToCache(selected, "selected-archive.qzra")
                            runOnUiThread {
                                status.text = "تم اختيار الأرشيف. اختر مجلد الوجهة لإتمام الاستخراج."
                                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_OUTPUT_TREE)
                            }
                        } catch (error: Exception) {
                            runOnUiThread { status.text = "تعذرت قراءة الأرشيف: ${error.message}" }
                        }
                    }.start()
                }
                REQUEST_OUTPUT_TREE -> {
                    data.data?.let { uri ->
                        val permissionFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        try { contentResolver.takePersistableUriPermission(uri, permissionFlags) } catch (_: Exception) { }
                    }
                    extractToTree(data.data)
                }
            }
        } catch (error: Exception) {
            status.text = "فشلت العملية: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun createFromSelection(data: Intent) {
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let(uris::add)
        require(uris.isNotEmpty()) { "لم يتم اختيار ملفات" }
        status.text = "جارٍ قراءة الملفات وإنشاء الأرشيف..."
        Thread {
            try {
                val sourceDir = File(cacheDir, "selected-sources").apply { deleteRecursively(); mkdirs() }
                val usedNames = HashSet<String>()
                val files = uris.mapIndexed { index, uri ->
                    val original = safeName(displayName(uri) ?: "source-$index")
                    var name = original
                    var suffix = 1
                    while (!usedNames.add(name.lowercase())) {
                        val dot = original.lastIndexOf('.')
                        name = if (dot > 0) original.substring(0, dot) + "_${suffix}" + original.substring(dot) else "${original}_${suffix}"
                        suffix++
                    }
                    copyUriTo(uri, File(sourceDir, name))
                }
                val output = File(cacheDir, "QAMZOR-${System.currentTimeMillis()}.qzra")
                val result = ArchiveEngine.create(output, files)
                pendingArchive = output
                runOnUiThread {
                    status.text = "تم إنشاء أرشيف QZRA 1.1 بنجاح.\nالعناصر: ${result.entries}\nالملفات: ${result.files}\nالحجم الأصلي: ${result.originalBytes} بايت\nاختر مكان حفظ الأرشيف."
                    val save = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, output.name)
                    }
                    startActivityForResult(save, REQUEST_SAVE_ARCHIVE)
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "فشل إنشاء الأرشيف: ${error.message ?: error.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun savePendingArchive(uri: Uri?) {
        val archive = pendingArchive ?: error("No archive is waiting to be saved")
        val destination = uri ?: error("No destination selected")
        status.text = "جارٍ حفظ الأرشيف..."
        Thread {
            try {
                val output = contentResolver.openOutputStream(destination, "w") ?: error("Cannot open selected destination")
                output.use { stream -> archive.inputStream().use { it.copyTo(stream) } }
                runOnUiThread {
                    status.text = "تم حفظ الأرشيف بنجاح: ${archive.name}"
                    pendingArchive = null
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "فشل حفظ الأرشيف: ${error.message ?: error.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun verifySelectedArchive(uri: Uri?) {
        val selected = uri ?: error("No archive selected")
        status.text = "جارٍ فحص الأرشيف والتحقق من سلامته..."
        Thread {
            try {
                val archive = copyUriToCache(selected, "verify-input.qzra")
                val result = ArchiveEngine.verify(archive)
                val entries = ArchiveEngine.list(archive).take(30).joinToString("\n") { "${it.type} · ${it.size} B · ${it.path}" }
                runOnUiThread { status.text = "التحقق ناجح ✓\nالإصدار: ${result.version}\nالملفات المفحوصة: ${result.filesChecked}\nالبيانات المفحوصة: ${result.bytesChecked} بايت\n\n$entries" }
            } catch (error: Exception) {
                runOnUiThread { status.text = "فشل التحقق: ${error.message ?: error.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun extractToTree(treeUri: Uri?) {
        val archive = pendingExtraction ?: error("No archive selected")
        val destination = treeUri ?: error("No output folder selected")
        status.text = "جارٍ التحقق والاستخراج إلى المجلد المحدد..."
        Thread {
            try {
                val temporary = File(cacheDir, "qamzor-extract-${System.currentTimeMillis()}").apply { deleteRecursively(); mkdirs() }
                val extracted = ArchiveEngine.extract(archive, temporary)
                val tree = DocumentFile.fromTreeUri(this, destination) ?: error("Cannot access destination folder")
                val entries = ArchiveEngine.list(archive)
                for (entry in entries.filter { it.type == "directory" }) {
                    var parent = tree
                    for (part in entry.path.split('/')) parent = parent.findFile(part) ?: parent.createDirectory(part) ?: error("Cannot create folder $part")
                }
                for (file in extracted) {
                    val relative = temporary.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    val parts = relative.split('/')
                    var parent = tree
                    for (part in parts.dropLast(1)) parent = parent.findFile(part) ?: parent.createDirectory(part) ?: error("Cannot create folder $part")
                    require(parent.findFile(parts.last()) == null) { "Destination already contains ${parts.last()}" }
                    val target = parent.createFile("application/octet-stream", parts.last()) ?: error("Cannot create output file")
                    val output = contentResolver.openOutputStream(target.uri, "w") ?: error("Cannot open output file")
                    output.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                }
                temporary.deleteRecursively()
                runOnUiThread {
                    status.text = "اكتمل الاستخراج والتحقق.\nعدد الملفات: ${extracted.size}\nالمجلد: ${tree.name}"
                    pendingExtraction = null
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "فشل الاستخراج: ${error.message ?: error.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun copyUriToCache(uri: Uri, name: String): File = copyUriTo(uri, File(cacheDir, safeName(name)))

    private fun copyUriTo(uri: Uri, target: File): File {
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read selected document" }
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

    private fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[/\\\\\u0000-\u001F]"), "_").ifBlank { "file.bin" }

    companion object {
        private const val REQUEST_SOURCES = 10
        private const val REQUEST_SAVE_ARCHIVE = 11
        private const val REQUEST_VERIFY = 12
        private const val REQUEST_EXTRACT = 13
        private const val REQUEST_OUTPUT_TREE = 14
    }
}
