package com.jabra.mgb

import android.content.Context
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * WORD MUTE (0.0.267, C6). Pref sm_mute in mgb_ui = the words, one per line (max 20, each max 40 letters). Empty = feature off.
 * A post (feed item) whose text contains one of the words (any case) is hidden on Facebook, Instagram, Reddit, X and LinkedIn
 * (Social mode, site switch ON). The check runs in the page only: the words never leave the phone, nothing is sent.
 * Selectors in [SEL] are ASSUMPTIONS about live sites and WILL change (a fix = edit one line). A post that was already hidden stays hidden
 * until the next page load, even if its word is removed. Password fields are never read (only post containers are looked at).
 */
object SocialMute {
    private const val KEY = "sm_mute"
    const val MAX = 20
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun words(c: Context): List<String> =
        (sp(c).getString(KEY, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.take(MAX)

    fun count(c: Context): Int = words(c).size

    /** Text from the edit box: one word per line (commas also split). Empty text removes the list. */
    fun setWords(c: Context, text: String) {
        val l = text.split("\n", ",").map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(MAX)
        if (l.isEmpty()) sp(c).edit().remove(KEY).apply() else sp(c).edit().putString(KEY, l.joinToString("\n")).apply()
    }

    private val SEL: Map<String, String> = mapOf(
        "facebook" to "div[role=article]",
        "instagram" to "article",
        "reddit" to "shreddit-post,article",
        "x" to "article[data-testid=tweet]",
        "linkedin" to ".feed-shared-update-v2"
    )

    private const val JS = "(function(){try{var W=__W__;var S=__S__;window.__mgSwL=W;" +
        "if(window.__mgSw){window.__mgSw();return;}" +
        "var run=function(){var l=window.__mgSwL||[];var els=document.querySelectorAll(S);" +
        "for(var i=0;i<els.length;i++){var e=els[i];if(e.__mgm===1)continue;var t=(e.innerText||'').toLowerCase();if(!t)continue;" +
        "for(var j=0;j<l.length;j++){if(t.indexOf(l[j].toLowerCase())>=0){e.__mgm=1;e.style.setProperty('display','none','important');break;}}}};" +
        "window.__mgSw=run;run();var tm=0;var o=new MutationObserver(function(){if(tm)return;tm=setTimeout(function(){tm=0;run();},300);});" +
        "o.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}})();"

    /** Called from SocialMode.inject (social page, site switch ON). No words or no rule for this site = nothing. */
    fun inject(c: Context, view: WebView?, id: String?) {
        if (view == null || id == null) return
        val w = words(c)
        if (w.isEmpty()) return
        val sel = SEL[id] ?: return
        view.evaluateJavascript(JS.replace("__W__", JSONArray(w).toString()).replace("__S__", JSONObject.quote(sel)), null)
    }
}
