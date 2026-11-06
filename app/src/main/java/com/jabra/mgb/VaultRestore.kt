package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * V10: "Restore into the browser" page + the uninstall shortcut.
 *  - Offered after the vault was opened from an existing folder (fresh install / reinstall) and from the vault home.
 *  - Per category (only those that have items): Bookmarks and Saved pages go back into the browser as COPIES (the vault keeps them),
 *    Files are exported as readable copies to Downloads/Thazir (default off: can be big). History stays in the vault:
 *    the browser history list cannot take back entries with their original dates.
 *  - Nothing is ever removed from the vault here, and items that are already in the browser are skipped (running it twice is safe).
 *  - uninstall(a): opens Android's own uninstall confirmation (needs REQUEST_DELETE_PACKAGES on API 28+). The vault folder stays on the phone.
 * Reuses VaultItems.restoreBookmarks, VaultFiles.restorePages / exportFiles. Dialog is rebuilt from object state, so rotation is safe.
 */
object VaultRestore {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val main = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null
    private var host: FrameLayout? = null
    private var act: Activity? = null
    private var onClose: (() -> Unit)? = null
    @Volatile private var running = false
    private var bm = true
    private var pg = true
    private var fl = false

    private fun dp(a: android.content.Context, v: Int) = Util.dp(a, v)
    private fun lp(top: Int = 0, w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h).apply { topMargin = top }

    // ---------- uninstall shortcut ----------

    /** Asks once, then opens Android's uninstall screen. The vault folder is not touched. */
    fun uninstall(a: Activity) {
        if (a.isFinishing || a.isDestroyed) return
        MaterialAlertDialogBuilder(a).setTitle("Uninstall Thazir?")
            .setMessage("Android will ask you to confirm. Your vault folder (" + VaultScreen.folderLabel(a) + ") stays on the phone. To use it again, install Thazir and choose \"I already have a vault\".")
            .setPositiveButton("Continue") { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.packageName)))
                } catch (e: Exception) {
                    Util.toast(a, "Could not open it. Use Android Settings > Apps > Thazir > Uninstall.")
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- the work (BLOCKING, background thread) ----------

    private fun ids(type: String, skip: (org.json.JSONObject) -> Boolean = { false }): List<String> =
        VaultStore.items(type).filter { !skip(it) }.map { it.optString("id") }.filter { it.isNotEmpty() }

    private fun doRestore(a: Activity, doBm: Boolean, doPg: Boolean, doFl: Boolean, say: (String) -> Unit): List<String> {
        val app = a.applicationContext
        if (!VaultStore.isUnlocked()) return listOf("The vault is locked. Nothing was changed.")
        val out = ArrayList<String>()
        if (doBm) {
            say("Restoring bookmarks...")
            val all = VaultStore.count("bm")
            val r = VaultItems.restoreBookmarks(app, ids("bm"), true)
            out.add(if (all == 0) "Bookmarks: none in the vault." else if (r.ok) "Bookmarks: $all checked, ${r.added} are in the browser now." else "Bookmarks: " + r.msg)
        }
        if (doPg) {
            say("Restoring saved pages...")
            val all = VaultStore.count("page")
            val todo = ids("page")
            val r = if (todo.isEmpty()) null else VaultFiles.restorePages(app, todo, true)
            out.add(when {
                all == 0 -> "Saved pages: none in the vault."
                r == null -> "Saved pages: nothing to restore."
                r.ok -> "Saved pages: ${r.added} of $all restored." + (if (r.msg.isNotEmpty()) " " + r.msg else "")
                else -> "Saved pages: " + r.msg
            })
        }
        if (doFl) {
            say("Exporting files...")
            val all = ids("file")
            val r = if (all.isEmpty()) null else VaultFiles.exportFiles(app, all, true)
            out.add(when {
                r == null -> "Files: none in the vault."
                r.ok -> "Files: ${r.added} of ${all.size} copied to Downloads/Thazir." + (if (r.msg.isNotEmpty()) " " + r.msg else "")
                else -> "Files: " + r.msg
            })
        }
        if (VaultStore.count("hist") > 0) out.add("History: stays in the vault (the browser cannot take back dated entries).")
        return out
    }

    // ---------- UI ----------

    /** Opens the page. closed runs when it goes away (VaultScreen refreshes its tile counts). */
    fun open(a: Activity, closed: () -> Unit) {
        if (a.isFinishing || a.isDestroyed || dlg?.isShowing == true) return
        if (!VaultStore.isUnlocked()) { Util.toast(a, "Open the vault first"); return }
        act = a; onClose = closed
        bm = true; pg = true; fl = false; running = false
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val h = FrameLayout(a)
        h.setBackgroundColor(a.getColor(R.color.mg_bg))
        host = h
        d.setContentView(h, ViewGroup.LayoutParams(MATCH, MATCH))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(a.getColor(R.color.mg_bg)))
            if (VaultStore.hideRecents(a)) setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.setOnKeyListener { _, k, e ->
            if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { if (!running) dlg?.dismiss(); true } else false
        }
        d.setOnDismissListener { if (dlg === d) { dlg = null; host = null; val f = onClose; onClose = null; f?.invoke() } }
        dlg = d
        try { d.show() } catch (e: Exception) { dlg = null; return }
        choosePage()
    }

    /** VaultScreen.refresh: a locked vault must not leave the page open (a running copy ends itself). */
    fun refresh() { if (dlg?.isShowing == true && !running && !VaultStore.isUnlocked()) dlg?.dismiss() }

    private fun page(a: Activity, build: (LinearLayout) -> Unit) {
        val h = host ?: return
        h.removeAllViews()
        val sv = ScrollView(a).apply { isFillViewport = true }
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 28), dp(a, 20), dp(a, 28)) }
        c.addView(android.view.View(a).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(a.getColor(R.color.mg_primary)); cornerRadius = dp(a, 2).toFloat() }
        }, lp(0, dp(a, 18), dp(a, 3)))
        build(c)
        sv.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))
        val w = minOf(a.resources.displayMetrics.widthPixels, dp(a, 560))
        h.addView(sv, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL))
    }

    private fun n(t: String, c: Int) = if (c == 1) "1 item" else "$c items"

    private fun choosePage() {
        val a = act ?: return
        page(a) { c ->
            c.addView(VaultScreen.tv(a, "Restore into the browser", 24f, R.color.mg_text, true), lp(dp(a, 10)))
            c.addView(VaultScreen.tv(a, "Your vault in " + VaultScreen.folderLabel(a) + " is open. You can copy things back into this browser now. The vault keeps everything; nothing is removed.", 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 8)))
            val nb = VaultStore.count("bm"); val np = VaultStore.count("page"); val nf = VaultStore.count("file"); val nh = VaultStore.count("hist")
            if (nb + np + nf == 0) {
                c.addView(VaultScreen.tv(a, if (nh > 0) "Only history is in the vault. It stays there; open it from the vault home." else "The vault is empty. There is nothing to restore.", 14f, R.color.mg_text, false), lp(dp(a, 20)))
            } else {
                c.addView(VaultScreen.tv(a, "COPY BACK", 11f, R.color.mg_primary).apply { typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }, lp(dp(a, 20)))
                val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = VaultScreen.box(a) }
                if (nb > 0) card.addView(VaultScreen.switchRow(a, "Bookmarks", n("bm", nb) + ", skipped when already saved", bm) { bm = it })
                if (np > 0) card.addView(VaultScreen.switchRow(a, "Saved pages", n("page", np) + " into Saved pages", pg) { pg = it })
                if (nf > 0) card.addView(VaultScreen.switchRow(a, "Files", n("file", nf) + " as readable copies in Downloads/Thazir. Can be large.", fl) { fl = it })
                c.addView(card, lp(dp(a, 8)))
                if (nh > 0) c.addView(VaultScreen.tv(a, "History (" + n("hist", nh) + ") stays in the vault: the browser cannot take back entries with their original dates.", 12f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 10)))
                c.addView(VaultScreen.btn(a, "Restore selected") { start() }, lp(dp(a, 20), MATCH, dp(a, 48)))
            }
            c.addView(VaultScreen.link(a, if (nb + np + nf == 0) "Close" else "Skip for now") { dlg?.dismiss() }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    private fun start() {
        val a = act ?: return
        if (!bm && !pg && !fl) { Util.toast(a, "Nothing selected"); return }
        if (running) return
        running = true
        val b = bm; val p = pg; val f = fl
        lateinit var status: android.widget.TextView
        page(a) { c ->
            c.addView(VaultScreen.tv(a, "Restoring", 24f, R.color.mg_text, true), lp(dp(a, 10)))
            status = VaultScreen.tv(a, "Starting...", 14f, R.color.mg_text_dim)
            c.addView(status, lp(dp(a, 12)))
            c.addView(android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }, lp(dp(a, 16)))
            c.addView(VaultScreen.tv(a, "Keep Thazir open until this is done.", 12f, R.color.mg_text_dim), lp(dp(a, 12)))
        }
        Thread {
            val rep = try { doRestore(a, b, p, f) { t -> main.post { if (running) status.text = t } } } catch (e: Exception) { listOf("Something went wrong. The vault was not changed.") }
            main.post { running = false; if (dlg?.isShowing == true) report(rep) }
        }.start()
    }

    private fun report(rep: List<String>) {
        val a = act ?: return
        page(a) { c ->
            c.addView(VaultScreen.tv(a, "Done", 24f, R.color.mg_text, true), lp(dp(a, 10)))
            for (l in rep) c.addView(VaultScreen.tv(a, l, 14f, R.color.mg_text), lp(dp(a, 10)))
            c.addView(VaultScreen.btn(a, "Close") { dlg?.dismiss() }, lp(dp(a, 24), MATCH, dp(a, 48)))
        }
    }
}
