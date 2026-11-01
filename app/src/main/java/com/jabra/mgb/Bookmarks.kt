package com.jabra.mgb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * S13: bookmarks (also the speed dial tiles) and browsing history, stored as JSON in filesDir.
 *  - mgb_bookmarks.json: seeded with a few sites on the first run, then fully user controlled
 *  - mgb_history.json: newest first, max MAX_HISTORY entries, same url twice in a row = one entry
 * All calls are cheap and safe from the main thread; the disk write runs on one background thread.
 */
object Bookmarks {
    class Item(val title: String, val url: String, val time: Long)

    private const val MAX_HISTORY = 500
    private const val F_BM = "mgb_bookmarks.json"
    private const val F_HIST = "mgb_history.json"

    private var dir: File? = null
    private val bm = ArrayList<Item>()
    private val hist = ArrayList<Item>()
    private val io = Executors.newSingleThreadExecutor()

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        val d = ctx.applicationContext.filesDir
        dir = d
        val fb = File(d, F_BM)
        if (fb.exists()) read(fb, bm) else {
            val now = System.currentTimeMillis()
            bm.add(Item("Google", "https://www.google.com", now))
            bm.add(Item("YouTube", "https://www.youtube.com", now))
            bm.add(Item("Wikipedia", "https://www.wikipedia.org", now))
            bm.add(Item("GitHub", "https://github.com", now))
            saveAsync(F_BM, bm)
        }
        read(File(d, F_HIST), hist)
    }

    private fun read(f: File, out: ArrayList<Item>) {
        try {
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                if (u.isNotEmpty()) out.add(Item(o.optString("t"), u, o.optLong("w")))
            }
        } catch (e: Exception) { /* broken file: start empty */ }
    }

    private fun saveAsync(name: String, list: List<Item>) {
        val d = dir ?: return
        val arr = JSONArray()
        for (x in list) arr.put(JSONObject().put("t", x.title).put("u", x.url).put("w", x.time))
        val text = arr.toString()
        io.execute {
            try {
                val tmp = File(d, "$name.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(File(d, name))) { File(d, name).writeText(text); tmp.delete() }
            } catch (e: Exception) { /* storage full etc: keep running */ }
        }
    }

    // ---------- bookmarks ----------

    @Synchronized fun bookmarks(): List<Item> = bm.toList()

    @Synchronized fun isBookmarked(url: String): Boolean = bm.any { it.url == url }

    @Synchronized
    fun addBookmark(title: String, url: String) {
        if (url.isBlank() || bm.any { it.url == url }) return
        bm.add(Item(title.ifBlank { Util.host(url) }, url, System.currentTimeMillis()))
        saveAsync(F_BM, bm)
    }

    @Synchronized
    fun removeBookmark(url: String) {
        if (bm.removeAll { it.url == url }) saveAsync(F_BM, bm)
    }

    // ---------- history ----------

    @Synchronized fun history(): List<Item> = hist.toList()

    @Synchronized
    fun addHistory(title: String, url: String) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        val top = hist.firstOrNull()
        if (top != null && top.url == url) hist.removeAt(0)
        hist.add(0, Item(title.ifBlank { Util.host(url) }, url, now))
        while (hist.size > MAX_HISTORY) hist.removeAt(hist.size - 1)
        saveAsync(F_HIST, hist)
    }

    @Synchronized
    fun removeHistory(time: Long, url: String) {
        if (hist.removeAll { it.time == time && it.url == url }) saveAsync(F_HIST, hist)
    }

    @Synchronized
    fun clearHistory() {
        hist.clear()
        saveAsync(F_HIST, hist)
    }
}
