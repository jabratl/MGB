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
    private var folder: String? = null // S26: folder open on the home screen (null = top level)
    /** S26: private tab: small "Private tab" note under the wordmark. */
    var priv = false
    /** S28: tap on the search pill (MainActivity focuses the address bar). */
    var onSearch: (() -> Unit)? = null
    private var animateNext = false

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

    /** S26: back to the top level (called when the home tab is shown). */
    fun showTop() { folder = null; editing = false; pencil.setImageResource(R.drawable.ic_edit); animateNext = true }

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

        val f0 = folder
        if (f0 != null && !Bookmarks.folders().contains(f0)) folder = null
        val cur = folder
        if (cur == null) {
            content.addView(TextView(context).apply {
                text = "Thazir"
                setTextColor(context.getColor(R.color.mg_text))
                textSize = 34f
                letterSpacing = 0.32f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, (this@HomeView.height * 0.10f).toInt(), 0, if (priv) dp(8) else dp(28))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            if (priv) content.addView(TextView(context).apply {
                text = "Private tab"
                setTextColor(context.getColor(R.color.mg_primary))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(20))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            content.addView(searchPill(), LinearLayout.LayoutParams(MATCH, dp(48)).apply {
                marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(32)
            })
        } else {
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(24), 0, dp(24))
            }
            head.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(context.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { folder = null; rebuild() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            head.addView(TextView(context).apply {
                text = cur
                setTextColor(context.getColor(R.color.mg_text))
                textSize = 20f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(48) })
            content.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val cells = ArrayList<View>()
        if (cur == null) { // S28: only what the user pinned (not every bookmark)
            for (h in Bookmarks.home()) {
                if (h.folder.isNotEmpty()) { if (Bookmarks.folders().contains(h.folder)) cells.add(folderTile(h.folder)) }
                else cells.add(tile(h.title, h.url))
            }
        } else {
            for (b in Bookmarks.bookmarks()) if (b.folder == cur) cells.add(tile(b.title, b.url))
        }
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
        if (animateNext) { animateNext = false; fadeIn() }
    }

    private fun fadeIn() {
        content.alpha = 0f
        content.translationY = dp(10).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    /** S28: search pill under the wordmark. Tap = focus the address bar. */
    private fun searchPill(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        background = squircle(context.getColor(R.color.mg_surface), context.getColor(R.color.mg_outline), false).apply { cornerRadius = dp(24).toFloat() }
        isClickable = true
        isFocusable = true
        setOnClickListener { onSearch?.invoke() }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(context.getColor(R.color.mg_text_dim))
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = "Search or type address"
            textSize = 15f
            setTextColor(context.getColor(R.color.mg_text_dim))
        })
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
        setOnTouchListener { v, e -> // S28: soft press
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
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
        if (editing && folder == null) addBadge(box) { Bookmarks.removeHome(url); refresh() } // S28: unpin only
        val c = cell(box, name)
        box.layoutParams = LinearLayout.LayoutParams(dp(64), dp(60))
        c.setOnClickListener { if (!editing) onOpen(url) }
        c.setOnLongClickListener {
            val labels = ArrayList<String>()
            val acts = ArrayList<() -> Unit>()
            labels.add("Open in new tab"); acts.add { onOpenNew(url) }
            if (folder == null) {
                labels.add("Remove from home"); acts.add { Bookmarks.removeHome(url); refresh() }
            } else {
                labels.add("Move out of folder"); acts.add { Bookmarks.setFolder(url, ""); refresh() }
                labels.add("Remove bookmark"); acts.add { Bookmarks.removeBookmark(url); refresh() }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(name)
                .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
                .show()
            true
        }
        return c
    }

    private fun addBadge(box: FrameLayout, onTap: () -> Unit) {
        box.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(context.getColor(R.color.mg_on_primary))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.mg_primary)) }
            setOnClickListener { onTap() }
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
    }

    /** S26: folder tile (opens the folder here). Edit mode: badge deletes the folder, its bookmarks go back to the top level. */
    private fun folderTile(name: String): View {
        val box = FrameLayout(context)
        val inner = FrameLayout(context).apply {
            background = squircle(context.getColor(R.color.mg_surface), context.getColor(R.color.mg_outline), false)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_folder)
                setColorFilter(context.getColor(R.color.mg_text_dim))
            }, LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        box.addView(inner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        if (editing) addBadge(box) { Bookmarks.removeHomeFolder(name); refresh() } // S28: unpin only
        val c = cell(box, name)
        box.layoutParams = LinearLayout.LayoutParams(dp(64), dp(60))
        c.setOnClickListener { if (!editing) { folder = name; rebuild() } }
        return c
    }

    private fun addTile(): View {
        val b = addBox()
        val c = cell(b, "Add")
        b.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        c.setOnClickListener { if (folder == null) chooseAdd() else askAdd() }
        return c
    }

    /** S28: top level: pick from bookmarks (many at once) or type a new address. */
    private fun chooseAdd() {
        MaterialAlertDialogBuilder(context)
            .setTitle("Add to home")
            .setItems(arrayOf("From bookmarks", "New address")) { _, which -> if (which == 0) pickFromBookmarks() else askAdd() }
            .show()
    }

    private fun pickFromBookmarks() {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        for (f in Bookmarks.folders()) if (Bookmarks.home().none { it.folder == f }) {
            labels.add("Folder: $f"); acts.add { Bookmarks.addHomeFolder(f) }
        }
        for (b in Bookmarks.bookmarks()) if (!Bookmarks.isHome(b.url)) {
            labels.add(b.title.ifBlank { Util.host(b.url) }); acts.add { Bookmarks.addHome(b.title, b.url) }
        }
        if (labels.isEmpty()) { Util.toast(context, "Nothing to add"); return }
        val checked = BooleanArray(labels.size)
        MaterialAlertDialogBuilder(context)
            .setTitle("Add to home")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Add") { _, _ ->
                for (i in labels.indices) if (checked[i]) acts[i]()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                    val fo = folder
                    if (fo != null) Bookmarks.setFolder(u, fo) // S26: add inside the open folder
                    else Bookmarks.addHome(name.text.toString().trim(), u) // S28: pin to home
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
