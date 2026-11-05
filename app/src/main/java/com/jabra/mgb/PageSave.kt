package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** T3: "Save page" = one .mht web archive per page in filesDir/saved_pages + index.json. Opened offline (no JS, no network). */
object PageSave {
    private const val DIR = "saved_pages"
    private const val INDEX = "index.json"
    const val MAX = 100

    class Entry(val id: String, val title: String, val url: String, val time: Long, val size: Long)

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).also { it.mkdirs() }

    fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.mht")

    /** Newest first. */
    @Synchronized
    fun list(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val f = File(dir(ctx), INDEX)
            if (!f.exists()) return out
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Entry(o.getString("id"), o.optString("title"), o.optString("url"), o.optLong("time"), o.optLong("size")))
            }
        } catch (e: Exception) { /* broken index: treated as empty */ }
        return out.sortedByDescending { it.time }
    }

    fun count(ctx: Context): Int = list(ctx).size

    @Synchronized
    private fun write(ctx: Context, items: List<Entry>) {
        val a = JSONArray()
        for (e in items) a.put(JSONObject().put("id", e.id).put("title", e.title).put("url", e.url).put("time", e.time).put("size", e.size))
        File(dir(ctx), INDEX).writeText(a.toString())
    }

    fun add(ctx: Context, e: Entry) = write(ctx, list(ctx) + e)

    fun remove(ctx: Context, id: String) {
        write(ctx, list(ctx).filter { it.id != id })
        file(ctx, id).delete()
    }

    fun fileUrl(ctx: Context, id: String): String = "file://" + file(ctx, id).absolutePath

    /** True for an address inside our own saved_pages folder. */
    fun isSaved(ctx: Context, url: String): Boolean = url.startsWith("file://" + dir(ctx).absolutePath + "/")

    /** Offline view flags for ONE tab (never touch other tabs): file access on, JavaScript off, network blocked. */
    fun mark(t: TabManager.Tab) {
        t.savedPage = true
        t.web.settings.apply {
            allowFileAccess = true
            javaScriptEnabled = false
            blockNetworkLoads = true
        }
    }

    // ---- save

    fun save(act: Activity, t: TabManager.Tab) {
        if (t.priv || t.home || t.isExt || t.savedPage || !t.url.startsWith("http")) { Util.toast(act, "This page cannot be saved"); return }
        if (count(act) >= MAX) { Util.toast(act, "Limit reached ($MAX pages). Delete one first."); return }
        val et = EditText(act).apply {
            setText(t.web.title?.takeIf { it.isNotBlank() } ?: t.title.ifBlank { Util.host(t.url) })
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setTextColor(act.getColor(R.color.mg_text))
        }
        val box = FrameLayout(act).apply { setPadding(Util.dp(act, 20), Util.dp(act, 8), Util.dp(act, 20), 0); addView(et) }
        MaterialAlertDialogBuilder(act)
            .setTitle("Save page")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { Util.host(t.url) }
                val id = java.lang.Long.toString(System.currentTimeMillis(), 36)
                val f = file(act, id)
                val url = t.web.url ?: t.url
                try {
                    t.web.saveWebArchive(f.absolutePath, false) { path ->
                        if (path == null || !f.exists() || f.length() == 0L) { f.delete(); Util.toast(act, "Could not save this page") }
                        else { add(act, Entry(id, name, url, System.currentTimeMillis(), f.length())); Util.toast(act, "Page saved") }
                    }
                } catch (e: Exception) { Util.toast(act, "Could not save this page") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- minimal list (T4 replaces it with a screen)

    fun dialog(act: Activity, tabs: TabManager) {
        val items = list(act)
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(act).setTitle("Saved pages").setMessage("No saved pages yet. Use Save page in the menu while a web page is open.")
                .setPositiveButton("Close", null).show()
            return
        }
        val df = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val labels = items.map { it.title + "\n" + Util.host(it.url) + " · " + df.format(Date(it.time)) + " · " + Util.fmtBytes(it.size) }
        val lv = ListView(act)
        lv.addFooterView(TextView(act).apply {
            text = "Tap = open. Long press = delete."
            textSize = 12f
            setTextColor(act.getColor(R.color.mg_text_dim))
            gravity = Gravity.CENTER
            setPadding(0, Util.dp(act, 12), 0, Util.dp(act, 12))
        }, null, false)
        lv.adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, labels)
        val total = items.sumOf { it.size }
        val dlg = MaterialAlertDialogBuilder(act).setTitle("Saved pages (" + items.size + ", " + Util.fmtBytes(total) + ")")
            .setView(lv).setPositiveButton("Close", null).create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            val e = items.getOrNull(pos) ?: return@setOnItemClickListener
            dlg.dismiss()
            open(act, tabs, e)
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            val e = items.getOrNull(pos) ?: return@setOnItemLongClickListener true
            MaterialAlertDialogBuilder(act).setTitle("Delete saved page?").setMessage(e.title)
                .setPositiveButton("Delete") { _, _ -> remove(act, e.id); dlg.dismiss(); dialog(act, tabs) }
                .setNegativeButton("Cancel", null).show()
            true
        }
        dlg.show()
    }

    fun open(act: Activity, tabs: TabManager, e: Entry) {
        if (!file(act, e.id).exists()) { Util.toast(act, "File is missing"); return }
        tabs.openSaved(fileUrl(act, e.id), e.title)
    }
}
