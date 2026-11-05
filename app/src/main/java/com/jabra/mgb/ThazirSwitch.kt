package com.jabra.mgb

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * APPEARANCE A1: the Thazir toggle. Drop-in MaterialSwitch (same API, same isChecked / listeners).
 * Track 48 x 28 dp pill: ON = solid mg_toggle_on, OFF = transparent with a 1.5 dp mg_text_dim outline (alpha 0x80).
 * Thumb = rounded square 20 dp, radius 7 dp: ON = white (mg_toggle_thumb) with a small check in mg_toggle_on, OFF = mg_text_dim.
 * Both are drawn here, so no tint lists are used (they are cleared).
 */
class ThazirSwitch(ctx: Context) : MaterialSwitch(ctx) {
    init {
        val d = ctx.resources.displayMetrics.density
        val on = ctx.getColor(R.color.mg_toggle_on)
        val thumbOn = ctx.getColor(R.color.mg_toggle_thumb)
        val dim = ctx.getColor(R.color.mg_text_dim)
        setThumbTintList(null)
        setTrackTintList(null)
        setTrackDecorationDrawable(null)
        setThumbIconDrawable(null)
        setThumbDrawable(ThumbDrawable(d, on, thumbOn, dim))
        setTrackDrawable(TrackDrawable(d, on, dim))
        setSwitchMinWidth((48 * d).toInt())
    }

    companion object {
        /** A10: gives a plain SwitchCompat the same look (violet pill, rounded-square thumb). No behaviour change. */
        fun tint(sw: SwitchCompat) {
            val ctx = sw.context
            val d = ctx.resources.displayMetrics.density
            val on = ctx.getColor(R.color.mg_toggle_on)
            sw.thumbTintList = null
            sw.trackTintList = null
            sw.thumbDrawable = ThumbDrawable(d, on, ctx.getColor(R.color.mg_toggle_thumb), ctx.getColor(R.color.mg_text_dim))
            sw.trackDrawable = TrackDrawable(d, on, ctx.getColor(R.color.mg_text_dim))
            sw.switchMinWidth = (48 * d).toInt()
        }
    }

    private abstract class StateDrawable : Drawable() {
        var checked = false
        override fun isStateful(): Boolean = true
        override fun onStateChange(state: IntArray): Boolean {
            val c = state.contains(android.R.attr.state_checked)
            if (c == checked) return false
            checked = c
            return true
        }
        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(colorFilter: ColorFilter?) { }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class TrackDrawable(val d: Float, val on: Int, val dim: Int) : StateDrawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth(): Int = (48 * d).toInt()
        override fun getIntrinsicHeight(): Int = (28 * d).toInt()
        override fun draw(c: Canvas) {
            val b = bounds
            val r = RectF(b)
            if (checked) {
                p.style = Paint.Style.FILL; p.color = on
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p)
            } else {
                val sw = 1.5f * d
                r.inset(sw / 2f, sw / 2f)
                p.style = Paint.Style.STROKE; p.strokeWidth = sw
                p.color = (dim and 0x00FFFFFF) or (0x80 shl 24)
                c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p)
            }
        }
    }

    private class ThumbDrawable(val d: Float, val on: Int, val thumbOn: Int, val dim: Int) : StateDrawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        // 24 dp box (2 dp free space each side) so 2 x thumb width = 48 dp = the track width
        override fun getIntrinsicWidth(): Int = (24 * d).toInt()
        override fun getIntrinsicHeight(): Int = (24 * d).toInt()
        override fun draw(c: Canvas) {
            val b = bounds
            val s = 20f * d
            val l = b.exactCenterX() - s / 2f
            val t = b.exactCenterY() - s / 2f
            p.style = Paint.Style.FILL; p.color = if (checked) thumbOn else dim
            c.drawRoundRect(RectF(l, t, l + s, t + s), 7f * d, 7f * d, p)
            if (checked) {
                path.reset()
                path.moveTo(l + s * 0.27f, t + s * 0.52f)
                path.lineTo(l + s * 0.44f, t + s * 0.69f)
                path.lineTo(l + s * 0.74f, t + s * 0.33f)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = on
                p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                c.drawPath(path, p)
                p.strokeCap = Paint.Cap.BUTT; p.strokeJoin = Paint.Join.MITER
            }
        }
    }
}
