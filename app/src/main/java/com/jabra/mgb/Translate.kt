package com.jabra.mgb

import android.app.Activity
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.URLEncoder

/**
 * S41: "Translate page" (menu). Opens the same address through Google Translate in the SAME tab, so Back returns to the
 * original page. Target language is asked the first time and kept in prefs mgb_ui tr_lang (Settings > General > Translate to).
 */
object Translate {
    val LANGS = listOf(
        "English" to "en", "Bangla" to "bn", "Hindi" to "hi", "Urdu" to "ur", "Arabic" to "ar",
        "Chinese (Simplified)" to "zh-CN", "Japanese" to "ja", "Korean" to "ko", "Spanish" to "es",
        "French" to "fr", "German" to "de", "Russian" to "ru", "Indonesian" to "id", "Turkish" to "tr"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun hasLang(ctx: Context) = prefs(ctx).contains("tr_lang")

    fun lang(ctx: Context): String = prefs(ctx).getString("tr_lang", "en") ?: "en"

    fun langName(ctx: Context): String = LANGS.firstOrNull { it.second == lang(ctx) }?.first ?: "English"

    fun pickLang(act: Activity, done: () -> Unit = { }) {
        val cur = LANGS.indexOfFirst { it.second == lang(act) }
        MaterialAlertDialogBuilder(act).setTitle("Translate to")
            .setSingleChoiceItems(LANGS.map { it.first }.toTypedArray(), cur) { d, i ->
                prefs(act).edit().putString("tr_lang", LANGS[i].second).apply()
                d.dismiss()
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun page(act: Activity, tabs: TabManager) {
        val t = tabs.active
        val u = t?.url ?: ""
        if (t == null || t.isExt || !u.startsWith("http")) { Util.toast(act, "Open a web page first"); return }
        val h = Util.host(u)
        if (h.endsWith("translate.goog") || h.endsWith("translate.google.com")) { Util.toast(act, "Already translated: press Back for the original"); return }
        if (!hasLang(act)) { pickLang(act) { page(act, tabs) }; return }
        tabs.openUrl("https://translate.google.com/translate?sl=auto&tl=" + lang(act) + "&u=" + URLEncoder.encode(u, "UTF-8"))
    }
}
