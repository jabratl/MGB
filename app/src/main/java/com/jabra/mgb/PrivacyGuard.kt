package com.jabra.mgb

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * S68: PRIVACY GUARD. The shield button in the address bar opens one bottom sheet for the current site:
 * a big counter (blocked on this site, count-up animation), the ad / tracker blocker (global + this site),
 * and every privacy switch in one place (ad spaces, cookie banners, HTTPS, scripts, desktop site).
 * Switches act at once; the page reloads once when the sheet closes (only if something needs it).
 * Reuses the existing switches: AdBlock, SiteConfig, CosmeticFilter, HttpsOnly. Nothing new is stored except AdBlock "total".
 */
object PrivacyGuard {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** 0 = no shield (home, extension page, not a web page), 1 = guarding this site, 2 = blocker off here. */
    fun state(tab: TabManager.Tab?): Int {
        if (tab == null || tab.home || tab.isExt || !tab.url.startsWith("http")) return 0
        return if (AdBlock.enabled && !SiteConfig.has(tab.url, SiteConfig.ADS_OK)) 1 else 2
    }

    fun show(act: Activity, tabs: TabManager, onClose: () -> Unit) {
        val t = tabs.active
        if (t == null || state(t) == 0) {
            Util.toast(act, "No Privacy Guard here")
            return
        }
        val host = SiteConfig.hostOf(t.url)
        if (host.isEmpty()) return
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cOnPrimary = act.getColor(R.color.mg_on_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cVar = act.getColor(R.color.mg_surface_variant)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)
        fun sp(tv: TextView, v: Float) = tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

        var dirty = false      // something changed that needs a page reload
        var reloaded = false   // setDesktop already reloaded the page
        val d = BottomSheetDialog(act)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(20)) }

        // ---- header: shield badge + title + host
        val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val badge = FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(cPrimary, 0x26)) }
        }
        badge.addView(ImageView(act).apply { setImageResource(R.drawable.ic_shield); setColorFilter(cPrimary) },
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        head.addView(badge, LinearLayout.LayoutParams(dp(52), dp(52)))
        head.addView(TextView(act).apply {
            text = "PRIVACY GUARD"; setTextColor(cPrimary); sp(this, 12f); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        head.addView(TextView(act).apply { text = host; setTextColor(cDim); sp(this, 12f); gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val big = TextView(act).apply { text = "0"; setTextColor(cText); sp(this, 38f); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        head.addView(big)
        head.addView(TextView(act).apply { text = "blocked on this site"; setTextColor(cDim); sp(this, 12f); gravity = Gravity.CENTER })
        val totalLine = TextView(act).apply { setTextColor(cDim); sp(this, 12f); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(4)) }
        fun setTotal() { AdBlock.flushTotal(); totalLine.text = "${AdBlock.totalBlocked} ads and trackers blocked in total" }
        setTotal()
        head.addView(totalLine)
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        val n = AdBlock.blockedOn(host)
        if (n > 0) ValueAnimator.ofInt(0, n).apply { // smooth count-up
            duration = 650; interpolator = DecelerateInterpolator()
            addUpdateListener { big.text = (it.animatedValue as Int).toString() }
            start()
        }

        // ---- small builders
        fun label(title: String) = TextView(act).apply {
            text = title.uppercase(); setTextColor(cPrimary); sp(this, 11f); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f; setPadding(dp(8), dp(16), 0, dp(6))
        }
        fun card() = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(20).toFloat(); setStroke(dp(1), cLine) }
            setPadding(0, dp(4), 0, dp(4))
        }
        fun divider() = View(act).apply { setBackgroundColor(cLine) }
        fun switchOf(on: Boolean) = MaterialSwitch(act).apply {
            isChecked = on; isClickable = false; isFocusable = false
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(st, intArrayOf(cPrimary, cVar))
            thumbTintList = ColorStateList(st, intArrayOf(cOnPrimary, cDim))
        }
        /** One row: icon badge, title, one line of help, switch. [onTap] gets the NEW state and returns the state really set. */
        fun switchRow(icon: Int, title: String, info: String, on: Boolean, onTap: (Boolean) -> Boolean): LinearLayout {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56); setPadding(dp(14), dp(8), dp(12), dp(8))
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            }
            val b = FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(cPrimary, 0x26)) }
            }
            b.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(cPrimary) }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            row.addView(b, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(act).apply { text = title; setTextColor(cText); sp(this, 15f); maxLines = 2 })
            if (info.isNotBlank()) texts.addView(TextView(act).apply { text = info; setTextColor(cDim); sp(this, 12f); maxLines = 3 })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            val sw = switchOf(on)
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                sw.isChecked = onTap(!sw.isChecked)
            }
            return row
        }
        fun line(c: LinearLayout) = c.addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(64); marginEnd = dp(14) })

        // ---- card 1: the blocker
        val blockCard = card()
        val siteRow = switchRow(R.drawable.ic_shield, "Blocker on this site", "Turn off if a page or login stops working.",
            !SiteConfig.has(t.url, SiteConfig.ADS_OK)) { _ ->
            SiteConfig.toggle(host, SiteConfig.ADS_OK); dirty = true
            !SiteConfig.has(t.url, SiteConfig.ADS_OK)
        }
        siteRow.alpha = if (AdBlock.enabled) 1f else 0.45f
        blockCard.addView(switchRow(R.drawable.ic_shield, "Ads and trackers blocker",
            "Ads and pop-ups blocked and trackers prevented from profiling you.", AdBlock.enabled) { _ ->
            val on = AdBlock.toggle(); dirty = true
            siteRow.animate().alpha(if (on) 1f else 0.45f).setDuration(180).start()
            on
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        line(blockCard)
        blockCard.addView(siteRow, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(blockCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // ---- card 2: privacy control
        col.addView(label("Privacy control"))
        val pc = card()
        pc.addView(switchRow(R.drawable.ic_close, "Hide distracting items", "Empty ad spaces are hidden from pages.",
            CosmeticFilter.adsOn(act)) { v -> CosmeticFilter.setAds(act, v); dirty = true; v })
        line(pc)
        pc.addView(switchRow(R.drawable.ic_close, "Hide cookie banners", "Cookie pop-ups are hidden from pages.",
            CosmeticFilter.cookieOn(act)) { v -> CosmeticFilter.setCookie(act, v); dirty = true; v })
        line(pc)
        pc.addView(switchRow(R.drawable.ic_lock, "Always use HTTPS", "Opens the secure version of a site when it has one (next page).",
            HttpsOnly.on(act)) { v -> HttpsOnly.set(act, v); v })
        line(pc)
        pc.addView(switchRow(R.drawable.ic_info, "Scripts on this site", "Turn off to stop JavaScript here (some pages will not work).",
            !SiteConfig.has(t.url, SiteConfig.JS_OFF)) { v ->
            if (SiteConfig.has(t.url, SiteConfig.JS_OFF) == v) SiteConfig.toggle(host, SiteConfig.JS_OFF)
            t.web.settings.javaScriptEnabled = v; dirty = true
            v
        })
        line(pc)
        pc.addView(switchRow(R.drawable.ic_desktop, "Desktop site", "Always open this site with the desktop layout.",
            SiteConfig.has(t.url, SiteConfig.DESKTOP)) { v ->
            if (SiteConfig.has(t.url, SiteConfig.DESKTOP) != v) SiteConfig.toggle(host, SiteConfig.DESKTOP)
            tabs.setDesktop(t, v); reloaded = true // setDesktop reloads the page itself
            v
        })
        col.addView(pc, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- page info link (certificate, cookies, scripts)
        col.addView(label("This site"))
        val more = card()
        val infoRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52); setPadding(dp(18), dp(8), dp(12), dp(8))
            val tv = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            setOnClickListener { d.dismiss(); PageInfo.show(act, tabs) }
        }
        infoRow.addView(TextView(act).apply { text = "Certificate, cookies and scripts"; setTextColor(cText); sp(this, 15f) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        infoRow.addView(ImageView(act).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(cDim) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        more.addView(infoRow, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(more, LinearLayout.LayoutParams(MATCH, WRAP))

        d.setOnDismissListener {
            AdBlock.flushTotal()
            if (dirty && !reloaded) t.web.reload()
            onClose()
        }
        d.setContentView(ScrollView(act).apply { addView(col) })
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
