package com.jabra.mgb

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * V2: the vault container (storage + state). No UI. Crypto is in [VaultCrypto]; the old [Vault] object is untouched.
 *
 * The container is a folder the user picked with the system folder picker (SAF tree uri). It lives OUTSIDE the app, so it survives app
 * updates and uninstall. Layout (see VaultCrypto for the byte formats):
 *   vault.hdr      JSON header with the wrapped keys
 *   index.enc      encrypted JSON {v, items:[...], folders:[...]}; index.bak = previous good copy; index.tmp only while saving
 *   d/<16hex>.bin  encrypted blobs (saved pages, files); names are random ids, titles live only inside the encrypted index
 *
 * Items are plain JSONObjects (unknown fields are kept when saved). Agreed fields: id, type "bm"|"hist"|"page"|"file", title, url, time,
 * folder, size, mime, blob ("<id>.bin" or ""), orig (JSONObject: name, rel). V4+ wrap them in a list UI.
 *
 * Threading: everything that touches the folder BLOCKS, call it off the main thread. state() and lock() are cheap.
 * State: NONE (no folder chosen) | FOLDER_MISSING (folder / permission / header gone: never auto-creates a new vault) | LOCKED | UNLOCKED.
 */
object VaultStore {
    enum class State { NONE, FOLDER_MISSING, LOCKED, UNLOCKED }
    class Res(val ok: Boolean, val msg: String = "", val recovery: String = "")

    private const val PREFS = "mgb_vault"
    private const val HDR = "vault.hdr"
    private const val IDX = "index.enc"
    private const val IDX_TMP = "index.tmp"
    private const val IDX_BAK = "index.bak"
    private const val DATA = "d"
    const val INDEX_V = 1
    private const val BIN = "application/octet-stream"
    private val DIR = DocumentsContract.Document.MIME_TYPE_DIR

    private val ioLock = Any()
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vault-io").also { it.isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    private var dek: ByteArray? = null
    private var index: JSONObject? = null
    private var header: VaultCrypto.Header? = null
    private var dataUri: Uri? = null
    private var leftAt = 0L
    @Volatile private var dirty = false
    @Volatile private var gen = 0L // V4: bumped by every change so a flush that started earlier cannot clear "dirty" for newer changes
    @Volatile private var folderOk = true
    @Volatile var lastError = ""
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun treeUri(c: Context): Uri? = prefs(c).getString("tree", null)?.let { Uri.parse(it) }
    private fun rootDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    // ---------- state ----------

    fun state(c: Context): State = when {
        treeUri(c) == null -> State.NONE
        !folderOk -> State.FOLDER_MISSING
        dek == null -> State.LOCKED
        else -> State.UNLOCKED
    }

    fun isUnlocked(): Boolean = dek != null

    /** BLOCKING. Re-checks permission + vault.hdr and updates the state. Call when the vault screen opens. */
    fun check(c: Context): State {
        val tree = treeUri(c) ?: return State.NONE
        folderOk = try {
            val perm = c.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
            perm && child(c, tree, rootDoc(tree), HDR) != null
        } catch (e: Exception) { false }
        if (!folderOk) lastError = "The vault folder or its permission is gone. Pick the same folder again."
        return state(c)
    }

    // ---------- SAF helpers ----------

    private class Doc(val uri: Uri, val isDir: Boolean)

    private fun child(c: Context, tree: Uri, parent: Uri, name: String): Doc? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        c.contentResolver.query(
            kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { q ->
            while (q.moveToNext()) {
                if (q.getString(1) == name) return Doc(DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)), q.getString(2) == DIR)
            }
        }
        return null
    }

    private fun makeFile(c: Context, parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(c.contentResolver, parent, BIN, name) ?: throw IOException("cannot create $name")

    private fun readAll(c: Context, uri: Uri): ByteArray =
        c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("cannot read")

    private fun writeAll(c: Context, uri: Uri, data: ByteArray) {
        (c.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot write")).use { it.write(data); it.flush() }
    }

    private fun del(c: Context, uri: Uri) {
        try { DocumentsContract.deleteDocument(c.contentResolver, uri) } catch (e: Exception) { }
    }

    private fun dataDir(c: Context): Uri {
        dataUri?.let { return it }
        val tree = treeUri(c) ?: throw IOException("no folder")
        val root = rootDoc(tree)
        val ex = child(c, tree, root, DATA)
        val u = if (ex != null && ex.isDir) ex.uri
        else DocumentsContract.createDocument(c.contentResolver, root, DIR, DATA) ?: throw IOException("cannot create data folder")
        dataUri = u
        return u
    }

    private fun key(): ByteArray = dek ?: throw IllegalStateException("vault is locked")

    private fun readHeader(c: Context): VaultCrypto.Header {
        val tree = treeUri(c) ?: throw IOException("no folder")
        val d = child(c, tree, rootDoc(tree), HDR) ?: throw IOException("vault.hdr not found")
        val h = VaultCrypto.Header.parse(String(readAll(c, d.uri), Charsets.UTF_8))
        if (h.v > VaultCrypto.HDR_V) throw IOException("NEWER")
        return h
    }

    // ---------- bind / create / unlock / lock ----------

    /** Remember the folder the user picked (persistable permission). Does not create anything. */
    fun bind(c: Context, uri: Uri): Boolean {
        val app = c.applicationContext
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { lastError = "This folder cannot be used, pick another one."; return false }
        val old = prefs(app).getString("tree", null)
        lock(app)
        prefs(app).edit().putString("tree", uri.toString()).remove("qk").apply()
        dataUri = null; header = null; folderOk = true
        // do not release a folder the download feature also uses
        if (old != null && old != uri.toString() && old != DownloadFolder.treeUri(app)?.toString()) {
            try { app.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
        }
        return true
    }

    /** Forget the folder (does NOT delete anything in it). */
    fun unbind(c: Context) {
        val app = c.applicationContext
        lock(app)
        prefs(app).edit().remove("tree").remove("qk").apply()
        dataUri = null; header = null; folderOk = true
    }

    /** BLOCKING. New vault in the bound folder. Refuses when a vault.hdr is already there. Returns the recovery key (show once). */
    fun create(c: Context, pass: CharArray): Res {
        val app = c.applicationContext
        val tree = treeUri(app) ?: return Res(false, "Pick a folder first.")
        var hdrUri: Uri? = null
        try {
            val root = rootDoc(tree)
            if (child(app, tree, root, HDR) != null) return Res(false, "A vault already exists in this folder. Use restore instead.")
            val cr = VaultCrypto.create(pass)
            hdrUri = makeFile(app, root, HDR)
            writeAll(app, hdrUri, cr.header.toJson().toString().toByteArray(Charsets.UTF_8))
            dataDir(app)
            val idx = JSONObject().put("v", INDEX_V).put("items", JSONArray()).put("folders", JSONArray())
            val iu = makeFile(app, root, IDX)
            writeAll(app, iu, VaultCrypto.encStr(cr.dek, idx.toString()).toByteArray(Charsets.UTF_8))
            synchronized(this) { dek = cr.dek; index = idx; header = cr.header; dirty = false }
            folderOk = true; leftAt = System.currentTimeMillis()
            return Res(true, "", cr.recovery)
        } catch (e: Exception) {
            hdrUri?.let { del(app, it) }
            lastError = "Could not create the vault: " + (e.message ?: e.javaClass.simpleName)
            return Res(false, lastError)
        } finally { VaultCrypto.wipe(pass) }
    }

    /** BLOCKING. Unlock with the passphrase. */
    fun unlock(c: Context, pass: CharArray): Res = unlockWith(c) { h -> VaultCrypto.openPass(h, pass) }.also { VaultCrypto.wipe(pass) }

    /** BLOCKING. Unlock with the recovery key (after reinstall or forgotten passphrase). */
    fun unlockRecovery(c: Context, recovery: String): Res = unlockWith(c) { h -> VaultCrypto.openRecovery(h, recovery) }

    /** BLOCKING. Unlock with a DEK from the per-install quick-unlock wrap (V3). The array is copied, caller wipes its own. */
    fun unlockWithKey(c: Context, key: ByteArray): Res = unlockWith(c) { h -> if (VaultCrypto.verifyDek(h, key)) key.copyOf() else null }

    private fun unlockWith(c: Context, open: (VaultCrypto.Header) -> ByteArray?): Res {
        val app = c.applicationContext
        if (treeUri(app) == null) return Res(false, "No vault folder chosen.")
        try {
            val h = readHeader(app)
            val k = open(h) ?: return Res(false, "Wrong passphrase or key.")
            val idx = loadIndex(app, k)
            if (idx == null) { VaultCrypto.wipe(k); return Res(false, lastError.ifEmpty { "The vault index cannot be read." }) }
            synchronized(this) { dek = k; index = idx; header = h; dirty = false }
            folderOk = true; leftAt = System.currentTimeMillis()
            return Res(true)
        } catch (e: Exception) {
            if (e.message == "NEWER") lastError = "This vault was made by a newer Thazir. Update the app first."
            else { folderOk = false; lastError = "Cannot open the vault folder. Pick the same folder again." }
            return Res(false, lastError)
        }
    }

    private fun loadIndex(c: Context, k: ByteArray): JSONObject? {
        val tree = treeUri(c) ?: return null
        val root = rootDoc(tree)
        for (name in arrayOf(IDX, IDX_BAK)) {
            try {
                val d = child(c, tree, root, name) ?: continue
                val o = JSONObject(VaultCrypto.decStr(k, String(readAll(c, d.uri), Charsets.UTF_8)) ?: continue)
                if (o.optInt("v", 1) > INDEX_V) { lastError = "This vault was made by a newer Thazir. Update the app first."; return null }
                return migrate(o)
            } catch (e: Exception) { /* try the backup */ }
        }
        return null
    }

    /** Schema upgrades go here (index v1 is current). Unknown fields stay because the JSON is kept whole. */
    private fun migrate(o: JSONObject): JSONObject {
        if (!o.has("items")) o.put("items", JSONArray())
        if (!o.has("folders")) o.put("folders", JSONArray())
        o.put("v", INDEX_V)
        return o
    }

    /** Wipe the key from memory. Saves unsaved changes first (blocking only when something is unsaved). */
    fun lock(c: Context?) {
        if (dirty && c != null && dek != null) { try { flush(c) } catch (e: Exception) { } }
        pending?.cancel(false)
        synchronized(this) { VaultCrypto.wipe(dek); dek = null; index = null }
    }

    // ---------- per-install quick unlock + screen privacy (V3) ----------

    /**
     * Quick unlock: the DEK is wrapped with the Android Keystore through the existing [Vault] helper and kept in prefs "mgb_vault" (key "qk").
     * It dies with the app on uninstall, so after a reinstall the passphrase or recovery key is needed once. The caller must ask the user
     * to authenticate (Vault.unlock) before using [quickKey]; only offer it when AppLock.available() is true.
     */
    fun quickOn(c: Context): Boolean = prefs(c).getString("qk", null) != null

    fun quickEnable(c: Context): Boolean {
        val k = dek ?: return false
        return try {
            prefs(c).edit().putString("qk", Vault.encrypt(Base64.encodeToString(k, Base64.NO_WRAP))).apply()
            true
        } catch (e: Exception) { false }
    }

    fun quickDisable(c: Context) { prefs(c).edit().remove("qk").apply() }

    /** The wrapped DEK, or null. Caller wipes the array. */
    fun quickKey(c: Context): ByteArray? = try {
        prefs(c).getString("qk", null)?.let { Vault.decrypt(it) }?.let { Base64.decode(it, Base64.NO_WRAP) }
    } catch (e: Exception) { null }

    /** Blank recents preview + no screenshots while the vault screen is open (default ON). */
    fun hideRecents(c: Context): Boolean = prefs(c).getBoolean("hide_recents", true)
    fun setHideRecents(c: Context, on: Boolean) { prefs(c).edit().putBoolean("hide_recents", on).apply() }

    /** V4: true when everything changed so far is on disk (set only after flush wrote and read back the index). */
    fun isSaved(): Boolean = !dirty

    /** V5: "Send history to vault" (default off). While the vault is open, browser history moves into it when the app is left. */
    fun histAuto(c: Context): Boolean = prefs(c).getBoolean("hist_auto", false)
    fun setHistAuto(c: Context, on: Boolean) { prefs(c).edit().putBoolean("hist_auto", on).apply() }

    // ---------- auto-lock ----------

    fun autoLockMs(c: Context): Long = prefs(c).getLong("autolock_ms", 120_000L)
    fun setAutoLockMs(c: Context, ms: Long) { prefs(c).edit().putLong("autolock_ms", ms).apply() }

    /** Call from MainActivity.onStop (wired in V3). */
    fun onAppStop(c: Context) {
        VaultFiles.wipeTemp(c) // V6: decrypted saved pages never stay in cache
        if (dek == null) return
        VaultItems.sweepHistory(c) // V5: no-op unless "Send history to vault" is on; runs on its own thread
        leftAt = System.currentTimeMillis()
        if (autoLockMs(c) <= 0L) lock(c)
    }

    /** Call from MainActivity.onStart (wired in V3). Locks when the app was away longer than the delay. */
    fun onAppStart(c: Context) {
        VaultFiles.wipeTemp(c) // V6
        if (dek == null) return
        if (System.currentTimeMillis() - leftAt > autoLockMs(c)) lock(c)
    }

    // ---------- index (items + folders) ----------

    /** Read-only view: do not change the returned objects, use [updateItem]. Newest first is the caller's job. */
    @Synchronized fun items(type: String? = null): List<JSONObject> {
        val a = index?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (type == null || o.optString("type") == type) out.add(o)
        }
        return out
    }

    @Synchronized fun item(id: String): JSONObject? = items().firstOrNull { it.optString("id") == id }

    @Synchronized fun count(type: String): Int = items(type).size

    @Synchronized fun bytes(type: String): Long = items(type).sumOf { it.optLong("size", 0L) }

    /** Adds the item (id and time are filled in when missing), saves soon. Returns the id, or "" when locked. */
    fun addItem(c: Context, o: JSONObject): String {
        val id: String
        synchronized(this) {
            val a = index?.optJSONArray("items") ?: return ""
            if (o.optString("id").isEmpty()) o.put("id", VaultCrypto.newId())
            if (!o.has("time")) o.put("time", System.currentTimeMillis())
            id = o.getString("id")
            a.put(o)
        }
        saveSoon(c)
        return id
    }

    fun updateItem(c: Context, o: JSONObject): Boolean {
        var found = false
        synchronized(this) {
            val a = index?.optJSONArray("items") ?: return false
            for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == o.optString("id")) { a.put(i, o); found = true; break }
        }
        if (found) saveSoon(c)
        return found
    }

    /** Removes the item and, by default, its blob. */
    fun removeItem(c: Context, id: String, withBlob: Boolean = true): Boolean {
        var gone: JSONObject? = null
        synchronized(this) {
            val a = index?.optJSONArray("items") ?: return false
            for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == id) { gone = a.optJSONObject(i); a.remove(i); break }
        }
        val g = gone ?: return false
        saveSoon(c)
        if (withBlob && g.optString("blob").isNotEmpty()) deleteBlob(c, g.getString("blob"))
        return true
    }

    @Synchronized fun folders(): List<String> {
        val a = index?.optJSONArray("folders") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    fun addFolder(c: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        synchronized(this) {
            val a = index?.optJSONArray("folders") ?: return false
            if (folders().contains(n)) return true
            a.put(n)
        }
        saveSoon(c)
        return true
    }

    // ---------- saving the index ----------

    private fun saveSoon(c: Context) {
        gen++
        dirty = true
        val app = c.applicationContext
        pending?.cancel(false)
        pending = io.schedule(Runnable { try { flush(app) } catch (e: Exception) { } }, 600, TimeUnit.MILLISECONDS)
    }

    /** BLOCKING. Writes index.tmp, reads it back, then swaps: index.enc -> index.bak, index.tmp -> index.enc. Old data stays on any failure. */
    fun flush(c: Context) {
        synchronized(ioLock) {
            val k = dek ?: return
            val g = gen
            val text = synchronized(this) { index?.toString() } ?: return
            if (!dirty) return
            val app = c.applicationContext
            try {
                val tree = treeUri(app) ?: return
                val root = rootDoc(tree)
                val cr = app.contentResolver
                val enc = VaultCrypto.encStr(k, text)
                val tmp = child(app, tree, root, IDX_TMP)?.uri ?: makeFile(app, root, IDX_TMP)
                writeAll(app, tmp, enc.toByteArray(Charsets.UTF_8))
                if (VaultCrypto.decStr(k, String(readAll(app, tmp), Charsets.UTF_8)) != text) throw IOException("verify failed")
                child(app, tree, root, IDX_BAK)?.let { del(app, it.uri) }
                val cur = child(app, tree, root, IDX)
                if (cur != null) DocumentsContract.renameDocument(cr, cur.uri, IDX_BAK)
                val nu = try { DocumentsContract.renameDocument(cr, tmp, IDX) } catch (e: Exception) { null }
                if (nu == null) {
                    // roll back: the previous index becomes current again
                    child(app, tree, root, IDX_BAK)?.let { DocumentsContract.renameDocument(cr, it.uri, IDX) }
                    throw IOException("swap failed")
                }
                if (gen == g) dirty = false else Unit
            } catch (e: Exception) {
                lastError = "The vault could not save its list. Nothing was lost, it will retry."
                io.schedule(Runnable { try { flush(app) } catch (x: Exception) { } }, 8, TimeUnit.SECONDS)
            }
        }
    }

    // ---------- blobs ----------

    /** BLOCKING. New encrypted blob "<id>.bin". Write plain bytes to the stream, close() finishes it. Delete it with [deleteBlob] if the import fails. */
    fun openBlobOut(c: Context, id: String): VaultCrypto.BlobWriter {
        val app = c.applicationContext
        val k = key()
        val file = makeFile(app, dataDir(app), "$id.bin")
        val raw: OutputStream = app.contentResolver.openOutputStream(file, "w") ?: throw IOException("cannot write blob")
        return VaultCrypto.BlobWriter(raw, k)
    }

    /** BLOCKING. Random-access reader (seekable). Close it when done. Null when the blob is missing. */
    fun openBlobIn(c: Context, id: String): VaultCrypto.BlobReader? {
        val app = c.applicationContext
        val k = key()
        val tree = treeUri(app) ?: return null
        val d = child(app, tree, dataDir(app), "$id.bin") ?: return null
        return VaultCrypto.BlobReader(SafSource(app, d.uri), k)
    }

    fun blobExists(c: Context, id: String): Boolean {
        val app = c.applicationContext
        val tree = treeUri(app) ?: return false
        return try { child(app, tree, dataDir(app), "$id.bin") != null } catch (e: Exception) { false }
    }

    fun deleteBlob(c: Context, id: String) {
        val app = c.applicationContext
        try {
            val tree = treeUri(app) ?: return
            child(app, tree, dataDir(app), "$id.bin")?.let { del(app, it.uri) }
        } catch (e: Exception) { }
    }

    /** Positional reads over a SAF file descriptor (needs a file-backed provider; internal/SD storage folders are). */
    private class SafSource(c: Context, uri: Uri) : RandomSource {
        private val pfd = c.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open blob")
        private val fin = FileInputStream(pfd.fileDescriptor)
        private val ch = fin.channel
        override fun size(): Long = ch.size()
        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int = ch.read(ByteBuffer.wrap(dst, off, len), pos)
        override fun close() {
            try { fin.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }

    /** Header copy for V11 (change passphrase / recovery key writes the new header with [writeHeader]). */
    @Synchronized fun currentHeader(): VaultCrypto.Header? = header

    /** BLOCKING. Replace vault.hdr (write tmp name first is not possible in SAF, so the old text is kept and restored on failure). */
    fun writeHeader(c: Context, h: VaultCrypto.Header): Boolean {
        val app = c.applicationContext
        synchronized(ioLock) {
            val tree = treeUri(app) ?: return false
            val d = child(app, tree, rootDoc(tree), HDR) ?: return false
            val old = try { readAll(app, d.uri) } catch (e: Exception) { return false }
            return try {
                writeAll(app, d.uri, h.toJson().toString().toByteArray(Charsets.UTF_8))
                if (VaultCrypto.Header.parse(String(readAll(app, d.uri), Charsets.UTF_8)).wrapPass.isEmpty()) throw IOException("verify")
                synchronized(this) { header = h }
                true
            } catch (e: Exception) {
                try { writeAll(app, d.uri, old) } catch (x: Exception) { }
                false
            }
        }
    }
}
