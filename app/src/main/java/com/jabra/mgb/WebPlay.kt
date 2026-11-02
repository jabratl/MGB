package com.jabra.mgb

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * S39: one place that decides "this web address is a video / audio stream" and sends it to the built-in player
 * (PlayerActivity) instead of Chromium's own media page or the V77 player.html page.
 * Used by TabsApi (V77 Play button = player.html?url=...), TabManager (link click, address bar, long press menu).
 */
object WebPlay {
    private const val TAG = "MGB.webplay"

    /** Direct media files and playlists that PlayerActivity (Media3 + HLS + DASH) can open from a link. */
    private val EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "ogv",
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac",
        "m3u8", "mpd"
    )

    /** True for http(s) links whose PATH ends with a media extension (query string is ignored). */
    fun isMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val u = Uri.parse(url)
            val s = u.scheme?.lowercase()
            if (s != "http" && s != "https") return false
            val last = (u.path ?: "").substringAfterLast('/')
            val ext = last.substringAfterLast('.', "").lowercase()
            ext.isNotEmpty() && ext in EXT
        } catch (e: Exception) {
            false
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    /** Settings > "Play video links in built-in player" (default ON). Only for links / address bar, not for the V77 Play button. */
    fun linksEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("web_play", true)

    fun setLinksEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("web_play", on).apply() }

    /** Title from the file name when the caller has none. */
    private fun nameOf(url: String): String = try {
        Uri.decode(Uri.parse(url).lastPathSegment ?: "").ifBlank { Util.host(url).ifBlank { "Video" } }
    } catch (e: Exception) {
        "Video"
    }

    /** Start the built-in player for a web address. Returns true when the player was started. */
    fun play(ctx: Context, url: String, title: String? = null, referer: String? = null, ua: String? = null): Boolean {
        val s = Uri.parse(url).scheme?.lowercase()
        if (s != "http" && s != "https") return false
        val act = ctx as? android.app.Activity
        if (act != null && (act.isFinishing || act.isDestroyed)) return false
        return try {
            PlayerActivity.startStream(ctx, url, if (title.isNullOrBlank()) nameOf(url) else title, referer, ua)
            true
        } catch (e: Exception) {
            Log.e(TAG, "play failed", e)
            Util.toast(ctx, "Cannot open the player")
            false
        }
    }

    /**
     * V77 "Play" asks for chrome.tabs.create({url: .../player.html?url=<media>&title=..&ref=..}).
     * Returns true when that was turned into a native play (no tab is created then). player.html without a url
     * (popup "Player" button: files / playlist page) is left alone.
     */
    fun fromExtPlayerUrl(ctx: Context, extUrl: String, ua: String?): Boolean {
        return try {
            val u = Uri.parse(extUrl)
            if (!extUrl.startsWith(ContentInjector.EXT_ORIGIN) || u.path?.endsWith("/player.html") != true) return false
            val media = u.getQueryParameter("url") ?: return false
            play(ctx, media, u.getQueryParameter("title"), u.getQueryParameter("ref"), ua)
        } catch (e: Exception) {
            false
        }
    }
}
