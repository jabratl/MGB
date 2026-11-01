package com.jabra.mgb

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
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

/**
 * S13: speed-dial home shown over the (blank) WebView of a "home" tab (Tab.home).
 * Tiles = bookmarks. Tap = open here, long press = open in new tab / remove, add tile = add a site.
 * S22: wordmark + 4 column tiles. S24: quiet monochrome tiles (letter, no colours), vector icons,
 * edit mode (pencil, top right): tiles get a remove badge.
 * Built in code (no layout xml), colours from the mg_* tokens so dark and light both follow the theme.
 */
class HomeView(
    ctx: Context,
    private val onOpen: (String) -> Unit,
    private val onOpenNew: (String) -> Unit,
    private val onBookmarks: () -> Unit, // kept for TabManager; the menu has these now
    private val onHistory: () -> Unit
) : FrameLayout(ctx) {

    private val content = LinearLayout(ctx)
    private val pencil = ImageView(ctx)
    private var cols = 0
    private var editing = false

    private companion object {
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

        pencil.setImageResource(R.drawable.ic_edit)
        pencil.setPadding(dp(10), dp(10), dp(10), dp(10))
        pencil.setColorFilter(context.getColor(R.color.mg_text_dim))
        pencil.contentDescription = "Edit"
        pencil.setOnClickListener {
            editing = !editing
            pencil.setImageResource(if (editing) R.drawable.ic_check else R.drawable.ic_edit)
            rebuild()
        }
        addView(pencil, LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { topMargin = dp(8); marginEnd = dp(8) })
    }

    /** Rebuild the tiles from the current bookmarks (width 0 = not laid out yet, onSizeChanged does it). */
    fun refresh() = rebuild()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val c = (w / dp(84)).coerceIn(4, 8)
        if (c != cols) rebuild()
    }

    private fun rebuild() {
        val w = width
        if (w <= 0) return
        cols = (w / dp(84)).coerceIn(4, 8)
        content.removeAllViews()

        content.addView(TextView(context).apply {
            text = "MGB"
            setTextColor(context.getColor(R.color.mg_text))
            textSize = 34f
            letterSpacing = 0.32f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, (this@HomeView.height * 0.14f).toInt(), 0, dp(52))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val cells = ArrayList<View>()
        for (b in Bookmarks.bookmarks()) cells.add(tile(b.title, b.url))
        if (!editing) cells.add(addTile())
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

    private fun squircle(fill: Int, stroke: Int, dashed: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(fill)
        if (dashed) setStroke(dp(1), stroke, dp(4).toFloat(), dp(3).toFloat()) else setStroke(dp(1), stroke)
    }

    private fun letterBox(letter: String): View = TextView(context).apply {
        text = letter
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.mg_text))
        textSize = 22f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = squircle(context.getColor(R.color.mg_surface), context.getColor(R.color.mg_outline), false)
    }

    private fun addBox(): View = FrameLayout(context).apply {
        background = squircle(0, context.getColor(R.color.mg_outline), true)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(context.getColor(R.color.mg_text_dim))
        }, LayoutParams(dp(22), dp(22), Gravity.CENTER))
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
            setTextColor(context.getColor(R.color.mg_text_dim))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun tile(title: String, url: String): View {
        val name = title.ifBlank { Util.host(url) }
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val box = FrameLayout(context)
        box.addView(letterBox(letter), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        if (editing) { // S24: remove badge
            box.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(context.getColor(R.color.mg_on_primary))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.mg_primary)) }
                setOnClickListener {
                    Bookmarks.removeBookmark(url)
                    refresh()
                }
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        }
        val c = cell(box, name)
        box.layoutParams = LinearLayout.LayoutParams(dp(64), dp(60))
        c.setOnClickListener { if (!editing) onOpen(url) }
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
        val b = addBox()
        val c = cell(b, "Add")
        b.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
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
