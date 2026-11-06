package com.jabra.mgb

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * V8: plays vault audio / video without a plain copy on disk (API 26+).
 * Not exported content provider "<applicationId>.vaultplay". Uri = content://<auth>/<random token>/<name>. The token (created per play, in memory only)
 * maps to a vault item id. openFile() returns a PROXY file descriptor (StorageManager.openProxyFileDescriptor): every read the player makes
 * lands in onRead(offset, size), which decrypts exactly those bytes with VaultCrypto.BlobReader.readAt (random access, 64 KiB chunks, nothing is
 * written to disk). Locked vault => no uri is served, open readers fail with EIO, tokens are cleared (VaultStore.lock -> revoke()).
 * API 24-25 (no proxy fds) and the "Play with temp copy" menu row use the V6/V7 temp decrypt (VaultFiles.openFile forceTemp).
 */
class VaultPlay : ContentProvider() {

    private class Tok(val id: String, val mime: String, val name: String)

    companion object {
        private val tokens = ConcurrentHashMap<String, Tok>()
        private var thread: HandlerThread? = null
        private val EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

        @Synchronized private fun handler(): Handler {
            val t = thread ?: HandlerThread("vaultplay").also { it.start(); thread = it }
            return Handler(t.looper)
        }

        fun supported() = Build.VERSION.SDK_INT >= 26

        fun isMedia(o: JSONObject): Boolean {
            val m = o.optString("mime").lowercase()
            if (m.startsWith("video/") || m.startsWith("audio/")) return true
            return o.optString("title").substringAfterLast('.', "").lowercase() in EXT
        }

        /** Playable uri for vault item [o] (null when the vault is locked). */
        fun uriFor(c: Context, o: JSONObject, name: String, mime: String): Uri? {
            if (!VaultStore.isUnlocked()) return null
            if (tokens.size > 50) tokens.clear()
            val tok = VaultCrypto.newId()
            tokens[tok] = Tok(o.optString("id"), mime, name)
            return Uri.Builder().scheme("content").authority(c.packageName + ".vaultplay").appendPath(tok).appendPath(name).build()
        }

        /** Forget every token (vault locked). */
        fun revoke() { tokens.clear() }
    }

    override fun onCreate(): Boolean = true

    private fun tok(uri: Uri): Tok = tokens[uri.pathSegments.firstOrNull() ?: ""] ?: throw FileNotFoundException("gone")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" || !supported()) throw FileNotFoundException("not available")
        val t = tok(uri)
        val ctx = context ?: throw FileNotFoundException("no context")
        if (!VaultStore.isUnlocked()) throw FileNotFoundException("locked")
        val item = VaultStore.item(t.id) ?: throw FileNotFoundException("gone")
        val r = try { VaultStore.openBlobIn(ctx, item.optString("blob")) } catch (e: Exception) { null } ?: throw FileNotFoundException("blob")
        return proxy(ctx, r)
    }

    @RequiresApi(26)
    private fun proxy(ctx: Context, r: VaultCrypto.BlobReader): ParcelFileDescriptor {
        try {
            val sm = ctx.getSystemService(StorageManager::class.java)
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Cb(r), handler())
        } catch (e: Exception) {
            try { r.close() } catch (x: Exception) { }
            throw FileNotFoundException("proxy")
        }
    }

    @RequiresApi(26)
    private class Cb(private val r: VaultCrypto.BlobReader) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = r.plainSize

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (!VaultStore.isUnlocked()) throw ErrnoException("onRead", OsConstants.EIO)
            try {
                val want = minOf(size, data.size)
                var got = 0
                while (got < want) {
                    val n = r.readAt(offset + got, data, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                return got
            } catch (e: Exception) { throw ErrnoException("onRead", OsConstants.EIO) }
        }

        override fun onRelease() { try { r.close() } catch (e: Exception) { } }
    }

    override fun getType(uri: Uri): String? = try { tok(uri).mime } catch (e: Exception) { null }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val t = try { tok(uri) } catch (e: Exception) { return null }
        val size = VaultStore.item(t.id)?.optLong("size", -1L) ?: -1L
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { if (it == OpenableColumns.DISPLAY_NAME) t.name else if (it == OpenableColumns.SIZE) size else null }.toTypedArray()
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
