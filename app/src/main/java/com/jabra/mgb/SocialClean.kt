package com.jabra.mgb

import android.content.Context
import android.net.Uri

/**
 * LINK CLEANER (0.0.259, C4). Switch sm_clean (default OFF) in mgb_ui.
 * When ON, a link that the person copies or shares (TabManager.copyText / shareText, so link long-press, menu Share, Command Center)
 * loses known tracking parameters (fbclid, utm_*, igsh, gclid ...). Only a text that is ONE plain http(s) address is touched;
 * any other text is returned unchanged. Parameter names are ASSUMPTIONS and can be extended in one place (GLOBAL / PER_HOST).
 * Nothing is sent anywhere; the page itself is never changed, only the copied / shared text.
 */
object SocialClean {
    private const val KEY = "sm_clean"

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    private val GLOBAL = setOf("fbclid", "gclid", "dclid", "msclkid", "igshid", "igsh", "mc_eid", "mibextid", "ref_src", "ref_url", "_hsenc", "_hsmi")
    private val PER_HOST: Map<String, Set<String>> = mapOf(
        "x.com" to setOf("s", "t"),
        "twitter.com" to setOf("s", "t"),
        "youtube.com" to setOf("si", "feature"),
        "youtu.be" to setOf("si", "feature"),
        "spotify.com" to setOf("si"),
        "reddit.com" to setOf("share_id", "ref", "ref_source", "rdt"),
        "tiktok.com" to setOf("_t", "_r", "is_copy_url", "is_from_webapp", "sender_device", "sender_web_id"),
        "linkedin.com" to setOf("trk", "trackingId", "lipi", "midToken", "midSig", "eid"),
        "facebook.com" to setOf("__cft__", "__tn__", "refsrc", "sfnsn")
    )

    /** Called from TabManager.copyText / shareText. Returns [s] unchanged when the switch is OFF or [s] is not one plain address. */
    fun maybe(c: Context, s: String): String {
        if (!on(c)) return s
        return clean(s)
    }

    /** Pure function (no Context): strips tracking parameters from one http(s) address. Any problem = the original text. */
    fun clean(s: String): String {
        try {
            val t = s.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return s
            if (!(t.startsWith("https://", true) || t.startsWith("http://", true))) return s
            val u = Uri.parse(t)
            val host = (u.host ?: return s).lowercase()
            if (u.query.isNullOrEmpty()) return s
            val extra = PER_HOST.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value ?: emptySet()
            // x.com: s / t only on status links (other pages may use them for real)
            val xLike = host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")
            val useExtra = if (xLike) (u.path ?: "").contains("/status/") else true
            val b = u.buildUpon().clearQuery()
            var removed = 0
            for (name in u.queryParameterNames) {
                val low = name.lowercase()
                val drop = low.startsWith("utm_") || low in GLOBAL || (useExtra && extra.any { it.lowercase() == low })
                if (drop) { removed++; continue }
                for (v in u.getQueryParameters(name)) b.appendQueryParameter(name, v)
            }
            if (removed == 0) return s
            return b.build().toString()
        } catch (e: Exception) {
            return s
        }
    }
}
