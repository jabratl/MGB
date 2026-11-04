package com.jabra.mgb

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.PI
import kotlin.math.sin

/** S21e: when a download starts, a small download badge flies in an arc to the 3-dot menu button, which pops. */
object DlFly {
    @Volatile private var run: (() -> Unit)? = null
    @Volatile private var last = 0L

    /** Called from DownloadsApi.newItem (any thread). One animation per 600 ms, so a batch does not spam. */
    fun fire() {
        val r = run ?: return
        val now = System.currentTimeMillis()
        if (now - last < 600) return
        last = now
        Handler(Looper.getMainLooper()).post { try { r() } catch (e: Exception) { /* look only */ } }
    }

    fun attach(act: Activity, target: View) {
        run = { play(act, target) }
    }

    fun detach() { run = null }

    private fun play(act: Activity, target: View) {
        if (act.isFinishing || target.width == 0) return
        if (TabPrefs.reduce(act)) return // S21f
        val d = act.resources.displayMetrics.density
        val root = act.window.decorView as? ViewGroup ?: return
        val size = (44 * d).toInt()
        val badge = ImageView(act).apply {
            setImageResource(R.drawable.ic_download)
            setColorFilter(Color.WHITE)
            val p = (11 * d).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(act.getColor(R.color.mg_primary)) }
            elevation = 12 * d
            alpha = 0f
        }
        root.addView(badge, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))
        val t = IntArray(2)
        target.getLocationOnScreen(t)
        val x0 = root.width / 2f - size / 2f
        val y0 = root.height * 0.55f
        val x1 = t[0] + target.width / 2f - size / 2f
        val y1 = t[1] + target.height / 2f - size / 2f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                val f = it.animatedValue as Float
                badge.translationX = x0 + (x1 - x0) * f
                badge.translationY = y0 + (y1 - y0) * f - sin(PI * f).toFloat() * 70 * d
                val s = 1f - 0.65f * f
                badge.scaleX = s; badge.scaleY = s
                badge.alpha = if (f < 0.15f) f / 0.15f else if (f > 0.85f) (1f - f) / 0.15f else 1f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    root.removeView(badge)
                    target.animate().cancel()
                    target.scaleX = 1.35f; target.scaleY = 1.35f
                    target.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.5f)).setDuration(300).start()
                }
            })
            start()
        }
    }
}
