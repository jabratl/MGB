package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.298 "Add to home screen" (Chrome: menu > Add to Home screen > Create shortcut).
 * Page menu + tab card menu -> name box (page title) -> the launcher pins an icon. Tap on that icon = Thazir opens the page in a NEW tab
 * (or comes to the front first when it already runs). Icon = the site's saved favicon on a light rounded tile, else a letter on the accent.
 * Only http / https, never from a private tab. Not a PWA: no own window, no manifest install (use "Site window" for that, social sites only).
 * The shortcut is an explicit intent (action ACTION + data = url) to MainActivity, so the manifest needs no new intent-filter.
 */
object HomeShortcut {
    const val ACTION = "com.jabra.mgb.OPEN_SITE"

    private fun intentFor(ctx: Context, url: String): Intent =
        Intent(ctx, MainActivity::class.java).setAction(ACTION).setData(Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Called from MainActivity (cold start + onNewIntent). true = the intent was ours. Handled once (action + data are cleared, so a rotation does not reopen it). */
    fun fromIntent(act: Activity, tabs: TabManager, i: Intent?): Boolean {
        if (i == null || i.action != ACTION) return false
        val u = i.dataString.orEmpty()
        i.action = null
        i.data = null
        if (u.startsWith("http://") || u.startsWith("https://")) tabs.newTab(u)
        return true
    }

    /** Menu entry point. Does nothing for private / extension / Home tabs (callers already hide the row). */
    fun add(act: Activity, url: String, title: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) { Util.toast(act, "Only web pages can be added"); return }
        val host = Util.host(url)
        val box = EditText(act).apply {
            setText(title.ifBlank { host }.take(40)); setSelectAllOnFocus(true); setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(act).apply { setPadding(pad, pad / 2, pad, 0); addView(box) }
        MaterialAlertDialogBuilder(act)
            .setTitle("Add to home screen")
            .setMessage(host)
            .setView(wrap)
            .setPositiveButton("Add") { _, _ -> pin(act, url, box.text.toString().trim().ifBlank { host }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pin(act: Activity, url: String, name: String) {
        try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(act)) {
                Util.toast(act, "This launcher cannot pin shortcuts"); return
            }
            val info = ShortcutInfoCompat.Builder(act, "site_" + Integer.toHexString(url.hashCode()))
                .setShortLabel(name.take(24)).setLongLabel(name.take(60))
                .setIcon(icon(act, Util.host(url), name))
                .setIntent(intentFor(act, url))
                .build()
            if (ShortcutManagerCompat.requestPinShortcut(act, info, null)) Util.toast(act, "Confirm on your home screen")
            else Util.toast(act, "Could not add the shortcut")
        } catch (e: Exception) {
            Util.toast(act, "Could not add the shortcut")
        }
    }

    /** Adaptive icon bitmap (108 dp grid, the visible part is the middle 66%): favicon on a light tile, else first letter on the accent colour. */
    private fun icon(ctx: Context, host: String, name: String): IconCompat {
        val s = 432
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fav = Favicons.get(host)
        if (fav != null) {
            c.drawColor(0xFFF2F2F5.toInt())
            val box = s * 0.46f
            val r = box / maxOf(fav.width, fav.height)
            val w = fav.width * r; val h = fav.height * r
            c.drawBitmap(fav, null, RectF((s - w) / 2f, (s - h) / 2f, (s + w) / 2f, (s + h) / 2f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawColor(ctx.getColor(R.color.mg_primary))
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ctx.getColor(R.color.mg_on_primary); textSize = 190f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER
            }
            val ch = (name.ifBlank { host }).trim().take(1).uppercase()
            c.drawText(ch, s / 2f, s / 2f - (t.ascent() + t.descent()) / 2f, t)
        }
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}
