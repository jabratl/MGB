package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** T2: full-screen "View page source": Rendered (the page as it is now) and Original (what the server sent). */
object PageSource {
    private const val CAP = 2_000_000   // characters / bytes shown at most
    private const val CHUNK = 400       // long lines are cut into rows of this many characters
    private const val MAX_MATCHES = 5000
    private val TAG_RE = Regex("<[^>]*>")

    /** num = line number (0 = continuation of a long line); whole = the line fits in one row (tag tint is safe). */
    private class Row(val num: Int, val text: String, val whole: Boolean)

    private val RENDER_JS = "(function(){var d=document;var t='';try{if(d.doctype)t=new XMLSerializer().serializeToString(d.doctype)+'\\n';}catch(e){}" +
        "return t+d.documentElement.outerHTML;})()"

    fun open(act: Activity, tab: TabManager.Tab) {
        if (tab.home || tab.isExt || !tab.url.startsWith("http")) { Util.toast(act, "No page source for this tab"); return }
        Viewer(act, tab).show()
    }

    private class Viewer(val act: Activity, val tab: TabManager.Tab) {
        val priv = tab.priv
        val url = tab.url
        val dlg = Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        fun dp(n: Int) = Util.dp(act, n)
        fun col(id: Int) = act.getColor(id)

        var mode = 0                         // 0 = Rendered, 1 = Original
        var wrap = true
        var gen = 0                          // bumps on every load, so a late answer of an older load is ignored
        val raw = arrayOfNulls<String>(2)    // cached text per mode
        val cut = BooleanArray(2)
        var rows: List<Row> = emptyList()
        var query = ""
        val matchRow = ArrayList<Int>()
        val matchPos = ArrayList<Int>()
        var cur = -1

        lateinit var rv: RecyclerView
        lateinit var holder: HorizontalScrollView
        lateinit var frame: android.widget.FrameLayout
        lateinit var msg: TextView
        lateinit var cutNote: TextView
        lateinit var count: TextView
        lateinit var pillR: TextView
        lateinit var pillO: TextView
        lateinit var adapter: RecyclerView.Adapter<VH>
        val monoPx = 12f * act.resources.displayMetrics.scaledDensity

        inner class VH(v: View, val num: TextView, val body: TextView) : RecyclerView.ViewHolder(v)

        fun show() {
            val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.mg_bg)) }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back); setColorFilter(col(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Back"
                setOnClickListener { gen++; dlg.dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = Util.host(url).ifEmpty { "Page source" }
                textSize = 20f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(col(R.color.mg_text)); setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val more = ImageView(act).apply {
                setImageResource(R.drawable.ic_more); setColorFilter(col(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "More"
            }
            more.setOnClickListener { menu(more) }
            head.addView(more, LinearLayout.LayoutParams(dp(44), dp(48)))
            root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

            // pills (not for a private tab: Original would send cookies from the normal jar, so it is not offered)
            val pills = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), 0, dp(14), dp(8)) }
            pillR = pill("Rendered") { select(0) }
            pillO = pill("Original") { select(1) }
            pills.addView(pillR, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
            pills.addView(pillO, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
            if (!priv) root.addView(pills, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // search row
            val sr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val search = EditText(act).apply {
                hint = "Search in source"; textSize = 15f; setSingleLine(true)
                setTextColor(col(R.color.mg_text)); setHintTextColor(col(R.color.mg_text_dim))
                setPadding(dp(16), 0, dp(12), 0)
                background = GradientDrawable().apply { setColor(col(R.color.mg_surface)); cornerRadius = dp(24).toFloat() }
            }
            sr.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
            count = TextView(act).apply { textSize = 12f; setTextColor(col(R.color.mg_text_dim)); gravity = Gravity.CENTER; minWidth = dp(52) }
            sr.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            sr.addView(arrow(-90f, "Previous match") { step(-1) }, LinearLayout.LayoutParams(dp(36), dp(44)))
            sr.addView(arrow(90f, "Next match") { step(1) }, LinearLayout.LayoutParams(dp(36), dp(44)))
            root.addView(sr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(14), 0, dp(8), dp(6)) })

            cutNote = TextView(act).apply { textSize = 12f; setTextColor(col(R.color.mg_text_dim)); setPadding(dp(16), 0, dp(16), dp(4)); visibility = View.GONE }
            root.addView(cutNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            frame = android.widget.FrameLayout(act)
            rv = RecyclerView(act).apply { layoutManager = LinearLayoutManager(act); setHasFixedSize(false) }
            adapter = object : RecyclerView.Adapter<VH>() {
                override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
                    val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(1), dp(10), dp(1)) }
                    val n = TextView(act).apply {
                        textSize = 11f; typeface = Typeface.MONOSPACE; gravity = Gravity.END
                        setTextColor(col(R.color.mg_text_dim)); setPadding(0, 0, dp(8), 0)
                    }
                    val b = TextView(act).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(col(R.color.mg_text)) }
                    r.addView(n, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
                    r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    r.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    return VH(r, n, b)
                }
                override fun getItemCount() = rows.size
                override fun onBindViewHolder(h: VH, i: Int) {
                    val row = rows[i]
                    h.num.text = if (row.num > 0) row.num.toString() else ""
                    h.body.setSingleLine(!wrap)
                    h.body.text = styled(row, i)
                }
            }
            rv.adapter = adapter
            holder = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = true }
            msg = TextView(act).apply { textSize = 14f; setTextColor(col(R.color.mg_text_dim)); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
            frame.addView(msg, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; findAll(true) }
            })

            dlg.setContentView(root)
            dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dlg.setOnDismissListener { gen++ }
            dlg.show()
            select(0)
        }

        fun pill(label: String, on: () -> Unit) = TextView(act).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setOnClickListener { on() }
        }

        fun arrow(rot: Float, desc: String, on: () -> Unit) = ImageView(act).apply {
            setImageResource(R.drawable.ic_chevron); setColorFilter(col(R.color.mg_text)); rotation = rot
            setPadding(dp(8), dp(10), dp(8), dp(10)); contentDescription = desc
            setOnClickListener { on() }
        }

        fun paintPills() {
            for ((i, p) in listOf(pillR, pillO).withIndex()) {
                val sel = i == mode
                p.setTextColor(if (sel) col(R.color.mg_primary) else col(R.color.mg_text_dim))
                p.background = GradientDrawable().apply {
                    setColor(if (sel) ColorUtils.setAlphaComponent(col(R.color.mg_primary), 0x26) else col(R.color.mg_surface))
                    cornerRadius = dp(18).toFloat()
                }
            }
        }

        fun select(m: Int) {
            mode = m
            paintPills()
            val have = raw[m]
            if (have != null) { setText(have, cut[m]); return }
            if (m == 0) loadRendered() else loadOriginal()
        }

        fun status(s: String) { rows = emptyList(); adapter.notifyDataSetChanged(); cutNote.visibility = View.GONE; matchRow.clear(); matchPos.clear(); cur = -1; count.text = ""; msg.text = s; msg.visibility = View.VISIBLE }

        // ---- Rendered: the page as it is now (JS result is a JSON string)
        fun loadRendered() {
            if (tab.suspended || tab.pending != null) { status("This tab has not loaded yet. Open it first, or use Original."); return }
            status("Loading...")
            val my = ++gen
            try {
                tab.web.evaluateJavascript(RENDER_JS) { res ->
                    if (my != gen) return@evaluateJavascript
                    val s = try { JSONTokener(res ?: "null").nextValue() as? String } catch (e: Exception) { null }
                    if (s == null) { status("Could not read the page."); return@evaluateJavascript }
                    val c = s.length > CAP
                    val t = if (c) s.substring(0, CAP) else s
                    raw[0] = t; cut[0] = c
                    if (mode == 0) setText(t, c)
                }
            } catch (e: Exception) { status("Could not read the page.") }
        }

        // ---- Original: what the server sent (background thread, never the main thread)
        fun loadOriginal() {
            if (priv) return
            status("Loading...")
            val my = ++gen
            val ua = try { tab.web.settings.userAgentString } catch (e: Throwable) { null }
            Thread {
                var text: String? = null
                var c = false
                var err: String? = null
                try {
                    var u = url
                    var hops = 0
                    while (true) {
                        val conn = URL(u).openConnection() as HttpURLConnection
                        try {
                            conn.instanceFollowRedirects = false
                            conn.connectTimeout = 15000
                            conn.readTimeout = 20000
                            if (!ua.isNullOrEmpty()) conn.setRequestProperty("User-Agent", ua)
                            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                            val ck = try { CookieManager.getInstance().getCookie(u) } catch (e: Throwable) { null }
                            if (!ck.isNullOrEmpty()) conn.setRequestProperty("Cookie", ck)
                            val code = conn.responseCode
                            if (code in 300..399 && hops < 8) {
                                val loc = conn.getHeaderField("Location")
                                if (loc.isNullOrEmpty()) { err = "Redirect without an address."; break }
                                u = URL(URL(u), loc).toString()
                                if (!u.startsWith("http")) { err = "Redirected to a non-web address."; break }
                                hops++
                                continue
                            }
                            val type = (conn.contentType ?: "").lowercase()
                            val textual = type.isEmpty() || type.startsWith("text/") || type.contains("xml") || type.contains("json") || type.contains("javascript")
                            if (!textual) { err = "Not a text page ($type)."; break }
                            val st = (if (code >= 400) conn.errorStream else conn.inputStream)
                            if (st == null) { err = "Empty answer (HTTP $code)."; break }
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(16384)
                            st.use { s ->
                                while (true) {
                                    val n = s.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    if (out.size() > CAP) { c = true; break }
                                }
                            }
                            val cs = Regex("charset=([^;\\s]+)").find(type)?.groupValues?.get(1)?.trim('"', '\'')
                            val charset = try { if (cs != null) java.nio.charset.Charset.forName(cs) else Charsets.UTF_8 } catch (e: Exception) { Charsets.UTF_8 }
                            val bytes = out.toByteArray()
                            text = String(bytes, 0, minOf(bytes.size, CAP), charset)
                            break
                        } finally { conn.disconnect() }
                    }
                } catch (e: Exception) { err = "Could not load: " + (e.message ?: e.javaClass.simpleName) }
                val fText = text
                val fErr = err
                val fCut = c
                act.runOnUiThread {
                    if (my != gen) return@runOnUiThread
                    if (fText == null) { Util.toast(act, if (fErr != null && fErr.startsWith("Not a text")) "Not a text page" else "Could not load source"); status(fErr ?: "Could not load."); return@runOnUiThread }
                    raw[1] = fText; cut[1] = fCut
                    if (mode == 1) setText(fText, fCut)
                }
            }.start()
        }

        // ---- text -> rows
        fun setText(s: String, c: Boolean) {
            val list = ArrayList<Row>()
            var n = 0
            for (line0 in s.split('\n')) {
                n++
                val line = line0.removeSuffix("\r")
                if (line.length <= CHUNK) list.add(Row(n, line, true))
                else {
                    var i = 0
                    var first = true
                    while (i < line.length) {
                        val e = minOf(i + CHUNK, line.length)
                        list.add(Row(if (first) n else 0, line.substring(i, e), false))
                        first = false
                        i = e
                    }
                }
            }
            rows = list
            msg.visibility = View.GONE
            cutNote.text = "Cut at 2 MB"
            cutNote.visibility = if (c) View.VISIBLE else View.GONE
            applyWidth()
            adapter.notifyDataSetChanged()
            rv.scrollToPosition(0)
            findAll(false)
        }

        // wrap on = the list fills the screen; wrap off = the list is as wide as the longest row (400 characters) inside a sideways scroller.
        // (A scroller measures its child without a width limit, so it is only used for wrap off.)
        fun applyWidth() {
            (rv.parent as? ViewGroup)?.removeView(rv)
            if (holder.parent != null) frame.removeView(holder)
            if (wrap) {
                frame.addView(rv, 0, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                val p = Paint().apply { typeface = Typeface.MONOSPACE; textSize = monoPx }
                holder.removeAllViews()
                holder.addView(rv, android.widget.FrameLayout.LayoutParams(dp(70) + p.measureText("M".repeat(CHUNK)).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
                frame.addView(holder, 0, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }

        // ---- search
        fun findAll(jump: Boolean) {
            matchRow.clear(); matchPos.clear(); cur = -1
            val q = query
            if (q.isNotEmpty()) {
                loop@ for ((i, r) in rows.withIndex()) {
                    var p = r.text.indexOf(q, 0, true)
                    while (p >= 0) {
                        matchRow.add(i); matchPos.add(p)
                        if (matchRow.size >= MAX_MATCHES) break@loop
                        p = r.text.indexOf(q, p + q.length, true)
                    }
                }
                if (matchRow.isNotEmpty()) cur = 0
            }
            adapter.notifyDataSetChanged()
            showCount()
            if (jump && cur >= 0) goTo()
        }

        fun showCount() {
            count.text = when {
                query.isEmpty() -> ""
                matchRow.isEmpty() -> "0"
                else -> "${cur + 1}/" + matchRow.size + (if (matchRow.size >= MAX_MATCHES) "+" else "")
            }
        }

        fun goTo() {
            if (cur < 0) return
            (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(matchRow[cur], dp(90))
            adapter.notifyDataSetChanged()
        }

        fun step(d: Int) {
            if (matchRow.isEmpty()) return
            cur = (cur + d + matchRow.size) % matchRow.size
            showCount(); goTo()
        }

        // ---- styling of one row: tag tint (only rows that fit in one row) + search highlights
        fun styled(row: Row, i: Int): CharSequence {
            val sp = SpannableString(row.text)
            if (row.whole) {
                val tint = ColorUtils.setAlphaComponent(col(R.color.mg_primary), 0xCC)
                for (m in TAG_RE.findAll(row.text)) sp.setSpan(ForegroundColorSpan(tint), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (query.isNotEmpty() && matchRow.isNotEmpty()) {
                val soft = ColorUtils.setAlphaComponent(col(R.color.mg_primary), 0x40)
                val strong = ColorUtils.setAlphaComponent(col(R.color.mg_primary), 0x99)
                var k = matchRow.binarySearch(i)
                if (k < 0) return sp
                while (k > 0 && matchRow[k - 1] == i) k--
                while (k < matchRow.size && matchRow[k] == i) {
                    val s0 = matchPos[k]
                    sp.setSpan(BackgroundColorSpan(if (k == cur) strong else soft), s0, minOf(s0 + query.length, row.text.length), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    k++
                }
            }
            return sp
        }

        // ---- overflow menu
        fun menu(anchor: View) {
            val pm = PopupMenu(act, anchor)
            pm.menu.add(0, 1, 0, "Copy all")
            pm.menu.add(0, 2, 1, "Share as .html")
            pm.menu.add(0, 3, 2, if (wrap) "Wrap lines: on" else "Wrap lines: off")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> copyAll()
                    2 -> shareHtml()
                    3 -> { wrap = !wrap; applyWidth(); adapter.notifyDataSetChanged() }
                }
                true
            }
            pm.show()
        }

        fun copyAll() {
            val t = raw[mode] ?: return
            try {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MGB", t))
                Util.toast(act, "Copied")
            } catch (e: Throwable) { Util.toast(act, "Too big to copy, use Share") }
        }

        fun shareHtml() {
            val t = raw[mode] ?: return
            try {
                val dir = File(act.cacheDir, "updates")
                dir.mkdirs()
                val name = "source-" + Util.host(url).replace(Regex("[^A-Za-z0-9.-]"), "_").ifEmpty { "page" } + ".html"
                val f = File(dir, name)
                f.writeText(t)
                val u = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) { Util.toast(act, "Cannot share") }
        }
    }
}
