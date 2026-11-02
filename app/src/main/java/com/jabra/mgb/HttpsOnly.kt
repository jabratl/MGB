package com.jabra.mgb

import android.content.Context

/**
 * S41: HTTPS-only mode (Settings > General, default OFF). A main-frame http:// address is opened as https://.
 * If that fails (TabManager.onReceivedError) the user is asked whether to open the http page once.
 * localhost, .local names and plain IP addresses are never upgraded.
 */
object HttpsOnly {
    private val IP = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun on(ctx: Context): Boolean = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean("https_only", false)

    fun set(ctx: Context, v: Boolean) { ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putBoolean("https_only", v).apply() }

    fun eligible(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h.isNotEmpty() && h != "localhost" && !h.endsWith(".local") && !h.startsWith("[") && !IP.matches(h)
    }

    /** https:// version of an http:// address, or null when it must stay as it is. */
    fun upgrade(url: String): String? {
        if (!url.startsWith("http://", true)) return null
        val host = try { android.net.Uri.parse(url).host } catch (e: Exception) { null }
        return if (eligible(host)) "https://" + url.substring(7) else null
    }
}
