package com.jabra.mgb

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S40: "Bookmark all tabs". One dialog: how many tabs, a folder name (default "Tabs 02 Nov 17:05"), Save.
 * Only normal web tabs (http / https) count: not private tabs, not the speed dial, not extension pages.
 * A page that is already bookmarked is skipped (Bookmarks.addBookmark ignores duplicates).
 */
object TabBookmarks {
    fun list(tm: TabManager): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (t in tm.allTabs()) {
            if (t.priv || t.isExt || t.home) continue
            val u = (t.pending ?: t.url)
            if (!(u.startsWith("http://") || u.startsWith("https://")) || !seen.add(u)) continue
            out.add((t.title.ifBlank { Util.host(u) }) to u)
        }
        return out
    }

    fun dialog(ctx: Context, tm: TabManager) {
        val items = list(tm)
        if (items.isEmpty()) { Util.toast(ctx, "No web tabs to bookmark"); return }
        val fresh = items.count { !Bookmarks.isBookmarked(it.second) }
        val et = EditText(ctx).apply {
            setText("Tabs " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date()))
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val pad = Util.dp(ctx, 20)
        val box = FrameLayout(ctx).apply { setPadding(pad, Util.dp(ctx, 8), pad, 0); addView(et) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Bookmark all tabs")
            .setMessage(
                if (fresh == 0) "All ${items.size} tabs are already in your bookmarks."
                else "$fresh new ${if (fresh == 1) "bookmark" else "bookmarks"} (${items.size - fresh} already saved are skipped). Folder name:"
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = save(items, et.text.toString())
                Util.toast(ctx, if (n == 0) "Nothing new to save" else "$n bookmarks saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Returns how many bookmarks were added. An empty new folder is removed again. */
    fun save(items: List<Pair<String, String>>, folderName: String): Int {
        val existed = Bookmarks.folders().any { it.equals(folderName.trim(), true) }
        val f = Bookmarks.addFolder(folderName)
        var n = 0
        for ((t, u) in items) {
            if (Bookmarks.isBookmarked(u)) continue
            Bookmarks.addBookmark(t, u)
            if (f.isNotEmpty()) Bookmarks.setFolder(u, f)
            n++
        }
        if (n == 0 && f.isNotEmpty() && !existed) Bookmarks.removeFolder(f)
        return n
    }
}
