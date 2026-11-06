package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * SOCIAL SITES PAGE (0.0.228, P4): full-screen page (same chrome as Appearance): master switch (SocialMode.on), one switch row per
 * site of SocialProfiles.all (key sm_s_<id>) with an "Open" pill, and a short "what does not work" text.
 * Entry: Settings > General > Social sites. Back = Settings again. State lives in the prefs, so a rotation just rebuilds the page.
 */
object SocialScreen {
    private const val TAG = "MGB.social"
    private var current: Screen? = null

    fun open(host: Activity, onClose: (() -> Unit)? = null) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host, onClose)
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

    /** LinearLayout that never gets wider than [cap] px (560 dp cap on tablets / landscape). */
    private class CapColumn(ctx: Context, private val cap: Int) : LinearLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val size = MeasureSpec.getSize(w)
            super.onMeasure(if (size > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else w, h)
        }
    }

    private class Screen(private val act: Activity, private val onClose: (() -> Unit)?) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val siteRows = ArrayList<View>()
        private var skipClose = false

        private fun dp(v: Int) = Util.dp(act, v)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
            setOnDismissListener {
                if (current === this) current = null
                if (!skipClose) try { onClose?.invoke() } catch (_: Exception) { }
            }
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
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = "Social sites"
                textSize = 22f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            val col = CapColumn(act, dp(560)).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(32))
            }
            root.addView(ScrollView(act).apply {
                isFillViewport = false
                addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill(col)
        }

        override fun onBackPressed() { dismiss() }

        private fun fill(col: LinearLayout) {
            siteRows.clear()
            col.removeAllViews()

            col.addView(sectionLabel("Master switch"))
            val master = card()
            switchRow(master, R.drawable.ic_desktop, "Social sites", "Mobile or phone-fit page, app links and Open-app bars blocked",
                null, { SocialMode.on(act) }) { on -> SocialMode.setOn(act, on); applyDim() }
            col.addView(master, LinearLayout.LayoutParams(MATCH, WRAP))

            val feeds = SocialProfiles.all.filter { it.defaultKind != SocialMode.Kind.CHAT }
            val chats = SocialProfiles.all.filter { it.defaultKind == SocialMode.Kind.CHAT }
            for ((title, list) in listOf("Feed sites" to feeds, "Chat sites (desktop page, fits the phone)" to chats)) {
                if (list.isEmpty()) continue
                col.addView(sectionLabel(title))
                val c = card()
                for (p in list) {
                    val row = switchRow(c, R.drawable.ic_desktop, p.label, hintOf(p), { openSite(p) },
                        { SocialProfiles.siteOn(act, p.id) }) { on -> SocialProfiles.setSiteOn(act, p.id, on) }
                    siteRows.add(row)
                }
                col.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            col.addView(sectionLabel("Good to know"))
            val info = card()
            info.addView(TextView(act).apply {
                text = "No push notifications, sounds or badge: a browser tab cannot receive them when it is closed.\n\n" +
                    "\"Continue with Google\" is often blocked in a browser tab. Use email and password.\n\n" +
                    "A site may show a security check after the browser identity changes. Hiding the Open-app bars depends on the live " +
                    "site and may need a small fix when the site changes.\n\n" +
                    "A site that is switched off here opens like any other page."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(18), dp(8), dp(16), dp(10))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
            applyDim()
        }

        private fun hintOf(p: SocialProfiles.Profile): String = when {
            p.sub.isNotBlank() -> p.sub
            p.id == "facebook" -> "Feed, chat pages fit the phone"
            p.id == "messenger" -> "Chat that fits the phone"
            p.id == "instagram" -> "Feed and messages without the app nag"
            else -> ""
        }

        /** Site rows look dimmed while the master switch is OFF (they can still be changed). */
        private fun applyDim() {
            val a = if (SocialMode.on(act)) 1f else 0.5f
            for (r in siteRows) r.alpha = a
        }

        /** "Open" pill: opens the site start page in a new tab (switches the site and the master switch ON). */
        private fun openSite(p: SocialProfiles.Profile) {
            val tabs = BookmarksScreen.tabsRef
            if (tabs == null) { Util.toast(act, "Open a tab first"); return }
            if (!SocialProfiles.siteOn(act, p.id)) SocialProfiles.setSiteOn(act, p.id, true)
            SocialMode.open(act, tabs, p.startUrl)
            skipClose = true
            SettingsScreen.closeAll()
            dismiss()
        }

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
            background = round(act.getColor(R.color.mg_surface), 20, 1f, ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        private fun rippleBg(v: View) {
            val tv = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) v.setBackgroundResource(tv.resourceId)
        }

        /** Switch row (>= 52 dp): icon + label + hint + optional "Open" pill + ThazirSwitch. Row tap toggles. Returns the row. */
        private fun switchRow(parent: LinearLayout, icon: Int, label: String, hint: String, onOpen: (() -> Unit)?,
                              get: () -> Boolean, set: (Boolean) -> Unit): View {
            val sw = ThazirSwitch(act).apply { isChecked = get(); isClickable = false; isFocusable = false }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(6), dp(12), dp(6))
                isClickable = true
                rippleBg(this)
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            if (hint.isNotBlank()) txt.addView(TextView(act).apply {
                text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0)
            })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (onOpen != null) {
                val pill = TextView(act).apply {
                    text = "Open"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(R.color.mg_primary))
                    gravity = Gravity.CENTER
                    background = round(act.getColor(R.color.mg_surface_variant), 16, 1f, act.getColor(R.color.mg_outline))
                    contentDescription = "Open $label"
                }
                val touch = FrameLayout(act).apply { isClickable = true; setOnClickListener { onOpen() } }
                touch.addView(pill, FrameLayout.LayoutParams(dp(56), dp(32), Gravity.CENTER))
                row.addView(touch, LinearLayout.LayoutParams(dp(64), dp(44)).apply { marginStart = dp(6) })
            }
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                set(!get())
                sw.isChecked = get()
            }
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            return row
        }
    }
}
