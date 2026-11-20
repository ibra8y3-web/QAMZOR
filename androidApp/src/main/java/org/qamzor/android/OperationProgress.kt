package org.qamzor.android

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.qamzor.core.ProgressListener
import java.util.concurrent.atomic.AtomicLong

/** Live progress of one background operation; written by the worker thread, read by the UI ticker. */
class OperationProgress(val label: String) : ProgressListener {
    val createdAt: Long = System.currentTimeMillis()
    private val processed = AtomicLong(0L)
    @Volatile private var total = 0L
    @Volatile private var phaseKey = ""
    @Volatile private var phaseStartedAt = System.currentTimeMillis()
    @Volatile private var cancelRequested = false

    override fun onPhase(phase: String, totalBytes: Long) {
        phaseKey = phase
        total = totalBytes
        processed.set(0L)
        phaseStartedAt = System.currentTimeMillis()
    }

    override fun onBytes(deltaBytes: Long) {
        processed.addAndGet(deltaBytes)
    }

    override fun isCancelled(): Boolean = cancelRequested

    fun cancel() {
        cancelRequested = true
    }

    val cancelled: Boolean get() = cancelRequested

    /** 0..100, or -1 while the amount of work is not known yet. */
    fun percent(): Int {
        val whole = total
        if (whole <= 0L) return -1
        return ((processed.get() * 100L) / whole).coerceIn(0L, 100L).toInt()
    }

    fun permille(): Int {
        val whole = total
        if (whole <= 0L) return 0
        return ((processed.get() * 1000L) / whole).coerceIn(0L, 1000L).toInt()
    }

    fun phaseName(): String = when (phaseKey) {
        "create" -> "إنشاء الأرشيف"
        "verify" -> "التحقق من السلامة"
        "extract" -> "الاستخراج"
        "split" -> "تقسيم الأرشيف"
        "join" -> "دمج الأجزاء"
        "recovery-create" -> "إنشاء بيانات الاسترجاع"
        "recovery-check" -> "فحص الكتل"
        "recovery-repair" -> "إصلاح الأرشيف"
        "duplicates" -> "مقارنة المحتوى"
        else -> "جارٍ التنفيذ"
    }

    /** Human readable "12.3 MB / 40 MB • 8.1 MB/s • about 4 s left". */
    fun detail(): String {
        val whole = total
        val done = processed.get()
        if (whole <= 0L) return "جارٍ تجهيز العملية…"
        val elapsed = (System.currentTimeMillis() - phaseStartedAt).coerceAtLeast(1L)
        val rate = done * 1000.0 / elapsed
        val text = StringBuilder(UiKit.formatBytes(done)).append(" / ").append(UiKit.formatBytes(whole))
        if (done > 0 && rate > 1.0) {
            text.append(" • ").append(UiKit.formatBytes(rate.toLong())).append("/ث")
            val remaining = ((whole - done) / rate).toLong()
            if (remaining in 1..86_399) text.append(" • المتبقي ").append(formatSeconds(remaining))
        }
        return text.toString()
    }

    fun headline(): String {
        val percent = percent()
        return if (percent < 0) "${phaseName()}…" else "${phaseName()} • $percent%"
    }

    private fun formatSeconds(seconds: Long): String = when {
        seconds < 60 -> "$seconds ث"
        seconds < 3600 -> "${seconds / 60} د ${seconds % 60} ث"
        else -> "${seconds / 3600} س ${(seconds % 3600) / 60} د"
    }
}

/** The thin strip under the pages that shows the percentage, speed and a cancel button while a task runs. */
class OperationStrip(private val ui: UiKit, private val onCancel: () -> Unit) {
    private val headline: TextView = ui.label("", 12.5f, 0xFFF4F7FF.toInt(), true)
    private val detail: TextView = ui.label("", 10.5f, UiKit.MUTED)
    private val bar: ProgressBar = ProgressBar(ui.context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF2A4678.toInt())
    }
    private val cancelButton: TextView = ui.button("إلغاء", danger = true) {
        onCancel()
    }

    val view: LinearLayout = ui.column().apply {
        visibility = View.GONE
        setBackgroundColor(0xFF10203D.toInt())
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        val top = ui.row()
        val texts = ui.column()
        texts.addView(headline)
        texts.addView(detail)
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        cancelButton.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
        top.addView(cancelButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(10) })
        addView(top, LinearLayout.LayoutParams(-1, -2))
        addView(bar, LinearLayout.LayoutParams(-1, ui.dp(8)).apply { topMargin = ui.dp(6) })
    }

    fun refresh(progress: OperationProgress?) {
        if (progress == null) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val percent = progress.percent()
        bar.isIndeterminate = percent < 0
        if (percent >= 0) bar.progress = progress.permille()
        headline.text = progress.label + " — " + progress.headline()
        detail.text = if (progress.cancelled) "جارٍ الإلغاء…" else progress.detail()
        cancelButton.isEnabled = !progress.cancelled
        cancelButton.alpha = if (progress.cancelled) 0.5f else 1f
        headline.gravity = Gravity.START
    }
}
