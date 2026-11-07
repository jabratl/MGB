package com.jabra.mgb

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/**
 * 0.0.294: Chrome-style page swipe. Start at the LEFT screen edge and drag right = Back, start at the RIGHT edge and drag left = Forward.
 * A round arrow follows the finger; let go after 80 dp (or when the arrow is full) and the page goes back / forward.
 * Works in the page area only (not on the bar). Off in video fullscreen. Setting: ap_swipe_nav (default ON).
 * Gesture navigation: the edge band (200 dp, lower middle of the screen) is excluded from the system back gesture so this one gets the swipe; elsewhere the system back gesture still works.
 */
object EdgeSwipe {
    private var side = 0          // -1 = left edge (back), +1 = right edge (forward), 0 = idle
    private var downX = 0f
    private var downY = 0f
    private var tracking = false  // true once the swipe is clearly horizontal: children got ACTION_CANCEL, we own the touch
    private var chip: FrameLayout? = null
    private var arrow: ImageView? = null

    /** Call from Activity.dispatchTouchEvent first. Returns true when the event is ours. cancel(ev) must send ev to super.dispatchTouchEvent. */
    fun handle(a: Activity, tm: TabManager, ev: MotionEvent, cancel: (MotionEvent) -> Unit): Boolean {
        if (!AppearancePrefs.swipeNav(a)) return false
        val w = a.window.decorView.width
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset(a)
                val t = tm.active
                if (t == null || tm.inFullscreen || ev.pointerCount != 1) return false
                val edge = Util.dp(a, 24)
                val s = if (ev.rawX <= edge) -1 else if (ev.rawX >= w - edge) 1 else 0
                if (s == 0) return false
                val bar = a.findViewById<View>(R.id.bottom_bar)
                if (bar != null && bar.visibility == View.VISIBLE) { // not on the address bar
                    val loc = IntArray(2); bar.getLocationOnScreen(loc)
                    if (ev.rawY >= loc[1] && ev.rawY <= loc[1] + bar.height) return false
                }
                side = s; downX = ev.rawX; downY = ev.rawY
                return false // the page still gets the DOWN until we know it is a swipe
            }
            MotionEvent.ACTION_MOVE -> {
                if (side == 0) return false
                if (ev.pointerCount != 1) { reset(a); return false }
                val dx = (ev.rawX - downX) * -side // positive = moving inward from the edge
                val dy = ev.rawY - downY
                if (!tracking) {
                    if (abs(dy) > Util.dp(a, 24) && abs(dy) > abs(dx)) { side = 0; return false } // vertical scroll: not ours
                    if (dx > Util.dp(a, 14) && dx > abs(dy) * 1.5f) {
                        tracking = true
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL; cancel(c); c.recycle()
                        showChip(a)
                    } else return false
                }
                moveChip(a, dx, tm)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) { side = 0; return false }
                val dx = (ev.rawX - downX) * -side
                val s = side
                val go = dx > Util.dp(a, 80)
                hideChip(a, go)
                reset(a, keepChip = true)
                if (go) tm.active?.let { t ->
                    if (s < 0) { if (tm.canBack(t)) tm.back(t) } else { if (tm.canFwd(t)) tm.fwd(t) }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { val was = tracking; hideChip(a, false); reset(a, keepChip = true); return was }
        }
        return tracking
    }

    /** Keep the system back gesture off a 200 dp band on both edges so our swipe (and Forward) can start there. Call after the layout exists. */
    fun attach(a: Activity) {
        reset(a)
        val d = a.window.decorView
        d.post {
            if (android.os.Build.VERSION.SDK_INT < 29) return@post
            try {
                val h = d.height; val w = d.width
                val band = Util.dp(a, 200)
                val top = (h * 0.5f).toInt()
                d.systemGestureExclusionRects =
                    if (AppearancePrefs.swipeNav(a)) listOf(Rect(0, top, Util.dp(a, 28), top + band), Rect(w - Util.dp(a, 28), top, w, top + band)) else emptyList()
            } catch (_: Exception) { }
        }
    }

    private fun reset(a: Activity, keepChip: Boolean = false) {
        side = 0; tracking = false
        if (!keepChip) { (chip?.parent as? ViewGroup)?.removeView(chip); chip = null; arrow = null }
    }

    private fun showChip(a: Activity) {
        val host = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        (chip?.parent as? ViewGroup)?.removeView(chip)
        val size = Util.dp(a, 44)
        val c = FrameLayout(a).apply {
            elevation = Util.dp(a, 4).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(a.getColor(R.color.mg_surface))
                setStroke(Util.dp(a, 1), a.getColor(R.color.mg_outline))
            }
            alpha = 0f
        }
        val img = ImageView(a).apply {
            setImageResource(if (side < 0) R.drawable.ic_back else R.drawable.ic_forward)
            setColorFilter(a.getColor(R.color.mg_text))
            setPadding(Util.dp(a, 11), Util.dp(a, 11), Util.dp(a, 11), Util.dp(a, 11))
        }
        c.addView(img, FrameLayout.LayoutParams(size, size))
        host.addView(c, FrameLayout.LayoutParams(size, size, Gravity.TOP or (if (side < 0) Gravity.START else Gravity.END)))
        c.translationX = if (side < 0) -size.toFloat() else size.toFloat() // starts fully outside the screen edge
        chip = c; arrow = img
    }

    private fun moveChip(a: Activity, dx: Float, tm: TabManager) {
        val c = chip ?: return
        val size = Util.dp(a, 44)
        val max = Util.dp(a, 80).toFloat()
        val p = (dx / max).coerceIn(0f, 1f)
        val t = tm.active
        val can = t != null && (if (side < 0) tm.canBack(t) else tm.canFwd(t))
        c.alpha = (if (can) 1f else 0.45f) * (0.35f + 0.65f * p)
        // slides in from outside the edge, stops with a 12 dp margin when full
        val shown = (size + Util.dp(a, 12)) * p - size
        c.translationX = if (side < 0) shown else -shown
        c.scaleX = 0.85f + 0.15f * p; c.scaleY = c.scaleX
        val loc = IntArray(2); (c.parent as? View)?.getLocationOnScreen(loc)
        c.translationY = downY - size / 2f - loc[1] // follows the height where the finger started
    }

    private fun hideChip(a: Activity, go: Boolean) {
        val c = chip ?: return
        val size = Util.dp(a, 44)
        val reduce = TabPrefs.reduce(a)
        c.animate().cancel()
        if (reduce) { (c.parent as? ViewGroup)?.removeView(c); return }
        c.animate().alpha(0f).translationX(if (side < 0) (if (go) c.translationX else -size.toFloat()) else (if (go) c.translationX else size.toFloat()))
            .setDuration(if (go) 140 else 180).withEndAction { (c.parent as? ViewGroup)?.removeView(c) }.start()
    }
}
