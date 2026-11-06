package com.jabra.mgb

import android.content.Context
import android.view.MotionEvent

/**
 * A3: helpers for the Accessibility page. (1) Force enable zoom: JS that rewrites the viewport meta so pinch zoom works,
 * with a per-site opt-out (prefs mgb_zoom_opt, host -> true). (2) Touchpad swipe: two-finger horizontal scroll = back / forward.
 * Both are OFF by default (ap_force_zoom, ap_touchpad) and cost nothing when OFF.
 */
object ZoomTools {
    /** Rewrites every viewport meta: user-scalable=yes, maximum-scale=10. Pages without a viewport meta are not touched. Observer stops after 5 s. */
    const val FORCE_JS = "(function(){try{" +
        "var fix=function(m){var c=m.getAttribute('content')||'';" +
        "c=c.replace(/,?\\s*user-scalable\\s*=\\s*[^,;]+/ig,'').replace(/,?\\s*maximum-scale\\s*=\\s*[^,;]+/ig,'').replace(/,?\\s*minimum-scale\\s*=\\s*[^,;]+/ig,'').replace(/^[\\s,;]+/,'');" +
        "c=(c?c+', ':'')+'user-scalable=yes, maximum-scale=10';if(m.getAttribute('content')!==c)m.setAttribute('content',c);};" +
        "var all=function(){var l=document.querySelectorAll('meta[name=viewport]');for(var i=0;i<l.length;i++)fix(l[i]);};" +
        "all();var o=new MutationObserver(function(){all();});" +
        "o.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});" +
        "setTimeout(function(){o.disconnect();},5000);}catch(e){}})();"

    private fun sp(c: Context) = c.getSharedPreferences("mgb_zoom_opt", Context.MODE_PRIVATE)
    fun optedOut(c: Context, host: String): Boolean = host.isNotEmpty() && sp(c).getBoolean(host, false)
    fun setOptOut(c: Context, host: String, off: Boolean) {
        if (host.isEmpty()) return
        if (off) sp(c).edit().putBoolean(host, true).apply() else sp(c).edit().remove(host).apply()
    }

    private var acc = 0f
    private var lastAt = 0L
    private var lastNav = 0L

    /** Called from MainActivity.dispatchGenericMotionEvent only when ap_touchpad is ON. true = event used (page changed). */
    fun onScroll(ev: MotionEvent, tabs: TabManager): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (h == 0f) return false
        val now = ev.eventTime
        if (now - lastAt > 300) acc = 0f // debounce: a new gesture starts after a short pause
        lastAt = now
        if (now - lastNav < 600) return true // swallow the tail of the gesture that already navigated
        acc += h
        if (Math.abs(acc) < 2f) return false
        val fwd = acc > 0f
        acc = 0f
        val t = tabs.active ?: return false
        if (fwd) { if (!tabs.canFwd(t)) return false; tabs.fwd(t) } else { if (!tabs.canBack(t)) return false; tabs.back(t) }
        lastNav = now
        return true
    }
}
