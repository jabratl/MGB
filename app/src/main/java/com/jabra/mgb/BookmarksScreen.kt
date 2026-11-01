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

    fun open(host: Activity, mode: Mode, tabs: TabManager) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
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
        override fun onBackPressed() { dismiss() }

        private fun header(): View {
            val h = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            h.addView(TextView(act).apply {
                text = "‹"
                gravity = Gravity.CENTER
                textSize = 30f
                setTextColor(act.getColor(R.color.mg_text))
                contentDescription = "Back"
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
            // S23: segmented pills Bookmarks | History | Downloads
            val seg = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                background = round(act.getColor(R.color.mg_surface_variant), 22)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            for (label in arrayOf("Bookmarks", "History", "Downloads")) {
                val on = (label == "Bookmarks" && mode == Mode.BOOKMARKS) || (label == "History" && mode == Mode.HISTORY)
                seg.addView(TextView(act).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 13f
                    maxLines = 1
                    setTextColor(act.getColor(if (on) R.color.mg_on_primary else R.color.mg_text))
                    if (on) background = round(act.getColor(R.color.mg_primary), 19)
                    setOnClickListener {
                        when (label) {
                            "Downloads" -> { dismiss(); DownloadsScreen.open(act) }
                            else -> {
                                val m = if (label == "Bookmarks") Mode.BOOKMARKS else Mode.HISTORY
                                if (m != mode) { mode = m; query = ""; build() }
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f))
            }
            h.addView(seg, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            // 3-dot: Import / Export (bookmarks, S18) or Clear (history)
            h.addView(TextView(act).apply {
                text = "⋮"
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(act.getColor(R.color.mg_text))
                contentDescription = "More"
                setOnClickListener {
                    val labels = if (mode == Mode.BOOKMARKS) arrayOf("Import bookmarks", "Export bookmarks") else arrayOf("Clear history")
                    MaterialAlertDialogBuilder(act).setItems(labels) { _, which ->
                        if (mode == Mode.BOOKMARKS) {
                            if (which == 0) BrowserExtras.importBookmarks { render() } else BrowserExtras.exportBookmarks()
                        } else askClear()
                    }.show()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
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
            val all = if (mode == Mode.BOOKMARKS) Bookmarks.bookmarks() else Bookmarks.history().take(300)
            val q = query.trim()
            val items = if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.url.contains(q, true) } // S23
            box.removeAllViews()
            empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            for (it in items) {
                val sub = if (mode == Mode.HISTORY) Util.host(it.url) + " · " + fmt.format(java.util.Date(it.time)) else Util.host(it.url)
                box.addView(row(it, sub))
            }
            if (items.isNotEmpty()) box.addView(TextView(act).apply {
                text = "Long press an item for more"
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(24))
            })
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
                    .setItems(arrayOf("Open in new tab", "Remove")) { _, which ->
                        if (which == 0) {
                            tabs.newTab(b.url)
                            dismiss()
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
