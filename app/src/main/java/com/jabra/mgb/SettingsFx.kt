package com.jabra.mgb

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils

/**
 * 0.0.285: motion + definition helpers for Settings. Every motion is skipped when Reduce motion is ON (TabPrefs.reduce).
 *  rise     = cards glide up and fade in one after another when Settings opens
 *  rows     = the rows of a category you just opened follow one after another
 *  ring     = the open category card gets its own colour ring (closed = hairline)
 *  hairline = a thin line under the search box that fades in while the list is scrolled
 */
object SettingsFx {
    private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun rise(ctx: Context, parent: ViewGroup) {
        if (TabPrefs.reduce(ctx)) return
        val dy = Util.dp(ctx, 14).toFloat()
        for (i in 0 until minOf(parent.childCount, 8)) {
            val v = parent.getChildAt(i)
            v.alpha = 0f
            v.translationY = dy
            v.animate().alpha(1f).translationY(0f).setStartDelay(40L + i * 35L).setDuration(300).setInterpolator(EASE)
                .withEndAction { v.animate().setStartDelay(0) }.start()
        }
    }

    fun rows(ctx: Context, content: ViewGroup) {
        if (TabPrefs.reduce(ctx)) return
        val dy = Util.dp(ctx, 6).toFloat()
        for (i in 0 until minOf(content.childCount, 10)) {
            val v = content.getChildAt(i)
            v.alpha = 0f
            v.translationY = dy
            v.animate().alpha(1f).translationY(0f).setStartDelay(90L + i * 22L).setDuration(200).setInterpolator(EASE)
                .withEndAction { v.animate().setStartDelay(0) }.start()
        }
    }

    fun tintOf(ctx: Context, key: String): Int {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return TabPrefs.tint(key, night)
    }

    fun ring(card: View?, tint: Int, open: Boolean, ctx: Context) {
        val d = card?.background as? GradientDrawable ?: return
        if (open) d.setStroke(Util.dp(ctx, 2), ColorUtils.setAlphaComponent(tint, 0xAA))
        else d.setStroke(Util.dp(ctx, 1), ColorUtils.setAlphaComponent(ctx.getColor(R.color.mg_outline), 0xD0))
    }

    fun hairline(ctx: Context, sv: ScrollView, line: View) {
        line.alpha = 0f
        val full = Util.dp(ctx, 24).toFloat()
        sv.setOnScrollChangeListener { _, _, y, _, _ -> line.alpha = (y / full).coerceIn(0f, 1f) }
    }
}
