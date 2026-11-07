package com.jabra.mgb

import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Shared helpers used by every screen. Keep it small and dependency-free. */
object Util {

    const val DEFAULT_SEARCH = "https://www.google.com/search?q="

    // S33: search engine setting (prefs mgb_ui "engine")
    val ENGINES = listOf(
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Ecosia" to "https://www.ecosia.org/search?q=",
        "Startpage" to "https://www.startpage.com/do/search?q="
    )
    var searchBase: String = DEFAULT_SEARCH
    fun engineName(ctx: Context): String =
        ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getString("engine", "Google") ?: "Google"
    fun loadEngine(ctx: Context) {
        val n = engineName(ctx)
        searchBase = ENGINES.firstOrNull { it.first == n }?.second ?: DEFAULT_SEARCH
    }
    fun setEngine(ctx: Context, name: String) {
        ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putString("engine", name).apply()
        loadEngine(ctx)
    }

    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).roundToInt()

    fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun fmtBytes(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v >= 100) "${v.roundToInt()} ${units[i]}" else "${"%.1f".format(v)} ${units[i]}"
    }

    fun fmtSpeed(bytesPerSec: Long): String = fmtBytes(bytesPerSec) + "/s"

    fun fmtDuration(totalSec: Long): String {
        val s = if (totalSec < 0) 0 else totalSec
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun host(url: String): String =
        try { Uri.parse(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")

    /** Address-bar logic: a real URL stays a URL, anything else becomes a search. */
    fun toUrlOrSearch(input: String, searchBase: String = this.searchBase): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        SocialQuick.shortcut(t)?.let { return it } // 0.0.308: ig:name, fb:name, r/name ...
        if (t.startsWith("localhost", true)) return "http://$t"
        if (SCHEME.matches(t) && !t.contains(' ')) return t
        val looksHost = !t.contains(' ') && t.contains('.')
        return if (looksHost) "https://$t" else searchBase + URLEncoder.encode(t, "UTF-8")
    }
}
