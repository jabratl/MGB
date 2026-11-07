package com.jabra.mgb

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/**
 * APPEARANCE A6: swipe on the address bar.
 *  - away from the page edge's opposite side (bottom bar: swipe DOWN, top bar: swipe UP) = page fullscreen (bar + system bars hidden, small restore handle)
 *  - the other way = tab switcher.
 * Each one only works when its Appearance toggle is ON (ap_swipe_fs / ap_swipe_tabs). Taps are untouched: a swipe needs 60 dp vertical movement.
 * This is PAGE fullscreen, not the video fullscreen (TabManager.inFullscreen).
 */
object BarGestures {
    /** true while the page is fullscreen (MainActivity.onScroll and back handling read this). */
    @Volatile var active = false
    private var handle: View? = null

    fun attach(a: Activity, tm: TabManager) {
        active = false; handle = null
        val bar = a.findViewById<ViewGroup>(R.id.bottom_bar) ?: return
        val top = AppearancePrefs.barTop(a)
        val need = Util.dp(a, 60)
        var downX = 0f
        var downY = 0f
        var fired = false
        val l = View.OnTouchListener { v, e ->
            if (SiteSheet.touch(a, tm, v, e)) return@OnTouchListener true // 0.0.287: lock tap
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fired = false; v === bar }
                MotionEvent.ACTION_MOVE -> {
                    if (fired) true else {
                        val dy = e.rawY - downY
                        val dx = e.rawX - downX
                        if (abs(dy) > need && abs(dy) > abs(dx) * 1.5f) {
                            val fsDir = if (top) dy < 0 else dy > 0
                            val ok = if (fsDir) AppearancePrefs.swipeFs(a) else AppearancePrefs.swipeTabs(a)
                            if (ok) {
                                fired = true
                                v.isPressed = false; v.cancelLongPress()
                                if (fsDir) enter(a) else tm.showSwitcher()
                            }
                            fired
                        } else false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val f = fired; fired = false; f }
                else -> fired
            }
        }
        bar.setOnTouchListener(l)
        for (i in 0 until bar.childCount) bar.getChildAt(i).setOnTouchListener(l)
    }

    fun enter(a: Activity) {
        if (active) return
        val bar = a.findViewById<View>(R.id.bottom_bar) ?: return
        active = true
        bar.visibility = View.GONE
        GroupStrip.sync()
        bars(a, false)
        showHandle(a)
    }

    fun exit(a: Activity) {
        if (!active) return
        active = false
        a.findViewById<View>(R.id.bottom_bar)?.visibility = View.VISIBLE
        GroupStrip.sync()
        bars(a, true)
        (handle?.parent as? ViewGroup)?.removeView(handle)
        handle = null
    }

    private fun bars(a: Activity, show: Boolean) {
        try {
            val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (show) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) { }
    }

    /** 72 x 44 dp tap target with a small translucent pill; sits where the bar was (bottom or top centre). */
    private fun showHandle(a: Activity) {
        val host = a.findViewById<FrameLayout>(R.id.web_container)?.parent as? FrameLayout ?: return
        val top = AppearancePrefs.barTop(a)
        val box = FrameLayout(a).apply {
            isClickable = true
            contentDescription = "Show address bar"
            setOnClickListener { exit(a) }
        }
        box.addView(View(a).apply {
            background = GradientDrawable().apply {
                cornerRadius = Util.dp(a, 3).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(a.getColor(R.color.mg_text_dim), 0xC0))
                setStroke(Util.dp(a, 1), androidx.core.graphics.ColorUtils.setAlphaComponent(a.getColor(R.color.mg_outline), 0x99))
            }
        }, FrameLayout.LayoutParams(Util.dp(a, 40), Util.dp(a, 6), Gravity.CENTER))
        handle = box
        host.addView(box, FrameLayout.LayoutParams(Util.dp(a, 72), Util.dp(a, 44),
            Gravity.CENTER_HORIZONTAL or (if (top) Gravity.TOP else Gravity.BOTTOM)))
    }
}
