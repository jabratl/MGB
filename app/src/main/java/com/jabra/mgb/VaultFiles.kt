package com.jabra.mgb

import android.app.Activity
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * V6: saved pages in the vault (V7 adds downloaded files to this file).
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

    /** Safe to call from any thread, any time (cheap when the folder does not exist). */
    fun wipeTemp(c: Context) {
        try { tmpDir(c).listFiles()?.forEach { shred(it) } } catch (e: Exception) { }
    }

    private class Sha : OutputStream() {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        override fun write(b: Int) { md.update(b.toByte()) }
        override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len) }
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
}
