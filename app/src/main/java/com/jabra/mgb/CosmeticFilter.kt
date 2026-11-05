package com.jabra.mgb

import android.content.Context
import android.webkit.WebView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S42: hides cookie banners and empty ad spaces with one CSS rule injected into the page.
 * Ad spaces follow the ad blocker (ON) and the per-site "allow ads" flag (Page info > Site configuration).
 * Cookie banners: own switch (Settings), default ON.
 */
object CosmeticFilter {
    private const val PREFS = "mgb_ui"

    private const val COOKIE_CSS =
        "#onetrust-banner-sdk,#onetrust-consent-sdk,.onetrust-pc-dark-filter,#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay," +
        ".cc-window,.cc-banner,#cookie-law-info-bar,#cookieChoiceInfo,.cookie-banner,#cookie-banner,.cookie-notice,#cookie-notice," +
        "#cookiebanner,.qc-cmp2-container,#qc-cmp2-container,#truste-consent-track,.truste_box_overlay,.osano-cm-window,#didomi-host," +
        ".fc-consent-root,#usercentrics-root,.iubenda-cs-container,#iubenda-cs-banner,.cky-consent-container,#cmplz-cookiebanner-container," +
        ".cmplz-cookiebanner,#moove_gdpr_cookie_info_bar,#cookie-consent,.cookie-consent,.gdpr-banner,#gdpr-cookie-notice,[id^=sp_message_container]"

    private const val ADS_CSS =
        "ins.adsbygoogle,.adsbygoogle,[id^=google_ads_iframe],[id^=div-gpt-ad],.ad-banner,.banner-ad,.advertisement,[data-ad-slot]," +
        "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],iframe[src*=\"adservice.google\"]"

    fun cookieOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hide_cookie", true)
    fun setCookie(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hide_cookie", on).apply() }

    fun adsOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hide_ad_space", true)
    fun setAds(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hide_ad_space", on).apply() }

    /** Safe to call many times per page: the script marks the document and does nothing the second time. */
    fun inject(ctx: Context, view: WebView?, url: String?) {
        if (view == null || url == null || !url.startsWith("http")) return
        val sel = ArrayList<String>()
        if (cookieOn(ctx)) sel.add(COOKIE_CSS)
        if (adsOn(ctx) && AdBlock.enabled && !SiteConfig.has(url, SiteConfig.ADS_OK) && !AdBlock.isAllowed(SiteConfig.hostOf(url))) sel.add(ADS_CSS)
        val extra = if (adsOn(ctx) && AdBlock.enabled && !SiteConfig.has(url, SiteConfig.ADS_OK) && !AdBlock.isAllowed(SiteConfig.hostOf(url))) FilterList.cosmeticCss(SiteConfig.hostOf(url)) else "" // S74
        if (sel.isEmpty() && extra.isEmpty()) return
        val css = (if (sel.isEmpty()) "" else sel.joinToString(",") + "{display:none!important}") + extra
        val js = "(function(){try{if(window.__mgbCos)return;window.__mgbCos=1;var s=document.createElement('style');" +
            "s.textContent='" + css.replace("\\", "\\\\").replace("'", "\\'") + "';" +
            "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
        try { view.evaluateJavascript(js, null) } catch (_: Exception) { }
    }

    /** Settings: list of sites where ads are allowed, tap one to block ads there again. */
    fun allowList(ctx: Context, onChange: () -> Unit) {
        val hosts = SiteConfig.hostsWith(SiteConfig.ADS_OK)
        if (hosts.isEmpty()) {
            MaterialAlertDialogBuilder(ctx).setTitle("Sites with ads allowed")
                .setMessage("None yet. Open a site, then Menu > Page info > Site configuration > allow ads.")
                .setPositiveButton("OK", null).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Sites with ads allowed")
            .setItems(hosts.toTypedArray()) { _, i ->
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(hosts[i])
                    .setMessage("Block ads on this site again?")
                    .setPositiveButton("Block again") { _, _ ->
                        if (SiteConfig.has("https://" + hosts[i], SiteConfig.ADS_OK)) SiteConfig.toggle(hosts[i], SiteConfig.ADS_OK)
                        onChange()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
