package com.jabra.mgb

import android.content.Context

/**
 * S65: the original V77 Grabber as a switchable backup for the native downloader.
 * OFF (default): menu Grabber = native "Files detected" panel, page buttons follow the Button mode.
 * ON: menu Grabber = V77 popup, and the Button mode is set to "Grabber only" (V77 page button + video player button).
 * Turning it OFF puts the Button mode back to what it was (Manual goes back to Smart).
 * Prefs mgb_ui: v77_backup (Boolean), v77_prev_mode (Int).
 */
object V77Alt {
    private fun prefs(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun on(c: Context): Boolean = prefs(c).getBoolean("v77_backup", false)

    fun set(c: Context, on: Boolean) {
        if (on == on(c)) return
        val p = prefs(c)
        if (on) {
            val cur = DlMode.mode(c)
            p.edit().putBoolean("v77_backup", true).putInt("v77_prev_mode", if (cur < 0) DlMode.SMART else cur).apply()
            DlMode.apply(c, DlMode.GRABBER)
        } else {
            val prev = p.getInt("v77_prev_mode", DlMode.SMART)
            p.edit().putBoolean("v77_backup", false).apply()
            DlMode.apply(c, if (prev == DlMode.MANUAL) DlMode.SMART else prev)
        }
    }
}
