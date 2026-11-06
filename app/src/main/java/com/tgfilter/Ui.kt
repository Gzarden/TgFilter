package com.tgfilter

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Цвета: светлая и тёмная тема выбираются по настройке телефона. */
object Pal {
    var dark = false

    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    val bg: Int get() = if (dark) 0xFF0E1013.toInt() else 0xFFF1F2F6.toInt()
    val card: Int get() = if (dark) 0xFF1A1D23.toInt() else 0xFFFFFFFF.toInt()
    val chip: Int get() = if (dark) 0xFF2A2E37.toInt() else 0xFFE6E8EF.toInt()
    val text: Int get() = if (dark) 0xFFECEEF2.toInt() else 0xFF14161B.toInt()
    val sub: Int get() = if (dark) 0xFF9AA1AD.toInt() else 0xFF6A7180.toInt()
    val accent: Int get() = if (dark) 0xFF8FA3FF.toInt() else 0xFF3F57E0.toInt()
    val onAccent: Int get() = if (dark) 0xFF0E1013.toInt() else 0xFFFFFFFF.toInt()
    val good: Int get() = if (dark) 0xFF5FD08A.toInt() else 0xFF1E9E55.toInt()
    val warn: Int get() = if (dark) 0xFFF0B84D.toInt() else 0xFFC77D00.toInt()
    val bad: Int get() = if (dark) 0xFFFF7A7A.toInt() else 0xFFD93636.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
    return g
}

fun Context.label(text: String, sizeSp: Float = 15f, color: Int = Pal.text, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sizeSp
    t.setTextColor(color)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    return t
}

fun Context.vbox(padH: Int = 0, padV: Int = 0): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    l.setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

fun Context.card(): LinearLayout {
    val l = vbox(16, 14)
    l.background = rounded(Pal.card, 18)
    return l
}

fun Context.pill(text: String, filled: Boolean, onClick: () -> Unit): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = 14f
    t.gravity = Gravity.CENTER
    t.typeface = Typeface.DEFAULT_BOLD
    t.setTextColor(if (filled) Pal.onAccent else Pal.accent)
    t.setPadding(dp(16), dp(10), dp(16), dp(10))
    val base = if (filled) rounded(Pal.accent, 20) else rounded(0, 20, Pal.accent, 1)
    t.background = RippleDrawable(ColorStateList.valueOf(0x33808080), base, null)
    t.setOnClickListener { onClick() }
    return t
}

/** Добавляет вид с отступом сверху (в dp). */
fun LinearLayout.put(v: View, topDp: Int = 12, width: Int = MATCH) {
    val lp = LinearLayout.LayoutParams(width, WRAP)
    lp.topMargin = context.dp(topDp)
    addView(v, lp)
}
