package com.jabra.mgb

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

/**
 * TIME LIMIT FOR SOCIAL SITES (0.0.261, C3). Pref sm_tl = limit index (0 = Off, default) in mgb_ui.
 * Counts foreground time while a SOCIAL tab (Social mode ON + site switch ON) is the active tab and the app is in front
 * (MainActivity onStart .. onStop), in steps of 10 seconds. Seconds per day: sm_t_day (year * 1000 + day of year) + sm_t_sec.
 * At the limit a soft reminder shows ("Keep going 10 more minutes" / "Leave app"); it never closes a tab or blocks the page.
 * Local only, nothing is sent. Total is shared by all social sites (one number per day).
 */
object SocialTime {
    private const val K_LIM = "sm_tl"
    private const val K_DAY = "sm_t_day"
    private const val K_SEC = "sm_t_sec"
    private const val STEP = 10
    private val LIMITS = intArrayOf(0, 15, 30, 60, 120)

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    private val h = Handler(Looper.getMainLooper())
    private var act: FragmentActivity? = null
    private var tabs: TabManager? = null
    private var dialog: AlertDialog? = null
    private var nextAt = -1            // seconds today at which the next reminder shows
    private var nextDay = -1           // day for which nextAt was computed
    private var nextLim = -1           // limit (minutes) for which nextAt was computed

    fun limitIdx(c: Context): Int = sp(c).getInt(K_LIM, 0).coerceIn(0, LIMITS.size - 1)
    fun limitMin(c: Context): Int = LIMITS[limitIdx(c)]
    fun label(c: Context): String = if (limitMin(c) == 0) "Off" else limitMin(c).toString() + " min per day"

    private fun today(): Int { val cal = Calendar.getInstance(); return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR) }
    fun todayMin(c: Context): Int = if (sp(c).getInt(K_DAY, -1) == today()) sp(c).getInt(K_SEC, 0) / 60 else 0

    /** One tap = next limit (Off -> 15 -> 30 -> 60 -> 120 -> Off). */
    fun cycle(c: Context) {
        sp(c).edit().putInt(K_LIM, (limitIdx(c) + 1) % LIMITS.size).apply()
        schedule()
    }

    private fun isSocial(c: Context, tab: TabManager.Tab?): Boolean {
        if (tab == null || !SocialMode.on(c)) return false
        val p = SocialProfiles.of(tab.url) ?: return false
        return SocialProfiles.siteOn(c, p.id)
    }

    private val tick = object : Runnable {
        override fun run() {
            val a = act ?: return
            try { step(a) } catch (e: Exception) { android.util.Log.w("Thazir.time", "tick failed", e) }
            schedule()
        }
    }

    private fun schedule() {
        h.removeCallbacks(tick)
        val a = act ?: return
        if (limitMin(a) > 0) h.postDelayed(tick, STEP * 1000L)
    }

    /** MainActivity.onStart. */
    fun start(a: FragmentActivity, tm: TabManager) { act = a; tabs = tm; schedule() }

    /** MainActivity.onStop. */
    fun stop() {
        h.removeCallbacks(tick)
        try { dialog?.dismiss() } catch (e: Exception) { }
        dialog = null
        act = null; tabs = null
    }

    private fun step(a: FragmentActivity) {
        val lim = limitMin(a)
        if (lim <= 0 || a.isFinishing || a.isDestroyed) return
        if (!isSocial(a, tabs?.active)) return
        val d = today()
        val p = sp(a)
        var sec = if (p.getInt(K_DAY, -1) == d) p.getInt(K_SEC, 0) else 0
        sec += STEP
        p.edit().putInt(K_DAY, d).putInt(K_SEC, sec).apply()
        if (nextDay != d || nextLim != lim) { nextDay = d; nextLim = lim; nextAt = lim * 60 }
        if (sec >= nextAt && dialog?.isShowing != true) remind(a, sec / 60, lim)
    }

    private fun remind(a: FragmentActivity, usedMin: Int, lim: Int) {
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("Time limit reached")
            .setMessage("You used about " + usedMin + " minutes on social sites today (limit " + lim + " minutes).")
            .setCancelable(false)
            .setPositiveButton("Keep going 10 more minutes") { _, _ -> nextAt += 600 }
            .setNegativeButton("Leave app") { _, _ -> nextAt += 600; a.moveTaskToBack(true) }
            .create()
        dialog = dlg
        dlg.show()
    }
}
