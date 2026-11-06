package com.jabra.mgb

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * V6: saved pages in the vault. V7 (bottom of this file): downloaded files (type "file") - stream-seal with progress + cancel, open / share through a
 * temp decrypt + FileProvider (cache-path vault_tmp), export out to Downloads/Thazir, delete original only after the user says so.
 * A saved page (.mht from PageSave) is stream-encrypted into the vault as a blob (VaultStore.openBlobOut), read back and compared by SHA-256,
 * and only then (move) removed with PageSave.remove. Item JSON: type "page", blob = bare blob id (VaultStore adds ".bin"), size = plain bytes,
 * time/url/title copied from the saved page, orig {name, rel}.
 * Opening: decrypt to cacheDir/vault_tmp/<random>.mht, show it with TabManager.openSaved (offline flags, JS off). The temp folder is wiped
 * (zero-filled, then deleted) on app stop, on app start and when the vault locks through those hooks. A tab that still shows such a page
 * cannot reload it after the wipe (it stays visible in the WebView until closed).
 * All functions marked BLOCKING must run on a background thread.
 */
object VaultFiles {
    private const val TMP = "vault_tmp"

    private fun tmpDir(c: Context) = File(c.cacheDir, TMP)

    private fun shred(f: File) {
        try {
            if (f.isDirectory) { f.listFiles()?.forEach { shred(it) }; f.delete(); return }
            val len = f.length()
            if (len > 0) RandomAccessFile(f, "rw").use { r ->
                val z = ByteArray(8192)
                var left = len
                while (left > 0) { val n = minOf(left, z.size.toLong()).toInt(); r.write(z, 0, n); left -= n }
                r.fd.sync()
            }
        } catch (e: Exception) { }
        f.delete()
    }

    private var cold = true

    /**
     * Safe to call from any thread, any time (cheap when the folder does not exist). Saved-page temp files go at once. V7: files handed to another
     * app live in vault_tmp/x/<random>/ and are kept for 15 minutes (the other app may still be reading them) unless [force] (vault locking)
     * or this is the first call of the app process (a crash restart leaves nothing behind).
     */
    fun wipeTemp(c: Context, force: Boolean = false) {
        val keep = if (force || cold) 0L else 15L * 60_000L
        cold = false
        try {
            tmpDir(c).listFiles()?.forEach { f ->
                if (f.isDirectory && f.name == "x") {
                    f.listFiles()?.forEach { d -> if (keep == 0L || System.currentTimeMillis() - d.lastModified() > keep) shred(d) }
                    if (f.listFiles().isNullOrEmpty()) f.delete()
                } else shred(f)
            }
        } catch (e: Exception) { }
    }

    private class Sha(private val on: ((Long) -> Unit)? = null) : OutputStream() {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        private var n = 0L
        override fun write(b: Int) { md.update(b.toByte()); n++; on?.invoke(n) }
        override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); n += len; on?.invoke(n) }
    }

    private fun sha(f: File): ByteArray {
        val s = Sha()
        f.inputStream().use { it.copyTo(s) }
        return s.md.digest()
    }

    /** BLOCKING. Encrypts [f] into blob [id], reads it back and compares. Returns null when verified, else a short user text. */
    private fun encryptFile(c: Context, f: File, id: String): String? {
        try {
            val w = VaultStore.openBlobOut(c, id)
            try { f.inputStream().use { it.copyTo(w) } } finally { w.close() }
            val r = VaultStore.openBlobIn(c, id) ?: return "Could not read the vault copy back."
            try {
                if (r.plainSize != f.length()) return "The vault copy has a different size."
                val s = Sha()
                r.copyTo(s)
                if (!MessageDigest.isEqual(s.md.digest(), sha(f))) return "The vault copy did not match."
            } finally { r.close() }
            return null
        } catch (e: Exception) {
            return "The page could not be sealed. Check the vault folder and free space."
        }
    }

    /** BLOCKING. Decrypts blob [blob] into [dest]. False (and no file left) on any failure. */
    fun decryptTo(c: Context, blob: String, dest: File): Boolean {
        val r = try { VaultStore.openBlobIn(c, blob) } catch (e: Exception) { null } ?: return false
        try {
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { r.copyTo(it) }
            return true
        } catch (e: Exception) {
            shred(dest)
            return false
        } finally { r.close() }
    }

    /** Saved pages as move/copy sources (ref = PageSave id; the Src action removes the saved page, run only after verification). */
    fun pageSources(c: Context): List<VaultItems.Src> {
        val app = c.applicationContext
        return PageSave.list(app).map { e -> VaultItems.Src(VaultItems.E(e.title, e.url, e.time, "", e.id, e.size), { PageSave.remove(app, e.id) }) }
    }

    /** BLOCKING. Seals saved pages into the vault. move = remove the saved page after its vault copy is verified AND the index is saved. */
    fun addPages(c: Context, src: List<VaultItems.Src>, move: Boolean): VaultItems.Result {
        val app = c.applicationContext
        if (!VaultStore.isUnlocked()) return VaultItems.Result(false, msg = "The vault is locked. Nothing was changed.")
        val have = HashSet<String>()
        for (o in VaultStore.items("page")) have.add(o.optLong("time").toString() + "|" + o.optString("url"))
        val done = ArrayList<VaultItems.Src>()
        val ids = ArrayList<String>()
        var dup = 0
        for (s in src) {
            val e = s.e
            val f = PageSave.file(app, e.ref)
            if (e.ref.isEmpty() || !f.exists()) continue
            val key = e.time.toString() + "|" + e.url
            if (have.contains(key)) { dup++; continue }
            val id = VaultCrypto.newId()
            val err = encryptFile(app, f, id)
            if (err != null) { VaultStore.deleteBlob(app, id); return VaultItems.Result(false, ids.size, dup, 0, "$err Pages already sealed stay in the vault; nothing was removed.", ids) }
            val o = JSONObject().put("id", id).put("type", "page").put("title", e.title).put("url", e.url).put("time", e.time).put("folder", "")
                .put("size", f.length()).put("mime", "application/x-mimearchive").put("blob", id)
                .put("orig", JSONObject().put("name", e.ref + ".mht").put("rel", "saved_pages"))
            if (VaultStore.addItem(app, o).isEmpty()) { VaultStore.deleteBlob(app, id); return VaultItems.Result(false, ids.size, dup, 0, "The vault locked while saving. Nothing was removed.", ids) }
            ids.add(id); done.add(s); have.add(key)
        }
        if (!VaultItems.sealed(app)) return VaultItems.Result(false, ids.size, dup, 0, "The vault could not save its list. Nothing was removed.", ids)
        val now = VaultStore.items("page").map { it.optString("id") }.toHashSet()
        if (!ids.all { now.contains(it) && VaultStore.blobExists(app, it) }) return VaultItems.Result(false, ids.size, dup, 0, "Could not verify the vault copy. Nothing was removed.", ids)
        var removed = 0
        if (move) for (s in done) { try { s.remove(); removed++ } catch (x: Exception) { } }
        return VaultItems.Result(true, ids.size, dup, removed, newIds = ids, moved = done.map { it.e })
    }

    /** BLOCKING. Puts vault pages back into Saved pages (new ids, original title / url / time). keep=false removes them from the vault afterwards. */
    fun restorePages(c: Context, ids: Collection<String>, keep: Boolean): VaultItems.Result {
        val app = c.applicationContext
        if (!VaultStore.isUnlocked()) return VaultItems.Result(false, msg = "The vault is locked. Nothing was changed.")
        val back = ArrayList<String>()
        var full = false
        var n = 0
        for (id in ids) {
            val o = VaultStore.item(id) ?: continue
            if (PageSave.count(app) >= PageSave.MAX) { full = true; break }
            val nid = java.lang.Long.toString(System.currentTimeMillis(), 36) + (n++).toString(36)
            val f = PageSave.file(app, nid)
            if (!decryptTo(app, o.optString("blob"), f) || f.length() == 0L || (o.optLong("size") > 0 && f.length() != o.optLong("size"))) { f.delete(); continue }
            PageSave.add(app, PageSave.Entry(nid, o.optString("title"), o.optString("url"), o.optLong("time"), f.length()))
            back.add(id)
        }
        if (back.isEmpty()) return VaultItems.Result(false, msg = if (full) "Saved pages is full (${PageSave.MAX}). Delete one first." else "Could not restore. Nothing was removed from the vault.")
        if (!keep) {
            for (id in back) VaultStore.removeItem(app, id, true)
            if (!VaultItems.sealed(app)) return VaultItems.Result(true, back.size, msg = "Restored, but the vault list could not update. Repeat to clean up.")
        }
        return VaultItems.Result(true, back.size, removed = if (keep) 0 else back.size, msg = if (full) "Saved pages is full, some were left in the vault." else "")
    }

    /** Opens a vault page offline in a new tab (decrypt to a temp file, see class comment). Call from the UI thread. */
    fun openPage(a: Activity, id: String, done: () -> Unit) {
        val o = VaultStore.item(id)
        val tabs = BookmarksScreen.tabsRef
        if (o == null || tabs == null) { Util.toast(a, "Could not open it now"); return }
        val title = o.optString("title")
        val blob = o.optString("blob")
        Thread {
            val f = File(tmpDir(a), VaultCrypto.newId() + ".mht")
            val ok = decryptTo(a.applicationContext, blob, f)
            a.runOnUiThread {
                if (!ok || a.isFinishing || a.isDestroyed) { Util.toast(a, "Could not open this page. Is the vault folder still there?"); f.delete(); return@runOnUiThread }
                tabs.openSaved("file://" + f.absolutePath, title)
                done()
            }
        }.start()
    }

    // ================= V7: downloaded files =================

    private val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private class Cancelled : Exception()

    private fun safeName(n: String): String {
        val x = n.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ ()\\-]"), "_").trim().take(100)
        return if (x.isEmpty() || x == "." || x == "..") "file" else x
    }

    private fun fkey(time: Long, name: String) = "$time|$name"

    /** Finished download as a move / copy source. h = one entry of EngineApi.history() (id, title, src, time, size). Null when it cannot be read. */
    fun srcOf(h: JSONObject): VaultItems.Src? {
        val id = h.optInt("id", 0)
        if (id <= 0 || DownloadsApi.itemOf(id) == null) return null
        return VaultItems.Src(VaultItems.E(h.optString("title"), h.optString("src"), h.optLong("time"), "", id.toString(), h.optLong("size")), { DownloadsApi.deleteFile(id) })
    }

    /** BLOCKING-ish (reads storage.local): finished downloads that still exist in the downloads list. ref = download id. */
    fun fileSources(c: Context): List<VaultItems.Src> = EngineApi.history().mapNotNull { srcOf(it) }

    private fun openSrc(c: Context, d: DlItem): InputStream? = try {
        if (d.uri.startsWith("content:")) c.contentResolver.openInputStream(Uri.parse(d.uri)) else FileInputStream(File(d.uri))
    } catch (e: Exception) { null }

    /** Best effort: only checks the phone's main storage when the vault folder is on it (an SD card / cloud folder cannot be asked). */
    private fun spaceOk(c: Context, need: Long): Boolean {
        if (need <= 0) return true
        return try {
            val t = VaultStore.treeUri(c) ?: return true
            if (!DocumentsContract.getTreeDocumentId(t).startsWith("primary:")) return true
            android.os.StatFs(Environment.getExternalStorageDirectory().path).availableBytes > need + 8L * 1048576L
        } catch (e: Exception) { true }
    }

    private fun lowSpace(e: Exception) = (e.message ?: "").let { it.contains("ENOSPC") || it.contains("No space", true) }

    /**
     * BLOCKING. Seals finished downloads into the vault, one blob each (64 KiB chunks, never the whole file in memory). The source is read ONCE
     * (SHA-256 while encrypting), then the vault copy is decrypted and compared (size + SHA-256). Originals are NEVER touched here: the caller
     * decides (Result.moved = the files whose copy is verified and whose index entry is saved). onProgress(text, 0..1) comes from this thread.
     */
    fun addFiles(c: Context, src: List<VaultItems.Src>, onProgress: (String, Float) -> Unit): VaultItems.Result {
        val app = c.applicationContext
        if (!VaultStore.isUnlocked()) return VaultItems.Result(false, msg = "The vault is locked. Nothing was changed.")
        val have = HashSet<String>()
        for (o in VaultStore.items("file")) have.add(fkey(o.optLong("time"), o.optString("title")))
        val done = ArrayList<VaultItems.Src>()
        val ids = ArrayList<String>()
        var dup = 0
        var skipped = 0
        for ((i, s) in src.withIndex()) {
            val e = s.e
            val d = DownloadsApi.itemOf(e.ref.toIntOrNull() ?: 0)
            if (d == null) { skipped++; continue }
            val name = e.title.ifBlank { d.rel.substringAfterLast('/') }.ifBlank { "file" }
            val key = fkey(e.time, name)
            if (have.contains(key)) { dup++; continue }
            val total = if (d.fileSize > 0) d.fileSize else e.size
            if (!spaceOk(app, total)) return VaultItems.Result(false, ids.size, dup, 0, "Not enough free space for \"$name\". Files already sealed stay in the vault; nothing was removed.", ids)
            val input = openSrc(app, d)
            if (input == null) { skipped++; continue }
            val id = VaultCrypto.newId()
            val label = if (src.size > 1) "Sealing ${i + 1} of ${src.size}: $name" else "Sealing $name"
            val msg = "Files already sealed stay in the vault; nothing was removed."
            try {
                val sha = Sha()
                var plain = 0L
                var last = 0L
                val w = VaultStore.openBlobOut(app, id)
                try {
                    input.use { inp ->
                        val buf = ByteArray(65536)
                        while (true) {
                            if (cancel.get()) throw Cancelled()
                            val n = inp.read(buf)
                            if (n < 0) break
                            w.write(buf, 0, n); sha.md.update(buf, 0, n); plain += n
                            val now = System.nanoTime()
                            if (now - last > 150_000_000L) { last = now; onProgress(label, if (total > 0) (plain.toFloat() / total).coerceIn(0f, 1f) else 0f) }
                        }
                    }
                } finally { w.close() }
                if (plain == 0L) throw java.io.IOException("empty")
                val r = VaultStore.openBlobIn(app, id) ?: throw java.io.IOException("readback")
                try {
                    if (r.plainSize != plain) throw java.io.IOException("size")
                    var last2 = 0L
                    val chk = Sha { n ->
                        val now = System.nanoTime()
                        if (now - last2 > 150_000_000L) { last2 = now; onProgress("Checking $name", (n.toFloat() / plain).coerceIn(0f, 1f)) }
                    }
                    r.copyTo(chk)
                    if (!MessageDigest.isEqual(chk.md.digest(), sha.md.digest())) throw java.io.IOException("hash")
                } finally { r.close() }
                val o = JSONObject().put("id", id).put("type", "file").put("title", name).put("url", e.url).put("time", e.time).put("folder", "")
                    .put("size", plain).put("mime", d.mime).put("blob", id)
                    .put("orig", JSONObject().put("name", name).put("rel", d.rel))
                if (VaultStore.addItem(app, o).isEmpty()) { VaultStore.deleteBlob(app, id); return VaultItems.Result(false, ids.size, dup, 0, "The vault locked while saving. $msg", ids) }
                ids.add(id); done.add(s); have.add(key)
            } catch (x: Cancelled) {
                VaultStore.deleteBlob(app, id)
                return VaultItems.Result(false, ids.size, dup, 0, "Cancelled. $msg", ids)
            } catch (x: Exception) {
                VaultStore.deleteBlob(app, id)
                val why = if (lowSpace(x)) "Not enough free space in the vault folder." else "\"$name\" could not be sealed. Check the vault folder and free space."
                return VaultItems.Result(false, ids.size, dup, 0, "$why $msg", ids)
            }
        }
        if (ids.isEmpty() && dup == 0) return VaultItems.Result(false, msg = if (skipped > 0) "The file could not be read (moved or deleted?). Nothing was changed." else "Nothing to seal.")
        onProgress("Saving the vault list", 1f)
        if (!VaultItems.sealed(app)) return VaultItems.Result(false, ids.size, dup, 0, "The vault could not save its list. Nothing was removed.", ids)
        val now = VaultStore.items("file").map { it.optString("id") }.toHashSet()
        if (!ids.all { now.contains(it) && VaultStore.blobExists(app, it) }) return VaultItems.Result(false, ids.size, dup, 0, "Could not verify the vault copy. Nothing was removed.", ids)
        return VaultItems.Result(true, ids.size, dup, 0, if (skipped > 0) "$skipped could not be read." else "", ids, done.map { it.e })
    }

    /** User text after runSeal. deleted = originals removed from the phone. */
    fun sealedText(r: VaultItems.Result, deleted: Int): String {
        if (!r.ok) return r.msg
        val n = r.added
        val what = if (n == 1) "1 file" else "$n files"
        return (if (n == 0) "Already in the vault" else "Sealed $what in the vault") + (if (r.dup > 0 && n > 0) " (${r.dup} already there)" else "") +
            (if (deleted > 0) ". Original deleted from your phone" + (if (deleted > 1) "s" else "") else "") + (if (r.msg.isNotEmpty()) ". " + r.msg else "")
    }

    /**
     * UI thread. Seals [src] with a progress dialog (Cancel stops after the current chunk, the half-written blob is removed). move = afterwards ASK
     * whether to delete the originals from the phone (default Keep). done(result, originalsDeleted) runs on the UI thread.
     */
    fun runSeal(a: Activity, src: List<VaultItems.Src>, move: Boolean, done: (VaultItems.Result, Int) -> Unit) {
        if (src.isEmpty()) { done(VaultItems.Result(false, msg = "Nothing to seal."), 0); return }
        if (!VaultStore.isUnlocked()) { done(VaultItems.Result(false, msg = "The vault is locked. Nothing was changed."), 0); return }
        cancel.set(false)
        val app = a.applicationContext
        val text = VaultScreen.tv(a, "Preparing...", 14f, R.color.mg_text_dim)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; isIndeterminate = false }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(Util.dp(a, 24), Util.dp(a, 12), Util.dp(a, 24), Util.dp(a, 4))
            addView(text); addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Util.dp(a, 24)).apply { topMargin = Util.dp(a, 12) })
        }
        val dlg = MaterialAlertDialogBuilder(a).setTitle("Sealing in the vault").setView(box).setCancelable(false).setNegativeButton("Cancel", null).create()
        if (VaultStore.hideRecents(a)) dlg.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dlg.show()
        dlg.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener { cancel.set(true); text.text = "Cancelling..." }
        Thread {
            val r = try {
                addFiles(app, src) { t, f -> a.runOnUiThread { if (!cancel.get()) text.text = t; bar.progress = (f * 1000).toInt() } }
            } catch (e: Exception) { VaultItems.Result(false, msg = "Something went wrong. Nothing was removed.") }
            a.runOnUiThread {
                try { dlg.dismiss() } catch (e: Exception) { }
                if (a.isFinishing || a.isDestroyed) { done(r, 0); return@runOnUiThread }
                val refs = r.moved.map { it.ref }.toSet()
                val del = src.filter { refs.contains(it.e.ref) }
                if (move && r.ok && del.isNotEmpty()) askDelete(a, del) { n -> done(r, n) } else done(r, 0)
            }
        }.start()
    }

    private fun askDelete(a: Activity, del: List<VaultItems.Src>, cb: (Int) -> Unit) {
        val n = del.size
        MaterialAlertDialogBuilder(a)
            .setTitle(if (n == 1) "Delete the original?" else "Delete $n originals?")
            .setMessage("The vault copy is saved and checked. Delete " + (if (n == 1) "the file" else "these files") + " from your phone's Downloads now? You can keep them and delete later.")
            .setCancelable(false)
            .setNegativeButton("Keep") { _, _ -> cb(0) }
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    var k = 0
                    for (s in del) { try { s.remove(); k++ } catch (e: Exception) { } }
                    a.runOnUiThread { cb(k) }
                }.start()
            }.show()
    }

    /** UI thread. One finished download (from the Downloads screen). after(true) = the original was deleted (caller drops it from its list). */
    fun sealDownload(a: Activity, h: JSONObject, move: Boolean, after: (Boolean) -> Unit) {
        if (!VaultStore.isUnlocked()) { Util.toast(a, "Open the vault first (menu > Vault)"); return }
        val s = srcOf(h)
        if (s == null) { Util.toast(a, "This file is not available (deleted or moved)"); return }
        runSeal(a, listOf(s), move) { r, del -> Util.toast(a, sealedText(r, del)); after(del > 0) }
    }

    // ---------- open / share / export (temp decrypt) ----------

    private fun mimeOf(o: JSONObject): String {
        val m = o.optString("mime")
        if (m.isNotEmpty() && m != "application/octet-stream") return m
        val ext = o.optString("title").substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** Decrypts into cacheDir/vault_tmp/x/<random>/<name>, then hands it to another app. The temp copy is wiped by wipeTemp (15 min / lock / next start). */
    fun openFile(a: Activity, id: String, share: Boolean, forceTemp: Boolean = false) {
        val o = VaultStore.item(id)
        if (o == null) { Util.toast(a, "Could not open it now"); return }
        val app = a.applicationContext
        val blob = o.optString("blob")
        val name = safeName(o.optString("title"))
        val mime = mimeOf(o)
        val inPlayer = !share && VaultPlay.isMedia(o)
        if (inPlayer && !forceTemp && VaultPlay.supported()) { // V8: no plain copy, the player reads through VaultPlay
            val pu = VaultPlay.uriFor(app, o, name, mime)
            if (pu == null) { Util.toast(a, "The vault is locked"); return }
            PlayerScreen.open(a, pu, o.optString("title").ifBlank { name })
            return
        }
        if (a.cacheDir.usableSpace < o.optLong("size") + 8L * 1048576L) { Util.toast(a, "Not enough free space to open this file. Export it instead."); return }
        Util.toast(a, "Opening...")
        Thread {
            val f = File(File(File(tmpDir(app), "x"), VaultCrypto.newId()), name)
            val ok = decryptTo(app, blob, f)
            a.runOnUiThread {
                if (!ok || a.isFinishing || a.isDestroyed) { Util.toast(a, "Could not open this file. Is the vault folder still there?"); shred(f.parentFile ?: f); return@runOnUiThread }
                try {
                    val u = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
                    if (inPlayer) { PlayerScreen.open(a, u, o.optString("title").ifBlank { name }); return@runOnUiThread } // V8 fallback: temp copy in the built-in player
                    val i = if (share) Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
                        else Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    a.startActivity(i)
                } catch (e: Exception) { Util.toast(a, if (share) "Cannot share this file" else "No app can open this file") }
            }
        }.start()
    }

    /** BLOCKING. Writes a readable copy of one blob to Downloads/Thazir (API 29+: MediaStore; older: the app's own Downloads folder). Returns true when written and size-checked. */
    private fun exportOne(c: Context, o: JSONObject): Boolean {
        val r = try { VaultStore.openBlobIn(c, o.optString("blob")) } catch (e: Exception) { null } ?: return false
        val name = safeName(o.optString("title"))
        val mime = mimeOf(o)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = c.contentResolver
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Thazir"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
                try {
                    var n = 0L
                    (cr.openOutputStream(u) ?: throw java.io.IOException("open")).use { out ->
                        r.copyTo(object : OutputStream() {
                            override fun write(b: Int) { out.write(b); n++ }
                            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
                        })
                    }
                    if (n != r.plainSize) throw java.io.IOException("size")
                    val len = try { cr.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L } catch (e: Exception) { -1L }
                    if (len >= 0 && len != n) throw java.io.IOException("size")
                    cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { try { cr.delete(u, null, null) } catch (x: Exception) { }; return false }
                return true
            }
            val dir = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Thazir")
            dir.mkdirs()
            var t = File(dir, name)
            var k = 1
            while (t.exists() && k < 1000) { t = File(dir, name.substringBeforeLast('.') + " ($k)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); k++ }
            FileOutputStream(t).use { r.copyTo(it) }
            if (t.length() != r.plainSize) { t.delete(); return false }
            return true
        } catch (e: Exception) { return false } finally { r.close() }
    }

    /** BLOCKING. Exports vault files as readable copies to Downloads/Thazir. keep=false removes the vault copy afterwards (only the ones that were written and size-checked). */
    fun exportFiles(c: Context, ids: Collection<String>, keep: Boolean): VaultItems.Result {
        val app = c.applicationContext
        if (!VaultStore.isUnlocked()) return VaultItems.Result(false, msg = "The vault is locked. Nothing was changed.")
        val ok = ArrayList<String>()
        for (id in ids) {
            val o = VaultStore.item(id) ?: continue
            if (exportOne(app, o)) ok.add(id)
        }
        if (ok.isEmpty()) return VaultItems.Result(false, msg = "Could not export. Is there free space? Nothing was removed from the vault.")
        if (!keep) {
            for (id in ok) VaultStore.removeItem(app, id, true)
            if (!VaultItems.sealed(app)) return VaultItems.Result(true, ok.size, msg = "Exported, but the vault list could not update. Repeat to clean up.")
        }
        return VaultItems.Result(true, ok.size, removed = if (keep) 0 else ok.size, msg = if (ok.size < ids.size) "${ids.size - ok.size} could not be exported and stayed in the vault." else "")
    }
}
