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
        val startUrl: String
    ) {
        fun kindOf(path: String): SocialMode.Kind {
            for ((re, k) in pathRules) if (re.containsMatchIn(path)) return k
            return defaultKind
        }
    }

    private val CHAT_PATH = Regex("^/(messages|messenger|messenger_media)(/|$)")

    val all: List<Profile> = listOf(
        Profile(
            id = "facebook", label = "Facebook",
            host = Regex("(^|\\.)(facebook\\.com|fb\\.com)$"),
            pathRules = listOf(CHAT_PATH to SocialMode.Kind.CHAT),
            defaultKind = SocialMode.Kind.FEED,
            schemes = setOf("fb", "fbapi", "fbauth2"),
            intentPkgs = listOf("com.facebook."),
            startUrl = SocialMode.FB_HOME
        ),
        Profile(
            id = "messenger", label = "Messenger",
            host = Regex("^(.+\\.)?messenger\\.com$"),
            pathRules = emptyList(),
            defaultKind = SocialMode.Kind.CHAT,
            schemes = setOf("fb-messenger", "fb-messenger-public", "fb-messenger-share"),
            intentPkgs = listOf("com.facebook."),
            startUrl = SocialMode.FB_CHAT
        ),
        Profile(
            id = "instagram", label = "Instagram",
            host = Regex("^(.+\\.)?instagram\\.com$"),
            pathRules = emptyList(),
            defaultKind = SocialMode.Kind.INSTA,
            schemes = setOf("instagram"),
            intentPkgs = listOf("com.instagram."),
            startUrl = SocialMode.IG_HOME
        )
    )

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

    /** All schemes of all sites (a link of any social app is not opened from any social page, as in 0.0.224). */
    val allSchemes: Set<String> by lazy { all.flatMap { it.schemes }.toSet() }
    val allIntentPkgs: List<String> by lazy { all.flatMap { it.intentPkgs }.distinct() }
}
