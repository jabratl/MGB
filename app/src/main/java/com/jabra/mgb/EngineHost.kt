package com.jabra.mgb

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/**
 * S6: the "engine" = V77 background.js running in a hidden WebView (it was a service worker).
 *  - serves the assets/ext folder at https://appassets.androidplatform.net/ext/ (same origin as every extension page)
 *  - generates /ext/_engine.html: shared.js + sites.js + background.js as plain <script> tags (importScripts = no-op)
 *  - every http(s) request of the engine goes through proxy(): done natively, so CORS never blocks it
 *    (this is what "host_permissions: <all_urls>" gave the real extension). S8 can set requestModifier.
 *  - tabs (TabManager) use ext() to open extension pages (downloader.html, player.html ...) as tabs.
 */
object EngineHost {
    private const val TAG = "MGB.engine"
    private const val ENGINE_PAGE = "_engine.html"

    private var web: WebView? = null
    private var loader: WebViewAssetLoader? = null
    private var loaded = false
    private val logBuf = ArrayList<String>()

    /** S8 hook: change headers / referer / cookies of an engine request before it is sent. */
    var requestModifier: ((HttpURLConnection, WebResourceRequest) -> Unit)? = null

    private val SKIP_REQ = setOf("origin", "host", "content-length", "accept-encoding", "connection", "x-requested-with")
    private val SKIP_RES = setOf(
        "content-encoding", "content-length", "transfer-encoding", "connection", "keep-alive",
        "set-cookie", "access-control-allow-origin", "access-control-allow-credentials"
    )

    fun isEngine(w: WebView): Boolean = w === web

    // 0.0.110: user agent a normal tab used per host. A Cloudflare cookie (cf_clearance) is tied to that user agent, so requests of an
    // installed extension to the same host must send the same one (the extension tab itself uses the desktop user agent since 0.0.109)
    private val hostUa = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun hostOf(u: String): String = try { android.net.Uri.parse(u).host?.lowercase() ?: "" } catch (e: Exception) { "" }
    fun noteUa(url: String?, ua: String?) {
        if (url == null || ua.isNullOrBlank() || !url.startsWith("http")) return
        val h = hostOf(url)
        if (h.isNotEmpty()) hostUa[h] = ua
    }

    @Synchronized fun addLog(s: String) {
        logBuf.add(s.take(400))
        if (logBuf.size > 120) logBuf.removeAt(0)
    }

    @Synchronized private fun logText(n: Int): String = logBuf.takeLast(n).joinToString("\n")

    @SuppressLint("SetJavaScriptEnabled")
    fun start(ctx: Context) {
        if (web != null) return
        val app = ctx.applicationContext
        ContentInjector.init(app)
        TabsApi.init(app)
        DownloadsApi.init(app)
        HeaderModes.init(app)
        Sniffer.init(app)
        OffscreenHost.init(app)
        ExtBackground.init(app) // S5
        ExtApi.init(app) // S5 + S6: per-extension api, workers wake through it
        val assets = WebViewAssetLoader.AssetsPathHandler(app)
        loader = WebViewAssetLoader.Builder()
            .addPathHandler("/ext/", WebViewAssetLoader.PathHandler { path -> page(assets, path) })
            .build()
        val w = WebView(app)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val line = "${m.message()}  (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})"
                addLog(line)
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Diag.e("engine", line) // S83
                Log.d(TAG, line.take(500))
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                loader?.shouldInterceptRequest(request.url)?.let { return it }
                return proxy(request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
                addLog("engine page loaded")
                TabsApi.engineStarted()
            }
        }
        ContentInjector.attachExt(app, w) // window.chrome + bridge, only for the extension origin
        web = w
        w.loadUrl(ContentInjector.EXT_BASE + ENGINE_PAGE)
    }

    // ---------- assets ----------

    private fun page(assets: WebViewAssetLoader.AssetsPathHandler, path: String): WebResourceResponse? {
        if (path.contains("..")) return null
        if (path == ENGINE_PAGE) {
            val html = "<!doctype html><meta charset=\"utf-8\"><title>MGB engine</title>" +
                "<script src=\"shared.js\"></script><script src=\"sites.js\"></script>" +
                "<script>self.importScripts=function(){};</script>" +
                "<script src=\"background.js\"></script>"
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
        }
        // S4: /ext/<id>/... = files of an installed extension (never falls back to the built-in assets)
        val seg = path.substringBefore('/')
        if (path.contains('/') && ExtRegistry.isValidId(seg)) {
            // S5: generated page of an extension's background worker
            if (path.substringAfter('/') == ExtBackground.PAGE) return ExtBackground.page(seg) ?: notFound()
            return extFile(seg, path.substringAfter('/'))
        }
        ExtUpdater.open(path)?.let { return it } // S16: hot-updated ext files first
        return assets.handle("ext/$path")
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
        }
    }

    /** S4: one file of an enabled installed extension (safe path check is ExtRegistry.file). 404 for unknown / disabled / missing. */
    private fun extFile(id: String, rel: String): WebResourceResponse {
        val info = ExtRegistry.get(id)
        if (info == null || !info.enabled) return notFound()
        val f = ExtRegistry.file(id, rel) ?: return notFound()
        return try {
            val mime = mimeOf(f.name)
            val enc = if (mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml") "utf-8" else null
            WebResourceResponse(mime, enc, 200, "OK", hashMapOf("Cache-Control" to "no-store"), java.io.FileInputStream(f))
        } catch (e: Exception) {
            addLog("ext file $id/$rel -> $e")
            notFound()
        }
    }

    /** S12: run a short script in the engine page (for example chrome.downloads.open(id)). Result is ignored. */
    fun eval(js: String) {
        val w = web ?: return
        w.post { try { w.evaluateJavascript(js, null) } catch (e: Exception) { Log.e(TAG, "eval", e) } }
    }

    /** For TabManager: serves https://appassets.androidplatform.net/ext/... (null = not an extension URL). */
    fun ext(u: Uri): WebResourceResponse? = loader?.shouldInterceptRequest(u)

    // 0.0.108: web_accessible_resources. A WEB page (or a frame the page shows) may load a file of an installed extension
    // only if the manifest lists it (images, css, fonts, scripts, iframes that a content script puts into the page), like Chrome.
    private class War(val sig: String, val rules: List<Pair<Regex, List<String>?>>)
    private val warCache = HashMap<String, War>()

    private fun globRx(g: String): Regex =
        Regex(g.trimStart('/').split("*").joinToString(".*") { Regex.escape(it) })

    private fun warOf(id: String): War? {
        val info = ExtRegistry.get(id) ?: return null
        val mf = java.io.File(info.dir, "manifest.json")
        if (!mf.isFile || mf.length() > 2_000_000L) return null
        val sig = "${mf.lastModified()}:${mf.length()}"
        synchronized(warCache) { warCache[id]?.let { if (it.sig == sig) return it } }
        val rules = ArrayList<Pair<Regex, List<String>?>>()
        try {
            val arr = org.json.JSONObject(mf.readText()).optJSONArray("web_accessible_resources")
            if (arr != null) for (i in 0 until arr.length()) {
                val e = arr.opt(i)
                if (e is String) rules.add(Pair(globRx(e), null)) // manifest v2: plain list
                else if (e is org.json.JSONObject) { // manifest v3: {resources, matches}
                    val res = e.optJSONArray("resources") ?: continue
                    val m = e.optJSONArray("matches")
                    val ms: List<String>? = if (m == null) null else (0 until m.length()).map { m.optString(it) }
                    for (j in 0 until res.length()) rules.add(Pair(globRx(res.optString(j)), ms))
                }
            }
        } catch (_: Exception) { }
        val w = War(sig, rules)
        synchronized(warCache) { warCache[id] = w }
        return w
    }

    /** null = not allowed (the caller then loads it like any other page request). pageUrl = url of the tab that asks, referer = Referer header of the request. */
    fun extPublic(u: Uri, pageUrl: String, referer: String? = null): WebResourceResponse? {
        val path = u.path ?: return null
        if (!path.startsWith("/ext/") || path.contains("..")) return null
        val rest = path.removePrefix("/ext/")
        val id = rest.substringBefore('/')
        if (!rest.contains('/') || !ExtRegistry.isValidId(id)) return null
        val rel = rest.substringAfter('/')
        if (rel == ExtBackground.PAGE) return null
        val info = ExtRegistry.get(id)
        if (info == null || !info.enabled) return null
        // a request made by a frame of the SAME extension (its iframe inside a web page loading its own script / css) is its own file
        val own = referer != null && referer.startsWith(ContentInjector.EXT_BASE + id + "/")
        val war = if (own) null else (warOf(id) ?: return null)
        val hit = own || war!!.rules.any { (rx, ms) -> rx.matches(rel) && (ms == null || ContentInjector.urlAllowed(ms, pageUrl)) }
        if (!hit) return null
        val r = extFile(id, rel)
        if (r.statusCode != 200) return r
        val h = HashMap<String, String>(r.responseHeaders ?: emptyMap())
        h["Access-Control-Allow-Origin"] = "*" // a page script (content script fetch) reads it cross-origin
        r.responseHeaders = h
        return r
    }

    // ---------- network proxy (engine only) ----------

    private fun cors(req: Map<String, String>, resHeaders: Collection<String>): HashMap<String, String> {
        val m = HashMap<String, String>()
        m["Access-Control-Allow-Origin"] = ContentInjector.EXT_ORIGIN
        m["Access-Control-Allow-Credentials"] = "true"
        m["Access-Control-Allow-Methods"] = "GET, HEAD, POST, OPTIONS"
        req["Access-Control-Request-Headers"]?.let { m["Access-Control-Allow-Headers"] = it }
        if (resHeaders.isNotEmpty()) m["Access-Control-Expose-Headers"] = resHeaders.joinToString(", ")
        return m
    }

    private fun empty(code: Int, reason: String, req: Map<String, String>): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, cors(req, emptyList()), ByteArrayInputStream(ByteArray(0)))

    private fun proxy(req: WebResourceRequest): WebResourceResponse? = proxyFor(req, -1)

    /** S8: also used for extension pages opened as tabs (tabId = that tab), so header rules and CORS work there too. S7: extId = the extension that made the request (its own rules only). */
    fun proxyFor(req: WebResourceRequest, tabId: Int, extId: String = ContentInjector.EXT_ID): WebResourceResponse? {
        val first = req.url.toString()
        if (!first.startsWith("http://") && !first.startsWith("https://")) return null
        val rh: Map<String, String> = req.requestHeaders ?: emptyMap()
        if (req.method == "OPTIONS") return empty(204, "No Content", rh)
        if (req.method != "GET" && req.method != "HEAD") return null // body is not available here
        return try {
            var u = first
            var result: WebResourceResponse? = null
            for (hop in 0..9) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.requestMethod = req.method
                val hm = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
                for ((k, v) in rh) {
                    val lk = k.lowercase()
                    if (lk in SKIP_REQ) continue
                    if (lk == "referer" && v.startsWith(ContentInjector.EXT_ORIGIN)) continue
                    hm[k] = v
                }
                if (!hm.containsKey("Cookie")) {
                    CookieManager.getInstance().getCookie(u)?.let { hm["Cookie"] = it }
                }
                if (extId != ContentInjector.EXT_ID) hostUa[hostOf(u)]?.let { hm["User-Agent"] = it } // 0.0.110 (V77 unchanged)
                // S8: declarativeNetRequest header rules (V77 sets Referer / Origin / site headers this way)
                try { HeaderModes.apply(u, tabId, typeOf(rh), req.method, hm, extId) } catch (e: Exception) { Log.e(TAG, "headerModes", e) } // S7: extId = whose rules
                for ((k, v) in hm) c.setRequestProperty(k, v)
                try { requestModifier?.invoke(c, req) } catch (e: Exception) { Log.e(TAG, "requestModifier", e) }
                val code = c.responseCode
                if (code == 403 && extId != ContentInjector.EXT_ID) addLog("ext 403 " + hostOf(u) + " cookie=" + hm.containsKey("Cookie") + " ua=" + (hm["User-Agent"] ?: "").take(60)) // 0.0.110
                for ((k, vs) in c.headerFields) {
                    if (k != null && k.equals("set-cookie", true)) {
                        for (v in vs) CookieManager.getInstance().setCookie(u, v)
                    }
                }
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc == null) break
                    u = URL(URL(u), loc).toString()
                    continue
                }
                val body: InputStream = (if (code >= 400) c.errorStream else c.inputStream) ?: ByteArrayInputStream(ByteArray(0))
                val ct = c.contentType ?: "application/octet-stream"
                val mime = ct.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
                val enc = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
                val hdr = HashMap<String, String>()
                for ((k, vs) in c.headerFields) {
                    if (k == null || k.lowercase() in SKIP_RES) continue
                    hdr[k] = vs.joinToString(", ")
                }
                hdr.putAll(cors(rh, hdr.keys))
                val msg = c.responseMessage
                result = WebResourceResponse(mime, enc, code, if (msg.isNullOrBlank()) "OK" else msg, hdr, body)
                break
            }
            result ?: empty(502, "Bad Gateway", rh)
        } catch (e: Exception) {
            addLog("proxy " + first.take(120) + " -> " + e)
            empty(502, "Bad Gateway", rh)
        }
    }

    private fun typeOf(rh: Map<String, String>): String =
        when (rh.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value?.lowercase()) {
            "video", "audio", "track" -> "media"
            "image" -> "image"
            "script" -> "script"
            "style" -> "stylesheet"
            null, "", "empty" -> "xmlhttprequest"
            else -> "other"
        }

    // ---------- status (menu > Engine status) ----------

    fun status(): String {
        val eng = web
        val frames = ChromeBridge.contexts().count { it.web === eng }
        return "Engine: " + (if (eng == null) "not started" else if (loaded) "running" else "loading") +
            "\nEngine frames: $frames" + TabsApi.status() + HeaderModes.status() + Sniffer.status() + OffscreenHost.status() + ExtBackground.status() + "\n\n" + logText(40)
    }

    fun showStatus(a: Activity) {
        val text = status()
        AlertDialog.Builder(a)
            .setTitle("Engine status")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("MGB engine", text))
                Util.toast(a, "Copied")
            }
            .show()
    }
}
