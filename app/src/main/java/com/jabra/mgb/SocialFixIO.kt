package com.jabra.mgb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

/**
 * SITE FIX EXPORT / IMPORT (0.0.269, C8 part). No pref of its own. Copies the person's Site fixes (U2: extra Open-app words + one CSS line per site)
 * as one JSON text to the clipboard, and applies such a text from the clipboard. Import goes through SocialProfiles.setFix (it cleans words and CSS
 * again), unknown site ids are skipped, an empty site entry clears that site. Only the fixes are touched, nothing else; nothing is sent anywhere.
 */
object SocialFixIO {
    private const val TAG = "thazir_site_fix"

    /** JSON text of all sites that have a fix, or null when there is none. */
    fun export(c: Context): String? {
        val sites = JSONObject()
        for (p in SocialProfiles.all) {
            if (!SocialProfiles.hasFix(c, p.id)) continue
            sites.put(p.id, JSONObject().put("w", SocialProfiles.fixWords(c, p.id).joinToString(", ")).put("c", SocialProfiles.fixCss(c, p.id)))
        }
        if (sites.length() == 0) return null
        return JSONObject().put(TAG, 1).put("sites", sites).toString()
    }

    /** Applies the text. Returns the number of sites set, or -1 when the text is not a Site fix export. */
    fun importText(c: Context, text: String?): Int {
        try {
            val o = JSONObject((text ?: "").trim())
            if (o.optInt(TAG, 0) != 1) return -1
            val sites = o.optJSONObject("sites") ?: return -1
            var n = 0
            for (p in SocialProfiles.all) {
                val e = sites.optJSONObject(p.id) ?: continue
                SocialProfiles.setFix(c, p.id, e.optString("w", ""), e.optString("c", ""))
                n++
            }
            return n
        } catch (_: Throwable) {
            return -1
        }
    }

    fun copy(c: Context, text: String) {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Thazir site fix", text))
    }

    fun clipboardText(c: Context): String? = try {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString()
    } catch (_: Throwable) { null }
}
