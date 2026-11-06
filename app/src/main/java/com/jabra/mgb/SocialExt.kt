package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/**
 * EXTERNAL LINK SHEET (0.0.271, D4, PARTIAL). Switch sm_ext (default OFF) in mgb_ui.
 * On a social tab (Social mode ON, site switch ON) a tap on a link that leaves the site (other host, not a page of the same social profile)
 * shows a small bottom sheet: Open in new tab (the feed tab stays where it is) / Open here / Cancel. Facebook and Instagram link wrappers
 * (l.facebook.com, lm.facebook.com, l.instagram.com with ?u=) are looked through, so the sheet shows the real address.
 * Built: the sheet with the choices. NOT built from the D4 plan: a small overlay browser inside the sheet (the new tab is a normal tab).
 * Only taps (hasGesture) are caught: script redirects are untouched. Popup tabs, extension pages and Open here (loadUrl) are not looped.
 */
object SocialExt {
    private const val KEY = "sm_ext"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    private val WRAPPERS = setOf("l.facebook.com", "lm.facebook.com", "l.instagram.com")

    /** The real address behind a Facebook / Instagram link wrapper, else the address itself. */
    private fun target(u: Uri): Uri {
        val h = (u.host ?: "").lowercase()
        if (h !in WRAPPERS) return u
        val t = u.getQueryParameter("u") ?: return u
        return if (t.startsWith("http", true)) Uri.parse(t) else u
    }

    /** true = the sheet is shown and the caller cancels this navigation. false = nothing changed (pref OFF, same site, no tap ...). */
    fun intercept(ctx: Context, tabUrl: String?, raw: Uri, gesture: Boolean, view: WebView, openNew: (String) -> Unit): Boolean {
        try {
            if (!on(ctx) || !gesture || ctx !is Activity || !SocialMode.on(ctx)) return false
            val from = SocialProfiles.of(tabUrl) ?: return false
            if (!SocialProfiles.siteOn(ctx, from.id)) return false
            val u = target(raw)
            val sc = u.scheme?.lowercase()
            if (sc != "http" && sc != "https") return false
            val to = SocialProfiles.of(u.toString())
            if (to != null && to.id == from.id) return false
            show(ctx, u.toString(), view, openNew)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun show(act: Activity, url: String, view: WebView, openNew: (String) -> Unit) {
        val d = BottomSheetDialog(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Util.dp(act, 16), Util.dp(act, 14), Util.dp(act, 16), Util.dp(act, 18))
            background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface))
                val r = Util.dp(act, 20).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        box.addView(TextView(act).apply {
            text = "Open this link?"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(act.getColor(R.color.mg_text))
        })
        box.addView(TextView(act).apply {
            text = Uri.parse(url).host ?: url; textSize = 12f; maxLines = 2; setTextColor(act.getColor(R.color.mg_text_dim))
            setPadding(0, Util.dp(act, 2), 0, Util.dp(act, 12))
        })
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(act, 48)).apply { bottomMargin = Util.dp(act, 8) } }
        box.addView(MaterialButton(act).apply {
            text = "Open in new tab"; isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(act.getColor(R.color.mg_primary)); setTextColor(act.getColor(R.color.mg_on_primary))
            setOnClickListener { d.dismiss(); openNew(url) }
        }, lp())
        box.addView(MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Open here"; isAllCaps = false; setTextColor(act.getColor(R.color.mg_text))
            setOnClickListener { d.dismiss(); view.loadUrl(url) }
        }, lp())
        box.addView(TextView(act).apply {
            text = "Cancel"; textSize = 14f; gravity = Gravity.CENTER; isClickable = true; setTextColor(act.getColor(R.color.mg_text_dim))
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(act, 44)))
        d.setContentView(box)
        d.show()
    }
}
