package com.jabra.mgb

import android.content.Context
import android.webkit.WebView

/**
 * 0.0.296 (tab menu "Mute site"): sound of a site is switched off in every tab of that site, and stays off for later pages / sessions.
 * How: a small script mutes every <video> / <audio> of the page (checked every 0.7 s + before play()); "Unmute" puts each element back as it was.
 * Not covered: sound made with Web Audio only (games, some synth sites). Hosts are kept in "mgb_ui" key muted_sites (inside Reset / Backup).
 */
object SiteMute {
    const val KEY = "muted_sites"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    private fun key(host: String) = host.lowercase().removePrefix("www.")

    fun isMuted(c: Context, host: String): Boolean = host.isNotBlank() && (sp(c).getStringSet(KEY, emptySet()) ?: emptySet()).contains(key(host))

    fun toggle(c: Context, tm: TabManager, host: String) {
        val s = HashSet(sp(c).getStringSet(KEY, emptySet()) ?: emptySet())
        val k = key(host)
        val on = if (s.remove(k)) false else { s.add(k); true }
        sp(c).edit().putStringSet(KEY, s).apply()
        for (t in tm.allTabs()) if (!t.isExt && !t.home && key(Util.host(t.url)) == k) apply(t.web, on)
        Util.toast(c, if (on) "$k muted" else "$k unmuted")
    }

    /** Page start / finish hook (TabManager): only does something on a muted site. */
    fun flag(view: WebView?, url: String?) {
        if (view == null || url == null || !url.startsWith("http")) return
        if (isMuted(view.context, Util.host(url))) apply(view, true)
    }

    private fun apply(view: WebView, on: Boolean) {
        try { view.evaluateJavascript(JS.replace("__ON__", if (on) "1" else "0"), null) } catch (_: Exception) { }
    }

    private const val JS = "(function(){try{var w=window;if(!w.__mgbMuteSet){var on=0,HM=HTMLMediaElement.prototype,P=HM.play;" +
        "function one(e){try{if(on){if(e.__mgbW===undefined)e.__mgbW=e.muted;e.muted=true;}else if(e.__mgbW!==undefined){e.muted=e.__mgbW;e.__mgbW=undefined;}}catch(x){}}" +
        "function sweep(){document.querySelectorAll('video,audio').forEach(one);}" +
        "HM.play=function(){one(this);return P.apply(this,arguments);};" +
        "w.__mgbMuteSet=function(v){on=v?1:0;sweep();};" +
        "setInterval(function(){if(on)sweep();},700);}w.__mgbMuteSet(__ON__);}catch(e){}})();"
}
