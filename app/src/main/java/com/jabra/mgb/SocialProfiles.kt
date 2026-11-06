package com.jabra.mgb

import android.content.Context
import android.net.Uri

/**
 * SOCIAL PROFILES (0.0.225, P1): the list of social sites SocialMode knows. A new site = one new row in [all]
 * (host, page kinds by path, app schemes, app packages, start page). SocialMode reads this table; TabManager hooks are unchanged.
 *
 * Per-site switch: key sm_s_<id> in mgb_ui. Not set = ON (follows the master switch sm_on), so 0.0.224 behaviour stays the same.
 */
object SocialProfiles {
    class Profile(
        val id: String,
        val label: String,
        /** Matched against the lower-case host. */
        val host: Regex,
        /** First rule whose path regex matches decides the kind; no match = [defaultKind]. */
        val pathRules: List<Pair<Regex, SocialMode.Kind>>,
        val defaultKind: SocialMode.Kind,
        /** App link schemes that must not open an app from a page of this site. */
        val schemes: Set<String>,
        /** Text inside an intent:// or market:// link that names the app (substring match). */
        val intentPkgs: List<String>,
        val startUrl: String,
        /** App names the "Open / Use / Get the app" banner button may contain (0.0.226). */
        val appWords: List<String> = emptyList(),
        /** Extra CSS for this site (hide a known promo element). Empty = none (0.0.226). */
        val css: String = "",
        /** Command Center row: sub line + search words. Empty sub = no automatic row (0.0.226). */
        val sub: String = "",
        val kw: String = ""
    ) {
        fun kindOf(path: String): SocialMode.Kind {
            for ((re, k) in pathRules) if (re.containsMatchIn(path)) return k
            return defaultKind
        }
    }

    private val CHAT_PATH = Regex("^/(messages|messenger|messenger_media)(/|$)")

    /** Legacy banner words of Facebook / Messenger / Instagram (same regex as 0.0.224). */
    private val LEGACY_WORDS = listOf("instagram", "facebook", "messenger")

    val all: List<Profile> = listOf(
        Profile(
            id = "facebook", label = "Facebook",
            host = Regex("(^|\\.)(facebook\\.com|fb\\.com)$"),
            pathRules = listOf(CHAT_PATH to SocialMode.Kind.CHAT),
            defaultKind = SocialMode.Kind.FEED,
            schemes = setOf("fb", "fbapi", "fbauth2"),
            intentPkgs = listOf("com.facebook."),
            startUrl = SocialMode.FB_HOME,
            appWords = LEGACY_WORDS
        ),
        Profile(
            id = "messenger", label = "Messenger",
            host = Regex("^(.+\\.)?messenger\\.com$"),
            pathRules = emptyList(),
            defaultKind = SocialMode.Kind.CHAT,
            schemes = setOf("fb-messenger", "fb-messenger-public", "fb-messenger-share"),
            intentPkgs = listOf("com.facebook."),
            startUrl = SocialMode.FB_CHAT,
            appWords = LEGACY_WORDS
        ),
        Profile(
            id = "instagram", label = "Instagram",
            host = Regex("^(.+\\.)?instagram\\.com$"),
            pathRules = emptyList(),
            defaultKind = SocialMode.Kind.INSTA,
            schemes = setOf("instagram"),
            intentPkgs = listOf("com.instagram."),
            startUrl = SocialMode.IG_HOME,
            appWords = LEGACY_WORDS
        ),
        // ---- 0.0.226 (P2): feed sites, mobile-clean user agent + banner script ----
        feed("reddit", "Reddit", "(^|\\.)reddit\\.com$", setOf("reddit"), listOf("com.reddit."),
            "https://www.reddit.com/", listOf("reddit"),
            "Reddit feed without the app nag", "reddit social",
            // assumption (live site, not tested): cross-promo elements are named xpromo-*
            css = "xpromo-bottom-sheet,xpromo-app-selector,[id^=\"xpromo\"]{display:none!important}"),
        feed("x", "X", "(^|\\.)(x|twitter)\\.com$", setOf("twitter", "x"), listOf("com.twitter."),
            "https://x.com/", listOf("x", "twitter"),
            "X (Twitter) in a normal tab", "twitter tweet x social"),
        feed("pinterest", "Pinterest", "(^|\\.)pinterest\\.[a-z.]+$", setOf("pinterest"), listOf("com.pinterest"),
            "https://www.pinterest.com/", listOf("pinterest"),
            "Pins without the open-app layer", "pins social"),
        feed("threads", "Threads", "(^|\\.)threads\\.(net|com)$", setOf("barcelona", "threads"), listOf("com.instagram.barcelona"),
            "https://www.threads.net/", listOf("threads"),
            "Threads in a normal tab", "social"),
        feed("bluesky", "Bluesky", "^(.+\\.)?bsky\\.app$", setOf("bluesky", "bsky"), listOf("xyz.blueskyweb."),
            "https://bsky.app/", listOf("bluesky"),
            "Bluesky in a normal tab", "bsky social"),
        feed("tiktok", "TikTok", "(^|\\.)tiktok\\.com$", setOf("tiktok", "snssdk1233", "musically"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "https://www.tiktok.com/", listOf("tiktok"),
            "TikTok web without the app nag", "tik tok social"),
        feed("linkedin", "LinkedIn", "(^|\\.)linkedin\\.com$", setOf("linkedin"), listOf("com.linkedin."),
            "https://www.linkedin.com/", listOf("linkedin"),
            "LinkedIn in a normal tab", "jobs social"),
        feed("tumblr", "Tumblr", "(^|\\.)tumblr\\.com$", setOf("tumblr"), listOf("com.tumblr"),
            "https://www.tumblr.com/", listOf("tumblr"),
            "Tumblr in a normal tab", "blog social"),
        // ---- 0.0.227 (P3): desktop-fit group = desktop user agent + phone-width viewport (same as Messenger chat) ----
        chat("discord", "Discord", "(^|\\.)(discord\\.com|discordapp\\.com)$", setOf("discord"), listOf("com.discord"),
            "https://discord.com/app",
            "Web chat that fits the phone (voice may not work)", "voice server social"),
        chat("whatsapp", "WhatsApp Web", "^web\\.whatsapp\\.com$", setOf("whatsapp"), listOf("com.whatsapp"),
            "https://web.whatsapp.com/",
            "Needs WhatsApp on a phone to link", "wa chat social"),
        chat("telegram", "Telegram Web", "^web\\.telegram\\.org$", setOf("tg", "telegram"), listOf("org.telegram."),
            "https://web.telegram.org/k/",
            "Telegram chat in the browser", "tg chat social"),
        chat("snapchat", "Snapchat Web", "^web\\.snapchat\\.com$", setOf("snapchat"), listOf("com.snapchat."),
            "https://web.snapchat.com/",
            "Limited by Snapchat web", "snap chat social")
    )

    private fun feed(id: String, label: String, host: String, schemes: Set<String>, pkgs: List<String>,
                     start: String, words: List<String>, sub: String, kw: String, css: String = ""): Profile =
        Profile(id = id, label = label, host = Regex(host), pathRules = emptyList(), defaultKind = SocialMode.Kind.FEED,
            schemes = schemes, intentPkgs = pkgs, startUrl = start, appWords = words, css = css, sub = sub, kw = kw)

    private fun chat(id: String, label: String, host: String, schemes: Set<String>, pkgs: List<String>,
                     start: String, sub: String, kw: String): Profile =
        Profile(id = id, label = label, host = Regex(host), pathRules = emptyList(), defaultKind = SocialMode.Kind.CHAT,
            schemes = schemes, intentPkgs = pkgs, startUrl = start, sub = sub, kw = kw)

    fun byId(id: String): Profile? = all.firstOrNull { it.id == id }

    /** Profile of this address (https pages only), or null. Ignores the per-site switch. */
    fun of(url: String?): Profile? {
        if (url == null || !url.startsWith("https://", true)) return null
        val u = try { Uri.parse(url) } catch (_: Exception) { return null }
        val h = (u.host ?: return null).lowercase()
        for (p in all) if (p.host.containsMatchIn(h)) return p
        return null
    }

    private fun key(id: String) = "sm_s_$id"
    fun siteOn(c: Context, id: String): Boolean =
        c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean(key(id), true)
    fun setSiteOn(c: Context, id: String, v: Boolean) {
        c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putBoolean(key(id), v).apply()
    }

    // ---- 0.0.236 (U2): "Site fix" = extra banner texts + CSS typed by the person for one site. Key sm_x_<id> = JSON {"w": "a, b", "c": "css"}; not set = built-in only ----
    private fun fixKey(id: String) = "sm_x_$id"
    private fun fixObj(c: Context, id: String): org.json.JSONObject? = try {
        val s = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getString(fixKey(id), null)
        if (s.isNullOrBlank()) null else org.json.JSONObject(s)
    } catch (_: Exception) { null }

    /** Cleaned extra banner texts (lower case, letters / digits / space / a few marks, max 40 chars, max 10). Safe to put into a script. */
    fun cleanWords(raw: String): List<String> = raw.split(',', '\n', ';')
        .map { it.replace(Regex("[^\\p{L}\\p{N} '\u2019.!?&-]"), "").trim().replace(Regex("\\s+"), " ").take(40).lowercase() }
        .filter { it.isNotEmpty() }.distinct().take(10)

    /** One line of CSS, max 600 chars; no @import / url( (nothing is loaded from outside) and no "<". */
    fun cleanCss(raw: String): String = raw.replace(Regex("[\\r\\n\\u2028\\u2029]+"), " ")
        .replace(Regex("(?i)@import|url\\s*\\("), "").replace("<", "").trim().take(600)

    fun fixWords(c: Context, id: String): List<String> = cleanWords(fixObj(c, id)?.optString("w", "") ?: "")
    fun fixCss(c: Context, id: String): String = cleanCss(fixObj(c, id)?.optString("c", "") ?: "")
    fun hasFix(c: Context, id: String): Boolean = fixWords(c, id).isNotEmpty() || fixCss(c, id).isNotEmpty()
    /** Saves the cleaned values; both empty = key removed. */
    fun setFix(c: Context, id: String, words: String, css: String) {
        val w = cleanWords(words).joinToString(", ")
        val s = cleanCss(css)
        val e = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit()
        if (w.isEmpty() && s.isEmpty()) e.remove(fixKey(id)) else e.putString(fixKey(id), org.json.JSONObject().put("w", w).put("c", s).toString())
        e.apply()
    }
    fun clearFix(c: Context, id: String) { setFix(c, id, "", "") }

    /** All schemes of all sites (a link of any social app is not opened from any social page, as in 0.0.224). */
    val allSchemes: Set<String> by lazy { all.flatMap { it.schemes }.toSet() }
    val allIntentPkgs: List<String> by lazy { all.flatMap { it.intentPkgs }.distinct() }
}
