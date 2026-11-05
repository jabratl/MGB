package com.jabra.mgb

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * PLAYER PLAN P8: helpers for hold-to-speed and pinch zoom. The touch layer itself stays in PlayerActivity.buildUi.
 * Prefs (mgb_ui): pl_hold_speed (float, default 2.0), pl_hold_slide (bool, default true). Settings UI for them comes in P13.
 */
object PlayerGestures {
    const val HOLD_MIN = 0.25f
    const val HOLD_MAX = 5f
    const val ZOOM_MIN = 0.5f
    const val ZOOM_MAX = 4f

    fun holdSpeed(a: PlayerActivity): Float = PlayerPrefs.getFloat(a, PlayerPrefs.K_HOLD_SPEED, 2f).coerceIn(HOLD_MIN, HOLD_MAX)

    fun holdSlide(a: PlayerActivity): Boolean = PlayerPrefs.getBool(a, PlayerPrefs.K_HOLD_SLIDE, true)

    /** cur plus steps of 0.1, kept in 0.25 .. 5.0 and on one decimal. */
    fun nextHold(cur: Float, steps: Int): Float = (((cur + steps * 0.1f) * 10f).roundToInt() / 10f).coerceIn(HOLD_MIN, HOLD_MAX)

    /** "2.0x  >>", "0.25x  >>". */
    fun chipText(s: Float): String {
        var t = String.format(java.util.Locale.US, "%.2f", s).trimEnd('0')
        if (t.endsWith(".")) t += "0"
        return t + "x  >>"
    }

    fun zoomText(z: Float): String = Math.round(z * 100f).toString() + "%"

    /** Round overlay chip (0xB3000000, radius 16 dp), hidden at the start. */
    fun pill(a: PlayerActivity, sizeSp: Float, bold: Boolean): TextView = TextView(a).apply {
        textSize = sizeSp
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER
        minimumHeight = Util.dp(a, 36)
        setPadding(Util.dp(a, 14), Util.dp(a, 6), Util.dp(a, 14), Util.dp(a, 6))
        background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = Util.dp(a, 16).toFloat() }
        visibility = android.view.View.GONE
    }
}
