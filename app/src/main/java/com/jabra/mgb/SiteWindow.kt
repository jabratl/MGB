package com.jabra.mgb

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri

/**
 * OWN WINDOW PER SITE (0.0.247, A7, experimental). Switch sm_win (default OFF) in mgb_ui.
 * ON: the pinned home-screen shortcut (P6) and the Open button of the Social sites page start MainActivity as its own document task
 * (FLAG_ACTIVITY_NEW_DOCUMENT, one per site), so Recent apps shows one card per site (site name + letter icon). That window starts with
 * only that site (no session restore) and never saves the session, so the main browser tabs are not touched.
 * App-wide helpers (BrowserExtras, TabsApi, GroupStrip, EngineHost, DlFly) stay with the main window while it lives: see MainActivity
 * fields siteWin / shared. Known limit: uploads / camera / permission questions in a site window use the main window if it is alive.
 */
object SiteWindow {
    const val ACTION = "com.jabra.mgb.SITE_WINDOW"
    private const val EXTRA = "mgb_site_id"
    private const val KEY = "sm_win"

    /** true while a normal (main) MainActivity is alive; false when only site windows run. */
    @Volatile var mainUp = false
    /** number of site windows alive (MainActivity.onResume re-points tabsRef only when this is > 0). */
    @Volatile var live = 0

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    fun isSite(intent: Intent?): Boolean = intent?.action == ACTION
    private fun siteId(intent: Intent?): String? = intent?.getStringExtra(EXTRA)

    /** Intent for the site window; the data address makes the document task unique per site (same site again = same window). */
    fun intentFor(ctx: Context, id: String): Intent =
        Intent(ctx, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA, id)
            .setData(Uri.parse("mgbsite://" + id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    /** Start (or bring to front) the window of this site. False = could not start (caller falls back to a normal tab). */
    fun launch(act: Activity, p: SocialProfiles.Profile): Boolean = try {
        act.startActivity(intentFor(act, p.id)); true
    } catch (e: Exception) {
        android.util.Log.w("Thazir.win", "launch failed", e); false
    }

    /** MainActivity.onCreate of a site window: one tab with the site start page + Recent apps card name / icon. */
    fun start(act: Activity, tabs: TabManager, intent: Intent?, fresh: Boolean) {
        val p = siteId(intent)?.let { SocialProfiles.byId(it) }
        if (p == null) { tabs.newTab(TabManager.HOME_PAGE); return }
        if (!SocialProfiles.siteOn(act, p.id)) SocialProfiles.setSiteOn(act, p.id, true)
        describe(act, p.label)
        if (fresh) SocialSplash.show(act, p.label)
        SocialMode.open(act, tabs, p.startUrl)
    }

    /** Recent apps card: site name + violet letter icon (same look as the shortcut icon). */
    @Suppress("DEPRECATION")
    private fun describe(act: Activity, label: String) {
        try {
            val s = 96
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(act.getColor(R.color.mg_primary))
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = act.getColor(R.color.mg_on_primary)
                textSize = 44f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(label.trim().take(1).uppercase(), s / 2f, s / 2f - (t.ascent() + t.descent()) / 2f, t)
            act.setTaskDescription(ActivityManager.TaskDescription(label, bmp))
        } catch (e: Exception) {
            android.util.Log.w("Thazir.win", "task description failed", e)
        }
    }
}
