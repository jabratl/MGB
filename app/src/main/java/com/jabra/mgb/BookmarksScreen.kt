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
        private val mode: Mode,
        private val tabs: TabManager
    ) : Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private var list: LinearLayout? = null
        private var empty: TextView? = null
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener { if (current === this) current = null }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(act.getColor(R.color.mg_bg))
            }
            root.addView(header(), LinearLayout.LayoutParams(MATCH, dp(52)))
            root.addView(View(act).apply { setBackgroundColor(act.getColor(R.color.mg_outline)) },
                LinearLayout.LayoutParams(MATCH, dp(1)))

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
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            render()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { dismiss() }

        private fun header(): View {
            val h = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(8), 0)
            }
            h.addView(TextView(act).apply {
                text = "‹"
                gravity = Gravity.CENTER
                textSize = 30f
                setTextColor(act.getColor(R.color.mg_text))
                contentDescription = "Back"
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
            h.addView(TextView(act).apply {
                text = if (mode == Mode.BOOKMARKS) "Bookmarks" else "History"
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (mode == Mode.BOOKMARKS) { // S18
                for (label in arrayOf("Import", "Export")) {
                    h.addView(TextView(act).apply {
                        text = label
                        gravity = Gravity.CENTER
                        textSize = 15f
                        setTextColor(act.getColor(R.color.mg_primary))
                        setPadding(dp(10), 0, dp(10), 0)
                        setOnClickListener {
                            if (label == "Import") BrowserExtras.importBookmarks { render() } else BrowserExtras.exportBookmarks()
                        }
                    }, LinearLayout.LayoutParams(WRAP, dp(48)))
                }
            }
            if (mode == Mode.HISTORY) {
                h.addView(TextView(act).apply {
                    text = "Clear"
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTextColor(act.getColor(R.color.mg_primary))
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener { askClear() }
                }, LinearLayout.LayoutParams(WRAP, dp(48)))
            }
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
            val items = if (mode == Mode.BOOKMARKS) Bookmarks.bookmarks() else Bookmarks.history().take(300)
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
