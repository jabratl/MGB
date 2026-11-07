package com.jabra.mgb

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * 0.0.281: small pop-up bars ("Closed m.facebook.com  Undo") go away when the finger swipes them left or right.
 * Drag follows the finger and fades; past 35 % of the width, or a quick flick, it flies out; else it springs back.
 * Reduce motion = the fly-out has no animation. Tapping a button on the bar (Undo) still works: a drag only starts after the touch slop.
 */
object SwipeAway {
    private const val MIN_ALPHA = 0.25f

    /** Decides what happens when the finger lifts. Returns true when the bar was sent away. */
    private fun finish(v: View, dx: Float, velX: Float, reduce: Boolean, onGone: () -> Unit): Boolean {
        val far = abs(dx) > v.width * 0.35f
        val flick = abs(velX) > 900f && velX * dx > 0f
        if (far || flick) {
            val dir = if (dx >= 0f) 1f else -1f
            if (reduce) { onGone(); return true }
            v.animate().translationX(dir * v.width * 1.1f).alpha(0f).setDuration(140).withEndAction { onGone() }.start()
            return true
        }
        v.animate().translationX(0f).alpha(1f).setDuration(if (reduce) 0L else 160L).start()
        return false
    }

    private fun follow(v: View, dx: Float) {
        v.animate().cancel()
        v.translationX = dx
        v.alpha = (1f - abs(dx) / (v.width * 1.2f)).coerceIn(MIN_ALPHA, 1f)
    }

    /** For a Material Snackbar (its view): swipe on the text area; the action button keeps its own click. */
    fun snack(sb: com.google.android.material.snackbar.Snackbar, reduce: Boolean) {
        val v = sb.view
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        var downX = 0f
        var drag = false
        var vt: VelocityTracker? = null
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; drag = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev) }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    val dx = ev.rawX - downX
                    if (!drag && abs(dx) > slop) { drag = true; view.parent?.requestDisallowInterceptTouchEvent(true) }
                    if (drag) follow(view, dx)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (drag) { drag = false; finish(view, ev.rawX - downX, vx, reduce) { try { sb.dismiss() } catch (_: Exception) { } } }
                }
            }
            drag
        }
    }

    /** The custom bar of the tab grid. It steals the touch from its children (Undo) only after a sideways drag starts. */
    class Bar(ctx: Context, private val reduce: Boolean) : LinearLayout(ctx) {
        var onGone: (() -> Unit)? = null
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var vt: VelocityTracker? = null
        var busy = false
            private set

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; busy = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev) }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    val dx = ev.rawX - downX
                    if (!busy && abs(dx) > slop && abs(dx) > abs(ev.rawY - downY)) {
                        busy = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { busy = false; vt?.recycle(); vt = null }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; busy = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev); return true }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    val dx = ev.rawX - downX
                    if (!busy && abs(dx) > slop) busy = true
                    if (busy) follow(this, dx)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (busy) {
                        busy = false
                        finish(this, ev.rawX - downX, vx, reduce) { onGone?.invoke() }
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
