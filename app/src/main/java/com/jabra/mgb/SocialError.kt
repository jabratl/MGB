package com.jabra.mgb

import android.content.Context
import android.text.TextUtils
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * APP-LIKE ERROR SCREEN (0.0.263, D5, partial). Switch sm_err (default OFF) in mgb_ui.
 * When a SOCIAL page (Social mode ON + site switch ON) fails to load because of the connection
 * (host not found, cannot connect, timeout, network I/O) the WebView shows a small page in the app colours
 * (mg_* tokens, dark + light follow the phone) with "No connection" and a "Try again" button instead of the system error page.
 * Try again = location.replace(original address). Only main-frame errors; other errors (certificate, http 4xx / 5xx) are untouched.
 * NOT built from the D5 plan: the site-coloured start screen instead of a white flash.
 */
object SocialError {
    private const val KEY = "sm_err"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    private fun hex(c: Context, res: Int): String = String.format("#%06X", 0xFFFFFF and c.getColor(res))

    /** Hook: TabManager.onReceivedError (after the Diag line). Never throws. */
    fun show(c: Context, view: WebView?, r: WebResourceRequest, code: Int, isExt: Boolean) {
        try {
            if (view == null || isExt || !r.isForMainFrame || !on(c) || !SocialMode.on(c)) return
            if (code != WebViewClient.ERROR_HOST_LOOKUP && code != WebViewClient.ERROR_CONNECT &&
                code != WebViewClient.ERROR_TIMEOUT && code != WebViewClient.ERROR_IO) return
            val url = r.url.toString()
            val p = SocialProfiles.of(url) ?: return
            if (!SocialProfiles.siteOn(c, p.id)) return
            val msg = when (code) {
                WebViewClient.ERROR_TIMEOUT -> "The site took too long to answer."
                WebViewClient.ERROR_HOST_LOOKUP -> "The site address could not be found. Check your internet connection."
                else -> "Could not connect. Check your internet connection."
            }
            val jsUrl = org.json.JSONObject.quote(url).replace("<", "\\u003c")
            val html = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<style>html,body{height:100%;margin:0}body{display:flex;flex-direction:column;align-items:center;justify-content:center;" +
                "background:" + hex(c, R.color.mg_bg) + ";color:" + hex(c, R.color.mg_text) + ";font-family:sans-serif;text-align:center;padding:24px;box-sizing:border-box}" +
                "h1{font-size:22px;margin:0 0 8px}p{margin:4px 0;color:" + hex(c, R.color.mg_text_dim) + ";font-size:15px}" +
                "button{margin-top:24px;min-height:48px;padding:0 28px;border:0;border-radius:24px;font-size:16px;background:" +
                hex(c, R.color.mg_primary) + ";color:" + hex(c, R.color.mg_on_primary) + "}</style></head><body>" +
                "<h1>No connection</h1><p>" + TextUtils.htmlEncode(p.label) + "</p><p>" + TextUtils.htmlEncode(msg) + "</p>" +
                "<button onclick=\"location.replace(" + jsUrl.replace("\"", "&quot;") + ")\">Try again</button></body></html>"
            view.post { try { view.loadDataWithBaseURL(url, html, "text/html", "utf-8", url) } catch (e: Exception) { } }
        } catch (e: Exception) {
            android.util.Log.w("Thazir.err", "show failed", e)
        }
    }
}
