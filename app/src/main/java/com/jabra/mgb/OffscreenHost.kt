package com.jabra.mgb

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * S9: chrome.offscreen + chrome.runtime.getContexts.
 *  - createDocument: one hidden WebView on the extension origin (V77 uses offhost.html, which puts every stream
 *    download into its own iframe: offscreen.html = downloader.js + remux.js + lib/mux.min.js).
 *  - Like a Chrome offscreen document the page only gets chrome.runtime (ContentInjector.attachExt with
 *    offscreen = true), so V77 offscreen-shim.js builds storage / downloads / dnr on its own: it asks the
 *    background (off-rpc) and the engine calls the real API. No V77 change needed.
 *  - Network = EngineHost.proxyFor (CORS-free, header rules), the same as the engine.
 *  - Only ONE document may exist (Chrome rule); closeDocument drops it (blobs of frames are gone with it).
 */
object OffscreenHost {
    private const val TAG = "MGB.offscreen"
    private const val LOAD_TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var inited = false
    private var web: WebView? = null
    private var docUrl = ""
    private var loaded = false
    private var pending: ChromeBridge.Call? = null
    private var timeout: Runnable? = null

    fun isOffscreen(w: WebView): Boolean = w === web

    private fun ui(b: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) b() else main.post { b() }
    }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext

        ChromeBridge.on("offscreen.createDocument") { c ->
            ui { try { create(c) } catch (e: Exception) { c.fail(e.message ?: e.toString()) } }
        }
        ChromeBridge.on("offscreen.closeDocument") { c ->
            ui {
                if (web == null) c.fail("No current offscreen document.")
                else { close("closeDocument"); c.ok(null) }
            }
        }
        ChromeBridge.onSync("offscreen.hasDocument") { web != null }
        ChromeBridge.onSync("runtime.getContexts") { c -> contexts(c.args.optJSONObject(0)) }
    }

    // ---------- document ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(c: ChromeBridge.Call) {
        val a = app
        if (a == null) { c.fail("Offscreen host not ready"); return }
        val raw = c.args.optJSONObject(0)?.optString("url", "") ?: ""
        if (web != null) { c.fail("Only a single offscreen document may be created."); return }
        val rel = raw.removePrefix(ContentInjector.EXT_BASE).trimStart('/')
        val path = rel.substringBefore('?').substringBefore('#')
        if (path.isEmpty() || path.contains("..") || path.contains("://")) {
            c.fail("Invalid offscreen document url: $raw"); return
        }
        try { a.assets.open("ext/$path").close() } catch (e: Exception) {
            c.fail("Offscreen document not found: $path"); return
        }

        val w = WebView(a)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val line = "off: ${m.message()}  (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})"
                EngineHost.addLog(line)
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Diag.e("engine", line) // S83
                Log.d(TAG, line.take(500))
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                EngineHost.ext(request.url)?.let { return it }
                return EngineHost.proxyFor(request, -1)
            }

            // the document and its iframes stay on the extension origin
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith(ContentInjector.EXT_BASE)

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== web || url == null || !url.startsWith(ContentInjector.EXT_BASE)) return
                loaded = true
                timeout?.let { main.removeCallbacks(it) }
                timeout = null
                val p = pending
                pending = null
                EngineHost.addLog("offscreen: document loaded ($docUrl)")
                p?.ok(null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view === web && request.isForMainFrame) {
                    EngineHost.addLog("offscreen: load error " + error.description)
                    Diag.e("engine", "offscreen load error " + error.description) // S83
                    main.post { if (view === web) close("load error") }
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                EngineHost.addLog("offscreen: render process gone")
                Diag.e("engine", "offscreen render process gone") // S83
                main.post { if (view === web) close("render process gone") }
                return true
            }
        }
        ContentInjector.attachExt(a, w, offscreen = true) // window.chrome = runtime only
        web = w
        docUrl = ContentInjector.EXT_BASE + rel
        loaded = false
        pending = c
        val t = Runnable {
            EngineHost.addLog("offscreen: document did not load in ${LOAD_TIMEOUT_MS / 1000}s")
            close("load timeout")
        }
        timeout = t
        main.postDelayed(t, LOAD_TIMEOUT_MS)
        // a WebView without a window may pause its timers: keep it running while downloads are going on
        try { w.onResume(); w.resumeTimers() } catch (e: Exception) { Log.e(TAG, "resume", e) }
        w.loadUrl(docUrl)
    }

    private fun close(why: String) {
        val w = web ?: return
        web = null
        loaded = false
        docUrl = ""
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        val p = pending
        pending = null
        p?.fail("Offscreen document closed before it loaded ($why)")
        ChromeBridge.unbind(w)
        try { w.stopLoading() } catch (_: Exception) { }
        w.webChromeClient = null
        main.post { try { w.destroy() } catch (e: Exception) { Log.e(TAG, "destroy", e) } }
        EngineHost.addLog("offscreen: document closed ($why)")
    }

    // ---------- runtime.getContexts ----------

    private fun strList(o: JSONObject?, key: String): List<String>? {
        val a = o?.optJSONArray(key) ?: return null
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun intList(o: JSONObject?, key: String): List<Int>? {
        val a = o?.optJSONArray(key) ?: return null
        return (0 until a.length()).map { a.optInt(it) }
    }

    private fun contexts(f: JSONObject?): JSONArray {
        val types = strList(f, "contextTypes")
        val urls = strList(f, "documentUrls")
        val tabs = intList(f, "tabIds")
        val out = JSONArray()
        fun add(type: String, url: String, tabId: Int, frameId: Int, key: Any) {
            if (types != null && type !in types) return
            if (urls != null && url !in urls) return
            if (tabs != null && tabId !in tabs) return
            out.put(
                JSONObject().put("contextType", type).put("contextId", "ctx-" + System.identityHashCode(key))
                    .put("documentUrl", url).put("documentOrigin", ContentInjector.EXT_ORIGIN)
                    .put("tabId", tabId).put("windowId", if (tabId > 0) 1 else -1)
                    .put("frameId", frameId).put("incognito", false)
            )
        }
        val ow = web
        if (ow != null) add("OFFSCREEN_DOCUMENT", docUrl, -1, 0, ow)
        val all = ChromeBridge.contexts().filter { it.kind == ChromeBridge.Kind.EXT }
        all.firstOrNull { EngineHost.isEngine(it.web) }
            ?.let { add("BACKGROUND", ContentInjector.EXT_BASE + "_engine.html", -1, -1, it) }
        for (x in all) {
            if (!x.isMainFrame || EngineHost.isEngine(x.web) || x.web === ow) continue
            if (ExtScreen.isScreen(x.web)) add("POPUP", ExtScreen.urlOf(x.web), -1, 0, x) // S10 window
            else if (x.tabId > 0) add("TAB", TabsApi.senderOf(x).optString("url"), x.tabId, 0, x)
        }
        return out
    }

    fun status(): String =
        "\nOffscreen document: " + (if (web == null) "none" else if (loaded) "running" else "loading") +
            "  frames: " + ChromeBridge.contexts().count { it.web === web }
}
