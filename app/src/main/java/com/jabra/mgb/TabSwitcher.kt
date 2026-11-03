package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * S31 + S32: Thazir tab switcher (full-screen Dialog). Own design, soft plum gradient background.
 * Top switch: Tabs | Private | Groups. 2-column cards with a page thumbnail (taken when you leave a tab or open the
 * switcher; a colour avatar until one exists), title, host, close. Select mode (menu or long press): tick cards, then
 * Group / Ungroup / Close. Groups live in memory only (lost when the app restarts); private tabs cannot be grouped.
 */
object TabSwitcher {
    private const val TAG = "Thazir.switcher"
    private var current: Screen? = null
    private val thumbs = HashMap<Int, Bitmap>()
    private val groups = LinkedHashMap<String, MutableSet<Int>>()
    private val colors = HashMap<String, Int>() // S43: group colour (palette value); missing = colour from the name

    private val PALETTE = intArrayOf(
        0xFFB0B4BC.toInt(), 0xFF6C8CF0.toInt(), 0xFFF06C7C.toInt(), 0xFFF5C26B.toInt(), 0xFF7FD67F.toInt(),
        0xFFF28BB5.toInt(), 0xFFB57CE8.toInt(), 0xFF5CC8F0.toInt(), 0xFFF59A5B.toInt(), 0xFF4DC7A8.toInt()
    )

    private fun colorOf(name: String): Int = colors[name] ?: PALETTE[(abs(name.hashCode()) % (PALETTE.size - 1)) + 1]

    /** S41: groups saved with the session. indexOf = tab id -> position in the saved tab list. S43: v2 also keeps colours. */
    fun exportGroups(indexOf: Map<Int, Int>): String {
        val g = org.json.JSONObject()
        val cc = org.json.JSONObject()
        for ((n, ids) in groups) {
            val a = org.json.JSONArray()
            for (id in ids) indexOf[id]?.let { a.put(it) }
            if (a.length() > 0) {
                g.put(n, a)
                colors[n]?.let { cc.put(n, it) }
            }
        }
        return org.json.JSONObject().put("v", 2).put("g", g).put("c", cc).toString()
    }

    fun importGroups(json: String, idAt: (Int) -> Int?) {
        try {
            val o = org.json.JSONObject(json)
            val v2 = o.optInt("v") == 2 && o.optJSONObject("g") != null // old files: the object itself is name -> indexes
            val g = if (v2) o.getJSONObject("g") else o
            val cc = if (v2) o.optJSONObject("c") else null
            groups.clear()
            colors.clear()
            for (n in g.keys()) {
                val a = g.getJSONArray(n)
                val s = LinkedHashSet<Int>()
                for (i in 0 until a.length()) idAt(a.getInt(i))?.let { s.add(it) }
                if (s.isNotEmpty()) {
                    groups[n] = s
                    if (cc != null && cc.has(n)) colors[n] = cc.getInt(n)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "groups restore", e) }
    }

    /** S40: long press menu "Open in new tab in group": pick a group (or make one), the new tab joins it. */
    fun openInGroup(ctx: Context, tm: TabManager, url: String, openerId: Int) {
        fun open(name: String) {
            val t = tm.newTab(url, openerId, false) ?: return
            groups.getOrPut(name) { LinkedHashSet() }.add(t.id)
            Util.toast(ctx, "Opened in group $name")
        }
        val names = groups.keys.toList()
        val et = android.widget.EditText(ctx).apply {
            hint = "Group name"
            setSingleLine()
            setText(if (names.isEmpty()) "Group 1" else "")
        }
        val pad = Util.dp(ctx, 20)
        val box = android.widget.FrameLayout(ctx).apply { setPadding(pad, Util.dp(ctx, 8), pad, 0); addView(et) }
        val b = MaterialAlertDialogBuilder(ctx).setTitle("Open in group")
        if (names.isEmpty()) {
            b.setView(box).setPositiveButton("Open") { _, _ -> et.text.toString().trim().take(30).takeIf { it.isNotEmpty() }?.let { open(it) } }
        } else {
            val all = names + "New group..."
            b.setItems(all.toTypedArray()) { _, i ->
                if (i < names.size) open(names[i])
                else MaterialAlertDialogBuilder(ctx).setTitle("New group").setView(box)
                    .setPositiveButton("Open") { _, _ ->
                        et.text.toString().trim().take(30).takeIf { it.isNotEmpty() && !groups.containsKey(it) }?.let { open(it) }
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        b.setNegativeButton("Cancel", null).show()
    }

    /** Small picture of the page in [t] (only possible while its WebView is attached, i.e. the active tab). */
    fun snap(t: TabManager.Tab) {
        try {
            if (t.home || t.isExt) return
            val w = t.web
            if (w.width <= 0 || w.height <= 0) return
            val s = 0.4f
            val b = Bitmap.createBitmap((w.width * s).toInt().coerceAtLeast(1), (w.height * s).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(b)
            c.drawColor(Color.WHITE)
            c.scale(s, s)
            w.draw(c)
            thumbs[t.id]?.recycle()
            thumbs[t.id] = b
        } catch (e: Throwable) { Log.w(TAG, "snap failed", e) }
    }

    fun show(act: Activity, tm: TabManager) {
        if (act.isFinishing || act.isDestroyed) return
        try { current?.dismiss() } catch (_: Exception) { }
        try {
            tm.active?.let { snap(it) }
            val s = Screen(act, tm)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    private fun groupOf(t: TabManager.Tab): String? = groups.entries.firstOrNull { t.id in it.value }?.key

    private class MI(val label: String, val icon: Int, val on: () -> Unit)

    private class Screen(private val act: Activity, private val tm: TabManager) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val CARD_H = 196
        private val HEAD_H = 36
        private var mode = if (tm.active?.priv == true) 1 else 0 // 0 tabs, 1 private, 2 groups
        private var backMode = 0 // S43: where the back arrow of an open group goes
        private var openGroup: String? = null
        private var selecting = false
        private var searching = false
        private var query = ""
        private var animateNext = true
        private val picked = HashSet<Int>()
        private var shown: List<TabManager.Tab> = emptyList()
        private lateinit var top: FrameLayout
        private lateinit var grid: LinearLayout
        private lateinit var empty: TextView
        private lateinit var bar: LinearLayout
        private lateinit var searchEt: EditText
        private lateinit var scroll: ScrollView
        private val targets = ArrayList<Pair<View, Any>>() // S44: drop targets of the current grid (group card -> String, tab card -> Tab)
        private var dragView: View? = null
        private var dragShift = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var hover: Pair<View, Any>? = null
        private lateinit var body: FrameLayout // S75: overlay host (drop zone, undo bar)
        private var zone: TextView? = null // S75: "Remove tab from group" drop zone (group view, while dragging)
        private var undoBar: View? = null // S75
        private val ZONE = Any()
        private lateinit var rootV: LinearLayout // S77: for the dim scrim behind the group panel
        private var panelAnim = false // S77: play the panel open animation on the next render
        private var popFrom: IntArray? = null // S77: screen centre of the group card that was tapped

        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(res: Int) = act.getColor(res)
        private val night get() = (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private val cardW get() = (act.resources.displayMetrics.widthPixels - dp(40)) / 2

        init { setOnDismissListener { if (current === this) current = null } }

        private fun round(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                it.setWindowAnimations(android.R.style.Animation_Dialog)
                val g = if (night) intArrayOf(0xFF2B2347.toInt(), 0xFF0A0C10.toInt()) else intArrayOf(0xFFECE6F8.toInt(), 0xFFFFFFFF.toInt())
                it.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, g))
            }
        }

        // back: search -> select -> open group -> close the switcher
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onBackPressed() {
            when {
                searching -> endSearch()
                selecting -> endSelect(true)
                mode == 2 && openGroup != null -> closeGroupView()
                else -> dismiss()
            }
        }

        // ---- data

        private fun clean() {
            val ids = tm.allTabs().map { it.id }.toSet()
            for (g in groups.values) g.retainAll(ids)
            groups.entries.removeAll { it.value.isEmpty() }
            colors.keys.retainAll(groups.keys)
            thumbs.keys.retainAll(ids)
            if (openGroup != null && !groups.containsKey(openGroup)) { openGroup = null; mode = backMode }
        }

        private fun matches(t: TabManager.Tab): Boolean {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return true
            return t.title.lowercase().contains(q) || (t.pending ?: t.url).lowercase().contains(q)
        }

        private fun listFor(): List<TabManager.Tab> {
            val all = tm.allTabs()
            val g = openGroup
            val base = when {
                mode == 1 -> all.filter { it.priv }
                mode == 2 && g != null -> (groups[g] ?: emptySet<Int>()).mapNotNull { id -> all.firstOrNull { !it.priv && it.id == id } } // S77: group order
                mode == 2 -> emptyList()
                query.isNotBlank() -> all.filter { !it.priv } // searching: flat list, groups included
                else -> all.filter { !it.priv && groupOf(it) == null }
            }
            return base.filter { matches(it) }
        }

        private fun urlOf(t: TabManager.Tab): String? {
            if (t.priv || t.isExt || t.home) return null
            val u = t.pending ?: t.url
            return if (u.startsWith("http")) u else null
        }

        private fun addFav(list: List<TabManager.Tab>) {
            var n = 0
            for (t in list) {
                val u = urlOf(t) ?: continue
                if (!Bookmarks.isBookmarked(u)) { Bookmarks.addBookmark(t.title.ifBlank { Util.host(u) }, u); n++ }
            }
            Util.toast(act, if (n == 0) "Nothing new to add" else n.toString() + (if (n == 1) " bookmark added" else " bookmarks added"))
        }

        private fun shareTabs(list: List<TabManager.Tab>) {
            val txt = list.mapNotNull { t -> urlOf(t)?.let { (t.title.ifBlank { Util.host(it) }) + "\n" + it } }.joinToString("\n\n")
            if (txt.isEmpty()) Util.toast(act, "No web tabs to share") else tm.shareText(txt)
        }

        // ---- build

        private fun build() {
            val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            rootV = root
            top = FrameLayout(act)
            root.addView(top, LinearLayout.LayoutParams(MATCH, dp(64)))
            grid = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(16))
            }
            val sv = ScrollView(act)
            scroll = sv
            sv.clipChildren = false
            grid.clipChildren = false
            sv.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
            val bodyFrame = FrameLayout(act)
            body = bodyFrame
            bodyFrame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
            empty = TextView(act).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(c(R.color.mg_text_dim))
                visibility = View.GONE
            }
            bodyFrame.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(bodyFrame, LinearLayout.LayoutParams(MATCH, 0, 1f))
            bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = round(c(R.color.mg_surface), 30, 1, c(R.color.mg_outline)) // S72: floating pill bar
                elevation = dp(6).toFloat()
            }
            root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(4), dp(14), dp(12)) })
            setContentView(root)
            render()
        }

        private fun icon(res: Int, desc: String, tint: Int = c(R.color.mg_text), on: (View) -> Unit) = ImageView(act).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            setOnClickListener { on(it) }
            press(this)
        }

        /** Soft press feedback: the view shrinks a little while the finger is down. Clicks still work (listener returns false). */
        private fun press(v: View) {
            v.setOnTouchListener { x, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
        }

        private fun labelBtn(res: Int, label: String, on: () -> Unit): View {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { on() }
            }
            col.addView(ImageView(act).apply { setImageResource(res); setColorFilter(c(R.color.mg_text)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            col.addView(TextView(act).apply { text = label; textSize = 11f; setTextColor(c(R.color.mg_text_dim)) })
            return col
        }

        private fun segItem(label: String, icon: Int, sel: Boolean, on: () -> Unit): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = if (sel) round(if (mode == 1) c(R.color.mg_primary) else c(R.color.mg_bg), 20) else null
                setOnClickListener { on() }
            }
            val fg = if (sel && mode == 1) c(R.color.mg_on_primary) else if (sel) c(R.color.mg_text) else c(R.color.mg_text_dim)
            row.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(fg) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) })
            row.addView(TextView(act).apply {
                text = label
                textSize = 14f
                setTextColor(fg)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            return row
        }

        private fun go(m: Int) { mode = m; openGroup = null; query = ""; searching = false; animateNext = true; endSelect(false); render() }

        // ---- popup menu (replaces the plain list dialogs)

        private fun popup(anchor: View, items: List<MI>, yOff: Int = 0) {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(c(R.color.mg_surface), 16, 1, c(R.color.mg_outline))
                setPadding(dp(6), dp(8), dp(6), dp(8))
            }
            val pw = PopupWindow(col, dp(236), WRAP, true)
            pw.elevation = dp(12).toFloat()
            pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            for (mi in items) {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener { pw.dismiss(); mi.on() }
                }
                row.addView(ImageView(act).apply { setImageResource(mi.icon); setColorFilter(c(R.color.mg_text)) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                row.addView(TextView(act).apply { text = mi.label; textSize = 15f; setTextColor(c(R.color.mg_text)) })
                col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            try { pw.showAsDropDown(anchor, 0, yOff, Gravity.END) } catch (e: Exception) { Log.w(TAG, "popup failed", e) }
        }

        // ---- top bar

        private fun drawTop() {
            if (searching && this::searchEt.isInitialized && searchEt.parent === top) return // keep the keyboard while typing
            top.removeAllViews()
            if (selecting) {
                top.addView(icon(R.drawable.ic_close, "Cancel") { endSelect(true) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
                top.addView(TextView(act).apply {
                    text = if (picked.isEmpty()) "Select items" else picked.size.toString() + (if (picked.size == 1) " item" else " items")
                    textSize = 18f
                    setTextColor(c(R.color.mg_text))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                val end = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                end.addView(TextView(act).apply {
                    text = if (picked.size == shown.size && shown.isNotEmpty()) "None" else "All"
                    textSize = 15f
                    setTextColor(c(R.color.mg_primary))
                    gravity = Gravity.CENTER
                    setOnClickListener { toggleAll() }
                }, LinearLayout.LayoutParams(dp(52), dp(48)))
                end.addView(icon(R.drawable.ic_more, "Menu") { selectMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                top.addView(end, FrameLayout.LayoutParams(WRAP, dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) })
                return
            }
            if (searching) {
                top.addView(icon(R.drawable.ic_back, "Close search") { endSearch() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
                searchEt = EditText(act).apply {
                    hint = "Search tabs"
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_SEARCH
                    textSize = 15f
                    setTextColor(c(R.color.mg_text))
                    setHintTextColor(c(R.color.mg_text_dim))
                    background = round(c(R.color.mg_surface_variant), 22)
                    setPadding(dp(16), 0, dp(16), 0)
                    setText(query)
                    setSelection(query.length)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c2: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            query = s?.toString().orEmpty()
                            shown = listFor()
                            renderList()
                        }
                    })
                }
                top.addView(searchEt, FrameLayout.LayoutParams(MATCH, dp(44), Gravity.CENTER_VERTICAL).apply { marginStart = dp(60); marginEnd = dp(16) })
                searchEt.post {
                    searchEt.requestFocus()
                    (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchEt, InputMethodManager.SHOW_IMPLICIT)
                }
                return
            }
            val g = openGroup
            if (mode == 2 && g != null) { // inside a group: back arrow, colour dot + name, menu
                top.addView(icon(R.drawable.ic_back, "Back") { closeGroupView() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
                val ttl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                ttl.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(g)) } }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) })
                ttl.addView(TextView(act).apply {
                    text = g
                    textSize = 18f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(c(R.color.mg_text))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                top.addView(ttl, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(60); marginEnd = dp(64) })
                top.addView(icon(R.drawable.ic_more, "Group menu") { groupMenu(it, g, true) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
                return
            }
            val all = tm.allTabs()
            top.addView(icon(R.drawable.ic_search, "Search tabs") { startSearch() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
            val seg = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = round(c(R.color.mg_surface_variant), 24)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            seg.addView(segItem(all.count { !it.priv }.toString(), R.drawable.ic_list, mode == 0) { go(0) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            seg.addView(segItem(all.count { it.priv }.toString(), R.drawable.ic_lock, mode == 1) { go(1) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            seg.addView(segItem(groups.size.toString(), R.drawable.ic_folder, mode == 2) { go(2) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            top.addView(seg, FrameLayout.LayoutParams(WRAP, dp(48), Gravity.CENTER))
            top.addView(icon(R.drawable.ic_more, "Menu") { menu(it) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
        }

        private fun drawBar() {
            bar.removeAllViews()
            if (selecting) {
                bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                if (mode == 0 || (mode == 2 && openGroup == null)) bar.addView(labelBtn(R.drawable.ic_folder, "Group") { askGroup(picked.toSet()) }, LinearLayout.LayoutParams(dp(80), dp(52)))
                if (mode == 2) bar.addView(labelBtn(R.drawable.ic_folder, "Ungroup") { ungroup() }, LinearLayout.LayoutParams(dp(80), dp(52)))
                bar.addView(labelBtn(R.drawable.ic_trash, "Close") { closePicked() }, LinearLayout.LayoutParams(dp(80), dp(52)))
                bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                return
            }
            bar.addView(icon(R.drawable.ic_back, "Done") { dismiss() }, LinearLayout.LayoutParams(dp(56), dp(52)))
            bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_add)
                setColorFilter(c(R.color.mg_on_primary))
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c(R.color.mg_primary), androidx.core.graphics.ColorUtils.blendARGB(c(R.color.mg_primary), 0xFF7A3CFF.toInt(), 0.35f))).apply { cornerRadius = dp(22).toFloat() } // S72
                contentDescription = "New tab"
                setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); newTab(mode == 1) }
                press(this)
            }, LinearLayout.LayoutParams(dp(96), dp(44)))
            bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(icon(R.drawable.ic_trash, "Close all") { closeAll() }, LinearLayout.LayoutParams(dp(56), dp(52)))
        }

        private fun render() {
            hideUndo() // S75: any new state makes an old Undo stale
            clean()
            shown = listFor()
            picked.retainAll(shown.map { it.id }.toSet())
            drawTop()
            drawBar()
            renderList()
            applyPanel() // S77
        }

        /** S77: an open group is a rounded panel over a dimmed switcher (like Brave); it grows out of the tapped group card. */
        private fun applyPanel() {
            val panel = mode == 2 && openGroup != null
            val r = dp(22).toFloat()
            val surf = c(R.color.mg_surface)
            rootV.setBackgroundColor(if (panel) 0x66000000 else Color.TRANSPARENT)
            val tl = top.layoutParams as LinearLayout.LayoutParams
            val bl = body.layoutParams as LinearLayout.LayoutParams
            val m = if (panel) dp(12) else 0
            tl.setMargins(m, if (panel) dp(8) else 0, m, 0)
            bl.setMargins(m, 0, m, if (panel) dp(6) else 0)
            top.layoutParams = tl
            body.layoutParams = bl
            top.background = if (panel) GradientDrawable().apply { setColor(surf); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) } else null
            body.background = if (panel) GradientDrawable().apply { setColor(surf); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) } else null
            if (panel && panelAnim) {
                val pf = popFrom
                val rl = IntArray(2)
                rootV.getLocationOnScreen(rl)
                val start = if (pf != null) 0.45f else 0.94f
                val views = listOf<View>(top, body)
                for ((i, v) in views.withIndex()) {
                    v.alpha = 0f
                    v.scaleX = start
                    v.scaleY = start
                    if (pf != null) {
                        v.pivotX = (pf[0] - rl[0] - m).toFloat()
                        v.pivotY = (pf[1] - rl[1] - dp(8) - (if (i == 0) 0 else dp(64))).toFloat()
                    }
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).setDuration(260).start()
                }
            }
            panelAnim = false
            popFrom = null
        }

        private fun groupNames(): List<String> {
            val q = query.trim().lowercase()
            return groups.keys.filter { q.isEmpty() || it.lowercase().contains(q) }
        }

        private fun renderList() {
            grid.removeAllViews()
            targets.clear()
            val anim = animateNext
            animateNext = false
            if (mode == 2 && openGroup == null) { // list of groups
                val names = groupNames()
                empty.text = if (groups.isEmpty()) "No groups yet.\nLong press a tab > Add tab to group." else "No match."
                empty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
                val views = names.map { groupRow(it) }
                for (v in views) grid.addView(v, LinearLayout.LayoutParams(MATCH, dp(76)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                if (anim) enter(views)
                return
            }
            val cards = ArrayList<View>()
            if (mode == 0 && query.isBlank() && !selecting) groupNames().forEach { n -> val v = groupCard(n); cards.add(v); targets.add(v to n) } // groups sit in the tab grid first
            shown.forEach { t -> val v = card(t); cards.add(v); targets.add(v to t) } // S77: inside a group a tab card = reorder target
            empty.text = when {
                query.isNotBlank() -> "No tab matches."
                mode == 1 -> "No private tabs.\nTap + to open one."
                else -> "No tabs here."
            }
            empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
            layoutCards(cards)
            if (anim) enter(cards)
        }

        private fun enter(views: List<View>) {
            for ((i, v) in views.withIndex()) {
                v.alpha = 0f
                v.translationY = dp(16).toFloat()
                v.scaleX = 0.94f; v.scaleY = 0.94f // S72
                v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                    .setDuration(240).setStartDelay((minOf(i, 10) * 24).toLong()).start()
            }
        }

        private fun layoutCards(cards: List<View>) {
            var i = 0
            while (i < cards.size) {
                val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                for (k in 0..1) {
                    val v = cards.getOrNull(i + k)
                    val lp = LinearLayout.LayoutParams(0, dp(CARD_H), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    line.addView(v ?: View(act), lp)
                }
                grid.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
                i += 2
            }
        }

        // ---- actions

        private fun endSelect(redraw: Boolean) { selecting = false; picked.clear(); if (redraw) render() }

        private fun startSelect(first: Int?) {
            selecting = true
            picked.clear()
            if (first != null) picked.add(first)
            render()
        }

        private fun toggleAll() {
            if (picked.size == shown.size) picked.clear() else { picked.clear(); shown.forEach { picked.add(it.id) } }
            render()
        }

        private fun startSearch() { searching = true; query = ""; render() }

        private fun endSearch() {
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(top.windowToken, 0)
            searching = false
            query = ""
            animateNext = true
            render()
        }

        private fun openGroupView(name: String, from: View? = null) {
            popFrom = from?.let { val l = IntArray(2); it.getLocationOnScreen(l); intArrayOf(l[0] + it.width / 2, l[1] + it.height / 2) } // S77
            panelAnim = true
            backMode = if (mode == 2) 2 else 0
            mode = 2
            openGroup = name
            query = ""
            searching = false
            animateNext = true
            render()
        }

        private fun closeGroupView() { openGroup = null; mode = backMode; animateNext = true; endSelect(false); render() }

        private fun newTab(p: Boolean) {
            val t = tm.newTab(TabManager.HOME_PAGE, 0, true, p)
            val g = openGroup
            if (t != null && !p && mode == 2 && g != null) groups[g]?.add(t.id)
            dismiss()
        }

        private fun menu(anchor: View) {
            val items = ArrayList<MI>()
            val onList = !(mode == 2 && openGroup == null)
            items.add(MI("New tab", R.drawable.ic_add) { newTab(false) })
            items.add(MI("New private tab", R.drawable.ic_lock) { newTab(true) })
            if (mode != 1) items.add(MI("New tab group", R.drawable.ic_folder) { newGroup() }) // S75
            if (onList) items.add(MI("Select tabs", R.drawable.ic_check) { startSelect(null) })
            if (mode != 1 && onList) items.add(MI("Bookmark all tabs", R.drawable.ic_bookmark_border) { TabBookmarks.dialog(act, tm) }) // S40
            if (mode != 1 && onList) items.add(MI("Share all tabs", R.drawable.ic_share) { TabBookmarks.shareAll(act, tm) }) // S41
            if (onList) items.add(MI(if (mode == 1) "Close all private tabs" else "Close all tabs", R.drawable.ic_trash) { closeAll() })
            items.add(MI("Delete browsing data", R.drawable.ic_trash) { deleteData() }) // S76
            items.add(MI("Settings", R.drawable.ic_settings) { dismiss(); SettingsScreen.open(act) }) // S76
            popup(anchor, items)
        }

        /** S76: choose what to delete (history, cookies and site data, cache, recently closed tabs). */
        private fun deleteData() {
            val names = arrayOf("Browsing history", "Cookies and site data", "Cache", "Recently closed tabs")
            val on = booleanArrayOf(true, false, true, true)
            MaterialAlertDialogBuilder(act)
                .setTitle("Delete browsing data")
                .setMultiChoiceItems(names, on) { _, i, v -> on[i] = v }
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    try {
                        if (on[0]) Bookmarks.clearHistory()
                        if (on[1]) {
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            android.webkit.CookieManager.getInstance().flush()
                            android.webkit.WebStorage.getInstance().deleteAllData()
                        }
                        if (on[2]) tm.active?.web?.clearCache(true)
                        if (on[3]) ClosedTabs.clear()
                        Util.toast(act, "Browsing data deleted")
                    } catch (e: Exception) { Util.toast(act, "Could not delete everything") }
                }
                .show()
        }

        private fun selectMenu(anchor: View) {
            val sel = shown.filter { it.id in picked }
            val items = ArrayList<MI>()
            items.add(MI(if (picked.size == shown.size && shown.isNotEmpty()) "Deselect all" else "Select all", R.drawable.ic_check) { toggleAll() })
            items.add(MI("Close tabs", R.drawable.ic_close) { closePicked() })
            if (mode != 1) items.add(MI("Add to group", R.drawable.ic_folder) { askGroup(picked.toSet()) })
            if (mode == 2) items.add(MI("Ungroup tabs", R.drawable.ic_folder) { ungroup() })
            if (mode != 1) items.add(MI("Add Favorite", R.drawable.ic_bookmark_border) { addFav(sel) })
            if (mode != 1) items.add(MI("Share tabs", R.drawable.ic_share) { shareTabs(sel) })
            popup(anchor, items)
        }

        private fun tabMenu(anchor: View, t: TabManager.Tab) {
            val items = ArrayList<MI>()
            items.add(MI("Select tab", R.drawable.ic_check) { startSelect(t.id) })
            if (!t.priv) items.add(MI("Add tab to group", R.drawable.ic_folder) { askGroup(setOf(t.id)) })
            if (urlOf(t) != null) items.add(MI("Add Favorite", R.drawable.ic_bookmark_border) { addFav(listOf(t)) })
            if (urlOf(t) != null) items.add(MI("Share", R.drawable.ic_share) { shareTabs(listOf(t)) })
            items.add(MI("Close tab", R.drawable.ic_close) { closeAnimated(anchor, t) })
            popup(anchor, items, -anchor.height + dp(44))
        }

        private fun groupMenu(anchor: View, name: String, inView: Boolean) {
            val items = ArrayList<MI>()
            if (inView) items.add(MI("Select tabs", R.drawable.ic_check) { startSelect(null) })
            items.add(MI("Edit group name", R.drawable.ic_edit) { renameGroup(name) })
            items.add(MI("Edit group colour", R.drawable.ic_theme) { recolor(name) })
            items.add(MI("Close group", R.drawable.ic_close) { closeGroup(name) })
            items.add(MI("Delete group", R.drawable.ic_trash) { deleteGroup(name) })
            popup(anchor, items, if (inView) 0 else -anchor.height + dp(44))
        }

        private fun closeAnimated(v: View, t: TabManager.Tab) {
            v.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f).translationY(-dp(18).toFloat()).setInterpolator(android.view.animation.AccelerateInterpolator()).setDuration(150).withEndAction { // S72
                tm.close(t)
                render()
            }.start()
        }

        // ---- groups

        private fun nextColor(): Int = PALETTE[(groups.size % (PALETTE.size - 1)) + 1]

        /** Colour circles (2 rows) + optional name field. [initName] null = colour only. */
        private fun groupDialog(title: String, initName: String?, initColor: Int, ok: String, onCancel: () -> Unit = {}, done: (String, Int) -> Unit) {
            var sel = PALETTE.indexOf(initColor).let { if (it >= 0) it else 1 }
            val dots = ArrayList<View>()
            fun paint() {
                for ((i, v) in dots.withIndex()) v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PALETTE[i])
                    if (i == sel) setStroke(dp(3), c(R.color.mg_text))
                }
            }
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
            for (r in 0..1) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                for (k in 0..4) {
                    val i = r * 5 + k
                    val d = View(act).apply { setOnClickListener { sel = i; paint() } }
                    dots.add(d)
                    row.addView(d, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) })
                }
                box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            var et: EditText? = null
            if (initName != null) {
                val field = EditText(act).apply {
                    setText(initName)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    setSelectAllOnFocus(true)
                }
                et = field
                box.addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            }
            paint()
            MaterialAlertDialogBuilder(act).setTitle(title).setView(box)
                .setPositiveButton(ok) { _, _ -> done(et?.text?.toString()?.trim()?.take(40).orEmpty(), PALETTE[sel]) }
                .setNegativeButton("Cancel") { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .show()
        }

        private fun askGroup(ids: Set<Int>) {
            if (ids.isEmpty()) { Util.toast(act, "Tick some tabs first"); return }
            val names = groups.keys.toList()
            val newGroup = {
                groupDialog("New tab group", "Group " + (groups.size + 1), nextColor(), "Save") { n, col ->
                    val name = n.ifBlank { "Group " + (groups.size + 1) }
                    if (!groups.containsKey(name)) colors[name] = col
                    addTo(name, ids)
                }
            }
            if (names.isEmpty()) { newGroup(); return }
            val items = (names + "New group...").toTypedArray()
            MaterialAlertDialogBuilder(act).setTitle("Move to group").setItems(items) { _, i ->
                if (i < names.size) addTo(names[i], ids) else newGroup()
            }.show()
        }

        private fun addTo(name: String, ids: Set<Int>) {
            for (g in groups.values) g.removeAll(ids)
            groups.getOrPut(name) { LinkedHashSet() }.addAll(ids)
            endSelect(false)
            openGroupView(name)
        }

        private fun ungroup() {
            for (g in groups.values) g.removeAll(picked)
            endSelect(false)
            render()
        }

        private fun renameGroup(old: String) {
            groupDialog("Edit group name", old, colorOf(old), "Save") { n, col ->
                val nn = n.ifBlank { old }
                if (nn != old && groups.containsKey(nn)) { Util.toast(act, "A group with this name exists"); return@groupDialog }
                val ids = groups.remove(old) ?: return@groupDialog
                groups[nn] = ids
                colors.remove(old)
                colors[nn] = col
                if (openGroup == old) openGroup = nn
                render()
            }
        }

        private fun recolor(name: String) {
            groupDialog("Group colour", null, colorOf(name), "Save") { _, col -> colors[name] = col; render() }
        }

        private fun closeGroup(name: String) {
            val ids = groups[name] ?: return
            closeList(tm.allTabs().filter { it.id in ids })
        }

        private fun deleteGroup(name: String) {
            groups.remove(name)
            colors.remove(name)
            if (openGroup == name) { openGroup = null; mode = backMode }
            Util.toast(act, "Group deleted, tabs kept")
            render()
        }

        private fun closeList(list: List<TabManager.Tab>) {
            if (list.isEmpty()) return
            MaterialAlertDialogBuilder(act)
                .setMessage("Close " + list.size + " tab" + (if (list.size == 1) "" else "s") + "?")
                .setPositiveButton("Close") { _, _ ->
                    for (t in list) tm.close(t)
                    endSelect(false)
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun closeAll() = closeList(shown)
        private fun closePicked() = closeList(shown.filter { it.id in picked })


        // ---- S44: drag a tab onto a group (or onto another tab = new group)

        private fun canDrag() = (mode == 0 || (mode == 2 && openGroup != null)) && query.isBlank() && !selecting && !searching // S75: also inside a group

        private val scroller = object : Runnable {
            override fun run() {
                val d = dragView ?: return
                val loc = IntArray(2)
                scroll.getLocationOnScreen(loc)
                val edge = dp(90)
                val dy = when {
                    lastY < loc[1] + edge -> -dp(9)
                    lastY > loc[1] + scroll.height - edge -> dp(9)
                    else -> 0
                }
                if (dy != 0) {
                    val before = scroll.scrollY
                    scroll.scrollBy(0, dy)
                    val moved = scroll.scrollY - before
                    if (moved != 0) { dragShift += moved; d.translationY += moved; updateHover(d) }
                }
                scroll.postDelayed(this, 16)
            }
        }

        private fun hitTarget(rx: Float, ry: Float, self: View): Pair<View, Any>? {
            val loc = IntArray(2)
            for (p in targets) {
                val v = p.first
                if (v === self) continue
                v.getLocationOnScreen(loc)
                if (rx >= loc[0] && rx <= loc[0] + v.width && ry >= loc[1] && ry <= loc[1] + v.height) return p
            }
            return null
        }

        private fun ring(v: View) {
            v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110).start()
            if (v === zone) v.background = round(tint(), 16, 2, c(R.color.mg_primary)) // S75
            (v as? FrameLayout)?.foreground = round(Color.TRANSPARENT, 18, 3, c(R.color.mg_primary))
        }

        private fun unring(v: View) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            if (v === zone) v.background = round(Color.TRANSPARENT, 16, 2, c(R.color.mg_primary)) // S75
            (v as? FrameLayout)?.foreground = null
        }

        private fun updateHover(self: View) {
            val tg = hitTarget(lastX, lastY, self)
            if (tg?.first === hover?.first) return
            hover?.first?.let { unring(it) }
            hover = tg
            tg?.first?.let { ring(it) }
        }

        private fun dragTouch(box: View, t: TabManager.Tab) {
            var downX = 0f
            var downY = 0f
            var armed = false
            var dragging = false
            val slop = ViewConfiguration.get(act).scaledTouchSlop
            val lift = Runnable {
                armed = true
                box.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (canDrag()) {
                    dragging = true
                    dragView = box
                    dragShift = 0f
                    (box.parent as? View)?.translationZ = dp(10).toFloat() // this row draws above the other rows
                    box.parent?.requestDisallowInterceptTouchEvent(true)
                    box.translationZ = dp(12).toFloat()
                    box.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.92f).setDuration(120).start()
                    if (openGroup != null) showZone() // S75
                    scroll.post(scroller)
                } else if (!selecting) tabMenu(box, t)
            }
            fun settle(v: View) {
                v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setInterpolator(OvershootInterpolator(1.1f)).setDuration(220)
                    .withEndAction { v.translationZ = 0f; (v.parent as? View)?.translationZ = 0f }.start()
            }
            box.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY
                        armed = false; dragging = false
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                        v.postDelayed(lift, ViewConfiguration.getLongPressTimeout().toLong())
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lastX = e.rawX; lastY = e.rawY
                        if (dragging) {
                            v.translationX = e.rawX - downX
                            v.translationY = e.rawY - downY + dragShift
                            updateHover(v)
                            true
                        } else {
                            if (!armed && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                                v.removeCallbacks(lift)
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                            }
                            armed // after the menu opened (no drag) swallow the rest
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(lift)
                        if (dragging) {
                            dragging = false
                            dragView = null
                            scroll.removeCallbacks(scroller)
                            val tg = hover
                            hover = null
                            if (tg != null) dropOn(v, t, tg)
                            else if (hypot(e.rawX - downX, e.rawY - downY) < slop * 2) { settle(v); tabMenu(v, t) }
                            else settle(v)
                            hideZone() // S75
                            true
                        } else {
                            if (!armed) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            armed // armed = the menu is open: no click
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(lift)
                        if (dragging) {
                            dragging = false
                            dragView = null
                            scroll.removeCallbacks(scroller)
                            hover?.first?.let { unring(it) }
                            hover = null
                            hideZone() // S75
                            settle(v)
                        } else v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        false
                    }
                    else -> false
                }
            }
        }

        /** The dragged card flies into the target and shrinks away, then the lists are rebuilt. */
        private fun dropOn(v: View, t: TabManager.Tab, tg: Pair<View, Any>) {
            val a = IntArray(2)
            val b = IntArray(2)
            v.getLocationOnScreen(a)
            tg.first.getLocationOnScreen(b)
            val dx = (b[0] + tg.first.width / 2f) - (a[0] + v.width / 2f)
            val dy = (b[1] + tg.first.height / 2f) - (a[1] + v.height / 2f)
            v.animate().translationX(v.translationX + dx).translationY(v.translationY + dy).scaleX(0.35f).scaleY(0.35f).alpha(0f)
                .setDuration(170).withEndAction {
                    val target = tg.second
                    if (target === ZONE) pullOut(t.id) // S75
                    else if (target is String) moveInto(target, setOf(t.id))
                    else if (target is TabManager.Tab && openGroup != null) reorder(t.id, target.id) // S77
                    else if (target is TabManager.Tab) {
                        groupDialog("New tab group", "Group " + (groups.size + 1), nextColor(), "Save", { render() }) { n, col ->
                            val name = n.ifBlank { "Group " + (groups.size + 1) }
                            if (!groups.containsKey(name)) colors[name] = col
                            moveInto(name, setOf(t.id, target.id))
                        }
                    } else render()
                }.start()
        }

        /** Puts the tabs into a group and stays on the tab grid (no jump into the group). S75: Undo bar instead of a toast. */
        private fun moveInto(name: String, ids: Set<Int>) {
            val before = HashMap<Int, String>()
            for ((n, g) in groups) for (i in ids) if (i in g) before[i] = n
            val hadGroup = groups.containsKey(name)
            for (g in groups.values) g.removeAll(ids)
            groups.getOrPut(name) { LinkedHashSet() }.addAll(ids)
            animateNext = false
            render()
            val n = groups[name]?.size ?: ids.size
            undo((if (n == 1) "1 tab" else "$n tabs") + " grouped") {
                for (g in groups.values) g.removeAll(ids)
                for ((i, gn) in before) groups.getOrPut(gn) { LinkedHashSet() }.add(i)
                if (!hadGroup) { groups.remove(name); colors.remove(name) }
                animateNext = false
                render()
            }
        }

        // ---- S75: pull a tab out of a group (drop zone + undo bar, like the Brave switcher)

        /** S77: drop a tab on another tab inside a group = it takes that place (order is saved with the session). */
        private fun reorder(id: Int, over: Int) {
            val g = groups[openGroup ?: return] ?: return
            val list = g.toMutableList()
            val oi = list.indexOf(id)
            val ti = list.indexOf(over)
            if (oi < 0 || ti < 0 || oi == ti) { render(); return }
            list.removeAt(oi)
            val at = list.indexOf(over)
            list.add(if (oi < ti) at + 1 else at, id)
            g.clear()
            g.addAll(list)
            animateNext = false
            render()
        }

        private fun tint(): Int = (c(R.color.mg_primary) and 0x00FFFFFF) or 0x33000000

        private fun showZone() {
            if (zone != null) return
            val z = TextView(act).apply {
                text = "Remove tab from group"
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(c(R.color.mg_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = round(Color.TRANSPARENT, 16, 2, c(R.color.mg_primary))
                elevation = dp(2).toFloat()
                alpha = 0f
                translationY = dp(24).toFloat()
            }
            body.addView(z, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
            z.animate().alpha(1f).translationY(0f).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).setDuration(200).start()
            zone = z
            targets.add(z to ZONE)
        }

        private fun hideZone() {
            val z = zone ?: return
            zone = null
            targets.removeAll { it.first === z }
            z.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(160).withEndAction { body.removeView(z) }.start()
        }

        private fun pullOut(id: Int) {
            val name = groups.entries.firstOrNull { id in it.value }?.key
            val col = if (name != null) colors[name] else null
            for (g in groups.values) g.remove(id)
            animateNext = false
            render()
            if (name == null) return
            undo("Tab removed from group") {
                groups.getOrPut(name) { LinkedHashSet() }.add(id)
                if (col != null) colors[name] = col
                animateNext = false
                render()
            }
        }

        private fun undo(msg: String, onUndo: () -> Unit) {
            hideUndo()
            val b = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(4), 0)
                background = round(c(R.color.mg_surface_variant), 16, 1, c(R.color.mg_outline))
                elevation = dp(8).toFloat()
                alpha = 0f
                translationY = dp(20).toFloat()
            }
            b.addView(TextView(act).apply {
                text = msg
                textSize = 14f
                setTextColor(c(R.color.mg_text))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            b.addView(TextView(act).apply {
                text = "Undo"
                textSize = 14f
                setTextColor(c(R.color.mg_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener { hideUndo(); onUndo() }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            body.addView(b, FrameLayout.LayoutParams(MATCH, dp(52), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(12)) })
            b.animate().alpha(1f).translationY(0f).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).setDuration(200).start()
            undoBar = b
            b.postDelayed({ if (undoBar === b) hideUndo(true) }, 5000)
        }

        private fun hideUndo(anim: Boolean = false) {
            val b = undoBar ?: return
            undoBar = null
            if (anim) b.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(160).withEndAction { body.removeView(b) }.start()
            else body.removeView(b)
        }

        private fun newGroup() {
            groupDialog("New tab group", "Group " + (groups.size + 1), nextColor(), "Save") { n, col ->
                val name = n.ifBlank { "Group " + (groups.size + 1) }
                if (groups.containsKey(name)) { Util.toast(act, "A group with this name exists"); return@groupDialog }
                colors[name] = col
                val t = tm.newTab(TabManager.HOME_PAGE, 0, true, false)
                if (t != null) groups[name] = LinkedHashSet<Int>().apply { add(t.id) }
                dismiss()
            }
        }

        // ---- cards

        private fun avatar(t: TabManager.Tab, sizeDp: Int, sp: Float): View {
            val host = if (t.home) "" else Util.host(t.url)
            val seed = if (t.home) "thazir" else host
            val hue = (abs(seed.hashCode()) % 360).toFloat()
            val letter = if (t.home) "T" else (host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?")
            val fav = if (t.home || t.priv) null else Favicons.get(host) // S45
            if (fav != null) return ImageView(act).apply {
                setImageBitmap(fav)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pd = dp(sizeDp) / 6
                setPadding(pd, pd, pd, pd)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(235, 255, 255, 255)) }
                layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
            }
            return TextView(act).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = sp
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.72f))) }
                layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
            }
        }

        /** Page picture scaled to the width [wPx] (top of the page), or a big coloured letter while there is no picture yet. */
        private fun previewOf(t: TabManager.Tab, wPx: Int, bigLetter: Boolean): View {
            val th = thumbs[t.id]
            if (th != null && !t.home) {
                return ImageView(act).apply {
                    setImageBitmap(th)
                    scaleType = ImageView.ScaleType.MATRIX
                    imageMatrix = Matrix().apply { val sc = wPx.toFloat() / th.width; setScale(sc, sc) }
                }
            }
            val f = FrameLayout(act)
            f.setBackgroundColor(c(R.color.mg_surface_variant))
            val s = if (bigLetter) 56 else 18
            f.addView(avatar(t, s, if (bigLetter) 26f else 9f), FrameLayout.LayoutParams(dp(s), dp(s), Gravity.CENTER))
            return f
        }

        private fun card(t: TabManager.Tab): View {
            val host = if (t.home) "" else Util.host(t.url)
            val title = if (t.home) (if (t.priv) "Private home" else "Home") else t.title.ifBlank { host }.ifBlank { "New tab" }
            val isCur = t === tm.active
            val hi = isCur && !selecting
            val on = selecting && t.id in picked
            val box = FrameLayout(act).apply {
                background = round(c(R.color.mg_surface), 18, if (on || hi) 2 else 0, c(R.color.mg_primary)) // S72: current tab has a ring too
                clipToOutline = true
                setOnClickListener {
                    if (selecting) { if (on) picked.remove(t.id) else picked.add(t.id); render() }
                    else { tm.switchTo(t); dismiss() }
                }
            }
            dragTouch(box, t) // S44: long press = lift the card (drag into a group) or the menu
            box.addView(previewOf(t, cardW, true), FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(HEAD_H) })
            val fg = if (hi) c(R.color.mg_on_primary) else c(R.color.mg_text)
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (hi) c(R.color.mg_primary) else c(R.color.mg_surface))
                setPadding(dp(8), 0, dp(4), 0)
            }
            head.addView(avatar(t, 18, 9f), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
            head.addView(TextView(act).apply {
                text = title
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(fg)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (selecting) {
                head.addView(TextView(act).apply {
                    gravity = Gravity.CENTER
                    text = if (on) "✓" else ""
                    textSize = 13f
                    setTextColor(c(R.color.mg_on_primary))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (on) setColor(c(R.color.mg_primary)) else { setColor(0x33000000); setStroke(dp(2), c(R.color.mg_text_dim)) }
                    }
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) })
            } else {
                head.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(fg)
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = "Close tab"
                    setOnClickListener { closeAnimated(box, t) }
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
            }
            box.addView(head, FrameLayout.LayoutParams(MATCH, dp(HEAD_H), Gravity.TOP))
            return box
        }

        /** 2 x 2 pictures of the first tabs; the 4th cell shows "+N" when there are more than 4. */
        private fun miniGrid(list: List<TabManager.Tab>, wPx: Int, gapDp: Int): View {
            val gap = dp(gapDp)
            val cell = ((wPx - gap * 3) / 2).coerceAtLeast(1)
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(gap, gap, gap, gap) }
            for (r in 0..1) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0..1) {
                    val idx = r * 2 + k
                    val t = list.getOrNull(idx)
                    val v: View = when {
                        t == null -> View(act)
                        idx == 3 && list.size > 4 -> TextView(act).apply {
                            text = "+" + (list.size - 3)
                            gravity = Gravity.CENTER
                            textSize = 13f
                            setTextColor(c(R.color.mg_text_dim))
                            background = round(c(R.color.mg_surface_variant), 8)
                        }
                        else -> FrameLayout(act).apply {
                            background = round(c(R.color.mg_surface_variant), 8)
                            clipToOutline = true
                            addView(previewOf(t, cell, false), FrameLayout.LayoutParams(MATCH, MATCH))
                        }
                    }
                    row.addView(v, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) })
                }
                col.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            return col
        }

        private fun groupCard(name: String): View {
            val ids = groups[name] ?: emptySet<Int>()
            val inside = tm.allTabs().filter { it.id in ids }
            val box = FrameLayout(act).apply {
                background = round(c(R.color.mg_surface), 18)
                clipToOutline = true
                setOnClickListener { v -> openGroupView(name, v) }
                setOnLongClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); groupMenu(v, name, false); true }
            }
            press(box)
            box.addView(miniGrid(inside, cardW, 6), FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(HEAD_H) })
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
            }
            head.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(name)) } }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) })
            head.addView(TextView(act).apply {
                text = name
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(TextView(act).apply {
                text = inside.size.toString()
                textSize = 12f
                setTextColor(c(R.color.mg_text_dim))
            })
            box.addView(head, FrameLayout.LayoutParams(MATCH, dp(HEAD_H), Gravity.TOP))
            return box
        }

        private fun groupRow(name: String): View {
            val ids = groups[name] ?: emptySet<Int>()
            val inside = tm.allTabs().filter { it.id in ids }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(c(R.color.mg_surface), 18)
                setPadding(dp(10), dp(8), dp(4), dp(8))
                setOnClickListener { v -> openGroupView(name, v) }
            }
            press(row)
            val mini = FrameLayout(act).apply { background = round(c(R.color.mg_surface_variant), 12); clipToOutline = true }
            mini.addView(miniGrid(inside, dp(58), 3), FrameLayout.LayoutParams(MATCH, MATCH))
            row.addView(mini, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(14) })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(name)) } }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) })
            line.addView(TextView(act).apply {
                text = name
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(line)
            col.addView(TextView(act).apply {
                text = inside.size.toString() + (if (inside.size == 1) " tab" else " tabs")
                textSize = 12f
                setTextColor(c(R.color.mg_text_dim))
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(icon(R.drawable.ic_more, "Group menu") { groupMenu(it, name, false) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            return row
        }
    }
}
