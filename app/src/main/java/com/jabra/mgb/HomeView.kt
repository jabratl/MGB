package com.jabra.mgb

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S13: speed-dial home shown over the (blank) WebView of a "home" tab (Tab.home).
 * Tiles = bookmarks. Tap = open here, long press = open in new tab / remove, "+" = add a site.
 * Built in code (no layout xml), colours from the mg_* tokens so dark and light both follow the theme.
 */
class HomeView(
    ctx: Context,
    private val onOpen: (String) -> Unit,
    private val onOpenNew: (String) -> Unit,
    private val onBookmarks: () -> Unit,
    private val onHistory: () -> Unit
) : FrameLayout(ctx) {

    private val content = LinearLayout(ctx)
    private var cols = 0

    private companion object {
        val PALETTE = arrayOf("#4C7DF0", "#1FA67A", "#E0613F", "#9B59D6", "#C98A1B", "#2AA3B8", "#D64F7E")
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun dp(v: Int) = Util.dp(context, v)

    init {
        setBackgroundColor(context.getColor(R.color.mg_bg))
        isClickable = true // swallow touches: the blank WebView below must not get them
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16), dp(24), dp(16), dp(24))
        val sv = ScrollView(context)
        sv.isFillViewport = true
        sv.addView(content, LayoutParams(MATCH, WRAP))
        addView(sv, LayoutParams(MATCH, MATCH))
    }

    /** Rebuild the tiles from the current bookmarks (width 0 = not laid out yet, onSizeChanged does it). */
    fun refresh() = rebuild()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val c = (w / dp(84)).coerceIn(4, 8) // S22
        if (c != cols) rebuild()
    }

    private fun rebuild() {
        val w = width
        if (w <= 0) return
        cols = (w / dp(84)).coerceIn(4, 8) // S22
        content.removeAllViews()

        // S22: wordmark instead of a heading, pushed down to about 1/5 of the height
        content.addView(TextView(context).apply {
            text = "MGB"
            setTextColor(context.getColor(R.color.mg_text))
            textSize = 46f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (this@HomeView.height * 0.14f).toInt(), 0, dp(56))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val cells = ArrayList<View>()
        for (b in Bookmarks.bookmarks()) cells.add(tile(b.title, b.url))
        cells.add(addTile())
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            for (n in 0 until cols) {
                val v = if (i + n < cells.size) cells[i + n] else View(context)
                row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            content.addView(row)
            i += cols
        }
    }

    private fun circle(color: Int, letter: String, letterColor: Int): TextView = TextView(context).apply {
        text = letter
        gravity = Gravity.CENTER
        setTextColor(letterColor)
        textSize = 24f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        // S22: rounded square tile (neutral fill), the colour goes to the letter
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat(); setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
    }

    private fun cell(icon: View, label: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        isClickable = true
        isFocusable = true
        addView(icon)
        addView(TextView(context).apply {
            text = label
            setTextColor(context.getColor(R.color.mg_text))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun tile(title: String, url: String): View {
        val name = title.ifBlank { Util.host(url) }
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val color = Color.parseColor(PALETTE[(Util.host(url).hashCode() and 0x7fffffff) % PALETTE.size])
        val c = cell(circle(context.getColor(R.color.mg_surface_variant), letter, color), name)
        c.setOnClickListener { onOpen(url) }
        c.setOnLongClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(name)
                .setItems(arrayOf("Open in new tab", "Remove")) { _, which ->
                    if (which == 0) onOpenNew(url) else {
                        Bookmarks.removeBookmark(url)
                        refresh()
                    }
                }
                .show()
            true
        }
        return c
    }

    private fun addTile(): View {
        val c = cell(circle(context.getColor(R.color.mg_surface_variant), "+", context.getColor(R.color.mg_text_dim)), "Add")
        c.setOnClickListener { askAdd() }
        return c
    }

    private fun askAdd() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val name = EditText(context).apply { hint = "Name"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
        val url = EditText(context).apply {
            hint = "Address (example.com)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(url, LinearLayout.LayoutParams(MATCH, WRAP))
        MaterialAlertDialogBuilder(context)
            .setTitle("Add to speed dial")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val u = Util.toUrlOrSearch(url.text.toString())
                if (!u.startsWith("http", true)) Util.toast(context, "Enter a web address")
                else {
                    Bookmarks.addBookmark(name.text.toString().trim(), u)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
