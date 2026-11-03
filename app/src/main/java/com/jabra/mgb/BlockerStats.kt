package com.jabra.mgb

import androidx.core.widget.NestedScrollView
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * S70: Ads and trackers blocker stats (opened from Privacy Guard > Blocker stats).
 * Today / last 7 days / total, a 21 day bar chart (today in rose) and the sites with most blocks in this app run.
 * Data: AdBlock.lastDays / topSites / totalBlocked. Nothing else is stored.
 */
object BlockerStats {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private class Bars(ctx: Context, val v: IntArray, val cOn: Int, val cOff: Int, val cDim: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val n = v.size
            if (n == 0) return
            val mx = maxOf(1, v.maxOrNull() ?: 0)
            val gap = Util.dp(context, 4).toFloat()
            val bw = (width - gap * (n - 1)) / n
            val base = height - Util.dp(context, 18).toFloat()
            val top = Util.dp(context, 6).toFloat()
            for (i in 0 until n) {
                val x = i * (bw + gap)
                val h = maxOf(Util.dp(context, 3).toFloat(), (base - top) * v[i] / mx)
                r.set(x, base - h, x + bw, base)
                p.color = if (i == n - 1) cOn else cOff
                c.drawRoundRect(r, bw / 2f, bw / 2f, p)
            }
            p.color = cDim; p.textSize = Util.dp(context, 10).toFloat()
            p.textAlign = Paint.Align.LEFT; c.drawText("${n - 1} days ago", 0f, height.toFloat() - Util.dp(context, 3), p)
            p.textAlign = Paint.Align.RIGHT; c.drawText("Today", width.toFloat(), height.toFloat() - Util.dp(context, 3), p)
        }
    }

    fun show(act: Activity) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)
        fun card() = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(20).toFloat(); setStroke(dp(1), cLine) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        fun label(t: String) = TextView(act).apply {
            text = t.uppercase(); setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f; setPadding(dp(8), dp(16), 0, dp(6))
        }
        val vals = AdBlock.lastDays(21)
        val today = vals[20]
        val week = vals.takeLast(7).sum()
        val d = BottomSheetDialog(act)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(20)) }
        col.addView(TextView(act).apply { text = "Ads and trackers blocker"; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(6), 0, dp(6), 0) })
        col.addView(TextView(act).apply { text = if (AdBlock.enabled) "ON" else "OFF (turn it on in Privacy Guard)"; setTextColor(if (AdBlock.enabled) cPrimary else cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(dp(6), dp(2), dp(6), dp(8)) })

        // three numbers
        val nums = card().apply { orientation = LinearLayout.HORIZONTAL }
        fun num(n: Int, what: String) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(act).apply { text = n.toString(); setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
            addView(TextView(act).apply { text = what; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); gravity = Gravity.CENTER })
        }
        nums.addView(num(today, "today"), LinearLayout.LayoutParams(0, WRAP, 1f))
        nums.addView(num(week, "last 7 days"), LinearLayout.LayoutParams(0, WRAP, 1f))
        nums.addView(num(AdBlock.totalBlocked, "total"), LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(nums, LinearLayout.LayoutParams(MATCH, WRAP))

        // chart
        col.addView(label("Last 3 weeks"))
        val chart = card()
        chart.addView(Bars(act, vals, cPrimary, ColorUtils.setAlphaComponent(cPrimary, 0x55), cDim), LinearLayout.LayoutParams(MATCH, dp(130)))
        col.addView(chart, LinearLayout.LayoutParams(MATCH, WRAP))

        // top sites
        col.addView(label("Most blocked sites (since the app started)"))
        val top = card()
        val sites = AdBlock.topSites(5)
        if (sites.isEmpty()) top.addView(TextView(act).apply { text = "Nothing blocked yet. Browse a few pages."; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
        else sites.forEach { (h, n) ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(TextView(act).apply { text = h; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(act).apply { text = n.toString(); setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD })
            top.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // S74: filter lists (EasyList + EasyPrivacy)
        col.addView(label("Filter lists"))
        val fl = card()
        val flText = TextView(act).apply { setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        fun flInfo() {
            val ts = FilterList.updatedAt()
            val age = if (ts == 0L) "not updated yet" else {
                val days = (System.currentTimeMillis() - ts) / 86_400_000L
                if (days <= 0) "updated today" else "updated $days day" + (if (days == 1L) "" else "s") + " ago"
            }
            flText.text = if (FilterList.loading) "Loading rules..." else "${FilterList.ruleCount} rules, $age"
        }
        flInfo()
        fl.addView(flText)
        val flNote = TextView(act).apply { setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, dp(2), 0, dp(10)) }
        fun flNoteSet() { flNote.text = FilterList.activeTitles().joinToString(" + ").ifEmpty { "No list is on" } + ". Updates itself once a week." }
        flNoteSet()
        fl.addView(flNote)
        val upd = TextView(act).apply {
            text = "Update filters now"; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(act.getColor(R.color.mg_on_primary)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply { setColor(cPrimary); cornerRadius = dp(22).toFloat() }
            isClickable = true
            setOnClickListener {
                text = "Updating..."; isEnabled = false
                FilterList.update(act) { msg -> act.runOnUiThread {
                    Util.toast(act, msg)
                    text = "Update filters now"; isEnabled = true
                    Thread { Thread.sleep(1500); act.runOnUiThread { flInfo() } }.start()
                } }
            }
        }
        fl.addView(upd, LinearLayout.LayoutParams(MATCH, dp(44)))
        // 0.0.107: switch lists on / off, add a list by address, remove a custom list
        fun after(msg: String) = act.runOnUiThread { Util.toast(act, msg); flNoteSet(); Thread { Thread.sleep(1500); act.runOnUiThread { flInfo() } }.start() }
        fun addDialog() {
            val inp = android.widget.EditText(act).apply { hint = "https://example.com/list.txt"; setSingleLine(); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT }
            val box = android.widget.FrameLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(inp) }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle("Add filter list").setMessage("EasyList / uBlock syntax, https only.").setView(box)
                .setPositiveButton("Add") { _, _ -> FilterList.addCustom(act, inp.text.toString()) { m -> after(m) }?.let { Util.toast(act, it) } }
                .setNegativeButton("Cancel", null).show()
        }
        fun removeDialog() {
            val cs = FilterList.lists().filter { it.custom }
            if (cs.isEmpty()) { Util.toast(act, "No custom lists"); return }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle("Remove custom list")
                .setItems(cs.map { it.title }.toTypedArray()) { _, i -> FilterList.removeCustom(act, cs[i].name); flNoteSet(); Util.toast(act, "Removed") }
                .setNegativeButton("Cancel", null).show()
        }
        fun manage() {
            val ls = FilterList.lists()
            val on = BooleanArray(ls.size) { ls[it].on }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle("Filter lists")
                .setMultiChoiceItems(ls.map { it.title + (if (it.custom) " (custom)" else "") }.toTypedArray(), on) { _, i, c -> on[i] = c }
                .setPositiveButton("Apply") { _, _ -> FilterList.setEnabledAll(act, ls.indices.associate { ls[it].name to on[it] }) { m -> after(m) } }
                .setNeutralButton("Add / remove") { _, _ ->
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setItems(arrayOf("Add list by address", "Remove a custom list")) { _, w -> if (w == 0) addDialog() else removeDialog() }.show()
                }
                .setNegativeButton("Cancel", null).show()
        }
        val mng = TextView(act).apply {
            text = "Manage lists"; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); isClickable = true
            setOnClickListener { manage() }
        }
        fl.addView(mng, LinearLayout.LayoutParams(MATCH, dp(44)))
        col.addView(fl, LinearLayout.LayoutParams(MATCH, WRAP))

        d.setContentView(NestedScrollView(act).apply { addView(col) })
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
