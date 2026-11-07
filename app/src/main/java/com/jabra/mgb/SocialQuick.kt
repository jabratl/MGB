package com.jabra.mgb

import android.content.Context
import android.os.SystemClock
import android.net.Uri

/**
 * 0.0.307: two more "like the app" parts for social sites.
 *  1. "Go to" row in the Social chip sheet: the pages the official app has as tabs / feed choices (Instagram Following / Favorites / Reels / Messages,
 *     Facebook Most recent / Friends / Marketplace / Watch ..., X, Reddit, Pinterest, LinkedIn, Threads). A plain address in the current tab.
 *  2. Refresh the feed when you come back: after Thazir was away for the chosen time (sm_resume, default 10 min) a social feed page
 *     (home / feed root only, never a chat, reel, post or a page you may be typing on) reloads once, like the app does. Switch sm_resume_on, default ON.
 */
object SocialQuick {
    class Q(val label: String, val url: String)

    fun links(id: String): List<Q> = when (id) {
        "instagram" -> listOf(Q("Following", "https://www.instagram.com/?variant=following"), Q("Favorites", "https://www.instagram.com/?variant=favorites"),
            Q("Reels", "https://www.instagram.com/reels/"), Q("Explore", "https://www.instagram.com/explore/"), Q("Messages", "https://www.instagram.com/direct/inbox/"),
            Q("Activity", "https://www.instagram.com/accounts/activity/"))
        "facebook" -> listOf(Q("Most recent", "https://m.facebook.com/?sk=h_chr"), Q("Friends", "https://m.facebook.com/friends/"), Q("Watch", "https://m.facebook.com/watch/"),
            Q("Marketplace", "https://m.facebook.com/marketplace/"), Q("Notifications", "https://m.facebook.com/notifications/"), Q("Messages", "https://www.facebook.com/messages/"))
        "x" -> listOf(Q("Notifications", "https://x.com/notifications"), Q("Messages", "https://x.com/messages"), Q("Bookmarks", "https://x.com/i/bookmarks"), Q("Explore", "https://x.com/explore"))
        "reddit" -> listOf(Q("Popular", "https://www.reddit.com/r/popular/"), Q("All", "https://www.reddit.com/r/all/"), Q("Chat", "https://www.reddit.com/chat/"), Q("Saved", "https://www.reddit.com/user/me/saved/"))
        "pinterest" -> listOf(Q("Today", "https://www.pinterest.com/today/"), Q("Following", "https://www.pinterest.com/following/"), Q("Saved", "https://www.pinterest.com/me/"), Q("Messages", "https://www.pinterest.com/messages/"))
        "linkedin" -> listOf(Q("My Network", "https://www.linkedin.com/mynetwork/"), Q("Jobs", "https://www.linkedin.com/jobs/"), Q("Messaging", "https://www.linkedin.com/messaging/"), Q("Notifications", "https://www.linkedin.com/notifications/"))
        "threads" -> listOf(Q("Activity", "https://www.threads.net/activity"), Q("Search", "https://www.threads.net/search"))
        else -> emptyList()
    }

    // ---- 0.0.308: address bar shortcuts: "ig:name", "fb:name", "x:name", "th:name", "pin:name", "li:name", "tt:name", "r/name", "u/name", "ig:#tag"
    private val SC = Regex("^(ig|insta|instagram|fb|facebook|x|tw|twitter|th|threads|pin|pinterest|li|linkedin|tt|tiktok)\\s*:\\s*([#@]?[A-Za-z0-9._-]{1,60})$", RegexOption.IGNORE_CASE)
    private val RD = Regex("^/?(r|u|user)/([A-Za-z0-9_-]{2,40})/?$", RegexOption.IGNORE_CASE)
    /** Address bar text -> the profile / community address, or null (then the normal URL / search logic runs). Needs the colon or r/ u/, so normal searches are not touched. */
    fun shortcut(input: String): String? {
        val t = input.trim()
        RD.matchEntire(t)?.let { m ->
            val k = m.groupValues[1].lowercase(); val n = m.groupValues[2]
            return if (k == "r") "https://www.reddit.com/r/$n/" else "https://www.reddit.com/user/$n/"
        }
        val m = SC.matchEntire(t) ?: return null
        val raw = m.groupValues[2]; val tag = raw.startsWith("#"); val n = raw.trimStart('#', '@')
        return when (m.groupValues[1].lowercase()) {
            "ig", "insta", "instagram" -> if (tag) "https://www.instagram.com/explore/tags/$n/" else "https://www.instagram.com/$n/"
            "fb", "facebook" -> if (tag) "https://m.facebook.com/hashtag/$n" else "https://m.facebook.com/$n"
            "x", "tw", "twitter" -> if (tag) "https://x.com/hashtag/$n" else "https://x.com/$n"
            "th", "threads" -> if (tag) "https://www.threads.net/search?q=%23$n" else "https://www.threads.net/@$n"
            "pin", "pinterest" -> "https://www.pinterest.com/$n/"
            "li", "linkedin" -> "https://www.linkedin.com/in/$n/"
            "tt", "tiktok" -> if (tag) "https://www.tiktok.com/tag/$n" else "https://www.tiktok.com/@$n"
            else -> null
        }
    }

    // ---- refresh when you come back
    private const val K_ON = "sm_resume_on"; private const val K_MIN = "sm_resume"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun resumeOn(c: Context) = sp(c).getBoolean(K_ON, true)
    fun setResumeOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_ON, v).apply() }
    val mins = listOf(5, 10, 30, 60)
    fun resumeMin(c: Context): Int = sp(c).getInt(K_MIN, 10).let { if (it in mins) it else 10 }
    fun cycleMin(c: Context): Int { val n = mins[(mins.indexOf(resumeMin(c)) + 1) % mins.size]; sp(c).edit().putInt(K_MIN, n).apply(); return n }

    private var leftAt = 0L
    fun onStop() { leftAt = SystemClock.elapsedRealtime() }

    /** MainActivity.onStart. */
    fun onStart(c: Context, tab: TabManager.Tab?) {
        val t = tab ?: return
        val left = leftAt; leftAt = 0L
        if (left == 0L || !resumeOn(c) || !SocialMode.on(c)) return
        if (SystemClock.elapsedRealtime() - left < resumeMin(c) * 60_000L) return
        if (t.isExt || t.home || t.suspended || t.loading) return
        val url = t.web.url ?: return
        val p = SocialProfiles.of(url) ?: return
        if (!SocialProfiles.siteOn(c, p.id) || SocialPull.isChat(url) || SocialReels.noPull(url)) return
        val path = try { Uri.parse(url).path ?: "" } catch (_: Exception) { return }
        if (path != "/" && path != "" && path != "/home" && path != "/feed/" && path != "/feed") return
        try { t.web.reload() } catch (_: Exception) { }
    }
}
