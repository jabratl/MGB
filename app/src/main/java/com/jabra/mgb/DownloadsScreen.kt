package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * S12: native downloads manager (full-screen window, no manifest entry needed).
 *  - running / finished / failed jobs come straight from V77 (storage.session mgJobs), refreshed every second
 *  - Pause / Resume / Cancel / Remove = the same messages V77 Queue page sends (job-pause, job-cancel, job-remove)
 *  - "Saved files" (storage.local mgHist): Open / Show call chrome.downloads.open / show in the engine
 *  - Clear = jobs-clear (removes everything that is not running)
 * Not here: Share (needs the file uri from DownloadsApi), notification buttons (DownloadService).
 */
object DownloadsScreen {
    private const val TAG = "MGB.dlscreen"
    private var current: Screen? = null

    fun open(host: Activity) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    fun closeAll() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {
        private val main = Handler(Looper.getMainLooper())
        private var list: LinearLayout? = null
        private var empty: TextView? = null
        private var sig = ""
        private val tick = object : Runnable {
            override fun run() {
                render()
                main.postDelayed(this, 1000)
            }
        }

        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener {
                main.removeCallbacks(tick)
                if (current === this) current = null
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.screen_downloads)
            window?.let {
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            list = findViewById(R.id.xd_list)
            empty = findViewById(R.id.xd_empty)
            findViewById<View>(R.id.xd_back).setOnClickListener { dismiss() }
            findViewById<View>(R.id.xd_close).setOnClickListener { dismiss() }
            findViewById<View>(R.id.xd_clear).setOnClickListener {
                EngineApi.send(JSONObject().put("type", "jobs-clear"))
                main.postDelayed({ sig = ""; render() }, 400)
            }
            main.post(tick)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { dismiss() }

        private fun pctOf(j: JSONObject): Int = (j.optDouble("pct", 0.0).coerceIn(0.0, 1.0) * 100).toInt()

        private fun render() {
            val box = list ?: return
            val jobs = EngineApi.jobs()
            val hist = EngineApi.history().take(30)
            val s = jobs.joinToString("|") {
                it.optString("key") + ":" + it.optString("state") + ":" + pctOf(it) + ":" + it.optBoolean("paused") + ":" + it.optString("text")
            } + "#" + hist.joinToString("|") { it.optString("id") + ":" + it.optLong("time") }
            if (s == sig) return
            sig = s
            box.removeAllViews()
            empty?.visibility = if (jobs.isEmpty() && hist.isEmpty()) View.VISIBLE else View.GONE
            val running = jobs.filter { it.optString("state") == "run" }
            val other = jobs.filter { it.optString("state") != "run" }
            if (running.isNotEmpty()) box.addView(section("Downloading (${running.size})"))
            for (j in running) box.addView(jobRow(j))
            if (other.isNotEmpty()) box.addView(section("Finished"))
            for (j in other) box.addView(jobRow(j))
            if (hist.isNotEmpty()) box.addView(section("Saved files"))
            for (h in hist) box.addView(fileRow(h))
        }

        private fun section(t: String): View = TextView(act).apply {
            text = t
            setTextColor(act.getColor(R.color.mg_text_dim))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(4))
        }

        private fun button(label: String, click: () -> Unit): MaterialButton =
            MaterialButton(act, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                minimumWidth = 0
                minWidth = 0
                setOnClickListener { click() }
            }

        private fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        private fun jobRow(j: JSONObject): View {
            val key = j.optString("key")
            val state = j.optString("state")
            val c = card()
            c.addView(TextView(act).apply {
                text = j.optString("title").ifBlank { key }
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            val status = when (state) {
                "run" -> (if (j.optBoolean("paused")) "Paused · " else "") + pctOf(j) + "%" +
                    j.optString("text").let { if (it.isBlank()) "" else " · $it" }
                "done" -> "Done"
                else -> j.optString("text").ifBlank { "Failed" }
            }
            c.addView(TextView(act).apply {
                text = status
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (state == "run") {
                c.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = pctOf(j)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
            }
            val actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            if (state == "run") {
                val paused = j.optBoolean("paused")
                actions.addView(button(if (paused) "Resume" else "Pause") {
                    EngineApi.send(JSONObject().put("type", "job-pause").put("key", key).put("on", !paused))
                })
                actions.addView(button("Cancel") { EngineApi.send(JSONObject().put("type", "job-cancel").put("key", key)) })
            } else {
                actions.addView(button("Remove") {
                    EngineApi.send(JSONObject().put("type", "job-remove").put("key", key))
                    main.postDelayed({ sig = ""; render() }, 400)
                })
            }
            c.addView(actions)
            return c
        }

        private fun fileRow(h: JSONObject): View {
            val id = h.optInt("id", 0)
            val c = card()
            c.addView(TextView(act).apply {
                text = h.optString("title").ifBlank { "file" }
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            val size = EngineApi.fmtSize(h.optLong("size", 0))
            val ago = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(h.optLong("time")))
            c.addView(TextView(act).apply {
                text = (if (size.isEmpty()) "" else "$size · ") + ago
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
            })
            if (id > 0) {
                val actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                actions.addView(button("Open") { EngineHost.eval("try{chrome.downloads.open($id)}catch(e){}") })
                actions.addView(button("Show") { EngineHost.eval("try{chrome.downloads.show($id)}catch(e){}") })
                c.addView(actions)
            }
            return c
        }
    }
}
