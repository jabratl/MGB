package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * S50: "Files detected!" panel. Everything V77 (and the Sniffer) found on the page, as a clean list:
 * format tag, size, resolution, audio language / no audio, duration, source link; select several and download them;
 * Filter (size, time, type, extension, experimental, silent videos), Sort, and a menu per file (stream, preview,
 * download, remove, copy / share / show link). V77 still does the analysis and the download (start-job);
 * this only shows and starts. Plain video / audio files are read natively (MediaExtractor) for resolution,
 * duration and audio tracks. Also: the "Convert .ts to .mp4" switch (V77 setting convertMp4).
 */
@androidx.annotation.OptIn(UnstableApi::class)
object DetectedPanel {
    private const val PREFS = "mgb_detected"
    private const val MB = 1024.0 * 1024.0

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val removed = HashMap<Int, HashSet<String>>()          // tab id -> keys the user removed from the list
    private val metaCache = ConcurrentHashMap<String, Meta>()
    private val probing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val KNOWN_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "avi", "3gp", "ts", "m3u8", "mpd",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "srt", "vtt", "ass", "ssa")

    /** What MediaExtractor says about a plain file. hasAudio null = not read (yet). */
    private class Meta(val w: Int, val h: Int, val durS: Double, val langs: List<String>, val hasAudio: Boolean?)

    private class DItem(val j: JSONObject, val idx: Int) {
        val key: String = j.optString("key")
        val kind: String = j.optString("kind")
        val url: String = j.optString("url")
        val info: JSONObject = j.optJSONObject("info") ?: JSONObject()
        val variants: JSONArray? = info.optJSONArray("variants")
        val time: Long = j.optLong("time", 0L)
        val drm: Boolean = info.optBoolean("protected", false)
        val ext: String = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val name: String = j.optString("label").ifBlank { url.substringBefore('?').substringAfterLast('/') }.ifBlank { url }
        val group: String get() = when (kind) { "audio" -> "audio"; "subtitle" -> "subtitle"; "image" -> "image"; else -> "video" }
        val isStream: Boolean get() = kind == "hls" || kind == "dash"
    }

    private class Filter {
        var name = ""
        var minMb = 0f
        var maxMb = 0f
        var minSec = 0f
        var maxSec = 0f
        var video = true
        var audio = true
        var image = true
        var subtitle = true
        var experimental = true
        var noAudio = false
        var ext = ""

        fun active(): Boolean = name.isNotBlank() || minMb > 0 || maxMb > 0 || minSec > 0 || maxSec > 0 ||
            !video || !audio || !image || !subtitle || !experimental || noAudio || ext.isNotBlank()

        fun reset() {
            name = ""; minMb = 0f; maxMb = 0f; minSec = 0f; maxSec = 0f
            video = true; audio = true; image = true; subtitle = true; experimental = true; noAudio = false; ext = ""
        }

        fun load(c: Context) {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            name = p.getString("f_name", "") ?: ""
            minMb = p.getFloat("f_minmb", 0f); maxMb = p.getFloat("f_maxmb", 0f)
            minSec = p.getFloat("f_minsec", 0f); maxSec = p.getFloat("f_maxsec", 0f)
            video = p.getBoolean("f_video", true); audio = p.getBoolean("f_audio", true)
            image = p.getBoolean("f_image", true); subtitle = p.getBoolean("f_sub", true)
            experimental = p.getBoolean("f_exp", true); noAudio = p.getBoolean("f_noaudio", false)
            ext = p.getString("f_ext", "") ?: ""
        }

        fun save(c: Context) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("f_name", name).putFloat("f_minmb", minMb).putFloat("f_maxmb", maxMb)
                .putFloat("f_minsec", minSec).putFloat("f_maxsec", maxSec)
                .putBoolean("f_video", video).putBoolean("f_audio", audio).putBoolean("f_image", image)
                .putBoolean("f_sub", subtitle).putBoolean("f_exp", experimental).putBoolean("f_noaudio", noAudio)
                .putString("f_ext", ext).apply()
        }
    }

    // ---------- conversion switch (V77 setting convertMp4) ----------

    fun convertOn(): Boolean = try { ChromeBridge.sync.get("convertMp4").optBoolean("convertMp4", true) } catch (e: Exception) { true }

    fun convertDialog(act: Activity, done: () -> Unit) {
        if (convertOn()) {
            ChromeBridge.storageSet("sync", JSONObject().put("convertMp4", false))
            Util.toast(act, ".ts files stay .ts")
            done()
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Convert .ts to .mp4")
            .setMessage("Streams that are saved as .ts pieces are turned into one .mp4 when the download ends.\n\n" +
                "If you enable file conversion, external subtitles might get out of sync in some cases.")
            .setPositiveButton("Enable") { _, _ ->
                ChromeBridge.storageSet("sync", JSONObject().put("convertMp4", true))
                Util.toast(act, "Conversion is ON")
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- reading plain files ----------

    private fun langName(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return "Unknown"
        val n = try { Locale(code).displayLanguage } catch (e: Exception) { "" }
        return if (n.isBlank() || n.equals(code, true)) code else n.replaceFirstChar { it.uppercase() }
    }

    private fun probe(url: String, ref: String?, ua: String?): Meta? {
        val ex = MediaExtractor()
        try {
            val hd = HashMap<String, String>()
            if (!ref.isNullOrEmpty()) hd["Referer"] = ref
            if (!ua.isNullOrEmpty()) hd["User-Agent"] = ua
            try { CookieManager.getInstance().getCookie(url)?.let { hd["Cookie"] = it } } catch (e: Exception) { }
            ex.setDataSource(url, hd)
            var w = 0
            var h = 0
            var dur = 0L
            var audio = false
            val langs = ArrayList<String>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (f.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/")) {
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) w = maxOf(w, f.getInteger(MediaFormat.KEY_WIDTH))
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) h = maxOf(h, f.getInteger(MediaFormat.KEY_HEIGHT))
                } else if (mime.startsWith("audio/")) {
                    audio = true
                    val l = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
                    val n = langName(l)
                    if (n !in langs) langs.add(n)
                }
            }
            return Meta(w, h, dur / 1e6, langs, audio)
        } catch (e: Exception) {
            return null
        } finally {
            try { ex.release() } catch (e: Exception) { }
        }
    }

    private fun ensureMeta(e: DItem, ref: String?, ua: String?, changed: () -> Unit) {
        if (e.kind != "video" && e.kind != "audio") return
        if (!e.url.startsWith("http") || metaCache.containsKey(e.url)) return
        if (!probing.add(e.url)) return
        pool.execute {
            val m = probe(e.url, ref, ua) ?: Meta(0, 0, 0.0, emptyList(), null)
            metaCache[e.url] = m
            probing.remove(e.url)
            main.post { changed() }
        }
    }

    // ---------- small helpers ----------

    private fun durLabel(sec: Double): String {
        val t = sec.toLong()
        if (t <= 0) return ""
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%dh%02dm%02ds", h, m, s) else if (m > 0) String.format("%dm%02ds", m, s) else "${s}s"
    }

    /** (bytes, estimated). Streams have no size: estimate from the best variant bit rate and the duration. */
    private fun sizeOf(e: DItem): Pair<Long, Boolean> {
        val s = e.j.optLong("size", 0)
        if (s > 0) return Pair(s, false)
        val dur = e.info.optDouble("dur", 0.0)
        val bw = e.variants?.optJSONObject(0)?.optLong("bw", 0) ?: 0L
        if (e.isStream && dur > 0 && bw > 0) return Pair((bw * dur / 8).toLong(), true)
        return Pair(0L, false)
    }

    private fun durOf(e: DItem): Double {
        val d = e.info.optDouble("dur", 0.0)
        if (d > 0) return d
        return metaCache[e.url]?.durS ?: 0.0
    }

    private fun isExperimental(e: DItem): Boolean =
        e.kind != "subtitle" && !e.isStream && e.j.optLong("size", 0) <= 0 && e.ext !in KNOWN_EXT

    private fun tagOf(e: DItem): String = when (e.kind) {
        "hls" -> "HLS"
        "dash" -> "DASH"
        "subtitle" -> if (e.ext.isNotEmpty() && e.ext.length <= 4) e.ext.uppercase() else "SUBTITLE"
        else -> if (e.ext in KNOWN_EXT) e.ext.uppercase() else e.kind.uppercase()
    }

    private fun passes(e: DItem, f: Filter, removedKeys: Set<String>): Boolean {
        if (e.j.optBoolean("hidden", false) || e.key in removedKeys) return false
        when (e.group) {
            "video" -> if (!f.video) return false
            "audio" -> if (!f.audio) return false
            "image" -> if (!f.image) return false
            "subtitle" -> if (!f.subtitle) return false
        }
        if (f.name.isNotBlank() && !e.name.contains(f.name.trim(), true) && !e.url.contains(f.name.trim(), true)) return false
        val size = sizeOf(e).first
        if (size > 0) {
            if (f.minMb > 0 && size < f.minMb * MB) return false
            if (f.maxMb > 0 && size > f.maxMb * MB) return false
        }
        val dur = durOf(e)
        if (dur > 0) {
            if (f.minSec > 0 && dur < f.minSec) return false
            if (f.maxSec > 0 && dur > f.maxSec) return false
        }
        if (!f.experimental && isExperimental(e)) return false
        if (!f.noAudio && e.kind == "video" && metaCache[e.url]?.hasAudio == false) return false
        if (f.ext.isNotBlank()) {
            val allowed = f.ext.lowercase().split(',', ' ', ';').map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }
            if (allowed.isNotEmpty() && e.ext !in allowed) return false
        }
        return true
    }

    // ---------- entry point ----------

    fun open(act: Activity, tabs: TabManager) {
        val t = tabs.active ?: return
        if (t.isExt || !t.url.startsWith("http")) { Util.toast(act, "Open a web page first"); return }
        val tabId = t.id
        val ref = t.url
        val ua = t.web.settings.userAgentString
        EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", tabId)) { r ->
            if (r == null) { Util.toast(act, "Media Grabber is not ready yet"); return@ask }
            Panel(act, tabId, ref, ua, tabs, itemsOf(r)).show()
        }
    }

    private fun itemsOf(r: JSONObject?): List<JSONObject> {
        val a = r?.optJSONObject("rec")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    // ---------- the panel ----------

    private class Panel(
        val act: Activity, val tabId: Int, val ref: String, val ua: String?, val tabs: TabManager, first: List<JSONObject>
    ) {
        private val dlg = BottomSheetDialog(act)
        private var all: List<DItem> = first.mapIndexed { i, j -> DItem(j, i) }
        private val selected = LinkedHashSet<String>()
        private val filter = Filter().also { it.load(act) }
        private var sort = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sort", 0)
        private lateinit var title: TextView
        private lateinit var sub: TextView
        private lateinit var list: LinearLayout
        private lateinit var selAll: CheckBox
        private lateinit var dlBtn: TextView
        private lateinit var filterBtn: TextView
        private var visible: List<DItem> = emptyList()
        private var sig = ""
        private val cText = act.getColor(R.color.mg_text)
        private val cDim = act.getColor(R.color.mg_text_dim)
        private val cPrimary = act.getColor(R.color.mg_primary)

        private fun dp(v: Int) = Util.dp(act, v)
        private fun removedKeys(): Set<String> = removed[tabId] ?: emptySet()

        private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

        private fun tv(text: String, sp: Float, color: Int, lines: Int = 1, bold: Boolean = false) = TextView(act).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private fun ripple(v: View) {
            val out = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) v.foreground = act.getDrawable(out.resourceId)
        }

        fun show() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (act.resources.displayMetrics.heightPixels * 0.9f).toInt())
            }
            // header
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(10), dp(4))
            }
            val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            title = tv("Files detected!", 19f, cText, 1, true)
            sub = tv("", 12f, cDim)
            tcol.addView(title)
            tcol.addView(sub)
            head.addView(tcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            filterBtn = pillBtn("Filter") { showFilter() }
            head.addView(filterBtn)
            head.addView(pillBtn("⚙") { convertDialog(act) { } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) })
            root.addView(head)
            root.addView(tv("Select files to download", 12f, cDim).apply { setPadding(dp(16), dp(6), dp(16), 0) })

            // select all + download selected
            val selRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(12), 0)
            }
            selAll = CheckBox(act).apply {
                text = "Select all to download"
                setTextColor(cText)
                textSize = 14f
                buttonTintList = ColorStateList.valueOf(cPrimary)
                setOnClickListener {
                    val on = isChecked
                    for (e in visible) {
                        if (on) { if (!e.drm) selected.add(e.key) } else selected.remove(e.key)
                    }
                    sig = ""
                    render()
                }
            }
            selRow.addView(selAll, LinearLayout.LayoutParams(0, dp(48), 1f))
            dlBtn = TextView(act).apply {
                text = "DOWNLOAD"
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_on_primary))
                background = round(cPrimary, 18)
                setPadding(dp(16), 0, dp(16), 0)
                visibility = View.GONE
                setOnClickListener { downloadSelected() }
            }
            selRow.addView(dlBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            root.addView(selRow)

            list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            root.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            // bottom bar
            val bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(act.getColor(R.color.mg_surface_variant))
            }
            fun barBtn(label: String, on: () -> Unit) {
                bar.addView(tv(label, 14f, cPrimary, 1, true).apply {
                    gravity = Gravity.CENTER
                    ripple(this)
                    setOnClickListener { on() }
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            barBtn("CLEAR ALL") { clearAll() }
            barBtn("SORT") { showSort() }
            barBtn("CLOSE") { dlg.dismiss() }
            root.addView(bar)

            dlg.setContentView(root)
            dlg.behavior.skipCollapsed = true
            dlg.setOnDismissListener { open = false }
            dlg.show()
            dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            render()
            main.postDelayed(loop, 3000)
        }

        private fun pillBtn(label: String, on: () -> Unit) = tv(label, 14f, cText, 1, true).apply {
            gravity = Gravity.CENTER
            background = round(act.getColor(R.color.mg_surface_variant), 18)
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = dp(36)
            setOnClickListener { on() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
        }

        // ---- live refresh while the panel is open ----
        private var open = true
        private val loop = object : Runnable {
            override fun run() {
                if (!open) return
                EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", tabId)) { r ->
                    if (!open) return@ask
                    if (r != null) {
                        all = itemsOf(r).mapIndexed { i, j -> DItem(j, i) }
                        render()
                    }
                    if (open) main.postDelayed(this, 3000)
                }
            }
        }

        // ---- list ----

        private fun sizeKey(e: DItem) = sizeOf(e).first

        private fun sorted(items: List<DItem>): List<DItem> = when (sort) {
            1 -> items.sortedByDescending { it.time }
            2 -> items.sortedBy { it.time }
            3 -> items.sortedByDescending { sizeKey(it) }
            4 -> items.sortedBy { sizeKey(it) }
            5 -> items.sortedByDescending { it.name.lowercase() }
            6 -> items.sortedBy { it.name.lowercase() }
            7 -> items.sortedBy { if (it.group == "video") 0 else if (it.group == "audio") 1 else 2 }
            8 -> items.sortedBy { if (it.group == "audio") 0 else if (it.group == "video") 1 else 2 }
            else -> {
                val order = listOf("hls", "dash", "video", "audio", "subtitle")
                items.sortedBy { val i = order.indexOf(it.kind); if (i < 0) 99 else i }
            }
        }

        private fun render() {
            if (!open) return
            val rk = removedKeys()
            val keysNow = all.map { it.key }.toSet()
            selected.retainAll(keysNow)
            for (e in all) ensureMeta(e, ref, ua) { render() }
            visible = sorted(all.filter { passes(it, filter, rk) })
            val shownKeys = visible.joinToString(",") { it.key + ":" + sizeOf(it).first + ":" + (metaCache[it.url]?.hasAudio ?: "?") + ":" + it.info.optDouble("dur", 0.0) }
            val s = shownKeys + "|" + selected.joinToString(",") + "|" + sort + "|" + filter.active()
            title.text = "Files detected!"
            val total = all.count { !it.j.optBoolean("hidden", false) && it.key !in rk }
            sub.text = if (visible.size == total) "${visible.size} file" + (if (visible.size == 1) "" else "s")
            else "${visible.size} of $total files (filter on)"
            filterBtn.text = if (filter.active()) "Filter ●" else "Filter"
            selAll.isChecked = visible.isNotEmpty() && visible.all { it.drm || selected.contains(it.key) }
            val n = selected.size
            dlBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
            dlBtn.text = "DOWNLOAD ($n)"
            if (s == sig) return
            sig = s
            val keep = list.parent?.let { (it as? ScrollView)?.scrollY } ?: 0
            list.removeAllViews()
            if (visible.isEmpty()) {
                list.addView(tv(
                    if (all.isEmpty()) "Nothing found yet.\nPlay the video or reload the page, then look again."
                    else "No file matches the filter.", 14f, cDim, 4
                ).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(48)) })
            } else for (e in visible) list.addView(rowFor(e))
            (list.parent as? ScrollView)?.post { (list.parent as? ScrollView)?.scrollTo(0, keep) }
        }

        private fun chip(text: String, bg: Int, fg: Int) = tv(text, 11f, fg, 1, true).apply {
            background = round(bg, 6)
            setPadding(dp(7), dp(1), dp(7), dp(1))
        }

        private fun rowFor(e: DItem): View {
            val meta = metaCache[e.url]
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(8), dp(2), dp(8))
                ripple(this)
            }
            val cb = CheckBox(act).apply {
                isChecked = selected.contains(e.key)
                isEnabled = !e.drm
                buttonTintList = ColorStateList.valueOf(cPrimary)
                setOnClickListener {
                    if (isChecked) selected.add(e.key) else selected.remove(e.key)
                    val n = selected.size
                    dlBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
                    dlBtn.text = "DOWNLOAD ($n)"
                    selAll.isChecked = visible.isNotEmpty() && visible.all { it.drm || selected.contains(it.key) }
                    sig = ""
                }
            }
            line.setOnClickListener { cb.performClick() }
            line.addView(cb, LinearLayout.LayoutParams(dp(40), dp(40)))

            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            chips.addView(chip(tagOf(e), act.getColor(R.color.mg_surface_variant), cText))
            val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) } }
            if (isExperimental(e)) chips.addView(chip("Experimental", 0xFFF57C00.toInt(), Color.WHITE), lp())
            if (e.info.optBoolean("live", false)) chips.addView(chip("LIVE", 0xFFE53935.toInt(), Color.WHITE), lp())
            if (e.drm) chips.addView(chip("DRM", 0xFFE53935.toInt(), Color.WHITE), lp())
            col.addView(chips)
            col.addView(tv(e.name, 14f, cText, 2, true).apply { setPadding(0, dp(3), 0, 0) })

            val bits = ArrayList<String>()
            val sz = sizeOf(e)
            if (sz.first > 0) bits.add((if (sz.second) "~" else "-") + EngineApi.fmtSize(sz.first))
            val vr = e.variants?.optJSONObject(0)
            val res = when {
                e.isStream && vr != null && vr.optString("res").isNotBlank() -> vr.optString("res")
                e.isStream && vr != null && vr.optInt("h", 0) > 0 -> vr.optInt("h").toString() + "p"
                meta != null && meta.w > 0 && meta.h > 0 -> "${meta.w}x${meta.h}"
                else -> ""
            }
            if (res.isNotEmpty()) bits.add(res)
            val d = durOf(e)
            if (d > 0) bits.add("Duration: " + durLabel(d))
            if (bits.isNotEmpty()) col.addView(tv(bits.joinToString("   "), 12f, cDim, 2))

            val audioText = when {
                e.kind == "subtitle" -> {
                    val l = e.j.optString("lang").ifBlank { e.j.optString("label") }
                    if (l.isNotBlank()) "Language: " + langName(l.take(12).takeIf { it.length <= 3 } ?: l) else ""
                }
                e.isStream -> {
                    val n = e.info.optInt("audioTracks", 0)
                    if (n > 0) "Audio tracks: $n" else ""
                }
                meta != null && meta.hasAudio == false -> "No audio"
                meta != null && meta.hasAudio == true -> "Language: " + (if (meta.langs.isEmpty()) "Unknown" else meta.langs.joinToString(", "))
                else -> ""
            }
            if (audioText.isNotEmpty()) col.addView(tv(audioText, 12f, cDim))
            col.addView(tv(e.url, 11f, cDim, 2).apply {
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(2), 0, 0)
            })
            line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            line.addView(TextView(act).apply {
                text = "⋮"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(cDim)
                contentDescription = "More"
                ripple(this)
                setOnClickListener { itemMenu(e) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            return line
        }

        // ---- actions ----

        private fun toast(s: String) = Util.toast(act, s)

        private fun startJob(e: DItem, variant: String?) {
            val m = JSONObject().put("type", "start-job").put("tabId", tabId).put("key", e.key)
            if (e.kind == "subtitle") m.put("fmt", "srt") else m.put("variant", variant ?: "best")
            EngineApi.send(m)
        }

        private fun downloadSelected() {
            var n = 0
            for (k in selected.toList()) {
                val e = all.firstOrNull { it.key == k } ?: continue
                if (e.drm) continue
                startJob(e, "best")
                n++
            }
            selected.clear()
            sig = ""
            toast(if (n > 0) "$n download" + (if (n == 1) "" else "s") + " started" else "Nothing to download")
            render()
        }

        private fun download(e: DItem) {
            if (e.drm) { toast("DRM protected: cannot be downloaded"); return }
            val v = e.variants
            if (e.isStream && v != null && v.length() > 0) {
                val names = ArrayList<String>()
                val vals = ArrayList<String>()
                names.add("Best quality (auto)"); vals.add("best")
                val dur = e.info.optDouble("dur", 0.0)
                for (i in 0 until minOf(v.length(), 10)) {
                    val q = v.optJSONObject(i) ?: continue
                    val h = q.optInt("h", 0)
                    val bw = q.optLong("bw", 0)
                    var label = if (h > 0) "${h}p" else if (bw > 0) String.format("%.1f Mbps", bw / 1e6) else "Default"
                    if (bw > 0 && dur > 0) label += "   ~" + EngineApi.fmtSize((bw * dur / 8).toLong())
                    names.add(label)
                    vals.add(q.optString("url").ifBlank { q.optString("id") })
                }
                MaterialAlertDialogBuilder(act).setTitle("Quality")
                    .setItems(names.toTypedArray()) { _, i -> startJob(e, vals[i]); toast("Download started") }
                    .setNegativeButton("Cancel", null).show()
            } else {
                startJob(e, "best")
                toast(if (e.kind == "subtitle") "Subtitle download started" else "Download started")
            }
        }

        private fun stream(e: DItem) {
            if (e.drm) { toast("DRM protected"); return }
            WebPlay.play(act, e.url, e.name, ref, ua)
            dlg.dismiss()
        }

        private fun preview(e: DItem) {
            if (e.drm) { toast("DRM protected"); return }
            try {
                val hdr = HashMap<String, String>()
                if (ref.isNotEmpty()) hdr["Referer"] = ref
                try { CookieManager.getInstance().getCookie(e.url)?.let { hdr["Cookie"] = it } } catch (x: Exception) { }
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                    .setUserAgent(ua ?: "Mozilla/5.0").setDefaultRequestProperties(hdr)
                val p = ExoPlayer.Builder(act).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
                val pv = PlayerView(act).apply { player = p; useController = true }
                p.setMediaItem(MediaItem.fromUri(e.url))
                p.prepare()
                p.playWhenReady = true
                val box = FrameLayout(act).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
                }
                val d = MaterialAlertDialogBuilder(act)
                    .setTitle("Preview " + (if (e.group == "audio") "audio" else "video"))
                    .setView(box)
                    .setNegativeButton("Close", null)
                    .create()
                d.setOnDismissListener { try { pv.player = null; p.release() } catch (x: Exception) { } }
                d.show()
            } catch (x: Exception) {
                toast("Cannot preview this file")
            }
        }

        private fun copy(e: DItem) {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", e.url))
            toast("Link copied")
        }

        private fun share(e: DItem) {
            try {
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.url), "Share link"))
            } catch (x: Exception) { toast("Cannot share") }
        }

        private fun showLink(e: DItem) {
            MaterialAlertDialogBuilder(act).setTitle("Download link").setMessage(e.url)
                .setPositiveButton("Copy") { _, _ -> copy(e) }
                .setNegativeButton("Close", null).show()
        }

        private fun remove(e: DItem) {
            removed.getOrPut(tabId) { HashSet() }.add(e.key)
            selected.remove(e.key)
            sig = ""
            render()
        }

        private fun clearAll() {
            EngineApi.send(JSONObject().put("type", "clear").put("tabId", tabId))
            removed.remove(tabId)
            selected.clear()
            all = emptyList()
            sig = ""
            render()
            toast("List cleared")
        }

        private fun itemMenu(e: DItem) {
            val names = ArrayList<String>()
            val acts = ArrayList<() -> Unit>()
            val av = if (e.group == "audio") "Audio" else "Video"
            if (e.kind != "subtitle") {
                names.add("Stream $av"); acts.add { stream(e) }
                names.add("Preview $av"); acts.add { preview(e) }
            }
            names.add("Download"); acts.add { download(e) }
            names.add("Remove link"); acts.add { remove(e) }
            names.add("Copy download link"); acts.add { copy(e) }
            names.add("Share link"); acts.add { share(e) }
            names.add("Show download link"); acts.add { showLink(e) }
            MaterialAlertDialogBuilder(act).setTitle(e.name.take(60))
                .setItems(names.toTypedArray()) { _, i -> acts[i]() }
                .setNegativeButton("Close", null).show()
        }

        // ---- sort / filter ----

        private fun showSort() {
            val names = arrayOf("Default", "Detected desc", "Detected asc", "Size desc", "Size asc", "Name desc", "Name asc", "Video first", "Audio first")
            MaterialAlertDialogBuilder(act).setTitle("Sort")
                .setSingleChoiceItems(names, sort) { d, i ->
                    sort = i
                    act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("sort", i).apply()
                    sig = ""
                    render()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }

        private fun showFilter() {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            fun field(label: String, hint: String, value: String, number: Boolean): EditText {
                box.addView(tv(label, 12f, cDim).apply { setPadding(0, dp(10), 0, 0) })
                val et = EditText(act).apply {
                    setText(value)
                    this.hint = hint
                    textSize = 15f
                    setSingleLine()
                    setTextColor(cText)
                    if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                box.addView(et)
                return et
            }
            fun check(label: String, on: Boolean): CheckBox {
                val c = CheckBox(act).apply { text = label; isChecked = on; setTextColor(cText); textSize = 14f; buttonTintList = ColorStateList.valueOf(cPrimary) }
                box.addView(c)
                return c
            }
            fun num(f: Float) = if (f <= 0f) "" else if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
            val name = field("File name contains", "any", filter.name, false)
            val minMb = field("Minimum size (MB)", "0", num(filter.minMb), true)
            val maxMb = field("Maximum size (MB)", "no limit", num(filter.maxMb), true)
            val minS = field("Minimum time (SEC)", "0", num(filter.minSec), true)
            val maxS = field("Maximum time (SEC)", "no limit", num(filter.maxSec), true)
            box.addView(tv("Media type", 12f, cDim).apply { setPadding(0, dp(14), 0, 0) })
            val cv = check("Video", filter.video)
            val ca = check("Audio", filter.audio)
            val ci = check("Image", filter.image)
            val cs = check("Subtitle", filter.subtitle)
            box.addView(tv("More", 12f, cDim).apply { setPadding(0, dp(14), 0, 0) })
            val ce = check("Show experimental links", filter.experimental)
            val cn = check("Show video links which don't have audio", filter.noAudio)
            val ext = field("Custom extensions (only these, empty = all)", "mp4, mkv, m3u8", filter.ext, false)
            MaterialAlertDialogBuilder(act).setTitle("Filter")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("OK") { _, _ ->
                    filter.name = name.text.toString()
                    filter.minMb = minMb.text.toString().toFloatOrNull() ?: 0f
                    filter.maxMb = maxMb.text.toString().toFloatOrNull() ?: 0f
                    filter.minSec = minS.text.toString().toFloatOrNull() ?: 0f
                    filter.maxSec = maxS.text.toString().toFloatOrNull() ?: 0f
                    filter.video = cv.isChecked; filter.audio = ca.isChecked; filter.image = ci.isChecked; filter.subtitle = cs.isChecked
                    filter.experimental = ce.isChecked; filter.noAudio = cn.isChecked
                    filter.ext = ext.text.toString()
                    filter.save(act)
                    sig = ""
                    render()
                }
                .setNegativeButton("CANCEL", null)
                .setNeutralButton("RESET") { _, _ ->
                    filter.reset()
                    filter.save(act)
                    sig = ""
                    render()
                }
                .show()
        }
    }
}
