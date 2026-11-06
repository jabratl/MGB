package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * SOCIAL DOCK (0.0.272, C1). Switch sm_dock (default OFF) in mgb_ui.
 * A slim row above the bottom bar (same place and style as GroupStrip, S13) while the ACTIVE tab is a social tab (Social mode ON, site switch ON).
 * One round letter icon per site whose switch is ON: sites with an open tab first (violet), the others dim. The site of the active tab has a ring.
 * Tap = go to that site's open tab (another one than the active if there are several), or open its start page in a new tab. The unread number
 * from the tab title "(3) ..." (SocialBadge.count) shows as a small red pill on the icon. Follows the bottom bar: hidden when the bar hides
 * (scroll, full screen social tab). Private tabs are not listed. Not built from C1: pinned sites without an open tab (there is no pin list).
 */
object SocialDock {
    private const val KEY = "sm_dock"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    private var act: Activity? = null
    private var tm: TabManager? = null
    private var row: LinearLayout? = null
    private var box: LinearLayout? = null
    private var wanted = false
    private var sig = ""

    private fun dp(v: Int) = Util.dp(act!!, v)

    fun attach(a: Activity, t: TabManager) {
        act = a; tm = t; sig = ""; wanted = false
        val bar = a.findViewById<View>(R.id.bottom_bar) ?: return
        val parent = bar.parent as? ViewGroup ?: return
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), 0)
            background = GradientDrawable().apply {
                setColor(a.getColor(R.color.mg_surface))
                setStroke(dp(1), a.getColor(R.color.mg_outline))
                cornerRadius = dp(22).toFloat()
            }
        }
        val sv = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val b = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sv.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        r.addView(sv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        parent.addView(r, parent.indexOfChild(bar), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(10), dp(6), dp(10), 0)
        })
        row = r; box = b
        refresh()
    }

    private fun tabsOf(m: TabManager, id: String): List<TabManager.Tab> = m.all().filter { !it.priv && SocialProfiles.of(it.url)?.id == id }

    /** Rebuilds the icons when something they show changed (skipped when the signature is the same), then fixes the visibility. */
    fun refresh() {
        val a = act ?: return
        val m = tm ?: return
        val b = box ?: return
        val cur = m.active
        val curP = if (cur != null && !cur.priv) SocialProfiles.of(cur.url) else null
        wanted = on(a) && SocialMode.on(a) && curP != null && SocialProfiles.siteOn(a, curP.id)
        if (!wanted) { sync(); return }
        val sites = SocialProfiles.all.filter { SocialProfiles.siteOn(a, it.id) }
        val info = sites.map { p ->
            val ts = tabsOf(m, p.id)
            Triple(p, ts.size, ts.sumOf { SocialBadge.count(a, it.url, it.title) })
        }.sortedByDescending { it.second > 0 }
        val s = (curP?.id ?: "") + "|" + info.joinToString(",") { it.first.id + ":" + it.second + ":" + it.third }
        if (s != sig) {
            sig = s
            b.removeAllViews()
            for ((p, n, badge) in info) b.addView(item(a, m, p, n, badge, p.id == curP?.id), LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        sync()
    }

    /** Only visibility: follows the bottom bar (it hides while scrolling or in a full screen social tab) and the rules above. */
    fun sync() {
        val a = act ?: return
        val r = row ?: return
        val barOn = a.findViewById<View>(R.id.bottom_bar)?.visibility == View.VISIBLE
        val v = if (wanted && barOn) View.VISIBLE else View.GONE
        if (r.visibility != v) r.visibility = v
    }

    /** 0.0.274 (D2): smooth scroll to the top of the page (window and the main scrolling element; instant when Reduce motion is ON). */
    private fun scrollTop(a: Activity, t: TabManager.Tab) {
        val b = if (TabPrefs.reduce(a)) "auto" else "smooth"
        try {
            t.web.evaluateJavascript("(function(){try{window.scrollTo({top:0,behavior:'$b'});var e=document.scrollingElement;if(e&&e.scrollTop>0)e.scrollTo({top:0,behavior:'$b'});}catch(x){}})()", null)
        } catch (_: Throwable) { }
    }

    private fun item(a: Activity, m: TabManager, p: SocialProfiles.Profile, open: Int, badge: Int, active: Boolean): View {
        val f = FrameLayout(a)
        f.addView(TextView(a).apply {
            text = p.label.trim().take(1).uppercase()
            textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
            setTextColor(a.getColor(if (open > 0) R.color.mg_on_primary else R.color.mg_text_dim))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(a.getColor(if (open > 0) R.color.mg_primary else R.color.mg_surface_variant))
                setStroke(dp(if (active) 2 else 1), a.getColor(if (active) R.color.mg_text else R.color.mg_outline))
            }
            contentDescription = p.label + (if (open > 0) "" else " (open)")
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                val cur = m.active
                val ts = tabsOf(m, p.id)
                val target = ts.firstOrNull { t -> t.id != cur?.id } ?: ts.firstOrNull()
                if (target == null) SocialMode.open(a, m, p.startUrl) else if (target.id != cur?.id) m.switchTo(target)
                else if (SocialMode.touchOn(a)) scrollTop(a, target) // 0.0.274 (D2): tap the icon of the site you are on = back to the top
            }
        }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        if (badge > 0) f.addView(TextView(a).apply {
            text = SocialBadge.label(badge); textSize = 9f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD; setTextColor(a.getColor(R.color.mg_on_primary))
            minWidth = dp(14); setPadding(dp(3), 0, dp(3), 0)
            background = GradientDrawable().apply { setColor(a.getColor(R.color.mg_error)); cornerRadius = dp(8).toFloat() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(14), Gravity.TOP or Gravity.END))
        return f
    }
}
