package com.jabra.mgb

import android.content.Context
import android.net.ConnectivityManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * DATA SAVER PLUS (0.0.266, C7). Switch sm_save2 (default OFF) in mgb_ui.
 * When ON, a social tab (site switch ON) does not download video files while the phone is on a METERED connection (mobile data, or a
 * Wi-Fi the phone marks as metered). Wi-Fi = nothing is blocked. Only addresses that look like a video file (.mp4 .webm .m3u8 .mpd .mov)
 * are blocked, the page itself, pictures and everything else load as before. Streaming sites that use other video addresses are NOT covered.
 * Same style as SocialNsfw.intercept: fail-open (any error = the request goes on). Nothing is stored or sent out.
 */
object SocialSave2 {
    private const val KEY = "sm_save2"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    /** Videos blocked since the app started (shown in the settings summary). */
    @Volatile var blocked: Int = 0

    private val VIDEO = Regex("\\.(mp4|webm|m3u8|mpd|mov)(\\?|#|$)", RegexOption.IGNORE_CASE)

    private fun metered(c: Context): Boolean = try {
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Throwable) { false }

    /** null = let the request go on. Called from TabManager.shouldInterceptRequest (web pages only). */
    fun intercept(c: Context, tabUrl: String, r: WebResourceRequest): WebResourceResponse? {
        try {
            if (!on(c) || r.isForMainFrame || !SocialMode.on(c)) return null
            val p = SocialProfiles.of(tabUrl) ?: return null
            if (!SocialProfiles.siteOn(c, p.id)) return null
            if (!VIDEO.containsMatchIn(r.url.toString())) return null
            if (!metered(c)) return null
            blocked++
            return WebResourceResponse("text/plain", "utf-8", 404, "Blocked by data saver", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        } catch (_: Throwable) {
            return null
        }
    }
}
