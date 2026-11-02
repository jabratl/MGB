package com.jabra.mgb

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * S19: simple ad / tracker blocker for web tabs.
 *  - sub-requests (images, scripts, frames, XHR) whose host is on the built-in list (or a sub-domain of it) get an empty answer
 *  - never the page itself (main frame), never extension pages, never a request to the same site as the page
 *  - on/off switch is saved; the counter is per app run
 * The list is short on purpose (well-known ad networks, pop-up networks and trackers); video player ad SDKs
 * and tag managers are left out so players and logins keep working.
 */
object AdBlock {
    private const val PREFS = "mgb_adblock"

    @Volatile var enabled: Boolean = true
        private set
    private val count = AtomicInteger(0)
    val blocked: Int get() = count.get()

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("on", true)
    }

    fun toggle(): Boolean {
        enabled = !enabled
        prefs?.edit()?.putBoolean("on", enabled)?.apply()
        return enabled
    }

    /** Runs on the WebView network thread: only plain fields are read here. */
    fun shouldBlock(req: WebResourceRequest, pageUrl: String?): Boolean {
        if (!enabled || req.isForMainFrame) return false
        if (SiteConfig.has(pageUrl, SiteConfig.ADS_OK)) return false // S29: site config "allow ads here"
        val host = req.url.host?.lowercase() ?: return false
        if (!listed(host)) return false
        val ph = try { android.net.Uri.parse(pageUrl ?: "").host?.lowercase() } catch (e: Exception) { null }
        if (ph != null && (ph == host || ph.endsWith(".$host") || host.endsWith(".$ph"))) return false
        count.incrementAndGet()
        return true
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun listed(host: String): Boolean {
        var h = host
        while (true) {
            if (h in HOSTS) return true
            val i = h.indexOf('.')
            if (i < 0 || i == h.length - 1) return false
            h = h.substring(i + 1)
        }
    }

    private val HOSTS: HashSet<String> = (
        // Google / big networks
        "doubleclick.net googlesyndication.com googleadservices.com google-analytics.com adservice.google.com " +
        "2mdn.net moatads.com adnxs.com adsrvr.org advertising.com criteo.com criteo.net taboola.com outbrain.com " +
        "scorecardresearch.com quantserve.com quantcount.com rubiconproject.com pubmatic.com openx.net casalemedia.com " +
        "amazon-adsystem.com smartadserver.com adform.net serving-sys.com mathtag.com bidswitch.net contextweb.com " +
        "yieldmo.com teads.tv sharethrough.com media.net adcolony.com applovin.com mopub.com inmobi.com vungle.com " +
        "chartboost.com supersonicads.com adroll.com adtechus.com agkn.com bluekai.com demdex.net everesttech.net " +
        "krxd.net lijit.com liadm.com adsymptotic.com tapad.com rlcdn.com zemanta.com sitescout.com yieldlab.net " +
        "33across.com indexww.com gumgum.com triplelift.com kargo.com nativo.com conversantmedia.com sovrn.com " +
        "undertone.com adition.com adspirit.de adscale.de emxdgt.com 360yield.com spotxchange.com spotx.tv " +
        "adsafeprotected.com doubleverify.com iasds01.com imrworldwide.com chartbeat.net bounceexchange.com " +
        // social ad / measurement
        "ads-twitter.com ads.linkedin.com analytics.tiktok.com ads.tiktok.com business-api.tiktok.com ads.pinterest.com " +
        "ads.yahoo.com adtech.yahooinc.com analytics.yahoo.com gemini.yahoo.com " +
        // Yandex / Russia / Asia
        "mc.yandex.ru an.yandex.ru yandexadexchange.net adfox.ru adriver.ru ad.mail.ru top-fwz1.mail.ru " +
        "adhigh.net mobvista.com mintegral.com pangleglobal.com byteoversea.com ads.vungle.com unityads.unity3d.com " +
        // pop-up / push / adult ad networks
        "popads.net popcash.net propellerads.com exoclick.com exosrv.com juicyads.com trafficjunky.net trafficfactory.biz " +
        "adsterra.com hilltopads.net clickadu.com adcash.com revcontent.com mgid.com zedo.com adblade.com yllix.com " +
        "onclickads.net a-ads.com adk2.com bidvertiser.com ero-advertising.com plugrush.com realsrv.com tsyndicate.com " +
        "acscdn.com clickaine.com richpush.co pushprofit.net pushwoosh.com pushengage.com " +
        "syndication.exoclick.com magsrv.com adxpansion.com adtng.com adspyglass.com ad-maven.com admaven.com " +
        "popunderjs.com popmyads.com trafmag.com tubecorporate.com ads.exdynsrv.com exdynsrv.com ap.mnaspm.com " +
        "mnaspm.com dolohen.com syndication.realsrv.com rtmark.net yadro.ru liveinternet.ru " +
        // analytics / session recording
        "hotjar.com hotjar.io mixpanel.com crazyegg.com mouseflow.com fullstory.com luckyorange.com inspectlet.com " +
        "smartlook.com clarity.ms bat.bing.com " +
        "stats.wp.com pixel.wp.com pixel.facebook.com an.facebook.com ads.facebook.com " +
        "cdn.mxpnl.com api.mixpanel.com sb.scorecardresearch.com b.scorecardresearch.com" )
        .split(" ").filter { it.isNotBlank() }.toHashSet()
}
