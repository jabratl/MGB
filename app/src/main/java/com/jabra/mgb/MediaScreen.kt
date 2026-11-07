package com.jabra.mgb

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 0.0.296: Settings > General > Media (Brave style: General + YouTube). Plain switch rows, theme colours, works in dark + light. */
object MediaScreen {
    fun open(act: Activity) {
        fun dp(v: Int) = Util.dp(act, v)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        fun header(t: String) = col.addView(TextView(act).apply {
            text = t; textSize = 13f; setTextColor(act.getColor(R.color.mg_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(24), dp(14), dp(24), dp(4))
        })
        fun row(key: String, label: String, hint: String?, after: (() -> Unit)? = null) {
            val sw = ThazirSwitch(act).apply { isChecked = MediaPrefs.get(act, key); isClickable = false; isFocusable = false }
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56); setPadding(dp(24), dp(8), dp(20), dp(8)); isClickable = true
                val o = android.util.TypedValue(); act.theme.resolveAttribute(android.R.attr.selectableItemBackground, o, true); setBackgroundResource(o.resourceId)
            }
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 16f; setTextColor(act.getColor(R.color.mg_text)) })
            if (!hint.isNullOrBlank()) txt.addView(TextView(act).apply { text = hint; textSize = 12.5f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0) })
            r.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            r.addView(sw)
            r.setOnClickListener {
                sw.isChecked = !sw.isChecked
                MediaPrefs.set(act, key, sw.isChecked)
                after?.invoke()
                BookmarksScreen.tabsRef?.active?.web?.let { w -> YtAds.flag(w, w.url) } // YouTube page in front: new switches apply at once
            }
            col.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        header("General")
        row(MediaPrefs.DRM, "Widevine DRM", "Let sites play protected media (DRM). Videos that are protected still cannot be downloaded: the downloader skips them.") {
            BookmarksScreen.tabsRef?.active?.web?.reload() // a page that was refused before asks again
        }
        row(MediaPrefs.BG, "Background play", "YouTube keeps playing when you switch tabs or apps, or the screen is off. A small notification shows while it plays.")
        header("YouTube")
        row(MediaPrefs.YTLINKS, "Open YouTube links in Thazir", "YouTube pages stay in the tab instead of jumping to the YouTube app.")
        row(MediaPrefs.YTREC, "Block YouTube recommended content", "Hides the up next and end card elements on youtube.com.")
        row(MediaPrefs.YTDIST, "Block YouTube distracting elements", "Hides the sign in, menu, comments and video info elements on youtube.com.")
        row(MediaPrefs.YTSHORTS, "Block YouTube Shorts", "Hides Shorts from the home page, trending page and search results on youtube.com.")
        val sv = ScrollView(act).apply { addView(col) }
        MaterialAlertDialogBuilder(act).setTitle("Media").setView(sv).setPositiveButton("Done", null).show()
    }
}
