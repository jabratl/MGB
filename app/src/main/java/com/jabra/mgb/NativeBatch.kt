package com.jabra.mgb

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * S53: V77 tools inside the native Files detected panel (panel bottom bar > TOOLS).
 *  - Episodes / chapters batch: list comes from content.js (storage.local mgNativeCh), select / filter / range,
 *    start = V77 message batch-episodes (same as the Grabber page list), live status from storage.local mgBatchLive, Stop = batch-stop.
 *  - All subtitles (.srt / .vtt / .txt) = V77 subs-all, subtitles from the player = V77 subs-capture.
 *  - Play & scan = V77 play-scan, Open full Grabber (manga pages, jobs) = V77 open-panel in the page.
 * V77 does all the work; this only asks and shows.
 */
object NativeBatch {
    private val main = Handler(Looper.getMainLooper())
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    class Ep(val url: String, val label: String)
    class Found(val book: String, val eps: List<Ep>)

    private fun noFrag(u: String) = u.substringBefore('#')

    /** Episode / chapter list the page script found for this page (null = none). */
    fun found(ref: String): Found? {
        try {
            val o = ChromeBridge.local.get("mgNativeCh").optJSONObject("mgNativeCh") ?: return null
            if (noFrag(o.optString("url")) != noFrag(ref)) return null
            val a = o.optJSONArray("chapters") ?: return null
            val list = ArrayList<Ep>()
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val u = c.optString("url")
                if (u.isNotEmpty()) list.add(Ep(u, c.optString("label").ifBlank { u }))
            }
            if (list.isEmpty()) return null
            return Found(o.optString("book").ifBlank { "video" }, list)
        } catch (e: Exception) {
            return null
        }
    }

    class Mg(val n: Int, val site: String)

    /** S55: manga / gallery info the page script published (storage.local mgNativeMg); null = not a manga page. */
    fun manga(ref: String): Mg? {
        try {
            val o = ChromeBridge.local.get("mgNativeMg").optJSONObject("mgNativeMg") ?: return null
            if (noFrag(o.optString("url")) != noFrag(ref)) return null
            if (!o.optBoolean("has", false)) return null
            return Mg(o.optInt("n", 0), o.optString("site"))
        } catch (e: Exception) {
            return null
        }
    }

    /** S55: V77 does the work: how = cbz / zip / pdf / epub / read / view (pages screen). */
    fun grab(act: Activity, tabId: Int, how: String) {
        EngineApi.send(JSONObject().put("type", "grab-pages").put("tabId", tabId).put("how", how))
        Util.toast(act, "Loading all pages...")
    }

    private fun mangaMenu(act: Activity, tabId: Int, closePanel: () -> Unit) {
        val how = arrayOf("cbz", "zip", "pdf", "epub", "read", "view")
        MaterialAlertDialogBuilder(act).setTitle("Manga pages")
            .setItems(arrayOf("Save as CBZ", "Save as ZIP", "Save as PDF", "Save as EPUB", "Read", "Pick pages")) { _, i ->
                grab(act, tabId, how[i])
                closePanel()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- S58: batch options (quality = V77 maxHeight, if missing = batch-episodes miss, parallel = V77 batchParallel) ----
    private val MISS = arrayOf("near", "low", "high", "skip")
    private val MISS_NAMES = arrayOf("Closest quality", "Lower quality", "Higher quality", "Skip the episode")

    private fun prefs(c: android.content.Context) = c.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
    private fun missOf(c: android.content.Context): String = (prefs(c).getString("nb_miss", "near") ?: "near").let { if (it in MISS) it else "near" }
    private fun parOf(): Int = try { ChromeBridge.sync.get("batchParallel").optInt("batchParallel", 1).coerceIn(1, 4) } catch (e: Exception) { 1 }
    private fun qOf(): String = DetectedPanel.maxQ().let { if (it > 0) "up to ${it}p" else "Best" }

    fun optionsText(act: Activity): String =
        "Quality: " + qOf() + "   ·   if missing: " + MISS_NAMES[MISS.indexOf(missOf(act))].lowercase() + "   ·   " + parOf() + " at a time   ▸"

    private fun choice(act: Activity, title: String, names: Array<String>, cur: Int, pick: (Int) -> Unit, back: () -> Unit) {
        MaterialAlertDialogBuilder(act).setTitle(title)
            .setSingleChoiceItems(names, cur) { d, i -> pick(i); d.dismiss(); back() }
            .setNegativeButton("Back") { _, _ -> back() }.show()
    }

    private fun batchOptions(act: Activity, changed: () -> Unit) {
        val items = arrayOf(
            "Video quality:  " + qOf(),
            "If the quality is missing:  " + MISS_NAMES[MISS.indexOf(missOf(act))],
            "Episodes searched at the same time:  " + parOf()
        )
        val again = { changed(); batchOptions(act, changed) }
        MaterialAlertDialogBuilder(act).setTitle("Batch options")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val v = intArrayOf(0, 2160, 1440, 1080, 720, 480, 360)
                        val n = arrayOf("Best available", "Up to 2160p (4K)", "Up to 1440p", "Up to 1080p", "Up to 720p", "Up to 480p", "Up to 360p")
                        choice(act, "Video quality (best up to)", n, v.indexOf(DetectedPanel.maxQ()).let { if (it < 0) 0 else it },
                            { k -> ChromeBridge.storageSet("sync", JSONObject().put("maxHeight", v[k])) }, again)
                    }
                    1 -> choice(act, "If the quality is missing", MISS_NAMES, MISS.indexOf(missOf(act)),
                        { k -> prefs(act).edit().putString("nb_miss", MISS[k]).apply() }, again)
                    else -> choice(act, "Episodes at the same time", arrayOf("1 (in order, safest)", "2", "3", "4"), parOf() - 1,
                        { k -> ChromeBridge.storageSet("sync", JSONObject().put("batchParallel", k + 1)) }, again)
                }
            }
            .setPositiveButton("Done") { _, _ -> changed() }.show()
    }

    fun tools(act: Activity, tabId: Int, ref: String, subCount: Int, shownCount: Int, downloadAll: (() -> Unit)?, closePanel: () -> Unit) {
        val f = found(ref)
        val mg = manga(ref)
        val names = ArrayList<String>()
        val runs = ArrayList<() -> Unit>()
        if (mg != null) { names.add("Manga pages" + (if (mg.n > 0) " (${mg.n})" else "")); runs.add { mangaMenu(act, tabId, closePanel) } }
        if (f != null) { names.add("Episodes / chapters (${f.eps.size})"); runs.add { episodes(act, tabId, f) } }
        if (shownCount > 0 && downloadAll != null) { names.add("Download all ($shownCount files)"); runs.add { downloadAll() } } // S56
        names.add("Buttons on this site: " + DlMode.siteLabel(act, Util.host(ref))); runs.add { DlMode.siteDialog(act, Util.host(ref)) {} } // S57
        names.add("Paste links (batch)"); runs.add { pasteLinks(act, tabId, ref) } // S56
        if (subCount > 0) { names.add("All subtitles of this page ($subCount)"); runs.add { subFormat(act, tabId) } }
        names.add("Subtitles from the player"); runs.add { subsCapture(act, tabId) }
        names.add("Play and scan (find hidden videos)"); runs.add {
            EngineApi.send(JSONObject().put("type", "play-scan").put("tabId", tabId))
            Util.toast(act, "Scanning... open Detected files again in a few seconds")
        }
        names.add("Open full Grabber (manga pages, jobs)"); runs.add {
            EngineApi.send(JSONObject().put("type", "open-panel").put("tabId", tabId))
            closePanel()
        }
        MaterialAlertDialogBuilder(act).setTitle("More tools")
            .setItems(names.toTypedArray()) { _, i -> runs[i]() }
            .setNegativeButton("Close", null).show()
    }

    /** S56: list of pasted page links -> the same V77 batch-episodes as the episode list. */
    private fun pasteLinks(act: Activity, tabId: Int, ref: String) {
        val et = EditText(act).apply {
            hint = "One page link per line"
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(act.getColor(R.color.mg_text))
            setHintTextColor(act.getColor(R.color.mg_text_dim))
        }
        try {
            val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() ?: ""
            if (t.contains("http")) et.setText(t)
        } catch (e: Exception) { /* no clipboard access */ }
        val pad = Util.dp(act, 20)
        val box = LinearLayout(act).apply { setPadding(pad, Util.dp(act, 8), pad, 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) }
        MaterialAlertDialogBuilder(act).setTitle("Paste links").setView(box)
            .setPositiveButton("Next") { _, _ ->
                val urls = Regex("https?://[^\\s\"'<>]+").findAll(et.text.toString()).map { it.value }.distinct().take(300).toList()
                if (urls.isEmpty()) { Util.toast(act, "No link found"); return@setPositiveButton }
                val eps = urls.mapIndexed { i, u ->
                    val slug = try { java.net.URLDecoder.decode(u.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/'), "UTF-8") } catch (e: Exception) { "" }
                    val nm = slug.replace('-', ' ').replace('_', ' ').trim().take(60)
                    Ep(u, if (nm.isNotBlank()) nm else "Link ${i + 1}")
                }
                episodes(act, tabId, Found(found(ref)?.book ?: "video", eps), true)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun subFormat(act: Activity, tabId: Int) {
        val fm = arrayOf("srt", "vtt", "txt")
        MaterialAlertDialogBuilder(act).setTitle("Save all subtitles as")
            .setItems(arrayOf(".srt", ".vtt", ".txt")) { _, i ->
                EngineApi.ask(JSONObject().put("type", "subs-all").put("tabId", tabId).put("fmt", fm[i])) { r ->
                    val n = r?.optInt("n", 0) ?: 0
                    Util.toast(act, if (n > 0) "$n subtitle file(s) saved" else "No subtitle saved")
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun subsCapture(act: Activity, tabId: Int) {
        Util.toast(act, "Reading subtitles from the player...")
        EngineApi.ask(JSONObject().put("type", "subs-capture").put("tabId", tabId).put("fmt", "srt")) { r ->
            val n = r?.optInt("n", 0) ?: 0
            val err = r?.optString("err") ?: ""
            Util.toast(act, if (n > 0) "$n subtitle file(s) saved" else if (err.isNotEmpty()) err else "No subtitles found in the player")
        }
    }

    private fun episodes(act: Activity, tabId: Int, f: Found, preselect: Boolean = false) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        fun dp(v: Int) = Util.dp(act, v)
        val d = BottomSheetDialog(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            layoutParams = ViewGroup.LayoutParams(MATCH, (act.resources.displayMetrics.heightPixels * 0.85f).toInt())
        }
        root.addView(TextView(act).apply {
            text = "Episodes / chapters (${f.eps.size})"
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val filter = EditText(act).apply { hint = "Filter by name"; setSingleLine(); setTextColor(cText); setHintTextColor(cDim) }
        val range = EditText(act).apply { hint = "Range, e.g. 1-12 (numbers of the list)"; setSingleLine(); setTextColor(cText); setHintTextColor(cDim) }
        val allCb = CheckBox(act).apply { text = "Select all shown"; setTextColor(cText) }
        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(act).apply { addView(listBox) }
        val status = TextView(act).apply { setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, dp(6), 0, dp(6)) }
        fun pill(t: String, bg: Int, fg: Int) = TextView(act).apply {
            text = t
            gravity = Gravity.CENTER
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(24).toFloat() }
        }
        val btn = pill("DOWNLOAD (0)", cPrimary, Color.WHITE)
        val stop = pill("STOP", 0xFFE53935.toInt(), Color.WHITE)
        val close = pill("CLOSE", act.getColor(R.color.mg_surface_variant), cText)

        val sel = LinkedHashSet<String>()
        var polling = false
        var building = false
        fun shown(): List<Ep> {
            val q = filter.text.toString().trim().lowercase()
            return if (q.isEmpty()) f.eps else f.eps.filter { it.label.lowercase().contains(q) }
        }
        fun upd() {
            btn.text = "DOWNLOAD (${sel.size})"
            btn.alpha = if (sel.isEmpty()) 0.4f else 1f
        }
        fun fillList() {
            building = true
            listBox.removeAllViews()
            for (e in shown()) {
                val pos = f.eps.indexOf(e) + 1
                val cb = CheckBox(act).apply {
                    text = "$pos. ${e.label}"
                    setTextColor(cText)
                    isChecked = sel.contains(e.url)
                    setOnCheckedChangeListener { _, on ->
                        if (!building) { if (on) sel.add(e.url) else sel.remove(e.url); upd() }
                    }
                }
                listBox.addView(cb, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            building = false
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fillList() }
        })
        range.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val m = Regex("^\\s*(\\d+)\\s*(?:[-–]\\s*(\\d+))?\\s*$").find(s?.toString() ?: "") ?: return
                val a = m.groupValues[1].toInt()
                val z = if (m.groupValues[2].isEmpty()) a else m.groupValues[2].toInt()
                sel.clear()
                for (e in shown()) { val p = f.eps.indexOf(e) + 1; if (p in minOf(a, z)..maxOf(a, z)) sel.add(e.url) }
                fillList()
                upd()
            }
        })
        allCb.setOnCheckedChangeListener { _, on ->
            for (e in shown()) { if (on) sel.add(e.url) else sel.remove(e.url) }
            fillList()
            upd()
        }
        close.setOnClickListener { d.dismiss() }

        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        top.addView(filter, LinearLayout.LayoutParams(MATCH, WRAP))
        top.addView(range, LinearLayout.LayoutParams(MATCH, WRAP))
        val optBtn = TextView(act).apply { // S58
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(act.getColor(R.color.mg_surface_variant)); cornerRadius = dp(14).toFloat() }
            text = optionsText(act)
        }
        optBtn.setOnClickListener { batchOptions(act) { optBtn.text = optionsText(act) } }
        top.addView(optBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(2) })
        top.addView(allCb, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        bar.addView(close, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        if (preselect) f.eps.forEach { sel.add(it.url) } // S56: pasted list starts fully selected
        fillList()
        upd()

        val rows = HashMap<String, TextView>()
        fun pollOnce(picks: List<Ep>) {
            val b = try { ChromeBridge.local.get("mgBatchLive").optJSONObject("mgBatchLive") } catch (e: Exception) { null } ?: return
            if (b.optInt("tab", -1) != tabId) return
            val total = b.optInt("total", picks.size)
            status.text = b.optString("text") + "   (" + b.optInt("ok", 0) + "/" + total + ")"
            val eps = b.optJSONObject("eps")
            for (e in picks) {
                val st = eps?.optJSONObject(e.url)?.optString("st") ?: ""
                rows[e.url]?.text = e.label + (if (st.isNotEmpty()) "   [" + st + "]" else "")
            }
        }
        btn.setOnClickListener {
            if (polling) return@setOnClickListener
            if (sel.isEmpty()) { Util.toast(act, "Select episodes first"); return@setOnClickListener }
            val picks = f.eps.filter { sel.contains(it.url) }
            val arr = JSONArray()
            for (e in picks) arr.put(JSONObject().put("url", e.url).put("label", e.label).put("book", f.book).put("title", f.book + " - " + e.label))
            val cap = DetectedPanel.maxQ().let { if (it > 0) it.toString() else "" }
            EngineApi.send(JSONObject().put("type", "batch-episodes").put("tabId", tabId).put("cap", cap).put("miss", missOf(act)).put("episodes", arr))
            Util.toast(act, "Finding videos in ${picks.size} episode(s)...")
            // status view
            polling = true
            top.visibility = android.view.View.GONE
            listBox.removeAllViews()
            for (e in picks) {
                val t = TextView(act).apply { text = e.label; setTextColor(cText); setPadding(0, dp(6), 0, dp(6)) }
                rows[e.url] = t
                listBox.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            status.text = "Starting the batch..."
            bar.removeAllViews()
            bar.addView(stop, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            bar.addView(close, LinearLayout.LayoutParams(0, WRAP, 1f))
            val tick = object : Runnable {
                override fun run() {
                    if (!polling) return
                    pollOnce(picks)
                    main.postDelayed(this, 2000)
                }
            }
            main.postDelayed(tick, 1500)
        }
        stop.setOnClickListener {
            EngineApi.send(JSONObject().put("type", "batch-stop").put("tabId", tabId))
            Util.toast(act, "Stopping...")
        }
        d.setOnDismissListener { polling = false }
        d.setContentView(root)
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
