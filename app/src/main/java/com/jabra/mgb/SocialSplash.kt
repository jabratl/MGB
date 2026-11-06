package com.jabra.mgb

import android.app.Activity
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
 * LAUNCH SPLASH FOR THE HOME-SCREEN SHORTCUT (0.0.241 -> 0.0.242, A4): when a pinned social shortcut (P6) opens the browser, a plain cover with the
 * site letter circle + name shows for 0.7 s over the loading page, then fades out (0.2 s). Switch "Splash when opening from a shortcut" (sm_splash,
 * default ON). Skipped with Reduce motion or when the switch is OFF. Only used by SocialShortcuts.fromIntent; touches are blocked while it shows.
 */
object SocialSplash {
    fun show(a: Activity, label: String) {
        if (!SocialMode.splashOn(a) || TabPrefs.reduce(a)) return
        try {
            val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
            val cover = FrameLayout(a).apply {
                setBackgroundColor(a.getColor(R.color.mg_bg))
                isClickable = true; isFocusable = true
                elevation = Util.dp(a, 40).toFloat()
            }
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val d = Util.dp(a, 88)
            col.addView(TextView(a).apply {
                text = label.trim().take(1).uppercase()
                setTextColor(a.getColor(R.color.mg_on_primary)); textSize = 38f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.getColor(R.color.mg_primary)) }
            }, LinearLayout.LayoutParams(d, d))
            col.addView(TextView(a).apply {
                text = label; setTextColor(a.getColor(R.color.mg_text)); textSize = 18f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, Util.dp(a, 14), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cover.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            root.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cover.postDelayed({
                try { cover.animate().alpha(0f).setDuration(200).withEndAction { try { root.removeView(cover) } catch (_: Exception) { } }.start() }
                catch (_: Exception) { try { root.removeView(cover) } catch (_: Exception) { } }
            }, 700)
        } catch (_: Exception) { }
    }
}
