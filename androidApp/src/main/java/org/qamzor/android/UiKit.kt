package org.qamzor.android

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Small view helpers that match the existing QAMZOR look, shared by the feature screens. */
class UiKit(val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).toInt()

    fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    fun title(text: String): TextView = label(text, 17f, WHITE, true)
    fun muted(text: String): TextView = label(text, 11.5f, MUTED, false).apply { setPadding(0, dp(2), 0, dp(6)) }

    fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun button(caption: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView = TextView(context).apply {
        text = caption
        textSize = 12.5f
        gravity = Gravity.CENTER
        maxLines = 2
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(if (danger) 0xFFFFD0D0.toInt() else WHITE)
        background = rounded(if (danger) 0xFF7A2B35.toInt() else if (primary) 0xFF3F6FD8.toInt() else 0xFF263D63.toInt(), 12)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun input(hint: String, secret: Boolean = false, numeric: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        setHintTextColor(0xFF7F95BA.toInt())
        setTextColor(0xFFF4F7FF.toInt())
        textSize = 13.5f
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        setSingleLine(true)
        background = rounded(0xFF1D3152.toInt(), 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    fun checkBox(text: String, checked: Boolean): CheckBox = CheckBox(context).apply {
        this.text = text
        textSize = 12.5f
        isChecked = checked
        setTextColor(0xFFD8E5F8.toInt())
        buttonTintList = ColorStateList.valueOf(ACCENT)
    }

    /** A vertical radio list; [onChange] receives the chosen index. */
    fun radioGroup(options: List<String>, selected: Int, onChange: (Int) -> Unit): RadioGroup = RadioGroup(context).apply {
        orientation = RadioGroup.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        for ((index, caption) in options.withIndex()) {
            addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = caption
                textSize = 12.5f
                setTextColor(0xFFD8E5F8.toInt())
                buttonTintList = ColorStateList.valueOf(ACCENT)
                isChecked = index == selected
                setOnClickListener { onChange(index) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun space(height: Int): View = View(context).also { it.layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    class Panel(val dialog: Dialog, val body: LinearLayout)

    /** A centered card dialog with a title; add views to [Panel.body] then call [Dialog.show]. */
    fun panel(titleText: String, secure: Boolean = false): Panel {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = column().apply {
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = rounded(0xFF14223F.toInt(), 22)
        }
        card.addView(title(titleText).apply { setPadding(0, 0, 0, dp(8)) })
        dialog.setContentView(ScrollView(context).apply { addView(card) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout((context.resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (secure) setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        return Panel(dialog, card)
    }

    class SheetEntry(val glyph: String, val caption: String, val danger: Boolean = false, val run: () -> Unit)

    /** A bottom sheet of tappable rows, in the same style as the file action sheets. */
    fun sheet(titleText: String, subtitle: String, entries: List<SheetEntry>): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val radius = dp(22).toFloat()
        val box = column().apply {
            setPadding(dp(14), dp(16), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(0xFF14223F.toInt())
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        box.addView(label(titleText, 15f, WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true) })
        if (subtitle.isNotEmpty()) box.addView(label(subtitle, 11f, MUTED).apply { setPadding(0, dp(2), 0, dp(4)) })
        for (entry in entries) {
            val line = row().apply {
                setPaddingRelative(dp(6), 0, dp(6), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss(); entry.run() }
            }
            line.addView(label(entry.glyph, 18f, if (entry.danger) 0xFFFFB6B6.toInt() else 0xFFBBD2FF.toInt()).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(30), -2).apply { marginEnd = dp(10) })
            line.addView(label(entry.caption, 14f, if (entry.danger) 0xFFFFB6B6.toInt() else 0xFFF4F7FF.toInt()))
            box.addView(line, LinearLayout.LayoutParams(-1, dp(50)))
        }
        dialog.setContentView(ScrollView(context).apply { addView(box) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        return dialog
    }

    fun confirm(titleText: String, message: String, positive: String, danger: Boolean = false, onYes: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(titleText)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun message(titleText: String, message: String) {
        AlertDialog.Builder(context).setTitle(titleText).setMessage(message).setPositiveButton("حسنًا", null).show()
    }

    fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height)

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val MUTED = 0xFF9FB4D3.toInt()
        const val ACCENT = 0xFF4C8DFF.toInt()
        const val DANGER_TEXT = 0xFFFF9C9C.toInt()

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
