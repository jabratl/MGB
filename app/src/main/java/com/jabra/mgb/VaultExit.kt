package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V9: Exit / Uninstall-prep wizard. Android cannot run code at uninstall, so this runs BEFORE it.
 * Pages: choose (per category KEEP | EXPORT | DELETE, default KEEP) -> review (export folder, "remove vault copy after verified export", optional
 * wipe of the whole vault, typed word DELETE when anything is destroyed) -> run (progress, Cancel between items) -> report.
 * KEEP = nothing happens, the encrypted vault folder stays. EXPORT = readable copy in <picked folder>/ThazirVault-export-<date>/ (bookmarks.html,
 * history.csv, pages/*.mht, files/<original names>); the vault copy stays unless the switch says to remove it after the copy was written and checked.
 * DELETE = items + blobs removed from the vault, index saved, then verified. WIPE = VaultStore.wipeAll (vault.hdr removed last, folder forgotten).
 * Never touches the normal Downloads folder, saved pages or bookmarks of the browser, or any non-vault file. The export folder must not be the vault folder.
 * State lives in this object (rotation safe, the dialog is rebuilt on each page).
 */
object VaultExit {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val TYPES = listOf("bm", "hist", "page", "file")
    private val NAMES = listOf("Bookmarks", "History", "Saved pages", "Files")
    private val CH = listOf("Keep", "Export", "Delete")
    private const val DIR = DocumentsContract.Document.MIME_TYPE_DIR

    private val main = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null
    private var host: FrameLayout? = null
    private var act: Activity? = null
    private var onClose: (() -> Unit)? = null

    private val choice = IntArray(4)
    private var rm = false
    private var wipe = false
    private var exportTree: Uri? = null
    private var confirm = ""
    private var running = false
    @Volatile private var cancel = false

    private fun dp(a: Context, v: Int) = Util.dp(a, v)
    private fun lp(top: Int = 0, w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h).apply { topMargin = top }
    private fun cnt(i: Int) = VaultStore.count(TYPES[i])
    private fun any(v: Int) = (0 until 4).any { cnt(it) > 0 && choice[it] == v }
    private fun anyKeep() = (0 until 4).any { cnt(it) > 0 && choice[it] == 0 }
    private fun needWord() = any(2) || wipe

    fun open(a: Activity, closed: () -> Unit) {
        if (a.isFinishing || a.isDestroyed || dlg?.isShowing == true) return
        if (!VaultStore.isUnlocked()) { Util.toast(a, "Open the vault first"); return }
        act = a; onClose = closed
        for (i in 0 until 4) choice[i] = 0
        rm = false; wipe = false; exportTree = null; confirm = ""; running = false; cancel = false
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val h = FrameLayout(a)
        h.setBackgroundColor(a.getColor(R.color.mg_bg))
        host = h
        d.setContentView(h, ViewGroup.LayoutParams(MATCH, MATCH))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(a.getColor(R.color.mg_bg)))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (VaultStore.hideRecents(a)) setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.setOnKeyListener { _, k, e ->
            if (k == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP) { if (!running) chooseOrClose() ; true } else false
        }
        d.setOnDismissListener { if (dlg === d) { dlg = null; host = null; val f = onClose; onClose = null; f?.invoke() } }
        dlg = d
        try { d.show() } catch (e: Exception) { dlg = null; return }
        choosePage()
    }

    /** Called by VaultScreen.refresh path: a locked vault must not leave the wizard open (not while a run is going: it ends itself). */
    fun refresh() { if (dlg?.isShowing == true && !running && !VaultStore.isUnlocked()) dlg?.dismiss() }

    private var page = "choose"
    private fun chooseOrClose() { if (page == "review") choosePage() else dlg?.dismiss() }

    // ---------- view pieces ----------

    private fun shell(a: Activity, title: String, back: (() -> Unit)?): Pair<LinearLayout, LinearLayout> {
        val h = host ?: throw IllegalStateException("no host")
        h.removeAllViews()
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 24)) }
        val sv = ScrollView(a).apply { isFillViewport = true }
        sv.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        val foot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 12)); setBackgroundColor(a.getColor(R.color.mg_bg)) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 8), dp(a, 8), dp(a, 20), dp(a, 0)) }
        if (back != null) top.addView(VaultScreen.link(a, "Back") { back() }, LinearLayout.LayoutParams(WRAP, dp(a, 48)))
        else top.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0)
        top.addView(VaultScreen.tv(a, title, 20f, R.color.mg_text, true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 2 }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(a, 4) })
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        val w = minOf(a.resources.displayMetrics.widthPixels, dp(a, 560))
        h.addView(root, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL))
        return Pair(list, foot)
    }

    private fun header(a: Activity, t: String) = VaultScreen.tv(a, t, 11f, R.color.mg_primary).apply { typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }

    private fun seg(a: Activity, i: Int): View {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for (k in 0 until 3) {
            val on = choice[i] == k
            val fill = if (!on) R.color.mg_surface_variant else if (k == 2) R.color.mg_error else R.color.mg_primary
            val t = VaultScreen.tv(a, CH[k], 14f, if (on) R.color.mg_on_primary else R.color.mg_text, true).apply {
                gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                background = GradientDrawable().apply { setColor(a.getColor(fill)); cornerRadius = dp(a, 12).toFloat() }
                contentDescription = NAMES[i] + ": " + CH[k] + if (on) ", selected" else ""
                setOnClickListener { if (running) return@setOnClickListener; choice[i] = k; choosePage() }
            }
            r.addView(t, LinearLayout.LayoutParams(0, dp(a, 44), 1f).apply { if (k > 0) marginStart = dp(a, 6) })
        }
        return r
    }

    // ---------- page 1: choose ----------

    private fun choosePage() {
        val a = act ?: return
        page = "choose"
        val (list, foot) = shell(a, "Exit vault", null)
        list.addView(VaultScreen.tv(a, "Android cannot run anything when an app is uninstalled, so decide here, before you uninstall. If you simply uninstall, nothing is lost: the encrypted vault stays in " + VaultScreen.folderLabel(a) + " and can be restored after a reinstall with your passphrase or recovery key.", 13f, R.color.mg_text_dim), lp(dp(a, 4)))
        list.addView(header(a, "WHAT TO DO WITH EACH PART"), lp(dp(a, 20)))
        for (i in 0 until 4) {
            val n = cnt(i)
            val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = VaultScreen.box(a); setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14)) }
            val b = VaultStore.bytes(TYPES[i])
            card.addView(VaultScreen.tv(a, NAMES[i], 16f, R.color.mg_text, true))
            card.addView(VaultScreen.tv(a, if (n == 0) "Empty" else (if (n == 1) "1 item" else "$n items") + (if (b > 0) " - " + Util.fmtBytes(b) else ""), 12f, R.color.mg_text_dim))
            if (n > 0) card.addView(seg(a, i), lp(dp(a, 10)))
            list.addView(card, lp(dp(a, 8)))
        }
        list.addView(VaultScreen.tv(a, "Keep: stays encrypted in the vault folder.\nExport: a readable copy in a folder you pick.\nDelete: removed from the vault for good.", 12f, R.color.mg_text_dim), lp(dp(a, 12)))
        val none = (0 until 4).all { cnt(it) == 0 || choice[it] == 0 }
        foot.addView(VaultScreen.btn(a, if (none) "Review (nothing selected)" else "Review", true) { reviewPage() }, lp(0, MATCH, dp(a, 48)))
        foot.addView(VaultScreen.link(a, "Close") { dlg?.dismiss() }, lp(dp(a, 4), MATCH, dp(a, 48)))
    }

    // ---------- page 2: review ----------

    private fun reviewPage() {
        val a = act ?: return
        page = "review"
        val (list, foot) = shell(a, "Review", { choosePage() })
        list.addView(header(a, "SUMMARY"), lp(dp(a, 8)))
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = VaultScreen.box(a); setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12)) }
        for (i in 0 until 4) {
            val n = cnt(i)
            if (n == 0) continue
            val what = if (wipe && choice[i] == 0) "Keep (conflicts with wipe)" else CH[choice[i]]
            card.addView(VaultScreen.tv(a, NAMES[i] + ": " + what + "  (" + n + ")", 14f, if (choice[i] == 2) R.color.mg_error else R.color.mg_text), lp(dp(a, 4)))
        }
        list.addView(card, lp(dp(a, 8)))
        val conflict = wipe && anyKeep()
        if (any(1)) {
            list.addView(header(a, "EXPORT FOLDER"), lp(dp(a, 20)))
            list.addView(VaultScreen.tv(a, if (exportTree == null) "Pick a normal folder (not the vault folder). A new folder ThazirVault-export-<date> is created inside it." else "Chosen: " + treeName(a, exportTree!!), 13f, R.color.mg_text_dim), lp(dp(a, 6)))
            list.addView(VaultScreen.btn(a, if (exportTree == null) "Choose folder" else "Change folder", false) {
                BrowserExtras.pickVaultFolder { u ->
                    if (u.toString() == VaultStore.treeUri(a)?.toString()) Util.toast(a, "That is the vault folder. Pick another one.")
                    else { exportTree = u; if (page == "review") reviewPage() }
                }
            }, lp(dp(a, 8), MATCH, dp(a, 48)))
            list.addView(VaultScreen.switchRow(a, "Remove vault copy after export", "Only items whose readable copy was written and checked. Off = the vault keeps them too.", rm) { rm = it }.also { it.background = VaultScreen.box(a) }, lp(dp(a, 8)))
        }
        list.addView(header(a, "OPTIONAL"), lp(dp(a, 20)))
        list.addView(VaultScreen.switchRow(a, "Wipe the whole vault", "Removes everything in the vault folder including vault.hdr. The vault can never be opened again. Needs every part set to Export or Delete.", wipe) { wipe = it; reviewPage() }.also { it.background = VaultScreen.box(a) }, lp(dp(a, 8)))
        if (conflict) list.addView(VaultScreen.tv(a, "Wipe needs Export or Delete for every part. Go back and change the Keep parts.", 13f, R.color.mg_error), lp(dp(a, 8)))
        val run = VaultScreen.btn(a, "Run", true) { startRun(a) }
        val et = if (needWord()) VaultScreen.field(a, "Type DELETE to confirm", false) else null
        fun ok(): Boolean = !conflict && (any(1) || any(2) || wipe) && (!any(1) || exportTree != null) && (et == null || et.text.toString().trim() == "DELETE")
        if (et != null) {
            et.setText(confirm); et.setSelection(et.text.length)
            et.doAfterTextChanged { confirm = it?.toString().orEmpty(); run.isEnabled = ok() }
            list.addView(VaultScreen.tv(a, "This destroys data that cannot be brought back.", 13f, R.color.mg_error), lp(dp(a, 16)))
            list.addView(et, lp(dp(a, 8), MATCH, dp(a, 52)))
        }
        run.isEnabled = ok()
        foot.addView(run, lp(0, MATCH, dp(a, 48)))
        if (!ok() && !conflict && !any(1) && !any(2) && !wipe) foot.addView(VaultScreen.tv(a, "Everything is set to Keep, so there is nothing to run.", 12f, R.color.mg_text_dim).apply { gravity = Gravity.CENTER }, lp(dp(a, 6)))
    }

    private fun treeName(c: Context, t: Uri): String = try {
        DocumentsContract.getTreeDocumentId(t).substringAfter(':').ifEmpty { "Storage" }
    } catch (e: Exception) { "selected folder" }

    // ---------- page 3: run ----------

    private lateinit var runText: TextView
    private lateinit var runBar: ProgressBar

    private fun startRun(a: Activity) {
        if (running) return
        running = true; cancel = false; page = "run"
        val (list, foot) = shell(a, "Working...", null)
        runText = VaultScreen.tv(a, "Preparing...", 14f, R.color.mg_text_dim)
        runBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = false }
        list.addView(runText, lp(dp(a, 24)))
        list.addView(runBar, lp(dp(a, 12), MATCH, dp(a, 24)))
        foot.addView(VaultScreen.btn(a, "Cancel", false) { cancel = true; runText.text = "Cancelling after the current item..." }, lp(0, MATCH, dp(a, 48)))
        val app = a.applicationContext
        val ch = choice.copyOf(); val doRm = rm; val doWipe = wipe; val tree = exportTree
        Thread {
            val rep = try { runAll(app, ch, doRm, doWipe, tree) } catch (e: Exception) { listOf("Something went wrong. Nothing was removed unless listed above.") }
            main.post { running = false; if (dlg?.isShowing == true) reportPage(rep, doWipe && !VaultStore.isUnlocked() && VaultStore.treeUri(app) == null) else onClose?.invoke() }
        }.start()
    }

    private fun prog(t: String, f: Float) { main.post { if (running && dlg?.isShowing == true) { runText.text = t; runBar.progress = (f.coerceIn(0f, 1f) * 1000).toInt() } } }

    private fun reportPage(rep: List<String>, wiped: Boolean) {
        val a = act ?: return
        page = "report"
        val (list, foot) = shell(a, "Done", null)
        for (l in rep) list.addView(VaultScreen.tv(a, l, 14f, R.color.mg_text), lp(dp(a, 10)))
        if (wiped) list.addView(VaultScreen.tv(a, "The vault was removed. You can uninstall the app now.", 14f, R.color.mg_primary, true), lp(dp(a, 20)))
        else list.addView(VaultScreen.tv(a, "Whatever you chose to keep is still sealed in " + VaultScreen.folderLabel(a) + ".", 13f, R.color.mg_text_dim), lp(dp(a, 20)))
        foot.addView(VaultScreen.btn(a, "Close", true) { dlg?.dismiss() }, lp(0, MATCH, dp(a, 48)))
    }

    // ---------- the work (BLOCKING, background thread) ----------

    private class Ex(val c: Context, tree: Uri) {
        val cr = c.contentResolver
        val base: Uri
        private val subs = HashMap<String, Uri>()
        init {
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            base = DocumentsContract.createDocument(cr, root, DIR, "ThazirVault-export-$stamp") ?: throw IOException("cannot create export folder")
        }
        fun sub(name: String): Uri = subs.getOrPut(name) { DocumentsContract.createDocument(cr, base, DIR, name) ?: throw IOException("cannot create $name") }
        fun text(parent: Uri, name: String, mime: String, s: String): Boolean = try {
            val d = DocumentsContract.createDocument(cr, parent, mime, name) ?: throw IOException("create")
            try { (cr.openOutputStream(d) ?: throw IOException("open")).use { it.write(s.toByteArray(Charsets.UTF_8)) }; true }
            catch (e: Exception) { try { DocumentsContract.deleteDocument(cr, d) } catch (x: Exception) { }; false }
        } catch (e: Exception) { false }
        fun blob(parent: Uri, name: String, mime: String, o: JSONObject): Boolean {
            val r = try { VaultStore.openBlobIn(c, o.optString("blob")) } catch (e: Exception) { null } ?: return false
            var d: Uri? = null
            try {
                d = DocumentsContract.createDocument(cr, parent, mime, name) ?: return false
                var n = 0L
                (cr.openOutputStream(d) ?: throw IOException("open")).use { out ->
                    r.copyTo(object : OutputStream() {
                        override fun write(b: Int) { out.write(b); n++ }
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
                    })
                }
                if (n != r.plainSize) throw IOException("size")
                return true
            } catch (e: Exception) {
                d?.let { try { DocumentsContract.deleteDocument(cr, it) } catch (x: Exception) { } }
                return false
            } finally { r.close() }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""

    private fun bookmarksHtml(items: List<JSONObject>): String {
        val sb = StringBuilder("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        fun line(o: JSONObject, pad: String) { sb.append(pad).append("<DT><A HREF=\"").append(esc(o.optString("url"))).append("\" ADD_DATE=\"").append(o.optLong("time") / 1000).append("\">").append(esc(o.optString("title").ifBlank { o.optString("url") })).append("</A>\n") }
        for (o in items) if (o.optString("folder").isEmpty()) line(o, "    ")
        for (f in items.map { it.optString("folder") }.filter { it.isNotEmpty() }.distinct().sorted()) {
            sb.append("    <DT><H3>").append(esc(f)).append("</H3>\n    <DL><p>\n")
            for (o in items) if (o.optString("folder") == f) line(o, "        ")
            sb.append("    </DL><p>\n")
        }
        return sb.append("</DL><p>\n").toString()
    }

    private fun runAll(app: Context, ch: IntArray, doRm: Boolean, doWipe: Boolean, tree: Uri?): List<String> {
        val rep = ArrayList<String>()
        if (!VaultStore.isUnlocked()) return listOf("The vault is locked. Nothing was changed.")
        val needEx = (0 until 4).any { ch[it] == 1 && VaultStore.count(TYPES[it]) > 0 }
        var ex: Ex? = null
        if (needEx) {
            ex = try { Ex(app, tree ?: throw IOException("no folder")) } catch (e: Exception) { null }
            if (ex == null) return listOf("The export folder could not be written. Nothing was changed.")
            rep.add("Export folder: " + treeName(app, tree!!) + "/ThazirVault-export-...")
        }
        var exportBad = false
        for (i in 0 until 4) {
            val t = TYPES[i]
            val items = VaultStore.items(t).sortedByDescending { it.optLong("time") }
            if (items.isEmpty()) continue
            if (cancel) { rep.add("Cancelled. " + NAMES[i] + " and the rest were not touched."); exportBad = true; break }
            when (ch[i]) {
                1 -> {
                    val ok = ArrayList<String>()
                    val e = ex!!
                    when (t) {
                        "bm" -> { prog("Exporting bookmarks", 0.5f); if (e.text(e.base, "bookmarks.html", "text/html", bookmarksHtml(items))) items.forEach { ok.add(it.optString("id")) } }
                        "hist" -> {
                            prog("Exporting history", 0.5f)
                            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            val sb = StringBuilder("time,title,url\n")
                            for (o in items) sb.append(csv(f.format(Date(o.optLong("time"))))).append(',').append(csv(o.optString("title"))).append(',').append(csv(o.optString("url"))).append('\n')
                            if (e.text(e.base, "history.csv", "text/csv", sb.toString())) items.forEach { ok.add(it.optString("id")) }
                        }
                        else -> {
                            val dir = try { e.sub(if (t == "page") "pages" else "files") } catch (x: Exception) { null }
                            if (dir != null) for ((k, o) in items.withIndex()) {
                                if (cancel) break
                                prog("Exporting " + NAMES[i].lowercase() + " " + (k + 1) + " of " + items.size, (k.toFloat() / items.size))
                                val nm = VaultFiles.safeName(o.optString("title"))
                                val name = if (t == "page") "$nm.mht" else nm
                                val mime = if (t == "page") "application/x-mimearchive" else o.optString("mime").ifEmpty { "application/octet-stream" }
                                if (e.blob(dir, name, mime, o)) ok.add(o.optString("id"))
                            }
                        }
                    }
                    if (ok.size < items.size) exportBad = true
                    var line = NAMES[i] + ": exported " + ok.size + " of " + items.size
                    if (doRm && ok.isNotEmpty()) {
                        for (id in ok) VaultStore.removeItem(app, id, t == "page" || t == "file")
                        if (t == "bm" && VaultStore.count("bm") == 0) VaultStore.clearFolders(app)
                        line += if (VaultItems.sealed(app)) ", removed from the vault" else ", vault list could not update (repeat to clean up)"
                    } else line += ", vault copy kept"
                    rep.add(line)
                }
                2 -> {
                    prog("Deleting " + NAMES[i].lowercase(), 0.5f)
                    for ((k, o) in items.withIndex()) {
                        if (cancel) break
                        VaultStore.removeItem(app, o.optString("id"), t == "page" || t == "file")
                        if (k % 20 == 0) prog("Deleting " + NAMES[i].lowercase() + " " + (k + 1) + " of " + items.size, k.toFloat() / items.size)
                    }
                    if (t == "bm" && VaultStore.count("bm") == 0) VaultStore.clearFolders(app)
                    val saved = VaultItems.sealed(app)
                    val left = VaultStore.count(t)
                    val blobsLeft = items.count { (t == "page" || t == "file") && VaultStore.blobExists(app, it.optString("blob")) }
                    if (left > 0) exportBad = true
                    rep.add(NAMES[i] + ": " + if (left == 0 && saved && blobsLeft == 0) "deleted (" + items.size + ")" else "NOT fully deleted ($left left, $blobsLeft files left, list saved=$saved). Run again.")
                }
                else -> rep.add(NAMES[i] + ": kept in the vault")
            }
        }
        if (doWipe) {
            if (exportBad || cancel) rep.add("Vault NOT wiped, because a step was incomplete or cancelled.")
            else {
                prog("Removing the vault", 1f)
                val err = VaultStore.wipeAll(app)
                rep.add(if (err == null) "Vault removed (blobs, index, vault.hdr)." else err)
            }
        }
        return rep
    }
}
