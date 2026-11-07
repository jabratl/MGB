package com.jabra.mgb

import android.content.Context
import android.webkit.WebView

/**
 * 0.0.302: Instagram Reels like the app.
 * 1. Tapping a reel / video tile opens the reels viewer (instagram.com/reels/<id>/: full screen, swipe up = next reel) instead of the
 *    single "Post" page (/p/<id>/ or /reel/<id>/), which cannot scroll to the next reel. Switch sm_reels (default ON, Social page > Extras).
 * 2. Pull-to-refresh is not armed on reel / story / shorts pages: their scroller is inside the page (WebView.scrollY stays 0), so every
 *    swipe down to the previous reel was counted as "pull to reload".
 */
object SocialReels {
    private const val K = "sm_reels"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(K, true)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K, v).apply() }

    private val NO_PULL = Regex("^https?://([^/]+\\.)?(instagram\\.com|facebook\\.com|tiktok\\.com|youtube\\.com)/(?:[^/?#]+/)?(reels?|stories|shorts|watch|video)(/|\\?|#|$)", RegexOption.IGNORE_CASE)
    /** true = this page scrolls inside itself (reels / shorts / stories): pull-to-refresh stays off. */
    fun noPull(url: String?): Boolean = url != null && NO_PULL.containsMatchIn(url)

    private const val JS = "(function(){try{" +
        "var R=/^\\/(?:[^\\/]+\\/)?(reel|reels|p)\\/([A-Za-z0-9_-]+)/;" +
        "function go(id){try{var k='__mgRl'+id;if(sessionStorage.getItem(k))return false;sessionStorage.setItem(k,'1');}catch(e){}location.assign('/reels/'+id+'/');return true;}" +
        "var m=R.exec(location.pathname);if(m&&m[1]==='reel'){go(m[2]);}" +
        "if(window.__mgRlOn)return;window.__mgRlOn=1;" +
        "document.addEventListener('click',function(e){try{" +
        "var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(!a)return;" +
        "var u=new URL(a.href,location.href);if(u.host.indexOf('instagram.com')<0)return;" +
        "var x=R.exec(u.pathname);if(!x||x[1]==='reels')return;" +
        "var vid=x[1]==='reel'||!!a.querySelector('svg[aria-label*=\"eel\" i],svg[aria-label*=\"lip\" i],svg[aria-label*=\"ideo\" i],video');" +
        "if(!vid)return;" +
        "e.preventDefault();e.stopImmediatePropagation();try{sessionStorage.removeItem('__mgRl'+x[2]);}catch(z){}go(x[2]);" +
        "}catch(e){}},true);" +
        "}catch(e){}})();"

    fun inject(ctx: Context, view: WebView, id: String?) {
        if (id != "instagram" || !on(ctx)) return
        try { view.evaluateJavascript(JS, null) } catch (_: Exception) { }
    }
}
