package com.jabra.mgb

import android.content.Context
import android.net.Uri
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * WARM-UP WHEN OPENING A SITE (0.0.250, A11). Switch sm_pre (default ON) in mgb_ui.
 * Only does a DNS lookup (phone resolver cache) of the site host + one or two known asset hosts on a background thread, so the first page
 * starts a little faster. No page is loaded, nothing is sent to the site. At most once per host per 60 s. Every error is ignored.
 * Called from SocialMode.open (Open buttons, shortcut, link from another app, site window).
 */
object SocialPreload {
    private const val KEY = "sm_pre"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, true)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    // ASSUMPTION: asset hosts of the live sites (a wrong name only wastes one lookup). New site = one line.
    private val EXTRA = mapOf(
        "facebook" to listOf("static.xx.fbcdn.net"),
        "instagram" to listOf("static.cdninstagram.com"),
        "reddit" to listOf("www.redditstatic.com", "styles.redditmedia.com"),
        "x" to listOf("abs.twimg.com", "pbs.twimg.com"),
        "pinterest" to listOf("s.pinimg.com")
    )
    private val last = HashMap<String, Long>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "mgb-preload").apply { isDaemon = true } }

    fun warm(ctx: Context, url: String) {
        try {
            if (!on(ctx)) return
            val p = SocialProfiles.of(url) ?: return
            val host = Uri.parse(url).host ?: return
            val now = System.currentTimeMillis()
            for (h in listOf(host) + (EXTRA[p.id] ?: emptyList())) {
                val skip = synchronized(last) {
                    val t = last[h] ?: 0L
                    if (now - t < 60_000L) true else { last[h] = now; false }
                }
                if (skip) continue
                pool.execute { try { InetAddress.getByName(h) } catch (e: Exception) { /* warm-up only */ } }
            }
        } catch (e: Exception) { /* warm-up only */ }
    }
}
