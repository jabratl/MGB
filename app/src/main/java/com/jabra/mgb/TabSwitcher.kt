package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

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

    private class Screen(private val act: Activity, private val tm: TabManager) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var mode = if (tm.active?.priv == true) 1 else 0 // 0 tabs, 1 private, 2 groups
        private var openGroup: String? = null
        private var selecting = false
        private val picked = HashSet<Int>()
        private var shown: List<TabManager.Tab> = emptyList()
        private lateinit var top: FrameLayout
        private lateinit var grid: LinearLayout
        private lateinit var empty: TextView
        private lateinit var bar: LinearLayout

        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(res: Int) = act.getColor(res)
        private val night get() = (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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
                val g = if (night) intArrayOf(0xFF2B2347.toInt(), 0xFF0A0C10.toInt()) else intArrayOf(0xFFECE6F8.toInt(), 0xFFFFFFFF.toInt())
                it.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, g))
            }
        }

        // ---- data

        private fun clean() {
            val ids = tm.allTabs().map { it.id }.toSet()
            for (g in groups.values) g.retainAll(ids)
            groups.entries.removeAll { it.value.isEmpty() }
            thumbs.keys.retainAll(ids)
            if (openGroup != null && !groups.containsKey(openGroup)) openGroup = null
        }

        private fun listFor(): List<TabManager.Tab> {
            val all = tm.allTabs()
            return when {
                mode == 1 -> all.filter { it.priv }
                mode == 2 && openGroup != null -> all.filter { !it.priv && openGroup?.let { g -> groups[g]?.contains(it.id) } == true }
                mode == 2 -> emptyList()
                else -> all.filter { !it.priv && groupOf(it) == null }
            }
        }

        // ---- build

        private fun build() {
            val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            top = FrameLayout(act)
            root.addView(top, LinearLayout.LayoutParams(MATCH, dp(72)))
            grid = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(16))
            }
            val sv = ScrollView(act)
            sv.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
            val bodyFrame = FrameLayout(act)
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
                setPadding(dp(16), dp(8), dp(16), dp(12))
            }
            root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            setContentView(root)
            render()
        }

        private fun icon(res: Int, desc: String, tint: Int = c(R.color.mg_text), on: () -> Unit) = ImageView(act).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            setOnClickListener { on() }
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

        private fun go(m: Int) { mode = m; openGroup = null; endSelect(false); render() }

        private fun drawTop() {
            top.removeAllViews()
            if (selecting) {
                top.addView(icon(R.drawable.ic_close, "Cancel") { endSelect(true) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
                top.addView(TextView(act).apply {
                    text = picked.size.toString() + " selected"
                    textSize = 18f
                    setTextColor(c(R.color.mg_text))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                top.addView(TextView(act).apply {
                    text = "All"
                    textSize = 15f
                    setTextColor(c(R.color.mg_primary))
                    gravity = Gravity.CENTER
                    setOnClickListener { if (picked.size == shown.size) picked.clear() else { picked.clear(); shown.forEach { picked.add(it.id) } }; render() }
                }, FrameLayout.LayoutParams(dp(64), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
                return
            }
            val all = tm.allTabs()
            val seg = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = round(c(R.color.mg_surface_variant), 24)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            seg.addView(segItem(all.count { !it.priv && groupOf(it) == null }.toString(), R.drawable.ic_list, mode == 0) { go(0) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            seg.addView(segItem(all.count { it.priv }.toString(), R.drawable.ic_lock, mode == 1) { go(1) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            seg.addView(segItem(groups.size.toString(), R.drawable.ic_folder, mode == 2) { go(2) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            top.addView(seg, FrameLayout.LayoutParams(WRAP, dp(48), Gravity.CENTER))
            top.addView(icon(R.drawable.ic_more, "Menu") { menu() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
            if (mode == 2 && openGroup != null) {
                top.addView(icon(R.drawable.ic_back, "Groups") { openGroup = null; render() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
            }
        }

        private fun drawBar() {
            bar.removeAllViews()
            if (selecting) {
                bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                if (mode == 0) bar.addView(labelBtn(R.drawable.ic_folder, "Group") { askGroup() }, LinearLayout.LayoutParams(dp(80), dp(52)))
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
                background = round(c(R.color.mg_primary), 26)
                contentDescription = "New tab"
                setOnClickListener { newTab(mode == 1) }
            }, LinearLayout.LayoutParams(dp(96), dp(52)))
            bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(icon(R.drawable.ic_trash, "Close all") { closeAll() }, LinearLayout.LayoutParams(dp(56), dp(52)))
        }

        private fun render() {
            clean()
            shown = listFor()
            picked.retainAll(shown.map { it.id }.toSet())
            drawTop()
            drawBar()
            grid.removeAllViews()
            if (mode == 2 && openGroup == null) {
                empty.text = "No groups yet.\nSelect tabs, then tap Group."
                empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
                layoutCards(groups.keys.map { groupCard(it) })
                return
            }
            empty.text = if (mode == 1) "No private tabs.\nTap + to open one." else "No tabs here."
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            layoutCards(shown.map { card(it) })
        }

        private fun layoutCards(cards: List<View>) {
            var i = 0
            while (i < cards.size) {
                val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0..1) {
                    val v = cards.getOrNull(i + k)
                    val lp = LinearLayout.LayoutParams(0, dp(176), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
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

        private fun newTab(p: Boolean) {
            val t = tm.newTab(TabManager.HOME_PAGE, 0, true, p)
            val g = openGroup
            if (t != null && !p && mode == 2 && g != null) groups[g]?.add(t.id)
            dismiss()
        }

        private fun menu() {
            val items = ArrayList<String>()
            val acts = ArrayList<() -> Unit>()
            items.add("New tab"); acts.add { newTab(false) }
            items.add("New private tab"); acts.add { newTab(true) }
            if (mode != 1 && !(mode == 2 && openGroup == null)) { items.add("Bookmark all tabs"); acts.add { TabBookmarks.dialog(act, tm) } } // S40
            if (!(mode == 2 && openGroup == null)) { items.add("Select tabs"); acts.add { startSelect(null) } }
            if (mode == 2 && openGroup != null) { items.add("Rename group"); acts.add { renameGroup() } }
            if (mode == 2 && openGroup != null) { items.add("Delete group (keep tabs)"); acts.add { openGroup?.let { g -> groups.remove(g) }; openGroup = null; render() } }
            if (!(mode == 2 && openGroup == null)) { items.add(if (mode == 1) "Close all private tabs" else "Close all tabs"); acts.add { closeAll() } }
            MaterialAlertDialogBuilder(act).setItems(items.toTypedArray()) { _, i -> acts[i]() }.show()
        }

        private fun renameGroup() {
            val old = openGroup ?: return
            val et = EditText(act).apply {
                setText(old)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            MaterialAlertDialogBuilder(act).setTitle("Rename group").setView(et)
                .setPositiveButton("Rename") { _, _ ->
                    val n = et.text.toString().trim().take(40)
                    if (n.isNotBlank() && n != old && !groups.containsKey(n)) {
                        val ids = groups.remove(old) ?: return@setPositiveButton
                        groups[n] = ids
                        openGroup = n
                        render()
                    }
                }
                .setNegativeButton("Cancel", null).show()
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

        private fun ungroup() {
            for (g in groups.values) g.removeAll(picked)
            endSelect(false)
            render()
        }

        private fun askGroup() {
            if (picked.isEmpty()) { Util.toast(act, "Tick some tabs first"); return }
            val names = groups.keys.toList()
            val items = (names + "New group...").toTypedArray()
            MaterialAlertDialogBuilder(act).setTitle("Move to group").setItems(items) { _, i ->
                if (i < names.size) addTo(names[i]) else newGroupName()
            }.show()
        }

        private fun newGroupName() {
            val et = EditText(act).apply {
                hint = "Group name"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
                setPadding(dp(20), dp(12), dp(20), dp(12))
            }
            MaterialAlertDialogBuilder(act).setTitle("New group").setView(et)
                .setPositiveButton("Create") { _, _ ->
                    val n = et.text.toString().trim().take(40).ifBlank { "Group " + (groups.size + 1) }
                    addTo(n)
                }
                .setNegativeButton("Cancel", null).show()
        }

        private fun addTo(name: String) {
            for (g in groups.values) g.removeAll(picked)
            groups.getOrPut(name) { LinkedHashSet() }.addAll(picked)
            endSelect(false)
            mode = 2
            openGroup = name
            render()
        }

        // ---- cards

        private fun card(t: TabManager.Tab): View {
            val host = if (t.home) "" else Util.host(t.url)
            val title = if (t.home) (if (t.priv) "Private home" else "Home") else t.title.ifBlank { host }.ifBlank { "New tab" }
            val isCur = t === tm.active
            val on = t.id in picked
            val box = FrameLayout(act).apply {
                background = round(c(R.color.mg_surface), 22, if (on || (isCur && !selecting)) 2 else 0, c(R.color.mg_primary))
                clipToOutline = true
                setOnClickListener {
                    if (selecting) { if (on) picked.remove(t.id) else picked.add(t.id); render() }
                    else { tm.switchTo(t); dismiss() }
                }
                setOnLongClickListener { if (!selecting) startSelect(t.id); true }
            }
            val th = thumbs[t.id]
            val previewH = dp(176 - 56)
            if (th != null && !t.home) {
                box.addView(ImageView(act).apply {
                    setImageBitmap(th)
                    scaleType = ImageView.ScaleType.MATRIX
                    imageMatrix = android.graphics.Matrix().apply {
                        val cardW = (act.resources.displayMetrics.widthPixels - dp(40)) / 2f
                        val sc = cardW / th.width
                        setScale(sc, sc)
                    }
                }, FrameLayout.LayoutParams(MATCH, previewH, Gravity.TOP))
            } else {
                val seed = if (t.home) "thazir" else host
                val hue = (abs(seed.hashCode()) % 360).toFloat()
                val letter = if (t.home) "T" else (host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?")
                box.addView(TextView(act).apply {
                    text = letter
                    gravity = Gravity.CENTER
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.72f))) }
                }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(32) })
            }
            // text strip
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(c(R.color.mg_surface))
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            col.addView(TextView(act).apply {
                text = title
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(TextView(act).apply {
                text = if (t.home) "Home" else host
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text_dim))
            })
            box.addView(col, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.BOTTOM))
            // corner button: close, or tick circle in select mode
            if (selecting) {
                box.addView(TextView(act).apply {
                    gravity = Gravity.CENTER
                    text = if (on) "✓" else ""
                    textSize = 14f
                    setTextColor(c(R.color.mg_on_primary))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (on) setColor(c(R.color.mg_primary)) else { setColor(0x66000000); setStroke(dp(2), Color.WHITE) }
                    }
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
            } else {
                box.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(Color.WHITE)
                    scaleType = ImageView.ScaleType.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x88000000.toInt()) }
                    contentDescription = "Close tab"
                    setOnClickListener { tm.close(t); render() }
                }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
            }
            return box
        }

        private fun groupCard(name: String): View {
            val ids = groups[name] ?: emptySet<Int>()
            val box = FrameLayout(act).apply {
                background = round(c(R.color.mg_surface), 22)
                setOnClickListener { openGroup = name; render() }
            }
            box.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_folder)
                setColorFilter(c(R.color.mg_primary))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(32) })
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            col.addView(TextView(act).apply {
                text = name
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(TextView(act).apply {
                text = ids.size.toString() + (if (ids.size == 1) " tab" else " tabs")
                textSize = 11f
                setTextColor(c(R.color.mg_text_dim))
            })
            box.addView(col, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.BOTTOM))
            return box
        }
    }
}
