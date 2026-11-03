package com.jabra.mgb

import androidx.core.widget.NestedScrollView
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * S64: one clean bottom sheet for the panel TOOLS button: groups in rounded cards, every row = icon + title + one line of help.
 * Replaces the plain text list. A row closes the sheet, then runs (so its own dialog opens on top of the panel).
 */
object ToolsSheet {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    class Item(val icon: Int, val title: String, val info: String, val action: () -> Unit)
    class Group(val title: String, val items: List<Item>)

    fun show(act: Activity, title: String, subtitle: String, groups: List<Group>) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)
        val d = BottomSheetDialog(act)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
        col.addView(TextView(act).apply {
            text = title
            setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, dp(6), 0)
        })
        if (subtitle.isNotBlank()) col.addView(TextView(act).apply {
            text = subtitle
            setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(2), dp(6), dp(4))
        })
        for (g in groups) {
            if (g.items.isEmpty()) continue
            col.addView(TextView(act).apply {
                text = g.title.uppercase()
                setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setPadding(dp(8), dp(16), 0, dp(6))
            })
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(20).toFloat(); setStroke(dp(1), cLine) }
                setPadding(0, dp(4), 0, dp(4))
            }
            g.items.forEachIndexed { i, item ->
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(14), dp(8), dp(12), dp(8))
                    val tv = TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                    setOnClickListener { d.dismiss(); item.action() }
                }
                val badge = android.widget.FrameLayout(act).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(cPrimary, 0x26)) }
                }
                badge.addView(ImageView(act).apply { setImageResource(item.icon); setColorFilter(cPrimary) },
                    android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                row.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) })
                val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(TextView(act).apply { text = item.title; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 2 })
                if (item.info.isNotBlank()) texts.addView(TextView(act).apply { text = item.info; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 2 })
                row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(ImageView(act).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(cDim) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                if (i < g.items.size - 1) card.addView(android.view.View(act).apply { setBackgroundColor(cLine) },
                    LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(64); marginEnd = dp(14) })
            }
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        d.setContentView(NestedScrollView(act).apply { addView(col) })
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
