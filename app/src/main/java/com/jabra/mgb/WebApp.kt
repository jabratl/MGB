package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.View
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.0.299 "Install app" (Chrome: menu > Install app). A web page becomes an app icon on the home screen that opens in its OWN window
 * (own card in Recent apps, name + icon of the site), optionally without the browser bar (app style).
 * Reads the page's web app manifest (link rel=manifest): name / short_name, start_url, icons (+ maskable), theme_color, background_color, display.
 * No manifest or no network = page title + saved favicon, the page address as start. Window = SiteWindow.startApp (MainActivity as its own document task).
 * Not covered: service worker / offline install, scope rules (links to other sites stay in the same window), push, shortcuts[] of the manifest.
 */
object WebApp {
    private class Info(var name: String, var start: String, var icon: Bitmap? = null, var maskable: Boolean = false,
                       var theme: Int = 0, var bg: Int = 0, var hideBar: Boolean = false)

    private const val JS = "(function(){try{var l=document.querySelector('link[rel~=\"manifest\"]'),t=document.querySelector('meta[name=\"theme-color\"]');" +
        "return JSON.stringify({m:l?l.href:'',t:t?t.content:''})}catch(e){return '{}'}})()"

    fun iconFile(c: Context, id: String) = File(File(c.applicationContext.filesDir, "webapps"), id + ".png")

    /** Menu entry. web = the page's WebView (null when the tab is suspended: then no manifest, title + favicon only). */
    fun install(act: Activity, web: WebView?, url: String, title: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) { Util.toast(act, "Only web pages can be installed"); return }
        Util.toast(act, "Checking the site…")
        fun go(manifest: String, theme: String) {
            Thread {
                val info = try { load(url, title, manifest, theme) } catch (e: Throwable) { Info(title.ifBlank { Util.host(url) }, url) }
                act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) dialog(act, url, info) }
            }.start()
        }
        if (web == null) { go("", ""); return }
        try {
            web.evaluateJavascript(JS) { raw ->
                var m = ""; var t = ""
                try {
                    val o = JSONObject(JSONTokener(raw ?: "\"{}\"").nextValue() as? String ?: "{}")
                    m = o.optString("m"); t = o.optString("t")
                } catch (_: Exception) { }
                go(m, t)
            }
        } catch (e: Exception) { go("", "") }
    }

    private fun get(u: String, max: Int): ByteArray? {
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) return null
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) return null }
            }
            out.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun color(s: String): Int = try { if (s.isBlank()) 0 else Color.parseColor(s.trim()) or 0xFF000000.toInt() } catch (_: Exception) { 0 }
    private fun sameSite(a: String, b: String) = Util.host(a).equals(Util.host(b), true)

    private fun load(url: String, title: String, manifestUrl: String, themeMeta: String): Info {
        val info = Info(title.ifBlank { Util.host(url) }.take(40), url)
        info.theme = color(themeMeta)
        val raw = if (manifestUrl.isNotBlank()) get(manifestUrl, 300_000) else null
        if (raw != null) {
            val m = JSONObject(String(raw, Charsets.UTF_8))
            val n = m.optString("short_name").ifBlank { m.optString("name") }
            if (n.isNotBlank()) info.name = n.take(40)
            val su = m.optString("start_url")
            if (su.isNotBlank()) try { val r = URL(URL(manifestUrl), su).toString(); if (sameSite(r, url)) info.start = r } catch (_: Exception) { }
            if (info.theme == 0) info.theme = color(m.optString("theme_color"))
            info.bg = color(m.optString("background_color"))
            info.hideBar = m.optString("display") in setOf("standalone", "fullscreen", "minimal-ui")
            // icons: biggest PNG / WebP / JPEG up to 512 px (SVG cannot be decoded here), best 3 tried
            val arr = m.optJSONArray("icons")
            val cands = ArrayList<Triple<Int, String, Boolean>>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type"); val src = o.optString("src")
                if (src.isBlank() || type.contains("svg") || src.substringBefore('?').endsWith(".svg")) continue
                var px = 0
                for (t in o.optString("sizes").split(" ")) t.substringBefore('x').toIntOrNull()?.let { px = maxOf(px, it) }
                if (px == 0) px = 192
                if (px > 512) px = 256 // too big: still usable but ranked lower
                cands.add(Triple(px, try { URL(URL(manifestUrl), src).toString() } catch (_: Exception) { "" }, o.optString("purpose").contains("maskable")))
            }
            for ((_, u, mk) in cands.sortedByDescending { it.first }.take(3)) {
                if (u.isBlank()) continue
                val b = get(u, 1_500_000)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: continue
                info.icon = b; info.maskable = mk; break
            }
        }
        return info
    }

    private fun dialog(act: Activity, pageUrl: String, info: Info) {
        val d = act.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val box = EditText(act).apply {
            setText(info.name); setSelectAllOnFocus(true); setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val hide = CheckBox(act).apply { text = "Hide the browser bar (app style)"; isChecked = info.hideBar }
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(box); addView(hide)
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Install app")
            .setMessage(Util.host(info.start) + if (info.icon == null) "" else "  ·  icon from the site")
            .setView(wrap)
            .setPositiveButton("Install") { _, _ -> pin(act, info, box.text.toString().trim().ifBlank { info.name }, hide.isChecked) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pin(act: Activity, info: Info, name: String, hideBar: Boolean) {
        try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(act)) { Util.toast(act, "This launcher cannot pin shortcuts"); return }
            val id = "app_" + Integer.toHexString(info.start.hashCode())
            val host = Util.host(info.start)
            info.icon?.let { b -> try { val f = iconFile(act, id); f.parentFile?.mkdirs(); java.io.FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) { } }
            val sc = ShortcutInfoCompat.Builder(act, id)
                .setShortLabel(name.take(24)).setLongLabel(name.take(60))
                .setIcon(tile(act, info, host, name))
                .setIntent(SiteWindow.appIntent(act, id, info.start, name, hideBar, info.theme))
                .build()
            if (ShortcutManagerCompat.requestPinShortcut(act, sc, null)) Util.toast(act, "Confirm on your home screen")
            else Util.toast(act, "Could not add the app")
        } catch (e: Exception) { Util.toast(act, "Could not add the app") }
    }

    /** Adaptive bitmap (108 dp grid, 432 px): maskable icon = full bleed; normal icon = on the site's background colour (or light) at ~60%. No icon = favicon / letter tile. */
    private fun tile(ctx: Context, info: Info, host: String, name: String): IconCompat {
        val ic = info.icon ?: return HomeShortcut.icon(ctx, host, name)
        val s = 432
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (info.maskable) c.drawBitmap(ic, null, RectF(0f, 0f, s.toFloat(), s.toFloat()), p)
        else {
            c.drawColor(if (info.bg != 0) info.bg else 0xFFF2F2F5.toInt())
            val box = s * 0.58f; val r = box / maxOf(ic.width, ic.height)
            val w = ic.width * r; val h = ic.height * r
            c.drawBitmap(ic, null, RectF((s - w) / 2f, (s - h) / 2f, (s + w) / 2f, (s + h) / 2f), p)
        }
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}
