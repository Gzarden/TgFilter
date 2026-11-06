package com.tgfilter

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView

/** Обновление «потянуть вниз» для прокручиваемого содержимого, без внешних библиотек. */
class PullRefreshLayout(ctx: Context, private val onRefresh: () -> Unit) : FrameLayout(ctx) {

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val threshold = ctx.dp(72).toFloat()
    private val maxPull = ctx.dp(110).toFloat()
    private var startY = 0f
    private var pulling = false
    private var content: View? = null

    private val hint = TextView(ctx)

    init {
        hint.textSize = 13f
        hint.setTextColor(Pal.sub)
        hint.gravity = Gravity.CENTER
        hint.alpha = 0f
        addView(hint, FrameLayout.LayoutParams(MATCH, ctx.dp(44), Gravity.TOP))
    }

    fun setContent(v: View) {
        content = v
        addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val c = content ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pulling && ev.y - startY > slop && !c.canScrollVertically(-1)) {
                    pulling = true
                    startY = ev.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pulling = false
        }
        return pulling
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val c = content ?: return false
        if (!pulling) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val pull = ((ev.y - startY) * 0.5f).coerceIn(0f, maxPull)
                c.translationY = pull
                hint.text = if (pull >= threshold) "Отпустите, чтобы обновить" else "Потяните вниз, чтобы обновить"
                hint.alpha = (pull / threshold).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val trigger = ev.actionMasked == MotionEvent.ACTION_UP && c.translationY >= threshold
                c.animate().translationY(0f).setDuration(180).start()
                hint.animate().alpha(0f).setDuration(180).start()
                pulling = false
                if (trigger) onRefresh()
            }
        }
        return true
    }
}
