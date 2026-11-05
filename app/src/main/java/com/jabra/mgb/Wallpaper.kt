package com.jabra.mgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * APPEARANCE A7: home-page wallpaper. The picked image is downscaled (long side <= 1920, short side <= 1080)
 * and saved as filesDir/wallpaper.jpg (file exists = wallpaper on). Not part of the backup. EXIF rotation is not applied.
 */
object Wallpaper {
    /** HomeView registers here while it is on screen; called on the UI thread after save / remove / dim change. */
    val listeners = ArrayList<() -> Unit>()
    private var cache: Bitmap? = null
    private var stamp = ""

    fun notifyChanged() {
        val run = { for (l in ArrayList(listeners)) try { l() } catch (_: Exception) { } }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else Handler(Looper.getMainLooper()).post { run() }
    }

    /** The wallpaper bitmap, or null when there is none / it cannot be read. Cached until the file changes. */
    fun load(c: Context): Bitmap? {
        val f = AppearancePrefs.wallpaperFile(c)
        if (!f.exists()) { cache = null; stamp = ""; return null }
        val s = f.lastModified().toString() + ":" + f.length()
        if (cache != null && s == stamp) return cache
        return try {
            val b = BitmapFactory.decodeFile(f.absolutePath)
            cache = b; stamp = s
            b
        } catch (_: Throwable) { cache = null; null }
    }

    /** Decodes, downscales and saves the picked image. Call off the UI thread. */
    fun save(c: Context, uri: Uri): Boolean {
        return try {
            val cr = c.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val lng = max(bounds.outWidth, bounds.outHeight)
            val sht = min(bounds.outWidth, bounds.outHeight)
            var s = 1
            while (lng / (s * 2) >= 1920 && sht / (s * 2) >= 1080) s *= 2
            val opt = BitmapFactory.Options().apply { inSampleSize = s }
            val bm = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) } ?: return false
            val k = minOf(1f, 1920f / max(bm.width, bm.height), 1080f / min(bm.width, bm.height))
            val out = if (k < 1f) Bitmap.createScaledBitmap(bm, (bm.width * k).roundToInt().coerceAtLeast(1), (bm.height * k).roundToInt().coerceAtLeast(1), true) else bm
            FileOutputStream(AppearancePrefs.wallpaperFile(c)).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            cache = null; stamp = ""
            true
        } catch (_: Throwable) { false } // includes OutOfMemoryError for huge photos
    }

    fun remove(c: Context) {
        try { AppearancePrefs.wallpaperFile(c).delete() } catch (_: Exception) { }
        cache = null; stamp = ""
    }
}
