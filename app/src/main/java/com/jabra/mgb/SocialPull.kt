package com.jabra.mgb

import android.content.Context

/**
 * 0.0.304: when pull-to-refresh (PullRefresh.kt) may start. Chats scroll inside the page (WebView.scrollY stays 0), so pulling the
 * chat list / thread down to read older messages used to reload the whole page.
 *
 * Global mode  sm_pull   0 Auto (default): off in chats + reels / stories / shorts, on elsewhere
 *                        1 On everywhere (old behaviour, except nothing is blocked)
 *                        2 Off on social sites (other sites keep it)
 * Per site     sm_pull_<id>  0 Follow the global mode, 1 always On, 2 always Off   (Social chip sheet and Settings > Social)
 * Chat page = Social profile of kind CHAT (Messenger, Facebook /messages, Discord, WhatsApp, Telegram, Snapchat web)
 *             or a path that starts with /messages /messaging /messenger /direct /chat /inbox (Instagram DMs, X, LinkedIn, Reddit chat...).
 * The main switch "Pull to refresh" (Settings > General) still decides first. Everything is read on every touch, so changes apply at once.
 */
object SocialPull {
    val modeNames = listOf("Auto (off in chats and reels)", "On everywhere", "Off on social sites")
    val siteNames = listOf("Auto", "On", "Off")
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun mode(c: Context): Int = sp(c).getInt("sm_pull", 0).coerceIn(0, 2)
    fun setMode(c: Context, v: Int) { sp(c).edit().putInt("sm_pull", v.coerceIn(0, 2)).apply() }
    fun site(c: Context, id: String): Int = sp(c).getInt("sm_pull_$id", 0).coerceIn(0, 2)
    fun setSite(c: Context, id: String, v: Int) { sp(c).edit().apply { if (v <= 0) remove("sm_pull_$id") else putInt("sm_pull_$id", v) }.apply() }

    private val CHAT_PATH = Regex("^/(messages|messaging|messenger|direct|chat|inbox)(/|$)", RegexOption.IGNORE_CASE)

    fun isChat(url: String?): Boolean {
        if (url == null) return false
        val p = SocialProfiles.of(url) ?: return false
        if (SocialMode.kind(url) == SocialMode.Kind.CHAT) return true
        val path = try { android.net.Uri.parse(url).path ?: "" } catch (_: Exception) { "" }
        return CHAT_PATH.containsMatchIn(path)
    }

    /** true = a pull down on this page may reload it. */
    fun allowed(c: Context, url: String?): Boolean {
        val p = SocialProfiles.of(url)
        if (p != null) {
            when (site(c, p.id)) { 1 -> return true; 2 -> return false }
            return when (mode(c)) { 1 -> true; 2 -> false; else -> !isChat(url) && !SocialReels.noPull(url) }
        }
        return mode(c) == 1 || !SocialReels.noPull(url)
    }
}
