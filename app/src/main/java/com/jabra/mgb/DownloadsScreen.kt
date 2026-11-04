package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * S12: native downloads manager (full-screen window, no manifest entry needed).
 *  - running / finished / failed jobs come straight from V77 (storage.session mgJobs), refreshed every second
 *  - Pause / Resume / Cancel / Remove = the same messages V77 Queue page sends (job-pause, job-cancel, job-remove)
 *  - "Saved files" (storage.local mgHist): Open / Show call DownloadsApi.openFile / showFile (S65; before: engine eval)
 *  - S66: search box, More menu / long press on a saved file: Share, Remove from list, Delete file
 *  - Clear = jobs-clear (removes everything that is not running)
 * Not here: Share (needs the file uri from DownloadsApi), notification buttons (DownloadService).
 */
object DownloadsScreen {
    private const val TAG = "MGB.dlscreen"
    private var current: Screen? = null

    fun open(host: Activity) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    fun closeAll() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {
        private val main = Handler(Looper.getMainLooper())
        private var list: LinearLayout? = null
        private var empty: TextView? = null
        private var sig = ""
        private var query = "" // S66: search box
        private val tick = object : Runnable {
            override fun run() {
                render()
                main.postDelayed(this, 1000)
            }
        }

        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener {
                main.removeCallbacks(tick)
                if (current === this) current = null
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.screen_downloads)
            window?.let {
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            list = findViewById(R.id.xd_list)
            empty = findViewById(R.id.xd_empty)
            findViewById<View>(R.id.xd_back).setOnClickListener { dismiss() }
            findViewById<View>(R.id.xd_close).setOnClickListener { dismiss() }
            // S25: same top pills as the Bookmarks screen (needs BookmarksScreen.tabsRef, set by MainActivity)
            val title = findViewById<View>(R.id.xd_title)
            val hdr = title?.parent as? ViewGroup
            val tabsRef = BookmarksScreen.tabsRef
            if (hdr != null && tabsRef != null) {
                val at = hdr.indexOfChild(title)
                hdr.removeView(title)
                hdr.addView(BookmarksScreen.pills(act, 2) { i ->
                    if (i != 2) {
                        dismiss()
                        BookmarksScreen.open(act, if (i == 0) BookmarksScreen.Mode.BOOKMARKS else BookmarksScreen.Mode.HISTORY, tabsRef)
                    }
                }, at, android.widget.LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                findViewById<View>(R.id.xd_close).visibility = View.GONE // the back arrow already closes
            }
            findViewById<android.widget.EditText>(R.id.xd_search)?.let { et -> // S66
                et.background = round(act.getColor(R.color.mg_surface_variant), 22)
                et.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString().orEmpty(); sig = ""; render() }
                })
            }
            findViewById<View>(R.id.xd_clear)?.visibility = View.GONE // 0.0.126: Clear did nothing, hidden (id + layout kept)
            main.post(tick)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { dismiss() }

        private fun pctOf(j: JSONObject): Int = (j.optDouble("pct", 0.0).coerceIn(0.0, 1.0) * 100).toInt()

        /** S66: saved files, newest first; with a search text every file is searched (max 100), else the latest 30. */
        private fun histOf(): List<JSONObject> {
            val all = EngineApi.history()
            val q = query.trim().lowercase()
            return if (q.isEmpty()) all.take(30) else all.filter { it.optString("title").lowercase().contains(q) }.take(100)
        }

        /** S66: Share / Remove from list / Delete file. */
        private fun fileMenu(h: JSONObject) {
            val id = h.optInt("id", 0)
            val names = if (id > 0) arrayOf("Share", "Remove from list", "Delete file from phone") else arrayOf("Remove from list")
            MaterialAlertDialogBuilder(act)
                .setTitle(h.optString("title").ifBlank { "file" })
                .setItems(names) { _, w ->
                    when (names[w]) {
                        "Share" -> DownloadsApi.shareFile(act, id)
                        "Remove from list" -> removeFromList(h)
                        else -> MaterialAlertDialogBuilder(act)
                            .setTitle("Delete this file from your phone?")
                            .setPositiveButton("Delete") { _, _ ->
                                Util.toast(act, if (DownloadsApi.deleteFile(id)) "File deleted" else "File not found")
                                removeFromList(h)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                .show()
        }

        /** Same as the V77 history page Remove: drops the entry (id + time) from storage.local mgHist. */
        private fun removeFromList(h: JSONObject) {
            val a = try { ChromeBridge.local.get("mgHist").optJSONArray("mgHist") } catch (e: Exception) { null } ?: return
            val out = org.json.JSONArray()
            for (k in 0 until a.length()) {
                val x = a.optJSONObject(k) ?: continue
                if (!(x.optInt("id") == h.optInt("id") && x.optLong("time") == h.optLong("time"))) out.put(x)
            }
            ChromeBridge.storageSet("local", JSONObject().put("mgHist", out))
            sig = ""
            render()
        }

        private fun render() {
            val box = list ?: return
            val jobs = EngineApi.jobs()
            val hist = histOf()
            val s = jobs.joinToString("|") {
                it.optString("key") + ":" + it.optString("state") + ":" + pctOf(it) + ":" + it.optBoolean("paused") + ":" + it.optString("text")
            } + "?" + query + "#" + hist.joinToString("|") { it.optString("id") + ":" + it.optLong("time") }
            if (s == sig) return
            sig = s
            box.removeAllViews()
            empty?.visibility = if (jobs.isEmpty() && hist.isEmpty()) View.VISIBLE else View.GONE
            empty?.text = if (query.isBlank()) "No downloads yet\nFiles you save show up here" else "No saved file matches"
            val running = jobs.filter { it.optString("state") == "run" }
            val other = jobs.filter { it.optString("state") != "run" }
            if (running.isNotEmpty()) box.addView(section("Downloading (${running.size})"))
            for (j in running) box.addView(jobRow(j))
            if (other.isNotEmpty()) box.addView(section("Finished"))
            for (j in other) box.addView(jobRow(j))
            if (hist.isNotEmpty()) box.addView(section("Saved files (${hist.size})"))
            for (h in hist) box.addView(fileRow(h))
        }

        // ---------- S65: card design (surface cards, icon tile, pill buttons) ----------

        private fun round(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

        private fun section(t: String): View = TextView(act).apply {
            text = t
            setTextColor(act.getColor(R.color.mg_text_dim))
            textSize = 12f
            letterSpacing = 0.04f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(18), dp(16), dp(6))
        }

        private fun button(label: String, primary: Boolean = false, click: () -> Unit): MaterialButton =
            MaterialButton(act).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                minimumWidth = 0
                minWidth = 0
                minimumHeight = 0
                minHeight = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(18)
                setPadding(dp(18), 0, dp(18), 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(act.getColor(if (primary) R.color.mg_primary else R.color.mg_surface_variant))
                setTextColor(act.getColor(if (primary) R.color.mg_on_primary else R.color.mg_text))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) }
                setOnClickListener { click() }
            }

        private fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(10))
            background = round(act.getColor(R.color.mg_surface), 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(12), dp(5), dp(12), dp(5)) }
        }

        /** Round tile with an icon, tinted with the accent colour. */
        private fun tile(icon: Int): View = android.widget.ImageView(act).apply {
            setImageResource(icon)
            setColorFilter(act.getColor(R.color.mg_primary))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = round(androidx.core.graphics.ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 40), 12)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        }

        private fun label(t: String, size: Float, color: Int, lines: Int): TextView = TextView(act).apply {
            text = t
            setTextColor(color)
            textSize = size
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }

        private fun actionRow(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }

        private fun jobRow(j: JSONObject): View {
            val key = j.optString("key")
            val state = j.optString("state")
            val c = card()
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tile(if (state == "run") R.drawable.ic_download else if (state == "done") R.drawable.ic_check else R.drawable.ic_info))
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(j.optString("title").ifBlank { key }, 15f, act.getColor(R.color.mg_text), 2))
            val status = when (state) {
                "run" -> (if (j.optBoolean("paused")) "Paused · " else "") + pctOf(j) + "%" +
                    j.optString("text").let { if (it.isBlank()) "" else " · $it" }
                "done" -> "Done"
                else -> j.optString("text").ifBlank { "Failed" }
            }
            col.addView(label(status, 12f, act.getColor(when (state) { "done" -> R.color.mg_success; "run" -> R.color.mg_text_dim; else -> R.color.mg_error }), 2))
            head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(head)
            if (state == "run") {
                c.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = pctOf(j)
                    progressTintList = android.content.res.ColorStateList.valueOf(act.getColor(R.color.mg_primary))
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(act.getColor(R.color.mg_surface_variant))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
            }
            val actions = actionRow()
            if (state == "run") {
                val paused = j.optBoolean("paused")
                actions.addView(button(if (paused) "Resume" else "Pause", paused) {
                    EngineApi.send(JSONObject().put("type", "job-pause").put("key", key).put("on", !paused))
                })
                actions.addView(button("Cancel") { EngineApi.send(JSONObject().put("type", "job-cancel").put("key", key)) })
            } else {
                if (state != "done") actions.addView(button("Why?") { // S86: explain this failure
                    DiagScreen.open(act, null, -1, j.optString("text").ifBlank { j.optString("error") }, j.optString("url"))
                })
                actions.addView(button("Remove") {
                    EngineApi.send(JSONObject().put("type", "job-remove").put("key", key))
                    main.postDelayed({ sig = ""; render() }, 400)
                })
            }
            c.addView(actions)
            return c
        }

        private fun fileRow(h: JSONObject): View {
            val id = h.optInt("id", 0)
            val c = card()
            val title = h.optString("title").ifBlank { "file" }
            val ext = title.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..4 && !it.contains(' ') }
            val media = ext in setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
            val size = EngineApi.fmtSize(h.optLong("size", 0))
            val ago = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(h.optLong("time")))
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tile(if (media) R.drawable.ic_play else R.drawable.ic_download))
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(title, 15f, act.getColor(R.color.mg_text), 2))
            col.addView(label(listOfNotNull(ext?.uppercase(), size.ifEmpty { null }, ago).joinToString(" · "), 12f, act.getColor(R.color.mg_text_dim), 1))
            head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(android.widget.ImageView(act).apply { // S66: more menu
                setImageResource(R.drawable.ic_more)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "More"
                setOnClickListener { fileMenu(h) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            c.addView(head)
            c.setOnLongClickListener { fileMenu(h); true }
            if (id > 0) {
                // S65: both buttons call DownloadsApi directly (the old engine eval did nothing when the id was unknown)
                val actions = actionRow()
                actions.addView(button("Show") { DownloadsApi.showFile(act, id) })
                actions.addView(button("Open", true) { DownloadsApi.openFile(act, id) })
                c.addView(actions)
                c.isClickable = true
                try {
                    val tv = android.util.TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) c.foreground = act.getDrawable(tv.resourceId)
                } catch (_: Exception) { }
                c.setOnClickListener { DownloadsApi.openFile(act, id) }
            }
            return c
        }
    }
}
