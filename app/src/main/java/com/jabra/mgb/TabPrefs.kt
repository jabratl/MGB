package com.jabra.mgb

import android.content.Context
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
}
