package com.jabra.mgb

import android.content.Context
import android.graphics.Color
import android.provider.Settings

/** S21f: small switches for the tab polish (prefs mgb_ui). Defaults: swipe-to-close ON, ask before close all OFF, reduce motion OFF. */
object TabPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    /** Reduce motion: our switch OR the phone's "remove animations" (animator scale 0). */
    fun reduce(c: Context): Boolean =
        sp(c).getBoolean("reduce_motion", false) ||
            Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun reduceSwitch(c: Context): Boolean = sp(c).getBoolean("reduce_motion", false)
    fun swipeClose(c: Context): Boolean = sp(c).getBoolean("swipe_close", true)
    fun confirmClose(c: Context): Boolean = sp(c).getBoolean("close_confirm", false)

    fun set(c: Context, key: String, on: Boolean) { sp(c).edit().putBoolean(key, on).apply() }

    /** 0.0.137 (Step B): soft tint per Settings category. Fixed hues keep the 9 categories apart; unknown key = hash hue. */
    private val HUE = mapOf("general" to 215, "tabs" to 265, "downloads" to 150, "grabber" to 25, "privacy" to 355,
        "data" to 45, "passwords" to 300, "extensions" to 185, "about" to 100)

    fun tint(key: String, night: Boolean): Int {
        val h = (HUE[key] ?: ((key.hashCode() and 0x7fffffff) % 360)).toFloat()
        return Color.HSVToColor(floatArrayOf(h, if (night) 0.45f else 0.6f, if (night) 0.95f else 0.78f))
    }
}
