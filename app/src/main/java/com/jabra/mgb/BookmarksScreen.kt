package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S13: full-screen list of bookmarks or browsing history (Dialog, no manifest entry, like DownloadsScreen).
 *  - tap = open in the current tab, long press = open in new tab / remove
 *  - history: "Clear" in the title bar (asks first)
 * Opening goes through TabManager.openUrl / newTab, so it works from any tab.
 */
object BookmarksScreen {
    private const val TAG = "MGB.bmscreen"
    private var current: Screen? = null

    enum class Mode { BOOKMARKS, HISTORY }

    /** S25: set by MainActivity so DownloadsScreen can jump back here. Cleared in onDestroy. */
    var tabsRef: TabManager? = null

    /** S25: the 3 top pills. selected: 0 Bookmarks, 1 History, 2 Downloads. pick(i) is called on tap. */
    fun pills(act: Activity, selected: Int, pick: (Int) -> Unit): View {
        fun dp(v: Int) = Util.dp(act, v)
        fun round(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }
        val seg = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(act.getColor(R.color.mg_surface_variant), 22)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val labels = arrayOf("Bookmarks", "History", "Downloads")
        for (i in labels.indices) {
            val on = i == selected
            seg.addView(TextView(act).apply {
                text = labels[i]
                gravity = Gravity.CENTER
                textSize = 13f
                maxLines = 1
                setTextColor(act.getColor(if (on) R.color.mg_on_primary else R.color.mg_text))
                if (on) background = round(act.getColor(R.color.mg_primary), 19)
                setOnClickListener { pick(i) }
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        return seg
    }

    fun open(host: Activity, mode: Mode, tabs: TabManager) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            tabsRef = tabs // S25
            val s = Screen(host, mode, tabs)
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

    private class Screen(
        private val act: Activity,
        private var mode: Mode, // S23: switchable from the top pills
        private val tabs: TabManager
    ) : Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private var list: LinearLayout? = null
        private var empty: TextView? = null
        private var query = "" // S23: search text
        private var folder: String? = null // S24: open folder (null = top level)
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener { if (current === this) current = null }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        /** S23: (re)build the whole screen: top pills + search + list. Called at start and when the mode changes. */
        private fun build() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(act.getColor(R.color.mg_bg))
            }
            root.addView(header(), LinearLayout.LayoutParams(MATCH, dp(56)))
            root.addView(searchBox(), LinearLayout.LayoutParams(MATCH, dp(44)).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(4); bottomMargin = dp(8)
            })

            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val emptyView = TextView(act).apply {
                text = if (mode == Mode.BOOKMARKS) "No bookmarks yet" else "No history yet"
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(48), dp(16), dp(16))
                visibility = View.GONE
            }
            box.addView(emptyView, LinearLayout.LayoutParams(MATCH, WRAP))
            val items = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            box.addView(items, LinearLayout.LayoutParams(MATCH, WRAP))
            val sv = ScrollView(act)
            sv.addView(box, ViewGroup.LayoutParams(MATCH, WRAP))
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

            list = items
            empty = emptyView
            setContentView(root)
            render()
        }

        private fun round(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

        private fun searchBox(): View = android.widget.EditText(act).apply {
            hint = if (mode == Mode.BOOKMARKS) "Search bookmarks" else "Search history"
            setText(query)
            setSingleLine()
            textSize = 15f
            setTextColor(act.getColor(R.color.mg_text))
            setHintTextColor(act.getColor(R.color.mg_text_dim))
            background = round(act.getColor(R.color.mg_surface_variant), 22)
            setPadding(dp(16), 0, dp(16), 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString().orEmpty(); render() }
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { if (folder != null) { folder = null; render() } else dismiss() } // S24

        private fun header(): View {
            val h = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            h.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { if (folder != null) { folder = null; render() } else dismiss() } // S24
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            // S23/S25: segmented pills Bookmarks | History | Downloads (shared with DownloadsScreen)
            val seg = pills(act, if (mode == Mode.BOOKMARKS) 0 else 1) { i ->
                if (i == 2) { dismiss(); DownloadsScreen.open(act) }
                else {
                    val m = if (i == 0) Mode.BOOKMARKS else Mode.HISTORY
                    if (m != mode) { mode = m; query = ""; folder = null; build() }
                }
            }
            h.addView(seg, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            // 3-dot: Import / Export (bookmarks, S18) or Clear (history)
            h.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_more)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "More"
                setOnClickListener {
                    val labels = if (mode == Mode.BOOKMARKS) arrayOf("New folder", "Import bookmarks", "Export bookmarks") else arrayOf("Clear history")
                    MaterialAlertDialogBuilder(act).setItems(labels) { _, which ->
                        if (mode == Mode.BOOKMARKS) {
                            when (which) {
                                0 -> askNewFolder { render() }
                                1 -> BrowserExtras.importBookmarks { render() }
                                else -> BrowserExtras.exportBookmarks()
                            }
                        } else askClear()
                    }.show()
                }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            return h
        }

        private fun askClear() {
            MaterialAlertDialogBuilder(act)
                .setMessage("Clear all browsing history?")
                .setPositiveButton("Clear") { _, _ -> Bookmarks.clearHistory(); render() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun render() {
            val box = list ?: return
            box.removeAllViews()
            val q = query.trim()
            var count = 0
            if (mode == Mode.BOOKMARKS) {
                val all = Bookmarks.bookmarks()
                val open = folder
                if (open != null && q.isEmpty()) { // S24: folder title
                    box.addView(TextView(act).apply {
                        text = open
                        setTextColor(act.getColor(R.color.mg_text))
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(dp(16), dp(4), dp(16), dp(8))
                    })
                }
                if (open == null && q.isEmpty()) {
                    for (f in Bookmarks.folders()) {
                        box.addView(folderRow(f, all.count { it.folder == f }))
                        count++
                    }
                }
                val items = when {
                    q.isNotEmpty() -> all.filter { it.title.contains(q, true) || it.url.contains(q, true) }
                    open == null -> all.filter { it.folder.isEmpty() }
                    else -> all.filter { it.folder == open }
                }
                for (b in items) box.addView(row(b, Util.host(b.url)))
                count += items.size
            } else {
                val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                val all = Bookmarks.history().take(300)
                val items = if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.url.contains(q, true) } // S23
                for (b in items) box.addView(row(b, Util.host(b.url) + " · " + fmt.format(java.util.Date(b.time))))
                count = items.size
            }
            empty?.visibility = if (count == 0) View.VISIBLE else View.GONE
            if (count > 0) box.addView(TextView(act).apply {
                text = "Long press an item for more"
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(24))
            })
        }

        /** S24: one folder line (icon, name, count). Tap = open, long press = delete folder. */
        private fun folderRow(name: String, n: Int): View {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                val out = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    foreground = act.getDrawable(out.resourceId)
                }
            }
            r.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_folder)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            r.addView(TextView(act).apply {
                text = name
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(TextView(act).apply {
                text = n.toString()
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 13f
            })
            r.setOnClickListener { folder = name; render() }
            r.setOnLongClickListener {
                MaterialAlertDialogBuilder(act)
                    .setTitle(name)
                    .setItems(arrayOf("Delete folder (bookmarks are kept)")) { _, _ ->
                        Bookmarks.removeFolder(name)
                        render()
                    }
                    .show()
                true
            }
            return r
        }

        /** S24: ask for a folder name, create it, then done(name). */
        private fun askNewFolder(done: (String) -> Unit) {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            val et = EditText(act).apply { hint = "Folder name"; setSingleLine() }
            box.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
            MaterialAlertDialogBuilder(act)
                .setTitle("New folder")
                .setView(box)
                .setPositiveButton("Create") { _, _ ->
                    val n = Bookmarks.addFolder(et.text.toString())
                    if (n.isNotEmpty()) done(n)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun askMove(b: Bookmarks.Item) {
            val names = ArrayList<String>()
            names.add("No folder")
            names.addAll(Bookmarks.folders())
            names.add("New folder...")
            MaterialAlertDialogBuilder(act)
                .setTitle("Move to")
                .setItems(names.toTypedArray()) { _, i ->
                    if (i == names.size - 1) askNewFolder { n -> Bookmarks.setFolder(b.url, n); render() }
                    else { Bookmarks.setFolder(b.url, if (i == 0) "" else names[i]); render() }
                }
                .show()
        }

        private fun row(b: Bookmarks.Item, sub: String): View {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(60)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                val out = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    foreground = act.getDrawable(out.resourceId)
                }
            }
            r.addView(TextView(act).apply {
                text = b.title.ifBlank { Util.host(b.url) }
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            r.addView(TextView(act).apply {
                text = sub
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            r.setOnClickListener { tabs.openUrl(b.url); dismiss() }
            r.setOnLongClickListener {
                MaterialAlertDialogBuilder(act)
                    .setTitle(b.title.ifBlank { Util.host(b.url) })
                    .setItems(if (mode == Mode.BOOKMARKS) arrayOf("Open in new tab", "Remove", "Move to folder") else arrayOf("Open in new tab", "Remove")) { _, which ->
                        if (which == 0) {
                            tabs.newTab(b.url)
                            dismiss()
                        } else if (which == 2) {
                            askMove(b)
                        } else {
                            if (mode == Mode.BOOKMARKS) Bookmarks.removeBookmark(b.url) else Bookmarks.removeHistory(b.time, b.url)
                            render()
                        }
                    }
                    .show()
                true
            }
            return r
        }
    }
}
