package com.jabra.mgb

import android.content.Context
import android.webkit.WebView

/**
 * SOCIAL BADGE + DATA SAVER (0.0.231, P7).
 *
 * Unread badge: social sites put the unread number in the page title, like "(3) Facebook". The tab switcher card shows that
 * number as a small pill. The number is read from the tab title each time the card is drawn (no stored count), so it goes away
 * by itself when the site drops the "(3)" or the tab leaves a social site. Only while the tab exists: no push, nothing in the background.
 * Counts only when Social sites is ON, this site's switch is ON and the badge switch (sm_badge, default ON) is ON.
 *
 * Data saver (key sm_save, default OFF = nothing is added to any page): stops video autoplay on social pages and sets preload=none.
 * A video the person starts (tap / key within 2 s before "play") plays normally. Page script guards itself (window.__mgSs); like the
 * blur script it keeps a batched observer for the page life (infinite scroll), never touches password fields, sends nothing out.
 * Turning the switch OFF takes effect on the next page load.
 */
object SocialBadge {
    private const val K_BADGE = "sm_badge"
    private const val K_SAVE = "sm_save"
    /** Keys for the SettingsScreen RESET list. */
    val keys: List<String> = listOf(K_BADGE, K_SAVE)

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun badgeOn(c: Context): Boolean = sp(c).getBoolean(K_BADGE, true)
    fun setBadgeOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_BADGE, v).apply() }
    fun saveOn(c: Context): Boolean = sp(c).getBoolean(K_SAVE, false)
    fun setSaveOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_SAVE, v).apply() }

    // "(3) Title", "(12+) Title", "[3] Title": 1 to 3 digits only (a year like "(2024) ..." is not a count)
    private val RE = Regex("^\\s*[(\\[]\\s*(\\d{1,3})\\s*\\+?\\s*[)\\]]")

    /** Unread number from the tab title, 0 = none / not a social tab / switched off. */
    fun count(ctx: Context, url: String?, title: String?): Int {
        if (title.isNullOrEmpty() || !badgeOn(ctx) || !SocialMode.on(ctx)) return 0
        val p = SocialProfiles.of(url) ?: return 0
        if (!SocialProfiles.siteOn(ctx, p.id)) return 0
        return RE.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Text for the pill: 1..99, then "99+". */
    fun label(n: Int): String = if (n > 99) "99+" else n.toString()

    private const val SAVE_JS = "(function(){try{if(window.__mgSs)return;window.__mgSs=1;var last=0;" +
        "var mark=function(){last=Date.now();};" +
        "['touchstart','pointerdown','click','keydown'].forEach(function(n){document.addEventListener(n,mark,true);});" +
        "var prep=function(v){try{if(v.__mgd)return;v.__mgd=1;v.removeAttribute('autoplay');v.autoplay=false;if(v.preload!=='none')v.preload='none';}catch(e){}};" +
        "var scan=function(){var l=document.getElementsByTagName('video');for(var i=0;i<l.length;i++)prep(l[i]);};" +
        "document.addEventListener('play',function(e){var v=e.target;if(!v||v.tagName!=='VIDEO')return;" +
        "if(v.__mgu)return;if(Date.now()-last<2000){v.__mgu=1;return;}try{v.pause();}catch(x){}},true);" +
        "scan();var t=0;var o=new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=0;scan();},250);});" +
        "o.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}})();"

    /** Called from SocialMode.inject (only on a social page where the site switch is ON). Switch OFF = nothing. */
    fun injectSaver(ctx: Context, view: WebView?) {
        if (view == null || !saveOn(ctx)) return
        view.evaluateJavascript(SAVE_JS, null)
    }
}
