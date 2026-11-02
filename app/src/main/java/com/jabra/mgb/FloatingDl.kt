package com.jabra.mgb

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * S11: 1DM-style floating download button over the page + quality sheet.
 *  - every few seconds (and after a page finishes) asks V77 get-state for the active tab (same message the popup uses);
 *    the button shows the number of downloadable videos / audio files, hidden when there are none
 *  - tap: bottom sheet listing what V77 found; a row starts the job (start-job: best, a quality, or a subtitle format)
 *  - the button can be dragged; the place is remembered
 * V77 does the real work (analysis, download, merge in the S9 hidden document); this class only shows and starts.
 */
class FloatingDl(
    private val act: Activity,
    private val parent: FrameLayout,
    private val tabs: TabManager
) {
    companion object {
        private const val PREFS = "mgb_ui"
        private const val POLL_MS = 3000L
        private val MEDIA = setOf("hls", "dash", "video", "audio")
    }

    private val main = Handler(Looper.getMainLooper())
    private val btn: FrameLayout
    private val badge: TextView
    private val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var running = false
    private var busy = false
    private var moved = false
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }
    private val pokeTask = Runnable { refresh() }

    private fun dp(v: Int) = Util.dp(act, v)

    init {
        val size = dp(52)
        btn = FrameLayout(act)
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1E88E5.toInt())
        }
        btn.elevation = dp(6).toFloat()
        btn.visibility = View.GONE
        btn.contentDescription = "Download"
        btn.addView(TextView(act).apply {
            text = "↓"
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(size, size))
        badge = TextView(act).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            minWidth = dp(18)
            minHeight = dp(18)
            setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(0xFFE53935.toInt())
            }
        }
        btn.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        val lp = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END)
        lp.setMargins(0, 0, dp(16), dp(24))
        parent.addView(btn, lp)
        btn.translationX = prefs.getFloat("fdl_x", 0f)
        btn.translationY = prefs.getFloat("fdl_y", 0f)
        btn.setOnClickListener { openSheet() }
        attachDrag()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag() {
        val slop = ViewConfiguration.get(act).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        btn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startTx = v.translationX; startTy = v.translationY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) moved = true
                    if (moved) {
                        val m = dp(16)
                        val minX = -(parent.width - v.width - 2 * m).toFloat()
                        val minY = -(parent.height - v.height - dp(24) - m).toFloat()
                        v.translationX = (startTx + e.rawX - downX).coerceIn(minOf(minX, 0f), 0f)
                        v.translationY = (startTy + e.rawY - downY).coerceIn(minOf(minY, 0f), 0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putFloat("fdl_x", v.translationX).putFloat("fdl_y", v.translationY).apply()
                    else v.performClick()
                    true
                }
                else -> true
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        main.post(loop)
    }

    fun stop() {
        running = false
        main.removeCallbacks(loop)
        main.removeCallbacks(pokeTask)
    }

    /** Page finished / tab changed: look again soon. */
    fun poke() {
        main.removeCallbacks(pokeTask)
        main.postDelayed(pokeTask, 700)
    }

    // ---------- state ----------

    private fun itemsOf(r: JSONObject?): List<JSONObject> {
        val a = r?.optJSONObject("rec")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (!it.optBoolean("hidden", false)) out.add(it) }
        return out
    }

    fun refresh() {
        val t = tabs.active
        // S29: the Grabber page button (V77) is the default; this native button is optional (Settings > Downloads)
        if (!prefs.getBoolean("native_btn", false)) { btn.visibility = View.GONE; return }
        if (t == null || t.isExt || !t.url.startsWith("http")) { btn.visibility = View.GONE; return }
        if (busy) return
        busy = true
        val id = t.id
        EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", id)) { r ->
            busy = false
            if (tabs.active?.id != id) return@ask
            val n = itemsOf(r).count { it.optString("kind") in MEDIA }
            if (n <= 0) btn.visibility = View.GONE
            else {
                badge.text = if (n > 99) "99+" else n.toString()
                btn.visibility = View.VISIBLE
            }
        }
    }

    // ---------- quality sheet ----------

    fun openSheet() {
        val t = tabs.active ?: return
        val id = t.id
        EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", id)) { r ->
            if (r == null) { Util.toast(act, "Media Grabber is not ready yet"); return@ask }
            val items = itemsOf(r)
            if (items.isEmpty()) { btn.visibility = View.GONE; Util.toast(act, "Nothing to download here yet"); return@ask }
            showSheet(id, r.optJSONObject("rec"), items)
        }
    }

    private fun showSheet(tabId: Int, rec: JSONObject?, items: List<JSONObject>) {
        val dlg = BottomSheetDialog(act)
        val v = LayoutInflater.from(act).inflate(R.layout.sheet_media, null)
        val list = v.findViewById<LinearLayout>(R.id.media_list)
        val title = rec?.optString("title").orEmpty().ifBlank { tabs.active?.title.orEmpty() }.ifBlank { "Download" }
        v.findViewById<TextView>(R.id.media_title).text = title
        v.findViewById<TextView>(R.id.media_sub).text = Util.host(tabs.active?.url ?: "")
        v.findViewById<View>(R.id.media_downloads).setOnClickListener { dlg.dismiss(); DownloadsScreen.open(act) }

        fun go(it: JSONObject, extra: JSONObject) {
            val m = JSONObject().put("type", "start-job").put("tabId", tabId).put("key", it.optString("key"))
            for (k in extra.keys()) m.put(k, extra.get(k))
            EngineApi.send(m)
            Util.toast(act, if (it.optString("kind") == "subtitle") "Subtitle download started" else "Download started")
            dlg.dismiss()
        }

        val order = listOf("hls", "dash", "video", "audio", "subtitle")
        val sorted = items.sortedBy { val i = order.indexOf(it.optString("kind")); if (i < 0) 99 else i }
        for (it in sorted) {
            val kind = it.optString("kind")
            val info = it.optJSONObject("info") ?: JSONObject()
            val vars = info.optJSONArray("variants")
            val dur = info.optDouble("dur", 0.0)
            val size = it.optLong("size", 0)
            val chips = ArrayList<String>()
            chips.add(if (kind == "subtitle") "SUBTITLE" else kind.uppercase())
            if (kind == "hls" || kind == "dash") {
                val h = vars?.optJSONObject(0)?.optInt("h", 0) ?: 0
                if (h > 0) chips.add(qLabel(h, 0L).first)
            } else if (size > 0) chips.add(EngineApi.fmtSize(size))
            if (dur > 0) chips.add(EngineApi.fmtDur(dur))
            if (info.optBoolean("live", false)) chips.add("LIVE")
            val name = it.optString("label").ifBlank { it.optString("url").substringBefore('?').substringAfterLast('/') }
            list.addView(header(chips.joinToString(" · "), name))
            when {
                info.optBoolean("protected", false) -> list.addView(row("DRM protected - cannot be downloaded", null, false) { })
                kind == "subtitle" -> {
                    list.addView(row(".srt", null, true) { go(it, JSONObject().put("fmt", "srt")) })
                    list.addView(row(".vtt", null, false) { go(it, JSONObject().put("fmt", "vtt")) })
                    list.addView(row("Original", null, false) { go(it, JSONObject().put("fmt", "orig")) })
                }
                (kind == "hls" || kind == "dash") && vars != null && vars.length() > 0 -> {
                    list.addView(row("Best quality (auto)", null, true) { go(it, JSONObject().put("variant", "best")) })
                    for (i in 0 until minOf(vars.length(), 8)) {
                        val q = vars.optJSONObject(i) ?: continue
                        val ql = qLabel(q.optInt("h", 0), q.optLong("bw", 0))
                        val bw = q.optLong("bw", 0)
                        val sz = if (bw > 0 && dur > 0) "~" + EngineApi.fmtSize((bw * dur / 8).toLong()) else ""
                        val value = q.optString("url").ifBlank { q.optString("id") }
                        list.addView(row(ql.first + "  " + ql.second, sz, false) { go(it, JSONObject().put("variant", value)) })
                    }
                }
                else -> list.addView(row("Download" + (if (size > 0) " · " + EngineApi.fmtSize(size) else ""), null, true) {
                    go(it, JSONObject().put("variant", "best"))
                })
            }
            // S41: play the detected stream in the built-in player (no download)
            val murl = it.optString("url")
            if (murl.startsWith("http") && kind in listOf("hls", "dash", "video", "audio") && !info.optBoolean("protected", false)) {
                val ref = tabs.active?.url?.takeIf { u -> u.startsWith("http") }
                val ua = tabs.active?.web?.settings?.userAgentString
                list.addView(row("Play in player", null, false) { WebPlay.play(act, murl, name, ref, ua); dlg.dismiss() })
            }
        }
        dlg.setContentView(v)
        dlg.behavior.skipCollapsed = true
        dlg.show()
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun stdNear(h: Int): Int {
        for (s in intArrayOf(144, 240, 360, 480, 540, 576, 720, 1080, 1440, 2160)) if (abs(h - s) <= s / 12) return s
        return 0
    }

    /** Same wording as V77 qLabel: (name, band). */
    private fun qLabel(h: Int, bw: Long): Pair<String, String> {
        if (h > 0) {
            val band = when {
                h >= 2000 -> "Ultra HD"; h >= 1400 -> "Quad HD"; h >= 1000 -> "Full HD"
                h >= 700 -> "HD"; h >= 450 -> "Standard"; else -> "Data saver"
            }
            val s = stdNear(h)
            return Pair(if (s == 2160) "4K" else (if (s > 0) s else h).toString() + "p", band)
        }
        return Pair(
            if (bw > 0) String.format("%.1f Mbps", bw / 1e6) else "Default",
            if (bw >= 3_000_000) "High" else if (bw >= 1_000_000) "Medium" else "Low"
        )
    }

    private fun header(chips: String, name: String): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(2))
        }
        box.addView(TextView(act).apply {
            text = chips
            setTextColor(act.getColor(R.color.mg_text_dim))
            textSize = 12f
            maxLines = 1
        })
        if (name.isNotBlank()) box.addView(TextView(act).apply {
            text = name
            setTextColor(act.getColor(R.color.mg_text))
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        return box
    }

    private fun row(label: String, sub: String?, primary: Boolean, click: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            val out = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                foreground = act.getDrawable(out.resourceId)
            }
            setOnClickListener { click() }
        }
        row.addView(TextView(act).apply {
            text = label
            setTextColor(act.getColor(if (primary) R.color.mg_primary else R.color.mg_text))
            textSize = 15f
            if (primary) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!sub.isNullOrBlank()) row.addView(TextView(act).apply {
            text = sub
            setTextColor(act.getColor(R.color.mg_text_dim))
            textSize = 13f
        })
        return row
    }
}
