package com.jabra.mgb

import android.content.Context

/**
 * KEEP SOCIAL TABS ALIVE (0.0.252, D1). Switch sm_keep (default OFF) in mgb_ui.
 * The background sweep (TabManager.sweep, "Suspend old tabs") frees the WebView of a tab that was not used for a while; coming back
 * reloads the page. With this switch ON a tab on a social site (Social sites ON + that site's switch ON) is skipped by the sweep,
 * so you come back to the same page and scroll position, like an app. Memory cost: at most MAX tabs are kept; the oldest social
 * tabs beyond that can still be suspended. Called from TabManager.sweep (1 line).
 */
object SocialKeep {
    private const val KEY = "sm_keep"
    const val MAX = 4
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    private fun isSocial(c: Context, t: TabManager.Tab): Boolean {
        val p = SocialProfiles.of(t.url) ?: return false
        return SocialProfiles.siteOn(c, p.id)
    }

    /** true = do not suspend [t]: it is one of the MAX most recently used social tabs. */
    fun keep(c: Context, tabs: List<TabManager.Tab>, t: TabManager.Tab): Boolean = try {
        if (!on(c) || !SocialMode.on(c) || !isSocial(c, t)) false
        else tabs.filter { !it.priv && !it.isExt && isSocial(c, it) }
            .sortedByDescending { it.lastActive }
            .take(MAX)
            .any { it === t }
    } catch (_: Throwable) { false }
}
