package com.jabra.mgb

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PLAYER PLAN P5: Subtitles page + caption style helper.
 * Prefs (mgb_ui): pl_sub_size (percent 50..200, default 100; old sub_size 0/1/2 migrates once to 80 / 100 / 135),
 * pl_sub_bg (0 none / 1 shadow / 2 box, default 1), pl_sub_pos (0 low / 1 middle / 2 high, default 0), sub_color (index, unchanged).
 */
object PlayerSubs {
    private const val K_COLOR = "sub_color"
    private const val K_OLD_SIZE = "sub_size"
    const val SIZE_MIN = 50f
    const val SIZE_MAX = 200f

    val COLORS = intArrayOf(Color.WHITE, 0xFFFFEB3B.toInt(), 0xFF4DD0E1.toInt(), 0xFF76FF03.toInt(), 0xFFFFA726.toInt())
    private val COLOR_NAMES = arrayOf("White", "Yellow", "Cyan", "Green", "Orange")
    private val POS_PAD = floatArrayOf(0.1f, 0.3f, 0.55f)
    private const val BASE_SIZE = 0.0533f

    fun colorIdx(a: PlayerActivity): Int = PlayerPrefs.getInt(a, K_COLOR, 0).coerceIn(0, COLORS.size - 1)

    /** Text size in percent. The first read after the update converts the old 3-step value and saves it. */
    fun sizePct(a: PlayerActivity): Int {
        if (PlayerPrefs.has(a, PlayerPrefs.K_SUB_SIZE)) return PlayerPrefs.getInt(a, PlayerPrefs.K_SUB_SIZE, 100).coerceIn(50, 200)
        val v = when (PlayerPrefs.getInt(a, K_OLD_SIZE, 1)) { 0 -> 80; 2 -> 135; else -> 100 }
        PlayerPrefs.putInt(a, PlayerPrefs.K_SUB_SIZE, v)
        return v
    }

    fun bg(a: PlayerActivity): Int = PlayerPrefs.getInt(a, PlayerPrefs.K_SUB_BG, 1).coerceIn(0, 2)
    fun pos(a: PlayerActivity): Int = PlayerPrefs.getInt(a, PlayerPrefs.K_SUB_POS, 0).coerceIn(0, 2)

    /** Puts colour, size, background and position on the real subtitle view. */
    fun applyStyle(a: PlayerActivity, sv: androidx.media3.ui.SubtitleView) {
        sv.setApplyEmbeddedStyles(true)
        val b = bg(a)
        val back = if (b == 2) 0xB3000000.toInt() else Color.TRANSPARENT
        val edge = when (b) {
            0 -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
            1 -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        }
        sv.setStyle(androidx.media3.ui.CaptionStyleCompat(COLORS[colorIdx(a)], back, Color.TRANSPARENT, edge, Color.BLACK, null))
        sv.setFractionalTextSize(BASE_SIZE * sizePct(a) / 100f)
        sv.setBottomPaddingFraction(POS_PAD[pos(a)])
    }

    /** The live sample line inside the page follows the same prefs. */
    private fun stylePreview(a: PlayerActivity, tv: TextView) {
        val b = bg(a)
        tv.setTextColor(COLORS[colorIdx(a)])
        tv.textSize = 16f * sizePct(a) / 100f
        if (b == 1) tv.setShadowLayer(4f, 0f, 1f, Color.BLACK) else tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        tv.background = GradientDrawable().apply {
            setColor(if (b == 2) 0xB3000000.toInt() else 0x14FFFFFF)
            cornerRadius = Util.dp(a, if (b == 2) 6 else 12).toFloat()
        }
    }

    private fun swatches(a: PlayerActivity): View {
        val cur = colorIdx(a)
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Util.dp(a, 16), Util.dp(a, 4), Util.dp(a, 16), Util.dp(a, 4))
        }
        for (i in COLORS.indices) {
            val dot = View(a).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLORS[i])
                    setStroke(Util.dp(a, 2), if (i == cur) Color.WHITE else 0x33FFFFFF)
                }
                contentDescription = COLOR_NAMES[i]
            }
            val cell = FrameLayout(a).apply {
                setOnClickListener {
                    PlayerPrefs.putInt(a, K_COLOR, i)
                    a.restyleSubs()
                    a.panel.refresh()
                }
            }
            cell.addView(dot, FrameLayout.LayoutParams(Util.dp(a, 32), Util.dp(a, 32), Gravity.CENTER))
            row.addView(cell, LinearLayout.LayoutParams(0, Util.dp(a, 44), 1f))
        }
        return row
    }

    /** Off + each text track (radio), add file, live sample, size slider, colour, background, position. */
    fun page(a: PlayerActivity): PlayerPanel.Page = PlayerPanel.Page("Subtitles") {
        val opts = a.subOpts()
        radio("Off", null, a.subOff()) { a.setSubOff() }
        for (o in opts) radio(o.label, o.sub, o.selected) { o.pick() }
        if (opts.isEmpty()) note("No subtitle in this video. Add a file, or put it next to the video with the same name.")
        row(R.drawable.ic_add, "Add subtitle file...", ".srt .vtt .ass") { a.addSubFile() }

        section("Timing")
        slider("Subtitle delay", -10000f, 10000f, 100f, a.subDelayMs.toFloat().coerceIn(-10000f, 10000f), { v -> SubDelay.fmtDelay(Math.round(v).toLong()) }, { _ -> }, { v ->
            a.queueSubDelay(Math.round(v).toLong())
        })
        note("Positive = subtitle shows later, negative = earlier. Works for subtitle files, not for tracks inside the video. The video blinks once when it reloads.")

        section("Style")
        val sample = TextView(a).apply {
            text = "Sample subtitle text"
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(Util.dp(a, 12), Util.dp(a, 10), Util.dp(a, 12), Util.dp(a, 10))
        }
        stylePreview(a, sample)
        val holder = FrameLayout(a).apply { setPadding(Util.dp(a, 16), Util.dp(a, 4), Util.dp(a, 16), Util.dp(a, 4)) }
        holder.addView(sample, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        custom(holder)

        slider("Text size", SIZE_MIN, SIZE_MAX, 5f, sizePct(a).toFloat(), { v -> Math.round(v).toString() + "%" }, { v ->
            PlayerPrefs.putInt(a, PlayerPrefs.K_SUB_SIZE, Math.round(v))
            a.restyleSubs()
            stylePreview(a, sample)
        }, { _ -> })

        section("Colour")
        custom(swatches(a))

        section("Background")
        val b = bg(a)
        val bgNames = arrayOf("None", "Shadow", "Box")
        val bgItems = ArrayList<PlayerPanel.Chip>()
        for (i in bgNames.indices) bgItems.add(PlayerPanel.Chip(bgNames[i], b == i) {
            PlayerPrefs.putInt(a, PlayerPrefs.K_SUB_BG, i)
            a.restyleSubs()
            a.panel.refresh()
        })
        chips(bgItems, 3)

        section("Position")
        val ps = pos(a)
        val posNames = arrayOf("Low", "Middle", "High")
        val posItems = ArrayList<PlayerPanel.Chip>()
        for (i in posNames.indices) posItems.add(PlayerPanel.Chip(posNames[i], ps == i) {
            PlayerPrefs.putInt(a, PlayerPrefs.K_SUB_POS, i)
            a.restyleSubs()
            a.panel.refresh()
        })
        chips(posItems, 3)
    }
}
