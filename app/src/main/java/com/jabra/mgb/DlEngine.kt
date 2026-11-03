package com.jabra.mgb

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * S60: V77 download engine options as one native screen (panel TOOLS > Download engine).
 * Every row reads / writes a V77 setting in chrome.storage.sync: maxJobs, concurrency, speedLimitKBps, requestDelayMs,
 * autoNetRetry, batchRetryMiss, sniffSeconds, dupAsk, siteMemory, skipDone, mergeAV, convertMp4, convertMaxMB,
 * notify, minSizeKB, minDurationSec, blockAds, liveMaxMin. V77 does the real work; this only asks and shows.
 */
object DlEngine {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun int(k: String, def: Int): Int = try { ChromeBridge.sync.get(k).optInt(k, def) } catch (e: Exception) { def }
    private fun bool(k: String, def: Boolean): Boolean = try { ChromeBridge.sync.get(k).optBoolean(k, def) } catch (e: Exception) { def }
    private fun put(k: String, v: Any) { try { ChromeBridge.storageSet("sync", JSONObject().put(k, v)) } catch (e: Exception) { /* engine not ready */ } }

    private fun kb(v: Int): String = if (v <= 0) "Unlimited" else if (v >= 1024) String.format("%.1f MB/s", v / 1024f).replace(".0 ", " ") else "$v KB/s"
    private fun sec(v: Int): String = if (v <= 0) "Off" else "$v s"
    private fun mb(v: Int): String = if (v <= 0) "Always convert" else if (v >= 1000) "Above ${v / 1000} GB" else "Above $v MB"
    private fun minutes(v: Int): String = if (v <= 0) "Until I press Stop" else if (v >= 60 && v % 60 == 0) "${v / 60} h" else "$v min"

    fun open(act: Activity) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)
        val d = BottomSheetDialog(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            layoutParams = ViewGroup.LayoutParams(MATCH, (act.resources.displayMetrics.heightPixels * 0.9f).toInt())
        }
        root.addView(TextView(act).apply {
            text = "Download engine"
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(2))
        })
        root.addView(TextView(act).apply {
            text = "Speed, retries and limits for all downloads. Changes apply to the next download."
            setTextColor(cDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), 0, 0, dp(6))
        })
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(act).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(TextView(act).apply {
            text = "DONE"
            gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.mg_on_primary))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(cPrimary); cornerRadius = dp(24).toFloat() }
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })

        fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(18).toFloat(); setStroke(dp(1), cLine) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        fun head(t: String) = TextView(act).apply {
            text = t.uppercase()
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(16), 0, dp(6))
        }

        fun rowView(title: String, value: String?, hint: String?): LinearLayout {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply { text = title; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
            if (!hint.isNullOrBlank()) col.addView(TextView(act).apply { text = hint; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setPadding(0, dp(2), 0, 0) })
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (value != null) r.addView(TextView(act).apply {
                text = value
                setTextColor(cPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                maxLines = 2
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            return r
        }

        lateinit var fill: () -> Unit

        fun sw(parent: LinearLayout, title: String, hint: String?, key: String, def: Boolean) {
            val r = rowView(title, null, hint)
            val s = SwitchCompat(act).apply { isChecked = bool(key, def) }
            r.addView(s, LinearLayout.LayoutParams(WRAP, WRAP))
            val flip = { put(key, !bool(key, def)); fill() }
            r.setOnClickListener { flip() }
            s.setOnClickListener { flip() }
            parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        /** A number setting with ready choices; a value set elsewhere (V77 options) stays in the list. */
        fun num(parent: LinearLayout, title: String, hint: String?, key: String, def: Int, values: IntArray, label: (Int) -> String) {
            val cur = int(key, def)
            val vals = if (values.contains(cur)) values.toList() else (values.toList() + cur).sorted()
            parent.addView(rowView(title, label(cur), hint).apply {
                setOnClickListener {
                    MaterialAlertDialogBuilder(act).setTitle(title)
                        .setSingleChoiceItems(vals.map { label(it) }.toTypedArray(), vals.indexOf(cur)) { dd, i -> put(key, vals[i]); dd.dismiss(); fill() }
                        .setNegativeButton("Cancel", null).show()
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fill = {
            box.removeAllViews()

            box.addView(head("Speed"))
            val s1 = card()
            num(s1, "Downloads at the same time", "Stream downloads running together (the rest wait in the queue)", "maxJobs", 2, intArrayOf(1, 2, 3, 4, 6)) { "$it" }
            num(s1, "Parts at the same time", "Segments fetched in parallel for one stream. More = faster, but some sites refuse", "concurrency", 6, intArrayOf(2, 4, 6, 8, 12, 16)) { "$it" }
            num(s1, "Speed limit per download", "Keeps your connection free for other things", "speedLimitKBps", 0, intArrayOf(0, 256, 512, 1024, 2048, 5120)) { kb(it) }
            num(s1, "Pause between page requests", "Slows page / chapter scraping so sites do not block you", "requestDelayMs", 0, intArrayOf(0, 500, 1000, 2000, 5000)) { if (it <= 0) "None" else "${it / 1000f} s".replace(".0 ", " ") }
            box.addView(s1, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Retries and checks"))
            val s2 = card()
            sw(s2, "Retry when the connection comes back", "Batch episodes that failed only because of the network", "autoNetRetry", true)
            sw(s2, "Second try for episodes without a video", "A longer wait for slow players", "batchRetryMiss", true)
            num(s2, "Wait for each player", "How long a batch waits to see the video of one episode", "sniffSeconds", 25, intArrayOf(10, 15, 25, 40, 60)) { sec(it) }
            sw(s2, "Ask before downloading again", "When the file is already saved and still on the phone", "dupAsk", true)
            sw(s2, "Remember what worked per site", "The next episode starts with the way that found the video", "siteMemory", true)
            sw(s2, "Skip chapters already downloaded", "When several chapters are selected", "skipDone", true)
            box.addView(s2, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Files"))
            val s3 = card()
            sw(s3, "Join video and audio into one file", "Separate audio + video become one MP4, no re-encoding", "mergeAV", true)
            sw(s3, "Convert .ts to .mp4", "Same switch as in the panel", "convertMp4", true)
            num(s3, "Skip conversion for big files", "Big .ts files are saved as they are (conversion is slow)", "convertMaxMB", 0, intArrayOf(0, 100, 250, 500, 1000, 2000)) { mb(it) }
            sw(s3, "Notify when downloads finish", null, "notify", true)
            box.addView(s3, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("What counts as a video"))
            val s4 = card()
            num(s4, "Hide small files", "Tracking pixels, beeps", "minSizeKB", 50, intArrayOf(0, 50, 200, 500, 1000, 5000)) { if (it <= 0) "Show all" else if (it >= 1000) "${it / 1000} MB" else "$it KB" }
            num(s4, "Hide short streams", "Ads and previews", "minDurationSec", 0, intArrayOf(0, 10, 30, 60, 120, 300)) { sec(it) }
            sw(s4, "Skip ads", "Ignore ad videos and sponsored links", "blockAds", true)
            box.addView(s4, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Live streams"))
            val s5 = card()
            num(s5, "Stop recording after", "Live HLS recording ends and saves at this time", "liveMaxMin", 120, intArrayOf(0, 30, 60, 120, 240, 480)) { minutes(it) }
            box.addView(s5, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fill()
        d.setContentView(root)
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
