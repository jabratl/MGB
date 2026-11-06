package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A2: full-screen Accessibility page (same chrome as AppearanceScreen: 56 dp header + back, scroll column, violet-dash section labels,
 * 20 dp cards, 52 dp rows). Default zoom (slider + - buttons + live comfort preview), Saved zoom for sites, Text size in main menu.
 * Opened from Appearance, Settings > General and Command Center. back = what the caller passes (Appearance / Settings) or just closes.
 * The few private helpers are copied from AppearanceScreen on purpose (its internals stay private).
 */
object AccessScreen {
    private var current: Screen? = null

    fun open(host: Activity, back: (() -> Unit)? = null) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host, back)
            current = s
            s.show()
        } catch (e: Exception) {
            android.util.Log.e("MGB.access", "open failed", e)
            current = null
        }
    }

    fun closeAll() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    /** Column that never gets wider than 560 dp (wide screens / landscape), centred by its parent. */
    private class CapColumn(act: Activity) : LinearLayout(act) {
        private val cap = Util.dp(act, 560)
        override fun onMeasure(w: Int, h: Int) {
            val ws = MeasureSpec.getSize(w)
            super.onMeasure(if (ws > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else w, h)
        }
    }

    private class Screen(private val act: Activity, private val back: (() -> Unit)?) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var body: LinearLayout? = null
        private var zoom = 100 // state lives here, views are rebuilt by fill()
        private var zLabel: TextView? = null
        private var zReset: TextView? = null
        private var zSeek: SeekBar? = null
        private var pvTitle: TextView? = null
        private var pvSub: TextView? = null
        private var pvBody: TextView? = null

        private fun dp(v: Int) = Util.dp(act, v)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
        }

        override fun onBackPressed() { goBack() }

        private fun goBack() {
            dismiss()
            if (current === this) current = null
            back?.invoke()
        }

        private fun round(color: Int, r: Int, strokeDp: Float = 0f, stroke: Int = 0) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (strokeDp > 0f) setStroke((strokeDp * act.resources.displayMetrics.density).toInt(), stroke)
        }

        private fun build() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(act.getColor(R.color.mg_bg))
            }
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { goBack() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = "Accessibility"
                textSize = 22f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            val col = CapColumn(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(32))
            }
            body = col
            root.addView(ScrollView(act).apply {
                isFillViewport = false
                addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill()
        }

        private fun fill() {
            val col = body ?: return
            col.removeAllViews()
            zoom = AppearancePrefs.zoomDef(act)

            col.addView(sectionLabel("Default zoom"))
            val zc = card()
            zc.addView(note("Text size for every site. A size saved for one site below always wins."))
            zc.addView(previewCard(), LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(6), dp(14), dp(8)) })
            zc.addView(zoomControl(), LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), 0, dp(10), dp(6)) })
            col.addView(zc, LinearLayout.LayoutParams(MATCH, WRAP))
            showZoom()

            col.addView(sectionLabel("Saved zoom for sites"))
            val sc = card()
            val saved = TextZoom.all(act).toSortedMap()
            if (saved.isEmpty()) {
                sc.addView(note("No site has its own zoom yet. Set one in Menu > Text size or Site settings."))
            } else {
                for ((host, pct) in saved) sc.addView(siteRow(host, pct), LinearLayout.LayoutParams(MATCH, WRAP))
                sc.addView(TextView(act).apply {
                    text = "Reset all"
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(R.color.mg_primary))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), 0, dp(18), 0)
                    isClickable = true
                    ripple(this)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(act).setMessage("Forget the saved zoom of all ${saved.size} sites? They will use the default zoom.")
                            .setPositiveButton("Reset all") { _, _ -> TextZoom.resetAll(act); TextZoom.applyActive(act); fill() }
                            .setNegativeButton("Cancel", null).show()
                    }
                }, LinearLayout.LayoutParams(MATCH, dp(48)))
            }
            col.addView(sc, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Pages")) // A3 + A4
            val pc = card()
            toggleRow(pc, R.drawable.ic_search, "Force enable zoom", "Pinch zoom even on sites that block it. Reload the page. Per site: Site settings",
                { AppearancePrefs.forceZoom(act) }) { on -> AppearancePrefs.setForceZoom(act, on) }
            toggleRow(pc, R.drawable.ic_list, "Simplified view for web pages", "Shows a small Simplified view pill on article pages. Press and hold the pill to hide it on a site",
                { AppearancePrefs.simpleHint(act) }) { on -> AppearancePrefs.setSimpleHint(act, on) }
            toggleRow(pc, R.drawable.ic_forward, "Swipe between pages using a touchpad", "Two-finger swipe = Back / Forward. Works with touchpads that send horizontal scroll",
                { AppearancePrefs.touchpad(act) }) { on -> AppearancePrefs.setTouchpad(act, on) }
            navRow(pc, R.drawable.ic_info, "Captions", "Opens Android caption style (size, colour, background)") {
                try { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_CAPTIONING_SETTINGS)) }
                catch (_: Exception) { Util.toast(act, "Caption settings are not available on this phone") }
            }
            col.addView(pc, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Main menu"))
            val mc = card()
            toggleRow(mc, R.drawable.ic_edit, "Show Text size in main menu", "The Text size row of the page actions",
                { MenuCustom.on(act, "Text size") }) { on -> MenuCustom.setOn(act, "Text size", on) }
            col.addView(mc, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // ---------------- default zoom ----------------

        /** Comfort preview: invented sample text in three sizes, scaled by the chosen zoom. */
        private fun previewCard(): View {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface_variant), 16, 1f, act.getColor(R.color.mg_outline))
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            pvTitle = TextView(act).apply {
                text = "A quiet morning at the harbour"
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
            }
            pvSub = TextView(act).apply {
                text = "Comfort preview"
                setTextColor(act.getColor(R.color.mg_primary))
                setPadding(0, dp(2), 0, dp(6))
            }
            pvBody = TextView(act).apply {
                text = "Boats rock softly while the market opens. Pick the size that lets you read this line without leaning in."
                setTextColor(act.getColor(R.color.mg_text_dim))
            }
            box.addView(pvTitle); box.addView(pvSub); box.addView(pvBody)
            return box
        }

        private fun zoomControl(): View {
            val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            zLabel = TextView(act).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
            }
            wrap.addView(zLabel, LinearLayout.LayoutParams(MATCH, WRAP))
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(stepButton(0, "Smaller", -10), LinearLayout.LayoutParams(dp(48), dp(48)))
            val seek = SeekBar(act).apply {
                max = 20
                progressTintList = ColorStateList.valueOf(act.getColor(R.color.mg_primary))
                thumbTintList = ColorStateList.valueOf(act.getColor(R.color.mg_primary))
                progressBackgroundTintList = ColorStateList.valueOf(act.getColor(R.color.mg_outline))
                tickMark = GradientDrawable().apply { setColor(act.getColor(R.color.mg_outline)); setSize(dp(2), dp(8)) }
                contentDescription = "Default zoom"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val z = 50 + v * 10
                        if (z != zoom) { zoom = z; showZoom(false); s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } // one tick per 10 %
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) { }
                    override fun onStopTrackingTouch(s: SeekBar?) { commit() }
                })
            }
            zSeek = seek
            row.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(stepButton(R.drawable.ic_add, "Larger", 10), LinearLayout.LayoutParams(dp(48), dp(48)))
            wrap.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            zReset = TextView(act).apply {
                text = "Reset to 100 %"
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_primary))
                isClickable = true
                ripple(this)
                setOnClickListener { zoom = 100; showZoom(); commit() }
            }
            wrap.addView(zReset, LinearLayout.LayoutParams(MATCH, dp(40)))
            return wrap
        }

        /** Round 48 dp - / + button: outline mg_outline, icon mg_primary. The minus uses a drawn bar (no extra icon file). */
        private fun stepButton(icon: Int, desc: String, delta: Int): View {
            val b = FrameLayout(act).apply {
                background = round(0, 24, 1f, act.getColor(R.color.mg_outline))
                isClickable = true
                contentDescription = desc
                ripple(this)
                setOnClickListener {
                    val z = (zoom + delta).coerceIn(50, 250)
                    if (z != zoom) { zoom = z; it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showZoom(); commit() }
                }
            }
            if (delta > 0) b.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_primary))
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            else b.addView(View(act).apply { background = round(act.getColor(R.color.mg_primary), 1) }, FrameLayout.LayoutParams(dp(14), dp(2), Gravity.CENTER))
            return b
        }

        /** Updates label, slider, preview and the reset link from the state. No animation, so Reduce motion needs nothing. */
        private fun showZoom(moveSeek: Boolean = true) {
            zLabel?.text = "$zoom %"
            if (moveSeek) zSeek?.progress = ((zoom - 50) / 10).coerceIn(0, 20)
            zReset?.visibility = if (zoom != 100) View.VISIBLE else View.GONE
            val f = zoom / 100f
            pvTitle?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * f)
            pvSub?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * f)
            pvBody?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * f)
        }

        private fun commit() {
            AppearancePrefs.setZoomDef(act, zoom)
            TextZoom.applyActive(act) // the open page follows now, other pages on load
        }

        // ---------------- saved zoom for sites ----------------

        private fun siteRow(host: String, pct: Int): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(18), dp(4), dp(6), dp(4))
                isClickable = true
                ripple(this)
                setOnClickListener { pick(host, pct) }
            }
            row.addView(TextView(act).apply {
                text = host; textSize = 15f; setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(act.getColor(R.color.mg_text))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(act).apply {
                text = "$pct %"; textSize = 13f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_primary))
                background = round(ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 0x22), 14)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            row.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = "Reset $host"
                isClickable = true
                ripple(this)
                setOnClickListener { TextZoom.reset(act, host); TextZoom.applyActive(act); fill() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            return row
        }

        /** Tap a site: choose a new size from the 10 % steps. Choosing the default size keeps the site pinned only when it differs from the default. */
        private fun pick(host: String, pct: Int) {
            val steps = (50..250 step 10).toList()
            MaterialAlertDialogBuilder(act).setTitle("Zoom · $host")
                .setSingleChoiceItems(steps.map { "$it %" }.toTypedArray(), steps.indexOf(pct)) { d, i ->
                    TextZoom.set(act, host, steps[i]); TextZoom.applyActive(act); d.dismiss(); fill()
                }
                .setNegativeButton("Cancel", null).show()
        }

        // ---------------- helpers (copied from AppearanceScreen) ----------------

        private fun sectionLabel(t: String) = TextView(act).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(act.getColor(R.color.mg_primary))
            setPadding(dp(8), dp(16), 0, dp(6))
            setCompoundDrawablesWithIntrinsicBounds(GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_toggle_on)); cornerRadius = dp(2).toFloat(); setSize(dp(4), dp(12))
            }, null, null, null)
            compoundDrawablePadding = dp(8)
        }

        private fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(act.getColor(R.color.mg_surface), 24, 1f, ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
            setPadding(dp(4), dp(10), dp(4), dp(6))
        }

        private fun note(t: String) = TextView(act).apply {
            text = t; textSize = 13f
            setTextColor(act.getColor(R.color.mg_text_dim))
            setPadding(dp(14), dp(2), dp(14), dp(6))
        }

        private fun ripple(v: View) {
            val tv = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                if (v.background == null) v.setBackgroundResource(tv.resourceId) else v.foreground = act.getDrawable(tv.resourceId)
            }
        }

        /** Row with icon, title, hint and a chevron (opens something). */
        private fun navRow(parent: LinearLayout, icon: Int, label: String, hint: String, onClick: () -> Unit) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(18), dp(6), dp(12), dp(6))
                isClickable = true
                ripple(this)
                setOnClickListener { onClick() }
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            txt.addView(TextView(act).apply { text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0) })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        private fun toggleRow(parent: LinearLayout, icon: Int, label: String, hint: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
            val sw = ThazirSwitch(act).apply { isChecked = get(); isClickable = false; isFocusable = false }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(18), dp(6), dp(16), dp(6))
                isClickable = true
                ripple(this)
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            if (!hint.isNullOrBlank()) txt.addView(TextView(act).apply {
                text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0)
            })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                set(!get())
                sw.isChecked = get()
            }
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }
}
