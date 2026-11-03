package com.jabra.mgb

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * S69: search engine picker. A small rose letter badge in the address bar (home page + while typing) opens a popup
 * card just above the bar: every engine with its address, tick on the current one. A tap sets the engine at once
 * (same pref as Settings > Search engine). No new storage.
 */
object EnginePicker {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun letter(ctx: Activity): String = Util.engineName(ctx).take(1).uppercase()

    /** Round tint behind the letter of the bar badge. */
    fun styleBadge(act: Activity, tv: TextView) {
        tv.text = letter(act)
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 0x2E))
        }
    }

    fun show(act: Activity, anchor: View, onPick: () -> Unit) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)
        val current = Util.engineName(act)
        var pop: PopupWindow? = null
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(20).toFloat(); setStroke(dp(1), cLine) }
            setPadding(0, dp(6), 0, dp(6))
        }
        for ((name, base) in Util.ENGINES) {
            val on = name == current
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52); setPadding(dp(14), dp(6), dp(14), dp(6))
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    Util.setEngine(act, name)
                    pop?.dismiss()
                    onPick()
                }
            }
            row.addView(TextView(act).apply {
                text = name.take(1).uppercase(); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(cPrimary)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(cPrimary, 0x26)) }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(act).apply { text = name; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); if (on) typeface = Typeface.DEFAULT_BOLD })
            val host = try { android.net.Uri.parse(base).host?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }
            texts.addView(TextView(act).apply { text = host; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (on) row.addView(ImageView(act).apply { setImageResource(R.drawable.ic_check); setColorFilter(cPrimary) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val w = minOf(dp(260), act.resources.displayMetrics.widthPixels - dp(24))
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = col.measuredHeight
        val p = PopupWindow(col, w, h, false)
        pop = p
        p.setBackgroundDrawable(ColorDrawable(0))
        p.isOutsideTouchable = true
        p.elevation = dp(8).toFloat()
        col.alpha = 0f; col.translationY = dp(10).toFloat()
        // the bar is at the bottom: open upwards, left edge on the badge
        p.showAsDropDown(anchor, 0, -(anchor.height + h + dp(8)), Gravity.START)
        col.animate().alpha(1f).translationY(0f).setDuration(150).start()
    }
}
