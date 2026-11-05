package com.jabra.mgb

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PLAYER PLAN P2: Playback speed page. Custom slider 0.05x - 6x (step 0.05) with - / +, preset chips, saved custom chips
 * (max 8, long press removes), "Save current as preset" row, option "Keep speed for next video".
 * Prefs (mgb_ui): pl_speeds (csv), pl_speed_keep (bool), pl_speed_last (float).
 */
object PlayerSpeed {
    const val MIN = 0.05f
    const val MAX = 6f
    const val STEP = 0.05f
    private const val MAX_SAVED = 8
    private val PRESETS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f, 5f, 6f)

    /** Range + 2 decimals (so 1.1500001 becomes 1.15). */
    fun clamp(s: Float): Float = ((s.coerceIn(MIN, MAX) * 100f).roundToInt() / 100f)

    /** 1 -> "1x", 1.5 -> "1.5x", 1.15 -> "1.15x". */
    fun fmt(s: Float): String = String.format(java.util.Locale.US, "%.2f", s).trimEnd('0').trimEnd('.') + "x"

    private fun same(a: Float, b: Float) = abs(a - b) < 0.005f

    fun saved(a: PlayerActivity): List<Float> {
        val out = ArrayList<Float>()
        for (t in PlayerPrefs.getStr(a, PlayerPrefs.K_SPEEDS, "").split(',')) {
            val v = t.trim().toFloatOrNull() ?: continue
            val c = clamp(v)
            if (out.none { same(it, c) } && PRESETS.none { same(it, c) }) out.add(c)
        }
        return out.take(MAX_SAVED)
    }

    private fun putSaved(a: PlayerActivity, l: List<Float>) {
        PlayerPrefs.putStr(a, PlayerPrefs.K_SPEEDS, l.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) })
    }

    fun keep(a: PlayerActivity): Boolean = PlayerPrefs.getBool(a, PlayerPrefs.K_SPEED_KEEP, false)

    fun last(a: PlayerActivity): Float = clamp(PlayerPrefs.getFloat(a, PlayerPrefs.K_SPEED_LAST, 1f))

    fun page(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Playback speed") {
        val cur = clamp(a.player?.playbackParameters?.speed ?: 1f)
        val sv = saved(a)
        fun pick(v: Float) { a.setSpeed(v); a.flash("Playback speed " + fmt(v)); a.panel.refresh() }

        slider("Custom", MIN, MAX, STEP, cur, { v -> fmt(v) }, { v -> a.setSpeed(v) }, { v -> a.flash("Playback speed " + fmt(clamp(v))) })

        val items = ArrayList<PlayerPanel.Chip>()
        for (v in PRESETS) items.add(PlayerPanel.Chip(fmt(v), same(cur, v)) { pick(v) })
        for (v in sv) items.add(PlayerPanel.Chip(fmt(v), same(cur, v), true, {
            putSaved(a, sv.filter { !same(it, v) })
            a.panel.refresh()
        }) { pick(v) })
        chips(items, if (wide) 4 else 3)

        if (PRESETS.none { same(it, cur) } && sv.none { same(it, cur) } && sv.size < MAX_SAVED) {
            row(R.drawable.ic_add, "Save " + fmt(cur) + " as preset") {
                putSaved(a, sv + cur)
                a.panel.refresh()
            }
        }
        if (sv.isNotEmpty()) note("Long press a saved speed to remove it.")
        section("Options")
        toggle(0, "Keep speed for next video", keep(a)) {
            val on = !keep(a)
            PlayerPrefs.putBool(a, PlayerPrefs.K_SPEED_KEEP, on)
            if (on) PlayerPrefs.putFloat(a, PlayerPrefs.K_SPEED_LAST, cur)
        }
    }
}
