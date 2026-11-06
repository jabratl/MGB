package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * HOME SHORTCUTS (0.0.230, P6): (1) pinned Android home-screen shortcut per social site: tap = this app opens, switches Social sites
 * (master + that site) ON and opens the site start page in a new tab. (2) optional tiles on the browser Home page = normal
 * Bookmarks home entries (no new store). The shortcut is an explicit intent to MainActivity (action ACTION + extra EXTRA), so the
 * manifest needs no new intent-filter.
 */
object SocialShortcuts {
    const val ACTION = "com.jabra.mgb.SOCIAL_OPEN"
    private const val EXTRA = "mgb_social_id"

    private fun intentFor(ctx: Context, id: String): Intent =
        Intent(ctx, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA, id)
            // reuse the running browser (onNewIntent) instead of starting a second one
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Called from MainActivity (cold start + onNewIntent). Returns true when the intent was ours. Handled once: action is cleared. */
    fun fromIntent(act: Activity, tabs: TabManager, intent: Intent?): Boolean {
        if (intent == null || intent.action != ACTION) return false
        val id = intent.getStringExtra(EXTRA)
        intent.action = null
        intent.removeExtra(EXTRA) // a recreate (rotation, theme) must not open the tab again
        val p = id?.let { SocialProfiles.byId(it) } ?: return true
        if (!SocialProfiles.siteOn(act, p.id)) SocialProfiles.setSiteOn(act, p.id, true)
        SocialMode.open(act, tabs, p.startUrl)
        return true
    }

    /** Round-less square icon (adaptive bitmap): violet background + first letter of the site name. */
    private fun icon(ctx: Context, label: String): IconCompat {
        val s = 432
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ctx.getColor(R.color.mg_primary))
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.getColor(R.color.mg_on_primary)
            textSize = 190f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        c.drawText(label.trim().take(1).uppercase(), s / 2f, s / 2f - (t.ascent() + t.descent()) / 2f, t)
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }

    /** Ask the launcher to pin a shortcut for this site (the launcher shows its own confirm). */
    fun pin(act: Activity, p: SocialProfiles.Profile) {
        try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(act)) {
                Util.toast(act, "This launcher cannot pin shortcuts"); return
            }
            val info = ShortcutInfoCompat.Builder(act, "social_" + p.id)
                .setShortLabel(p.label).setLongLabel(p.label)
                .setIcon(icon(act, p.label))
                .setIntent(intentFor(act, p.id))
                .build()
            if (!ShortcutManagerCompat.requestPinShortcut(act, info, null)) Util.toast(act, "Could not add the shortcut")
        } catch (e: Exception) {
            Util.toast(act, "Could not add the shortcut")
        }
    }

    /** List of all sites; tap one = pin it. */
    fun pickPin(act: Activity) {
        val list = SocialProfiles.all
        MaterialAlertDialogBuilder(act)
            .setTitle("Add to home screen")
            .setItems(list.map { it.label }.toTypedArray()) { _, i -> pin(act, list[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Tick sites = add them as tiles on the browser Home page (Bookmarks home list; ones already there are marked). */
    fun pickTiles(act: Activity) {
        val list = SocialProfiles.all
        val has = list.map { Bookmarks.isHome(it.startUrl) }
        val checked = BooleanArray(list.size)
        MaterialAlertDialogBuilder(act)
            .setTitle("Add tiles to Home")
            .setMultiChoiceItems(list.indices.map { list[it].label + if (has[it]) " (on Home)" else "" }.toTypedArray(), checked) { _, i, v ->
                checked[i] = v
            }
            .setPositiveButton("Add") { _, _ ->
                var n = 0
                for (i in list.indices) if (checked[i] && !has[i]) { Bookmarks.addHome(list[i].label, list[i].startUrl); n++ }
                if (n == 0) Util.toast(act, "Nothing added")
                else Util.toast(act, if (SocialMode.on(act)) "Added $n tile(s) to Home"
                    else "Added $n tile(s). Social sites is OFF: tiles open as normal pages")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
