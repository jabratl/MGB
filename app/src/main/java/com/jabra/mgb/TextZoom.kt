package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S41: text size per site (50% - 250%). Saved in prefs mgb_zoom (host -> percent), applied in TabManager.onPageStarted
 * through WebSettings.textZoom. Menu > Text size.
 */
object TextZoom {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("mgb_zoom", Context.MODE_PRIVATE)

    fun get(ctx: Context, host: String): Int = if (host.isEmpty()) 100 else sp(ctx).getInt(host, 100)

    private fun set(ctx: Context, host: String, v: Int) {
        if (host.isEmpty()) return
        if (v == 100) sp(ctx).edit().remove(host).apply() else sp(ctx).edit().putInt(host, v).apply()
    }

    fun dialog(act: Activity, tabs: TabManager) {
        val t = tabs.active
        if (t == null || t.isExt || !t.url.startsWith("http")) { Util.toast(act, "Open a web page first"); return }
        val host = Util.host(t.url)
        val old = get(act, host)
        val tv = TextView(act).apply { textSize = 18f; setTextColor(act.getColor(R.color.mg_text)); text = "$old%" }
        val bar = SeekBar(act).apply { max = 20; progress = ((old - 50) / 10).coerceIn(0, 20) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                val z = 50 + v * 10
                tv.text = "$z%"
                t.web.settings.textZoom = z
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        val pad = Util.dp(act, 24)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Util.dp(act, 8), pad, 0)
            addView(tv); addView(bar)
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Text size · $host")
            .setView(col)
            .setPositiveButton("Save") { _, _ -> set(act, host, 50 + bar.progress * 10); Util.toast(act, "Saved for $host") }
            .setNeutralButton("Reset") { _, _ -> set(act, host, 100); t.web.settings.textZoom = 100 }
            .setNegativeButton("Cancel") { _, _ -> t.web.settings.textZoom = old }
            .setOnCancelListener { t.web.settings.textZoom = old }
            .show()
    }
}
