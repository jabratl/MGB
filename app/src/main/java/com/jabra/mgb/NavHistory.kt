package com.jabra.mgb

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A6: long-press on the bar Back / Forward arrow = list of the pages behind (or ahead of) the current one; tap one to jump there.
 * Uses WebView's own back-forward list (goBackOrForward), so nothing is stored. The Home page counts only as the plain Back tap, not as a list row.
 */
object NavHistory {
    private const val MAX = 12

    /** Returns true when a list was shown (long-press consumed); false = nothing to list, the normal tooltip stays. */
    fun show(act: Activity, tabs: TabManager, forward: Boolean): Boolean {
        val t = tabs.active ?: return false
        if (t.home || t.isExt) return false
        val h = try { t.web.copyBackForwardList() } catch (_: Exception) { return false }
        val cur = h.currentIndex
        val idx = (if (forward) (cur + 1 until h.size) else (cur - 1 downTo 0)).take(MAX).toList()
        if (idx.isEmpty()) return false
        val labels = idx.map { i ->
            val e = h.getItemAtIndex(i)
            val host = Util.host(e.url ?: "")
            val title = e.title?.trim().orEmpty()
            if (title.isEmpty() || title == host) host else "$title  \u00B7  $host"
        }.toTypedArray()
        MaterialAlertDialogBuilder(act)
            .setTitle(if (forward) "Forward" else "Back")
            .setItems(labels) { _, which -> try { t.web.goBackOrForward(idx[which] - cur) } catch (_: Exception) { } }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }
}
