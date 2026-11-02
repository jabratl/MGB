package com.jabra.mgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/**
 * S45: real site icons. WebChromeClient.onReceivedIcon -> one small PNG per host in filesDir/favicons.
 * Used by the home tiles, the "Jump back in" chips and the tab switcher. Private tabs never save an icon.
 */
object Favicons {
    private lateinit var dir: File
    private val mem = LruCache<String, Bitmap>(80)
    private val none = HashSet<String>() // hosts we looked up and found nothing for (cleared on save)

    fun init(ctx: Context) {
        dir = File(ctx.applicationContext.filesDir, "favicons").also { it.mkdirs() }
    }

    private fun file(host: String) = File(dir, host.lowercase().replace(Regex("[^a-z0-9.-]"), "_") + ".png")

    fun save(host: String, icon: Bitmap) {
        if (!::dir.isInitialized || host.isBlank() || icon.width < 8 || icon.height < 8) return
        try {
            val s = 96
            val bmp = if (icon.width > s || icon.height > s) {
                val r = s.toFloat() / maxOf(icon.width, icon.height)
                Bitmap.createScaledBitmap(icon, maxOf(1, (icon.width * r).toInt()), maxOf(1, (icon.height * r).toInt()), true)
            } else icon
            FileOutputStream(file(host)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            synchronized(none) { none.remove(host) }
            mem.put(host.lowercase(), bmp)
        } catch (e: Exception) { /* icons are optional */ }
    }

    fun get(host: String): Bitmap? {
        if (!::dir.isInitialized || host.isBlank()) return null
        val k = host.lowercase()
        mem.get(k)?.let { return it }
        synchronized(none) { if (none.contains(k)) return null }
        return try {
            val f = file(k)
            val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            if (b != null) mem.put(k, b) else synchronized(none) { none.add(k) }
            b
        } catch (e: Exception) { null }
    }

    fun clear() {
        try { dir.listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
        mem.evictAll()
        synchronized(none) { none.clear() }
    }
}
