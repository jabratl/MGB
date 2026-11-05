package com.jabra.mgb

import android.content.Context

/**
 * PLAYER PLAN P1: one place for every player preference (file "mgb_ui", prefix "pl_", so Backup already saves them).
 * Defaults live here. Later steps add their getters / setters here, never raw keys elsewhere.
 */
object PlayerPrefs {
    const val K_SPEEDS = "pl_speeds"          // P2 csv of saved custom speeds
    const val K_SPEED_KEEP = "pl_speed_keep"  // P2 bool
    const val K_SPEED_LAST = "pl_speed_last"  // P2 float
    const val K_HOLD_SPEED = "pl_hold_speed"  // P8 float
    const val K_HOLD_SLIDE = "pl_hold_slide"  // P8 bool
    const val K_REPEAT = "pl_repeat"          // P3 int 0 off / 1 one / 2 folder
    const val K_QUALITY_CAP = "pl_quality_cap" // P3 int max height, 0 = auto
    const val K_SUB_SIZE = "pl_sub_size"      // P5 int percent 50..200
    const val K_SUB_BG = "pl_sub_bg"          // P5 int 0 none / 1 shadow / 2 box
    const val K_SUB_POS = "pl_sub_pos"        // P5 int 0 low / 1 middle / 2 high
    const val K_VOL_BOOST = "pl_vol_boost"    // P9 int 0..100 extra percent over system max, 0 = off
    const val K_BRIGHT = "pl_bright"          // P9 float last brightness, -1 = system
    const val K_SEEK_SENS = "pl_seek_sens"    // P9 int 0 slow / 1 normal / 2 fast
    const val K_DTAP_SEC = "pl_dtap_sec"      // P9 int 5 / 10 / 15 / 20
    const val K_GESTURES = "pl_gestures"      // P13 bool, default true
    const val K_FIT_DEFAULT = "pl_fit_default" // P13 int 0 fit / 1 fill / 2 stretch

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun has(c: Context, key: String): Boolean = try { sp(c).contains(key) } catch (_: Exception) { false }
    fun getInt(c: Context, key: String, def: Int): Int = try { sp(c).getInt(key, def) } catch (_: Exception) { def }
    fun getFloat(c: Context, key: String, def: Float): Float = try { sp(c).getFloat(key, def) } catch (_: Exception) { def }
    fun getBool(c: Context, key: String, def: Boolean): Boolean = try { sp(c).getBoolean(key, def) } catch (_: Exception) { def }
    fun getStr(c: Context, key: String, def: String): String = try { sp(c).getString(key, def) ?: def } catch (_: Exception) { def }

    fun remove(c: Context, key: String) { sp(c).edit().remove(key).apply() }
    fun putInt(c: Context, key: String, v: Int) { sp(c).edit().putInt(key, v).apply() }
    fun putFloat(c: Context, key: String, v: Float) { sp(c).edit().putFloat(key, v).apply() }
    fun putBool(c: Context, key: String, v: Boolean) { sp(c).edit().putBoolean(key, v).apply() }
    fun putStr(c: Context, key: String, v: String) { sp(c).edit().putString(key, v).apply() }
}
