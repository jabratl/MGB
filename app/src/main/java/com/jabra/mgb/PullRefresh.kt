package com.jabra.mgb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * S45: pull down at the top of a page = reload. No extra library: a touch listener on each tab WebView
 * (never consumes events) + one small round arrow drawn over the page while pulling.
 * Starts only when the page is scrolled to the very top, one finger, mostly vertical. Off in Settings > General.
 */
object PullRefresh {
    fun enabled(ctx: Context) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean("pull_refresh", true)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putBoolean("pull_refresh", on).apply()

    @SuppressLint("ClickableViewAccessibility")
    fun attach(web: WebView, container: FrameLayout, onRefresh: () -> Unit) {
        val ctx = web.context
        val dm = ctx.resources.displayMetrics
        val dp = dm.density
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val trigger = 96 * dp
        val maxPull = 150 * dp
        var downY = 0f
        var downX = 0f
        var pulling = false
        var armed = false // gesture began at the top of the page
        var dist = 0f
        var ind: ImageView? = null

        fun make(): ImageView = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_reload)
            setColorFilter(ContextCompat.getColor(ctx, R.color.mg_primary))
            val p = (9 * dp).toInt()
            setPadding(p, p, p, p)
            elevation = 6 * dp
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(ctx, R.color.mg_surface)) }
            alpha = 0f
        }

        fun finishIndicator(refresh: Boolean) {
            val v = ind ?: return
            ind = null
            if (refresh) {
                v.animate().translationY(trigger * 0.75f).rotationBy(360f).setDuration(350).withEndAction {
                    v.animate().alpha(0f).setDuration(200).withEndAction { container.removeView(v) }.start()
                }.start()
            } else {
                v.animate().translationY(0f).alpha(0f).setDuration(180).withEndAction { container.removeView(v) }.start()
            }
        }

        web.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; pulling = false; dist = 0f
                    armed = enabled(ctx) && web.scrollY == 0 && web.url?.startsWith(ContentInjector.EXT_ORIGIN) != true && SocialPull.allowed(ctx, web.url)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (armed && e.pointerCount == 1) {
                        val dy = e.y - downY
                        val dx = e.x - downX
                        if (!pulling) {
                            if (dy < -slop || Math.abs(dx) > Math.abs(dy) * 1.2f && Math.abs(dx) > slop) armed = false // went up / sideways
                            else if (dy > slop * 2 && web.scrollY == 0) {
                                pulling = true
                                ind = make().also { container.addView(it, FrameLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
                            }
                        }
                        if (pulling) {
                            dist = (dy - slop * 2).coerceAtLeast(0f)
                            val shown = (maxPull * (1f - 1f / (1f + dist / maxPull))).coerceAtMost(maxPull)
                            ind?.let {
                                it.translationY = shown - 40 * dp + 8 * dp
                                it.alpha = (dist / trigger).coerceIn(0f, 1f)
                                it.rotation = dist * 1.4f
                            }
                        }
                    } else if (e.pointerCount > 1 && pulling) {
                        pulling = false; armed = false; finishIndicator(false)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) {
                        val go = e.actionMasked == MotionEvent.ACTION_UP && dist >= trigger
                        finishIndicator(go)
                        if (go) onRefresh()
                    }
                    pulling = false; armed = false
                }
            }
            false // never consume: the page keeps its own touch handling
        }
    }
}
