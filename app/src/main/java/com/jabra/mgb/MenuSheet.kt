package com.jabra.mgb

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * S22: the 3-dot menu as a minimal bottom sheet, built in code (no layout xml).
 * Top: 4 quick tiles. Then one icon row (back / forward / bookmark / reload). Then plain text rows.
 * Colours come from the mg_* tokens, so dark and light follow the theme.
 */
object MenuSheet {

    class Quick(val glyph: String, val label: String, val onClick: () -> Unit)
    class Act(val glyph: String, val enabled: Boolean = true, val onClick: () -> Unit)
    class Item(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun show(ctx: Context, quick: List<Quick>, acts: List<Act>, items: List<Item>, footer: String) {
        val dlg = BottomSheetDialog(ctx)
        fun dp(v: Int) = Util.dp(ctx, v)
        fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val d = it.getDrawable(0)
            it.recycle()
            d
        }
        val cText = ctx.getColor(R.color.mg_text)
        val cDim = ctx.getColor(R.color.mg_text_dim)
        val cTile = ctx.getColor(R.color.mg_surface_variant)
        val cAccent = ctx.getColor(R.color.mg_primary)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }

        // quick tiles
        val qrow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (q in quick) {
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = round(cTile, 18)
                setPadding(0, dp(14), 0, dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { dlg.dismiss(); q.onClick() }
            }
            t.addView(TextView(ctx).apply { this.text = q.glyph; textSize = 22f; setTextColor(cAccent); gravity = Gravity.CENTER })
            t.addView(TextView(ctx).apply {
                this.text = q.label
                textSize = 12f
                setTextColor(cText)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            })
            qrow.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        col.addView(qrow, LinearLayout.LayoutParams(MATCH, WRAP))

        // icon row
        val arow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(cTile, 24)
        }
        for (a in acts) {
            arow.addView(TextView(ctx).apply {
                this.text = a.glyph
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(cText)
                alpha = if (a.enabled) 1f else 0.35f
                isEnabled = a.enabled
                isClickable = a.enabled
                background = ripple?.constantState?.newDrawable()
                setOnClickListener { dlg.dismiss(); a.onClick() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        col.addView(arow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12); bottomMargin = dp(8) })

        // text rows
        for (m in items) {
            col.addView(TextView(ctx).apply {
                this.text = m.label
                textSize = 16f
                setTextColor(cText)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                alpha = if (m.enabled) 1f else 0.4f
                isEnabled = m.enabled
                isClickable = m.enabled
                background = ripple?.constantState?.newDrawable()
                setOnClickListener { dlg.dismiss(); m.onClick() }
            }, LinearLayout.LayoutParams(MATCH, dp(48)))
        }
        col.addView(TextView(ctx).apply {
            this.text = footer
            textSize = 12f
            setTextColor(cDim)
            setPadding(dp(12), dp(10), dp(12), 0)
        })

        val sv = ScrollView(ctx)
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        dlg.setContentView(sv)
        dlg.behavior.skipCollapsed = true
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }
}
