package com.jabra.mgb

import android.webkit.WebView

/** 0.0.105: tells shim/yt-adblock.js (document-start script on youtube.com) if it should work on this page. */
object YtAds {
    /** Cheap: only does something on a youtube.com page. Blocker off or "allow ads" on the site = script is switched off. */
    fun flag(view: WebView?, url: String?) {
        if (view == null || url == null) return
        val h = SiteConfig.hostOf(url)
        if (h != "youtube.com" && !h.endsWith(".youtube.com")) return
        val off = !AdBlock.enabled || SiteConfig.has(url, SiteConfig.ADS_OK)
        try { view.evaluateJavascript("window.__mgbYtOff=" + (if (off) 1 else 0), null) } catch (_: Exception) { }
    }
}
