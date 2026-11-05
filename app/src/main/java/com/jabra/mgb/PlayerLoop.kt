package com.jabra.mgb

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * PLAYER PLAN P12: A-B loop state + the two marks drawn over the seek bar. Not saved (a loop belongs to one video).
 * a / b = positions in ms, -1 = not set. Playback wraps from b to a (PlayerActivity.loopRun, 100 ms while a loop is active).
 */
class PlayerLoop {
    var a = -1L
    var b = -1L

    val active: Boolean get() = a >= 0L && b > a

    fun clear() { a = -1L; b = -1L }

    /** Marks over the seek bar: two 2 dp accent ticks, 10 dp high. Not clickable, so the seek bar below keeps every touch. */
    class Marks(private val act: PlayerActivity) : View(act) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = act.getColor(R.color.mg_primary) }
        private var pa = -1L
        private var pb = -1L
        private var dur = 0L

        fun update(la: Long, lb: Long, d: Long) {
            if (la == pa && lb == pb && d == dur) return
            pa = la
            pb = lb
            dur = d
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            if (dur <= 0L) return
            val pad = Util.dp(act, 10).toFloat()
            val w = width - 2f * pad
            val cy = height / 2f
            val hh = Util.dp(act, 10) / 2f
            val tw = Util.dp(act, 2).toFloat()
            for (t in longArrayOf(pa, pb)) {
                if (t < 0L) continue
                val x = pad + w * (t.toFloat() / dur).coerceIn(0f, 1f)
                c.drawRect(x - tw / 2f, cy - hh, x + tw / 2f, cy + hh, paint)
            }
        }
    }
}
