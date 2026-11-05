package com.jabra.mgb

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PLAYER PLAN P10: the black "audio only" screen. A full-size black layer with a centred card (title, time, one note, a
 * "Show video" button). The layer itself is not clickable, so taps outside the button reach the touch layer below
 * (show / hide controls, double tap, swipes). The video track is switched off in PlayerActivity.setAudioOnly.
 */
class PlayerAudioOnly(private val a: PlayerActivity, onShowVideo: () -> Unit) {
    val root = FrameLayout(a)
    private val titleTv = TextView(a)
    private val timeTv = TextView(a)
    private val noteTv = TextView(a)

    private fun dp(n: Int) = Util.dp(a, n)

    init {
        val accent = a.getColor(R.color.mg_primary)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.setBackgroundColor(Color.BLACK)
        root.visibility = View.GONE
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(260)
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = GradientDrawable().apply { setColor(0xFF1B1C20.toInt()); cornerRadius = dp(22).toFloat() }
        }
        card.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_pl_headphones)
            setColorFilter(accent)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        titleTv.apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(280)
        }
        card.addView(titleTv, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(12) })
        timeTv.apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        card.addView(timeTv, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
        noteTv.apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxWidth = dp(280)
        }
        card.addView(noteTv, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(10) })
        val btn = TextView(a).apply {
            text = "Show video"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(a.getColor(R.color.mg_on_primary))
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(22), 0, dp(22), 0)
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(22).toFloat() }
            setOnClickListener { onShowVideo() }
        }
        card.addView(btn, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(16) })
        root.addView(card, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
    }

    fun show(on: Boolean) { root.visibility = if (on) View.VISIBLE else View.GONE }

    /** Called by the 500 ms ticker while audio only is on. Texts are only set when they change. */
    fun update(title: String, time: String, bgOn: Boolean) {
        if (root.visibility != View.VISIBLE) return
        if (titleTv.text.toString() != title) titleTv.text = title
        if (timeTv.text.toString() != time) timeTv.text = time
        val n = if (bgOn) "Keeps playing with the screen off" else "Turn on Background audio to play with the screen off"
        if (noteTv.text.toString() != n) noteTv.text = n
    }
}
