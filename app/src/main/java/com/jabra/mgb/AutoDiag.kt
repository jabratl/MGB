package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar

/**
 * S87.2: auto Diagnose pill. Watches the Diag log (S82) while the app is in front and shows one small
 * bar with a Diagnose button when something clearly went wrong:
 *  - a tab crashed or was killed (Diag tag "tab", level E)
 *  - the same download url failed twice in 10 minutes (Diag tag "download", msg "failed ...", extra = url)
 *  - the grabber panel found nothing (Diag tag "grabber", written by DetectedPanel)
 * Limits: at most one pill per 5 minutes; swiping the pill away = quiet for 1 hour.
 * Switch: prefs mgb_ui auto_diag (default ON). Reads the log only, never page content.
 */
object AutoDiag {
    private const val PREFS = "mgb_ui"
    private const val KEY_ON = "auto_diag"
    private const val KEY_QUIET = "auto_diag_quiet"
    private const val KEY_LAST = "auto_diag_last"
    private const val TICK_MS = 4000L
    private const val GAP_MS = 5 * 60 * 1000L
    private const val QUIET_MS = 60 * 60 * 1000L
    private const val TEN_MIN = 10 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var act: Activity? = null
    private var tabs: TabManager? = null
    private var seenTs = 0L
    private var pending: Trigger? = null
    private var running = false

    private class Trigger(val text: String, val focusText: String?, val focusUrl: String)

    fun on(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setOn(c: Context, v: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, v).apply()
        if (!v) pending = null
    }

    /** Called from MainActivity.onStart. Events older than this moment never raise a pill. */
    fun start(a: Activity, t: TabManager) {
        act = a
        tabs = t
        if (seenTs == 0L) seenTs = System.currentTimeMillis()
        if (running) return
        running = true
        main.postDelayed(tick, TICK_MS)
    }

    /** Called from MainActivity.onStop / onDestroy. */
    fun stop() {
        running = false
        main.removeCallbacks(tick)
        act = null
        tabs = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { check() } catch (_: Throwable) { }
            if (running) main.postDelayed(this, TICK_MS)
        }
    }

    private fun check() {
        val a = act ?: return
        if (a.isFinishing || a.isDestroyed) return
        // 1) read new events (always, so old ones never come back later)
        val now = System.currentTimeMillis()
        val recent = Diag.recent(100, null, true)
        val fresh = recent.filter { it.ts > seenTs }
        if (recent.isNotEmpty()) seenTs = maxOf(seenTs, recent.maxOf { it.ts })
        if (fresh.isNotEmpty()) {
            val p = pick(fresh, recent, now)
            if (p != null) pending = p
        }
        val p = pending ?: return
        // 2) gates: switch, quiet time, gap between pills
        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!on(a) || now < prefs.getLong(KEY_QUIET, 0L) || now - prefs.getLong(KEY_LAST, 0L) < GAP_MS) {
            pending = null
            return
        }
        // 3) wait while a dialog / sheet / screen has the focus (the pill would hide under it)
        if (!a.window.decorView.hasWindowFocus()) return
        pending = null
        show(a, p, prefs)
    }

    /** Crash first, then a repeated download failure, then an empty grabber. */
    private fun pick(fresh: List<Diag.Event>, recent: List<Diag.Event>, now: Long): Trigger? {
        if (fresh.any { it.tag == "tab" && it.level == "E" }) {
            return Trigger("Tab crashed. Find out why?", null, "")
        }
        for (e in fresh) {
            if (e.tag != "download" || !e.msg.startsWith("failed") || e.extra.isEmpty()) continue
            var n = 0
            for (r in recent) if (r.tag == "download" && r.msg.startsWith("failed") && r.extra == e.extra && now - r.ts <= TEN_MIN) n += r.n
            if (n >= 2) return Trigger("This download failed twice. Find out why?", e.msg, e.extra)
        }
        if (fresh.any { it.tag == "grabber" }) {
            return Trigger("Nothing found on this page. Find out why?", null, "")
        }
        return null
    }

    private fun show(a: Activity, p: Trigger, prefs: android.content.SharedPreferences) {
        try {
            val sb = Snackbar.make(a.findViewById<View>(android.R.id.content), p.text, 8000)
            AppearancePrefs.snackAnchor(a)?.let { sb.setAnchorView(it) } // A5: none in top mode
            sb.setAction("Diagnose") { DiagScreen.open(a, tabs, -1, p.focusText, p.focusUrl) }
            sb.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event == BaseTransientBottomBar.BaseCallback.DISMISS_EVENT_SWIPE) {
                        prefs.edit().putLong(KEY_QUIET, System.currentTimeMillis() + QUIET_MS).apply()
                    }
                }
            })
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            sb.show()
        } catch (e: Exception) {
            Diag.w("app", "auto diagnose pill failed: " + e.javaClass.simpleName)
        }
    }
}
