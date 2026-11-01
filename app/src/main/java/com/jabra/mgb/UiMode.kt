package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S20: night / day.
 *  - Theme choice System / Light / Dark (saved, applied at every start before the Activity is created)
 *  - Dark pages: lets the WebView darken web pages that have no dark style (only when the app itself is dark)
 */
object UiMode {
    private const val PREFS = "mgb_ui"
    private val NAMES = arrayOf("System", "Light", "Dark")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 0 = follow system, 1 = light, 2 = dark. */
    fun mode(ctx: Context): Int = sp(ctx).getInt("night", 0).coerceIn(0, 2)

    fun label(ctx: Context): String = NAMES[mode(ctx)]

    /** Call at the start of Activity.onCreate, before super.onCreate. */
    fun apply(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(ctx)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Menu > Theme: pick System / Light / Dark. The Activity is recreated (tabs come back from the saved session). */
    fun pick(act: Activity) {
        val cur = mode(act)
        MaterialAlertDialogBuilder(act)
            .setTitle("Theme")
            .setSingleChoiceItems(NAMES, cur) { d, which ->
                d.dismiss()
                if (which != cur) {
                    sp(act).edit().putInt("night", which).apply()
                    apply(act)
                    act.recreate()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun darkPages(ctx: Context): Boolean = sp(ctx).getBoolean("dark_pages", false)

    fun setDarkPages(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("dark_pages", on).apply()
    }

    /** allowed = user switch on and not an extension page. Takes effect on the next page load. */
    fun applyPage(w: WebView, allowed: Boolean) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, allowed)
            }
        } catch (e: Exception) { /* old WebView: pages stay as they are */ }
    }
}
