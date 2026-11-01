package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * S21: find in page. A slim bar at the bottom (Dialog, no manifest entry, page stays touchable).
 * Type = highlights all matches, arrows = previous / next, x or Back = close.
 */
object FindBar {
    private var current: Bar? = null

    fun open(act: Activity, web: WebView?) {
        if (web == null || act.isFinishing || act.isDestroyed) return
        close()
        try {
            val b = Bar(act, web)
            current = b
            b.show()
        } catch (e: Exception) {
            current = null
        }
    }

    fun close() {
        val b = current ?: return
        current = null
        try { b.dismiss() } catch (_: Exception) { }
    }

    private class Bar(private val act: Activity, private val web: WebView) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener {
                if (current === this) current = null
                try { web.clearMatches(); web.setFindListener(null) } catch (_: Exception) { }
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setBackgroundColor(act.getColor(R.color.mg_surface_variant))
            }
            val et = EditText(act).apply {
                hint = "Find in page"
                setSingleLine(true)
                setTextColor(act.getColor(R.color.mg_text))
                setHintTextColor(act.getColor(R.color.mg_text_dim))
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, _, _ -> web.findNext(true); true }
            }
            val count = TextView(act).apply {
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
                setPadding(dp(6), 0, dp(6), 0)
            }
            fun btn(t: String, desc: String, onClick: () -> Unit) = TextView(act).apply {
                text = t
                gravity = Gravity.CENTER
                textSize = 22f
                contentDescription = desc
                setTextColor(act.getColor(R.color.mg_text))
                setOnClickListener { onClick() }
            }
            row.addView(et, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(count, LinearLayout.LayoutParams(WRAP, WRAP))
            row.addView(btn("↑", "Previous") { web.findNext(false) }, LinearLayout.LayoutParams(dp(40), dp(48)))
            row.addView(btn("↓", "Next") { web.findNext(true) }, LinearLayout.LayoutParams(dp(40), dp(48)))
            row.addView(btn("✕", "Close") { dismiss() }, LinearLayout.LayoutParams(dp(40), dp(48)))
            setContentView(row)

            web.setFindListener { active, total, _ ->
                count.text = if (total == 0) (if (et.text.isNullOrEmpty()) "" else "0") else "${active + 1}/$total"
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString() ?: ""
                    if (q.isEmpty()) { web.clearMatches(); count.text = "" } else web.findAllAsync(q)
                }
            })
            window?.let {
                it.setLayout(MATCH, WRAP)
                it.setGravity(Gravity.BOTTOM)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_surface_variant)))
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            et.requestFocus()
        }
    }
}
