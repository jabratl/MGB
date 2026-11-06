package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * A4: "Simplified view" pill. When a loaded page looks like an article (>= 3 paragraphs, >= 600 characters) a small pill shows for 6 s;
 * tap = Reader view (PageInfo.reader), long-press = not on this site (prefs mgb_simple_opt). Switch: Accessibility, default OFF.
 * One detector run per page load. The pill lives in the web area, so it never covers the address bar (top or bottom).
 */
object SimpleHint {
    private const val TAG_PILL = "mgb_simple_pill"
    private val DETECT_JS = "(function(){try{if(document.getElementById('mgb-reader'))return false;" +
        "var r=document.querySelector('article,main,[role=main]')||document.body;var ps=r.querySelectorAll('p');var n=0,len=0;" +
        "for(var i=0;i<ps.length;i++){var t=(ps[i].innerText||'').trim();if(t.length>=40){n++;len+=t.length;}}" +
        "return n>=3&&len>=600;}catch(e){return false;}})()"
    private val seen = HashMap<Int, String>() // tab id -> url already checked

    private fun sp(c: Context) = c.getSharedPreferences("mgb_simple_opt", Context.MODE_PRIVATE)
    private fun optedOut(c: Context, host: String) = host.isNotEmpty() && sp(c).getBoolean(host, false)

    /** Called from TabManager.onPageFinished. Cheap exits first; nothing runs when the switch is OFF. */
    fun check(ctx: Context, tab: TabManager.Tab, tabs: TabManager) {
        if (!AppearancePrefs.simpleHint(ctx)) return
        val act = ctx as? Activity ?: return
        val url = tab.url
        if (tab.isExt || tab.home || !(url.startsWith("http://") || url.startsWith("https://"))) return
        if (seen[tab.id] == url) return
        seen[tab.id] = url
        val host = Util.host(url)
        if (optedOut(ctx, host)) return
        tab.web.postDelayed({ // late content: look 0.7 s after the load
            if (tabs.active !== tab || tab.url != url) return@postDelayed
            tab.web.evaluateJavascript(DETECT_JS) { res -> if (res == "true" && tabs.active === tab && tab.url == url) show(act, tab, host) }
        }, 700)
    }

    private fun show(act: Activity, tab: TabManager.Tab, host: String) {
        val area = act.findViewById<FrameLayout>(R.id.web_container)?.parent as? FrameLayout ?: return
        area.findViewWithTag<View>(TAG_PILL)?.let { area.removeView(it) }
        fun dp(v: Int) = Util.dp(act, v)
        val icon = act.getDrawable(R.drawable.ic_list)?.mutate()?.apply { setTint(act.getColor(R.color.mg_primary)); setBounds(0, 0, dp(18), dp(18)) }
        val pill = TextView(act).apply {
            tag = TAG_PILL
            text = "Simplified view"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(act.getColor(R.color.mg_text))
            gravity = Gravity.CENTER_VERTICAL
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(8)
            setPadding(dp(14), 0, dp(16), 0)
            minHeight = dp(40)
            elevation = dp(4).toFloat()
            background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface)); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), act.getColor(R.color.mg_outline))
            }
            contentDescription = "Simplified view. Tap to open reader view, press and hold to hide on this site"
            setOnClickListener { area.removeView(this); PageInfo.reader(act, tab) }
            setOnLongClickListener {
                sp(act).edit().putBoolean(host, true).apply()
                area.removeView(this)
                Util.toast(act, "Not on $host")
                true
            }
        }
        val reduce = TabPrefs.reduce(act)
        if (!reduce) pill.alpha = 0f
        area.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
        if (!reduce) pill.animate().alpha(1f).setDuration(180).start()
        pill.postDelayed({ if (pill.parent === area) area.removeView(pill) }, 6000)
    }
}
