package com.jabra.mgb

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
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

    /**
     * 0.0.281: smart HORIZONTAL slide after a long press. Finger to the right = faster (up to HOLD_MAX), to the left = slower (down to HOLD_MIN),
     * back at the start point = the start speed. Full range needs only [maxRoom] px of travel (not the whole screen), log scale,
     * 0.05 steps under 1x and 0.1 above. startX / x / w in px inside the touch layer, edge = dead zone at the screen edge.
     */
    fun slideHold(startSpeed: Float, startX: Float, x: Float, w: Float, minRoom: Float, maxRoom: Float, edge: Float): Float {
        val faster = x > startX
        val room = Math.min((if (faster) w - startX else startX) - edge, maxRoom)
        val t = (Math.abs(x - startX) / Math.max(room, minRoom)).coerceIn(0f, 1f)
        val target = if (faster) HOLD_MAX else HOLD_MIN
        if (t >= 0.985f) return target
        val v = startSpeed * Math.pow((target / startSpeed).toDouble(), t.toDouble()).toFloat()
        val step = if (v < 1f) 0.05f else 0.1f
        return ((v / step).roundToInt() * step).coerceIn(HOLD_MIN, HOLD_MAX)
    }

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

    // ---------- P9: level HUD helpers, volume boost, brightness memory, seek sensitivity, double tap seconds ----------

    /** Horizontal swipe seek: milliseconds of video for one full screen width (0 slow / 1 normal / 2 fast). */
    fun seekWindowMs(c: Context): Float = when (PlayerPrefs.getInt(c, PlayerPrefs.K_SEEK_SENS, 1)) {
        0 -> 45_000f
        2 -> 180_000f
        else -> 90_000f
    }

    /** Double tap seconds: 5 / 10 / 15 / 20, default 10. */
    fun dtapSec(c: Context): Int {
        val v = PlayerPrefs.getInt(c, PlayerPrefs.K_DTAP_SEC, 10)
        return if (v == 5 || v == 10 || v == 15 || v == 20) v else 10
    }

    /** P13: swipe, hold and pinch on / off. Single tap and double tap always work. */
    fun enabled(c: Context): Boolean = PlayerPrefs.getBool(c, PlayerPrefs.K_GESTURES, true)

    /** P13: picture size when the player opens: 0 fit / 1 fill / 2 stretch. */
    fun fitDefault(c: Context): Int = PlayerPrefs.getInt(c, PlayerPrefs.K_FIT_DEFAULT, 0).coerceIn(0, 2)

    /** Extra volume above the system maximum, in percent (0 = off). */
    fun boostCap(c: Context): Int = PlayerPrefs.getInt(c, PlayerPrefs.K_VOL_BOOST, 0).coerceIn(0, 100)

    /** Last brightness set by the swipe (0.02 .. 1), or -1 when none was saved. */
    fun savedBrightness(c: Context): Float {
        val v = PlayerPrefs.getFloat(c, PlayerPrefs.K_BRIGHT, -1f)
        return if (v < 0f) -1f else v.coerceIn(0.02f, 1f)
    }

    fun saveBrightness(c: Context, v: Float) { PlayerPrefs.putFloat(c, PlayerPrefs.K_BRIGHT, v) }

    /**
     * Volume above 100 percent. The system volume goes up to its maximum first, then more swipe adds extra gain through a
     * LoudnessEnhancer (600 mB = 6 dB = about 2x at 100 percent extra, so the cap is safe). The effect is created only
     * while the extra is above 0, and every call is wrapped: a device without the effect just stays at 100 percent.
     */
    class Boost {
        var pct = 0
        var sid = 0
        private var enh: LoudnessEnhancer? = null
        private var enhSid = 0

        /** Swipe steps (positive = louder). Returns the level to show: 0..100 system, 101..200 boosted. */
        fun change(c: Context, audio: AudioManager, steps: Int): Int {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val v = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val cap = boostCap(c)
            val unit = (100 / max).coerceAtLeast(1)
            if (pct > cap) { pct = cap; apply() }
            if (steps > 0 && v >= max && cap > 0) {
                pct = (pct + steps * unit).coerceAtMost(cap)
                apply()
            } else if (steps < 0 && pct > 0) {
                pct = (pct + steps * unit).coerceAtLeast(0)
                apply()
            } else if (steps != 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v + steps).coerceIn(0, max), 0)
            }
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            return if (pct > 0 && now >= max) 100 + pct else now * 100 / max
        }

        /** Create / update / release the effect for the current extra and audio session. */
        fun apply() {
            try {
                if (pct <= 0 || sid == 0) { release(); return }
                if (enh != null && enhSid != sid) release()
                var e = enh
                if (e == null) {
                    e = LoudnessEnhancer(sid)
                    enh = e
                    enhSid = sid
                }
                e.setTargetGain(pct * 6)
                e.setEnabled(true)
            } catch (_: Exception) {
                release()
            }
        }

        fun release() {
            try { enh?.release() } catch (_: Exception) { }
            enh = null
            enhSid = 0
        }
    }
}
