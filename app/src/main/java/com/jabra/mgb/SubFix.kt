package com.jabra.mgb

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * FIX OLD SUBTITLE NAMES (0.0.279). Before this version Android added ".txt" to saved subtitles (Title.en.srt.txt).
 * run() renames those files back (Title.en.srt) in the Downloads folder. Only names that end in a subtitle extension + ".txt" are touched,
 * a file is never overwritten (a name that already exists is skipped), and a rename the phone refuses is counted as skipped.
 * Android 10+: MediaStore Downloads (name and type are updated together, then the new name is read back to be sure).
 * Android 9 and older: plain files in the public Downloads folder (needs the storage permission, else nothing is found).
 * NOT covered: a folder the person picked in Settings > Downloads > Download folder (message() says so).
 * Blocking: call from a background thread.
 */
object SubFix {
    private val SUB = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

    class Result(val found: Int, val fixed: Int, val skipped: Int)

    /** True for names like Title.en.srt.txt (a subtitle extension followed by .txt). */
    fun isOld(n: String): Boolean {
        val l = n.lowercase()
        if (!l.endsWith(".txt")) return false
        return l.removeSuffix(".txt").substringAfterLast('.', "") in SUB
    }

    fun run(c: Context): Result = try {
        if (Build.VERSION.SDK_INT >= 29) runQ(c) else runLegacy()
    } catch (e: Exception) {
        Result(0, 0, 0)
    }

    @RequiresApi(29)
    private fun runQ(c: Context): Result {
        val cr = c.contentResolver
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val rows = ArrayList<Triple<Long, String, String>>()
        cr.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
            MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", arrayOf("%.txt"), null)?.use { q ->
            while (q.moveToNext()) {
                val n = q.getString(1) ?: continue
                if (isOld(n)) rows.add(Triple(q.getLong(0), n, q.getString(2) ?: ""))
            }
        }
        var fixed = 0
        var skipped = 0
        for ((id, n, rel) in rows) {
            val want = n.dropLast(4)
            val uri = ContentUris.withAppendedId(base, id)
            try {
                var taken = false
                cr.query(base, arrayOf(MediaStore.MediaColumns._ID),
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    arrayOf(want, rel), null)?.use { taken = it.moveToFirst() }
                if (taken) { skipped++; continue }
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, want)
                    put(MediaStore.MediaColumns.MIME_TYPE, Downloader.safeMime(want, ""))
                }
                cr.update(uri, v, null, null)
                var real = ""
                cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) real = it.getString(0) ?: ""
                }
                if (real == want) fixed++ else skipped++
            } catch (e: Exception) {
                skipped++
            }
        }
        return Result(rows.size, fixed, skipped)
    }

    private fun runLegacy(): Result {
        var found = 0
        var fixed = 0
        var skipped = 0
        fun walk(d: File, depth: Int) {
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) { if (depth < 4) walk(f, depth + 1); continue }
                if (!isOld(f.name)) continue
                found++
                val target = File(f.parentFile, f.name.dropLast(4))
                if (target.exists()) { skipped++; continue }
                if (f.renameTo(target)) fixed++ else skipped++
            }
        }
        walk(DownloadsApi.publicDir(), 0)
        return Result(found, fixed, skipped)
    }

    /** Text for the toast after run(). */
    fun message(c: Context, r: Result): String {
        val picked = try { DownloadFolder.treeUri(c) != null } catch (e: Exception) { false }
        var s = if (r.found == 0) "No old subtitle names found in Downloads"
        else r.fixed.toString() + " fixed" + (if (r.skipped > 0) ", " + r.skipped + " skipped (name already used or the phone refused)" else "")
        if (picked) s += ". A folder you picked is not checked"
        return s
    }
}
