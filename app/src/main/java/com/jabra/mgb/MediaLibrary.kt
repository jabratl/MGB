package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

/**
 * S10: Thazir Media Library, one read-only hub for media the browser already tracks.
 * Nothing is stored, copied or started here; every row hands over to the screen that owns the record:
 *  - On this page   = V77 "get-state" of the active tab (same answer DetectedPanel uses). Tap opens DetectedPanel.
 *  - Downloading    = EngineApi.jobs() with state "run" (+ failed jobs). Tap opens DownloadsScreen. Finished jobs are
 *                     NOT listed again here, they already are "Saved files" (no duplicate rows).
 *  - Saved files    = EngineApi.history() (mgHist), media files only. Open / Share / Show use DownloadsApi by id.
 *  - Continue watching = the player's own resume positions (SharedPreferences "mgb_resume", read only).
 * Entry point: 3-dot menu row in MainActivity. No new preferences, ids, backup or bridge changes.
 */
object MediaLibrary {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val MAX_DETECTED = 6
    private const val MAX_JOBS = 5
    private const val MAX_FILES = 8
    private const val MAX_RESUME = 8
    private val MEDIA_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "avi", "flv", "ogv",
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")

    private var shown: BottomSheetDialog? = null

    private class Det(val label: String, val kind: String, val size: Long, val protectedDrm: Boolean)

    fun show(act: Activity, tabs: TabManager) {
        if (act.isFinishing || act.isDestroyed) return
        try { shown?.dismiss() } catch (_: Exception) { }
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cError = act.getColor(R.color.mg_error)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)

        val d = BottomSheetDialog(act)
        shown = d
        val main = Handler(Looper.getMainLooper())

        // ---- state (memory only) ----
        var detLoading = true
        var detError = false
        var detUnavailable = false // not a web page
        var detected: List<Det> = emptyList()
        var sig = ""

        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(18))
            minimumHeight = dp(320)
        }
        col.addView(TextView(act).apply {
            text = "Media Library"
            setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, dp(6), 0)
            isFocusable = true
        })
        col.addView(TextView(act).apply {
            text = "Detected, downloading, saved and resumable media"
            setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(2), dp(6), dp(4))
        })
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        fun section(t: String) = TextView(act).apply {
            text = t.uppercase()
            setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(8), dp(16), 0, dp(6))
        }

        fun card() = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(20).toFloat(); setStroke(dp(1), cLine) }
            setPadding(0, dp(4), 0, dp(4))
        }

        fun note(c: LinearLayout, t: String) {
            c.addView(TextView(act).apply {
                text = t
                setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
        }

        fun divider(c: LinearLayout) = c.addView(View(act).apply { setBackgroundColor(cLine) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(64); marginEnd = dp(14) })

        /** One row: icon badge + title + info line + optional "more" button. */
        fun row(c: LinearLayout, icon: Int, title: String, info: String, infoColor: Int, click: () -> Unit, more: (() -> Unit)? = null) {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(8), dp(6), dp(8))
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                isClickable = true
                contentDescription = if (info.isBlank()) title else "$title, $info"
                setOnClickListener { click() }
            }
            val badge = FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(cPrimary, 0x26)) }
            }
            badge.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(cPrimary); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            r.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(act).apply {
                text = title; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            if (info.isNotBlank()) texts.addView(TextView(act).apply {
                text = info; setTextColor(infoColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            r.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (more != null) r.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_more); setColorFilter(cDim)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "More for $title"
                isClickable = true
                setOnClickListener { more() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            else r.addView(ImageView(act).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(cDim); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            c.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fun closeThen(block: () -> Unit) { d.dismiss(); block() }

        fun fmtPos(ms: Long): String = EngineApi.fmtDur(ms / 1000.0).ifEmpty { "0:00" }

        fun nameOfUri(u: String): String = try {
            Uri.decode(Uri.parse(u).lastPathSegment ?: "").substringAfterLast('/').ifBlank { Util.host(u).ifBlank { "Video" } }
        } catch (_: Exception) { "Video" }

        fun resumeEntries(): List<Pair<String, Long>> = try {
            act.getSharedPreferences("mgb_resume", Context.MODE_PRIVATE).all
                .mapNotNull { (k, v) -> if (v is Long && v > 5000L) k to v else null }
                .sortedBy { nameOfUri(it.first).lowercase() }
        } catch (_: Exception) { emptyList() }

        fun savedMedia(): List<JSONObject> = EngineApi.history().filter {
            val ext = it.optString("title").substringAfterLast('.', "").lowercase()
            ext in MEDIA_EXT
        }

        fun render() {
            val jobs = EngineApi.jobs()
            val running = jobs.filter { it.optString("state") == "run" }
            val failed = jobs.filter { it.optString("state") != "run" && it.optString("state") != "done" }
            val files = savedMedia()
            val resume = resumeEntries()
            val s = detLoading.toString() + detError + detUnavailable + detected.joinToString("|") { it.label + it.kind + it.size } +
                "?" + running.joinToString("|") { it.optString("key") + ":" + (it.optDouble("pct", 0.0) * 100).toInt() + ":" + it.optBoolean("paused") } +
                "!" + failed.joinToString("|") { it.optString("key") } +
                "#" + files.joinToString("|") { it.optString("id") + ":" + it.optLong("time") } +
                "~" + resume.joinToString("|") { it.first + it.second }
            if (s == sig) return
            sig = s
            body.removeAllViews()

            // ---- On this page (detected) ----
            body.addView(section(if (detected.isNotEmpty()) "On this page (${detected.size})" else "On this page"))
            val c1 = card()
            when {
                detUnavailable -> note(c1, "Open a web page to see media found on it.")
                detLoading -> note(c1, "Looking for media...")
                detError -> note(c1, "Media Grabber is not ready yet. Try Refresh in a moment.")
                detected.isEmpty() -> note(c1, "Nothing detected on this page yet. Play the video and check again.")
                else -> {
                    detected.take(MAX_DETECTED).forEachIndexed { i, e ->
                        val bits = ArrayList<String>()
                        bits.add(when (e.kind) { "hls", "dash" -> "Stream"; "audio" -> "Audio"; else -> "Video" })
                        if (e.protectedDrm) bits.add("Protected, cannot be saved")
                        val sz = EngineApi.fmtSize(e.size)
                        if (sz.isNotEmpty()) bits.add(sz)
                        row(c1, if (e.kind == "audio") R.drawable.ic_play else R.drawable.ic_download, e.label,
                            bits.joinToString(" · "), cDim, { closeThen { DetectedPanel.open(act, tabs) } })
                        if (i < minOf(detected.size, MAX_DETECTED) - 1) divider(c1)
                    }
                    if (detected.size > MAX_DETECTED) {
                        divider(c1)
                        row(c1, R.drawable.ic_search, "See all ${detected.size} on this page", "Opens the detected media panel", cDim,
                            { closeThen { DetectedPanel.open(act, tabs) } })
                    }
                }
            }
            body.addView(c1, LinearLayout.LayoutParams(MATCH, WRAP))

            // ---- Downloading ----
            if (running.isNotEmpty() || failed.isNotEmpty()) {
                body.addView(section("Downloads"))
                val c2 = card()
                val shownJobs = (running + failed).take(MAX_JOBS)
                shownJobs.forEachIndexed { i, j ->
                    val st = j.optString("state")
                    val pct = (j.optDouble("pct", 0.0).coerceIn(0.0, 1.0) * 100).toInt()
                    val info = if (st == "run") (if (j.optBoolean("paused")) "Paused · " else "Downloading · ") + pct + "%"
                    else j.optString("text").ifBlank { "Failed" }
                    row(c2, if (st == "run") R.drawable.ic_download else R.drawable.ic_info,
                        j.optString("title").ifBlank { j.optString("key") }, info, if (st == "run") cDim else cError,
                        { closeThen { DownloadsScreen.open(act) } })
                    if (i < shownJobs.size - 1) divider(c2)
                }
                body.addView(c2, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            // ---- Saved files ----
            if (files.isNotEmpty()) {
                body.addView(section("Saved files"))
                val c3 = card()
                val list = files.take(MAX_FILES)
                list.forEachIndexed { i, h ->
                    val id = h.optInt("id", 0)
                    val title = h.optString("title").ifBlank { "file" }
                    val ext = title.substringAfterLast('.', "").uppercase()
                    val size = EngineApi.fmtSize(h.optLong("size", 0))
                    val ago = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(h.optLong("time")))
                    val info = listOfNotNull(ext.ifEmpty { null }, size.ifEmpty { null }, ago).joinToString(" · ")
                    val open = { if (id > 0) closeThen { DownloadsApi.openFile(act, id) } else Util.toast(act, "This file can only be opened from Downloads") }
                    row(c3, R.drawable.ic_play, title, info, cDim, open, if (id > 0) ({
                        val rows = ArrayList<MenuSheet.Item>()
                        rows.add(MenuSheet.Item("Open", R.drawable.ic_play) { d.dismiss(); DownloadsApi.openFile(act, id) })
                        rows.add(MenuSheet.Item("Share...", R.drawable.ic_share) { DownloadsApi.shareFile(act, id) })
                        rows.add(MenuSheet.Item("Open in File Manager", R.drawable.ic_folder, trail = DownloadsApi.folderPath(id)) { DownloadsApi.showFile(act, id) })
                        MenuSheet.showLink(act, title, DownloadsApi.folderPath(id), rows)
                    }) else null)
                    if (i < list.size - 1) divider(c3)
                }
                body.addView(c3, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            // ---- Continue watching (player resume positions) ----
            if (resume.isNotEmpty()) {
                body.addView(section("Continue watching"))
                val c4 = card()
                val list = resume.take(MAX_RESUME)
                list.forEachIndexed { i, (u, pos) ->
                    val name = nameOfUri(u)
                    val web = u.startsWith("http://") || u.startsWith("https://")
                    row(c4, R.drawable.ic_play, name, "Continue from " + fmtPos(pos) + (if (web) " · " + Util.host(u) else ""), cDim, {
                        closeThen {
                            try {
                                if (web) WebPlay.play(act, u, name) else PlayerScreen.open(act, Uri.parse(u), name)
                            } catch (_: Exception) { Util.toast(act, "Cannot open this video any more") }
                        }
                    })
                    if (i < list.size - 1) divider(c4)
                }
                body.addView(c4, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            // ---- footer: full Downloads screen ----
            body.addView(section("Manage"))
            val c5 = card()
            row(c5, R.drawable.ic_folder, "All downloads", "Pause, cancel, rename, delete and search", cDim, { closeThen { DownloadsScreen.open(act) } })
            body.addView(c5, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fun loadDetected() {
            val t = tabs.active
            if (t == null || t.isExt || !t.url.startsWith("http")) {
                detUnavailable = true; detLoading = false; detError = false; detected = emptyList()
                render(); return
            }
            detUnavailable = false
            detLoading = true
            render()
            EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", t.id)) { r ->
                if (shown !== d) return@ask
                detLoading = false
                if (r == null) { detError = true; detected = emptyList() }
                else {
                    detError = false
                    val a = r.optJSONObject("rec")?.optJSONArray("items")
                    val out = ArrayList<Det>()
                    if (a != null) for (i in 0 until a.length()) {
                        val j = a.optJSONObject(i) ?: continue
                        val kind = j.optString("kind")
                        if (kind == "subtitle" || kind == "image" || j.optBoolean("hidden")) continue
                        val url = j.optString("url")
                        val label = j.optString("label").ifBlank { url.substringBefore('?').substringAfterLast('/') }.ifBlank { url }
                        out.add(Det(label, kind, j.optLong("size", 0), j.optJSONObject("info")?.optBoolean("protected", false) ?: false))
                    }
                    detected = out
                }
                render()
            }
        }

        // Refresh: re-asks the engine and rebuilds; sits at the very top so it stays reachable.
        col.addView(TextView(act).apply {
            text = "Refresh"
            setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            minWidth = dp(88)
            isClickable = true
            contentDescription = "Refresh media list"
            setOnClickListener { sig = ""; loadDetected() }
        }, 2, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END })

        val tick = object : Runnable {
            override fun run() {
                if (shown !== d) return
                render()
                main.postDelayed(this, 1500)
            }
        }
        d.setOnDismissListener { main.removeCallbacks(tick); if (shown === d) shown = null }
        d.setContentView(NestedScrollView(act).apply { addView(col) })
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        loadDetected()
        main.postDelayed(tick, 1500)
    }
}
