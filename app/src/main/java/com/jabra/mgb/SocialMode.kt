package com.jabra.mgb

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * SOCIAL MODE (0.0.224; 0.0.225: sites come from SocialProfiles.kt, per-site switch sm_s_<id>; 0.0.226-227: banner words + CSS per site, more rows; 0.0.229: media blur via SocialBlur): Facebook, Messenger chat and Instagram in a normal tab, without the Desktop site switch.
 *
 * Why it is needed: Facebook's mobile site has no chat (it sends you to the Messenger app), and Facebook / Instagram show
 * "Open app" banners and app redirects. The real chat only opens for a desktop user agent, but a desktop page is tiny on a phone.
 *
 * What it does (only when the switch is ON, key sm_on in mgb_ui, default OFF = old behaviour):
 *  - Chat pages (facebook.com/messages, messenger.com): desktop user agent + a "width=device-width" viewport, so the chat
 *    fits the phone. Every other Facebook page and Instagram: a mobile user agent without the WebView marker ("; wv").
 *    The user agent follows the address of each page, so tapping the chat icon switches by itself (and back).
 *  - Any other site: a user agent set by this file is put back to the default.
 *  - A tab the person put in Desktop site mode by hand is never touched.
 *  - Links to the Facebook / Messenger / Instagram apps (fb://, fb-messenger://, instagram://, intent:// of those apps)
 *    on a social page are not opened: you stay in the browser.
 *  - A small script hides the "Open app / Use the app / Get Messenger" banner (fixed bar, found by its text).
 * Not possible in a browser: background notifications / push (that is what the real Lite app has).
 * Hooks in TabManager: shouldOverrideUrlLoading (ensure, blockApp), onPageStarted (ensure), onProgressChanged + onPageFinished (inject),
 * doUpdateVisitedHistory (onRoute, 0.0.235: in-page navigation such as pushState on Facebook / Instagram / Reddit / X).
 * 0.0.236: a per-site "Site fix" (extra banner texts + CSS, SocialProfiles.fixWords / fixCss) is added to the built-in ones.
 */
object SocialMode {
    private const val KEY = "sm_on"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    /** 0.0.234 (P9): "Keep social logins separate" (default OFF). ON = a NEW tab on a social site gets its own WebView profile social_<id> (own cookies). */
    private const val K_SEP = "sm_sep"
    fun sepOn(c: Context): Boolean = sp(c).getBoolean(K_SEP, false)
    fun setSepOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_SEP, v).apply() }
    /** false = this WebView has no multi-profile (screen shows "Not supported on this phone"). */
    fun sepSupported(): Boolean = try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (_: Throwable) { false }
    /** Profile name for a tab that opens [url] now, or null = normal shared profile (switch OFF, Social OFF, site OFF, not a social site, no support). */
    fun profileName(c: Context, url: String?): String? {
        if (url == null || !on(c) || !sepOn(c) || !sepSupported()) return null
        val p = SocialProfiles.of(url) ?: return null
        if (!SocialProfiles.siteOn(c, p.id)) return null
        return "social_" + p.id
    }
    /** Cookie header for [u] from the profile that a tab on [tabUrl] uses (SocialNsfw re-downloads pictures with the page's cookies). */
    fun cookies(c: Context, tabUrl: String, u: String): String? = try {
        val cm = profileName(c, tabUrl)?.let { ProfileStore.getInstance().getProfile(it)?.cookieManager }
        (cm ?: CookieManager.getInstance()).getCookie(u)
    } catch (_: Throwable) { null }

    /** Per-site switch (0.0.228, P4): thin wrappers over SocialProfiles (key sm_s_<id>, not set = ON). */
    fun siteOn(c: Context, id: String): Boolean = SocialProfiles.siteOn(c, id)
    fun setSiteOn(c: Context, id: String, v: Boolean) { SocialProfiles.setSiteOn(c, id, v) }

    enum class Kind { NONE, FEED, CHAT, INSTA }

    /** Start pages for the shortcuts (Command Center). */
    const val FB_HOME = "https://m.facebook.com/"
    const val FB_CHAT = "https://www.facebook.com/messages/"
    const val IG_HOME = "https://www.instagram.com/"
    const val IG_DM = "https://www.instagram.com/direct/inbox/"

    /** Which kind of page this address is (table: SocialProfiles). Only https pages count. Ignores the per-site switch. */
    fun kind(url: String?): Kind {
        val p = SocialProfiles.of(url) ?: return Kind.NONE
        val path = try { Uri.parse(url).path ?: "" } catch (_: Exception) { "" }
        return p.kindOf(path)
    }

    /** Same as [kind] but NONE when the person switched this site off (SocialProfiles.siteOn). */
    private fun kindFor(ctx: Context, url: String?): Kind {
        val p = SocialProfiles.of(url) ?: return Kind.NONE
        if (!SocialProfiles.siteOn(ctx, p.id)) return Kind.NONE
        return kind(url)
    }

    // WebViews whose user agent this file changed (so a page of another site gets the default back, nothing else is reset)
    private val applied = WeakHashMap<WebView, Boolean>()
    private var mUa: String? = null

    /** Default WebView user agent without the "; wv" marker and "Version/4.0": looks like Chrome on Android to Facebook / Instagram. */
    private fun mobileUa(ctx: Context): String {
        mUa?.let { return it }
        val base = try { WebSettings.getDefaultUserAgent(ctx) }
        catch (e: Exception) { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
        val ua = base.replace("; wv)", ")").replace("Version/4.0 ", "")
        mUa = ua
        return ua
    }

    /**
     * Sets the user agent for this address. true = it changed, the caller must load the address again (the new user agent
     * is used from the next request). false = nothing to do (switch OFF, Desktop site chosen by hand, already right, not a social page).
     */
    fun ensure(ctx: Context, view: WebView, url: String, handDesktop: Boolean): Boolean {
        if (handDesktop || !on(ctx) || !url.startsWith("http", true)) return false
        val s = view.settings
        val k = kindFor(ctx, url)
        if (k == Kind.NONE) {
            if (applied.remove(view) == null) return false
            s.userAgentString = null // default again
            return true
        }
        val want = if (k == Kind.CHAT) BrowserExtras.desktopUa(ctx) else mobileUa(ctx)
        if (s.userAgentString == want) return false
        s.userAgentString = want
        if (k == Kind.CHAT) { s.useWideViewPort = true; s.loadWithOverviewMode = true } // the viewport script then makes the chat fit; feed / Instagram keep their own mobile viewport
        applied[view] = true
        return true
    }

    /** true = this link opens a Facebook / Messenger / Instagram app and the tab is on a social page: do not open it. */
    fun blockApp(ctx: Context, tabUrl: String, u: Uri): Boolean {
        if (!on(ctx) || kindFor(ctx, tabUrl) == Kind.NONE) return false
        val s = u.scheme?.lowercase() ?: return false
        if (s in SocialProfiles.allSchemes) return true
        if (s == "intent" || s == "market") {
            val t = u.toString()
            return SocialProfiles.allIntentPkgs.any { t.contains(it) }
        }
        return false
    }

    // Viewport for the chat page: desktop pages have none, so WebView would draw them 980 px wide and tiny.
    private const val VIEWPORT_JS = "(function(){try{if(window.__mgSv)return;window.__mgSv=1;" +
        "var c='width=device-width, initial-scale=1';" +
        "var fix=function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
        "if(m.getAttribute('content')!==c)m.setAttribute('content',c);};" +
        "fix();var o=new MutationObserver(fix);o.observe(document.documentElement,{childList:true,subtree:true});" +
        "setTimeout(function(){o.disconnect();},8000);}catch(e){}})();"

    // "Open app" banner: a button or link with that text inside a small fixed / sticky bar. Looks for 40 s, then stops.
    // 0.0.226: the app names in the text come from the site row (SocialProfiles.Profile.appWords).
    // 0.0.235 (U1): again = true restarts the 40 s scan after an in-page route change (counter window.__mgSbN, timer window.__mgSbI).
    // 0.0.236 (U2): phrases = extra whole button texts typed by the person (Site fix), compared in lower case.
    private val bannerCache = HashMap<String, String>()
    private fun bannerJs(words: List<String>, phrases: List<String> = emptyList(), again: Boolean = false): String {
        val w = words.filter { it.matches(Regex("[a-z0-9]+")) }
        val key = w.joinToString("|") + "#" + phrases.joinToString("|") + "#" + again
        return bannerCache.getOrPut(key) {
            val names = if (w.isEmpty()) "" else "( " + w.joinToString("| ") + ")?"
            val ex = org.json.JSONArray(phrases).toString()
            "(function(){try{" +
            "var re=/^(open|use|get|install|continue in|open in)( the)?" + names + "( app)?$/i;var ex=" + ex + ";" +
            "var scan=function(){var l=document.querySelectorAll('a,button,[role=button]');" +
            "for(var i=0;i<l.length;i++){var e=l[i];var t=(e.textContent||'').trim();if(t.length>40||!(re.test(t)||ex.indexOf(t.toLowerCase())>=0))continue;" +
            "var p=e;for(var d=0;d<8&&p&&p!==document.body;d++,p=p.parentElement){var cs=getComputedStyle(p);" +
            "if(cs.position==='fixed'||cs.position==='sticky'){var r=p.getBoundingClientRect();" +
            "if(r.height<window.innerHeight*0.4&&(p.innerText||'').length<200){p.style.setProperty('display','none','important');}break;}}}};" +
            "var tick=function(){scan();if(++window.__mgSbN>40){clearInterval(window.__mgSbI);window.__mgSbI=0;}};" +
            (if (again) "if(window.__mgSb){window.__mgSbN=0;scan();if(!window.__mgSbI)window.__mgSbI=setInterval(tick,1000);return;}"
             else "if(window.__mgSb)return;") +
            "window.__mgSb=1;window.__mgSbN=0;scan();window.__mgSbI=setInterval(tick,1000);}catch(e){}})();"
        }
    }

    // Site CSS (0.0.226): one <style> element, guarded by its id, no loop.
    private fun cssJs(css: String): String {
        val q = css.replace("\\", "\\\\").replace("'", "\\'")
        return "(function(){try{if(document.getElementById('__mgSc'))return;var s=document.createElement('style');s.id='__mgSc';" +
            "s.textContent='" + q + "';(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
    }

    /** Runs the page scripts for this address (each one guards itself, so calling it many times is fine). */
    fun inject(ctx: Context, view: WebView?, url: String?, handDesktop: Boolean, again: Boolean = false) {
        if (view == null || handDesktop || !on(ctx)) return
        val p = SocialProfiles.of(url)
        when (kindFor(ctx, url)) {
            Kind.CHAT -> view.evaluateJavascript(if (again) "window.__mgSv=0;" + VIEWPORT_JS else VIEWPORT_JS, null)
            Kind.FEED, Kind.INSTA -> view.evaluateJavascript(bannerJs(p?.appWords ?: emptyList(), if (p != null) SocialProfiles.fixWords(ctx, p.id) else emptyList(), again), null)
            Kind.NONE -> return
        }
        val css = ((p?.css ?: "") + " " + (if (p != null) SocialProfiles.fixCss(ctx, p.id) else "")).trim() // 0.0.236 (U2): built-in + Site fix
        if (css.isNotEmpty()) view.evaluateJavascript(cssJs(css), null)
        SocialBlur.inject(ctx, view, p?.id) // 0.0.229 (P5): sensitive-media blur (mode OFF = only removes an old blur)
        SocialBadge.injectSaver(ctx, view) // 0.0.231 (P7): data saver (switch OFF = nothing)
    }

    // ---- 0.0.235 (U1): in-page navigation (history.pushState / popstate): no page load, so ensure() and the page scripts did not run ----
    private val lastKey = WeakHashMap<WebView, String>()
    private val lastRe = WeakHashMap<WebView, Long>()
    private val flips = WeakHashMap<WebView, ArrayList<Long>>()
    private fun routeKey(url: String): String = try { val u = Uri.parse(url); (u.host ?: "") + (u.path ?: "") } catch (_: Exception) { url }

    /**
     * Called from TabManager.doUpdateVisitedHistory (every address change, also in-page ones). true = the user agent changed (Facebook feed -> chat,
     * Instagram feed -> /direct/ ...): the caller loads the address again. false = nothing to load; when only the path changed, the banner / viewport
     * scripts are started again (blur, data saver and CSS guard themselves). Loop guard: at most 2 user agent flips per 15 s per tab, then this does nothing.
     */
    fun onRoute(ctx: Context, view: WebView, url: String, handDesktop: Boolean): Boolean {
        if (handDesktop || !on(ctx) || !url.startsWith("http", true)) return false
        val now = SystemClock.uptimeMillis()
        val key = routeKey(url)
        val prev = lastKey.put(view, key)
        val fl = flips.getOrPut(view) { ArrayList() }
        fl.removeAll { now - it > 15000L }
        if (fl.size >= 2) return false
        if (ensure(ctx, view, url, false)) { fl.add(now); return true }
        if (prev == null || prev == key) return false
        if (kindFor(ctx, url) == Kind.NONE) return false
        if (now - (lastRe[view] ?: 0L) < 1200L) return false
        lastRe[view] = now
        inject(ctx, view, url, false, true)
        return false
    }

    /** Command Center shortcut: switch Social mode ON (the person asked for a social page) and open it in a new tab. */
    fun open(ctx: Context, tabs: TabManager, url: String) {
        if (!on(ctx)) { setOn(ctx, true); Util.toast(ctx, "Social sites ON (Settings > General)") }
        tabs.newTab(url)
    }
}
