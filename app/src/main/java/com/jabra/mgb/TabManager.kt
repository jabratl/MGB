package com.jabra.mgb

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * S3: owns all tabs (one WebView each), the tab switcher sheet, popup (window.open) handling
 * and session save/restore. `ctx` must be the Activity.
 */
class TabManager(
    private val ctx: Context,
    private val container: FrameLayout,
    private val listener: Listener
) {
    interface Listener {
        fun onTabsChanged()
        fun onActiveChanged(tab: Tab?)
        fun onPageInfo(tab: Tab)
    }

    class Tab(val id: Int, val web: WebView, val openerId: Int) {
        var url: String = ""
        var title: String = ""
        var progress: Int = 100
        /** Restored tabs are loaded lazily, the first time they are selected. */
        var pending: String? = null
        val loading: Boolean get() = progress < 100
        /** Extension page (downloader.html, player.html ...) served by EngineHost. */
        val isExt: Boolean get() = url.startsWith(ContentInjector.EXT_ORIGIN)
    }

    companion object {
        const val HOME = "https://www.google.com"
        const val MAX_TABS = 30
        private const val PREFS = "mgb_session"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var nextId = 1 // never reused while the app runs (the engine keys its state by tab id)
    }

    private val tabs = ArrayList<Tab>()

    var active: Tab? = null
        private set
    val count: Int get() = tabs.size

    /** The browser Activity (S10: extension windows are shown over it). */
    fun host(): Context = ctx

    fun all(): List<Tab> = tabs.toList()
    fun byId(id: Int): Tab? = tabs.firstOrNull { it.id == id }

    // ---------- tabs ----------

    fun newTab(url: String?, openerId: Int = 0, select: Boolean = true): Tab? {
        if (tabs.size >= MAX_TABS) {
            Util.toast(ctx, "Too many tabs")
            return null
        }
        val web = createWebView()
        val tab = Tab(nextId++, web, openerId)
        web.webViewClient = client(tab)
        web.webChromeClient = chrome(tab)
        tabs.add(tab)
        ChromeBridge.bindTab(web, tab.id)
        if (url != null) tab.url = url
        TabsApi.onCreated(tab) // S6
        if (select) switchTo(tab)
        if (url != null) web.loadUrl(url)
        listener.onTabsChanged()
        return tab
    }

    fun switchTo(tab: Tab) {
        if (active === tab) return
        active?.let { container.removeView(it.web) }
        active = tab
        container.addView(tab.web, FrameLayout.LayoutParams(MATCH, MATCH))
        tab.web.requestFocus()
        TabsApi.onActivated(tab) // S6
        tab.pending?.let { tab.pending = null; tab.web.loadUrl(it) }
        listener.onActiveChanged(tab)
    }

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasActive = active === tab
        tabs.removeAt(idx)
        TabsApi.onRemoved(tab) // S6
        if (wasActive) {
            container.removeView(tab.web)
            active = null
        }
        tab.web.stopLoading()
        ChromeBridge.unbind(tab.web)
        tab.web.webChromeClient = null
        container.post { tab.web.destroy() }
        if (wasActive) {
            val next = tabs.firstOrNull { it.id == tab.openerId }
                ?: tabs.getOrNull(minOf(idx, tabs.size - 1))
            if (next != null) switchTo(next) else newTab(HOME)
        }
        listener.onTabsChanged()
    }

    fun destroyAll() {
        ExtScreen.closeAll() // S10
        for (t in tabs) {
            TabsApi.onRemoved(t) // S6
            container.removeView(t.web)
            ChromeBridge.unbind(t.web)
            t.web.destroy()
        }
        tabs.clear()
        active = null
    }

    // ---------- session ----------

    fun saveSession() {
        val arr = JSONArray()
        var activeIdx = 0
        for (t in tabs) {
            val u = t.web.url ?: t.url
            if (u.isBlank() || u == "about:blank" || u.startsWith(ContentInjector.EXT_ORIGIN)) continue
            if (t === active) activeIdx = arr.length()
            arr.put(u)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tabs", arr.toString())
            .putInt("active", activeIdx)
            .apply()
    }

    /** Returns false when there is nothing to restore (caller opens the home page). */
    fun restoreSession(): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString("tabs", null) ?: return false
        val arr = try { JSONArray(raw) } catch (_: Exception) { return false }
        if (arr.length() == 0) return false
        val act = sp.getInt("active", 0).coerceIn(0, arr.length() - 1)
        var target: Tab? = null
        for (i in 0 until arr.length()) {
            val t = newTab(null, 0, false) ?: break
            val u = arr.getString(i)
            t.url = u
            t.pending = u
            if (i == act) target = t
        }
        val sel = target ?: tabs.firstOrNull() ?: return false
        switchTo(sel)
        return true
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        ContentInjector.attach(ctx, w) // S5: V77 content scripts + chrome shim (before first load)
        return w
    }

    private fun client(tab: Tab) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return false
            // S6: a web page may not navigate to the extension origin (only our own code opens those)
            if (u.toString().startsWith(ContentInjector.EXT_ORIGIN) && !tab.isExt) return true
            when (u.scheme?.lowercase()) {
                null, "http", "https", "about", "data", "blob", "file", "javascript" -> return false
            }
            if (request.isForMainFrame) openExternal(u.toString())
            return true
        }

        // S6: extension pages (https://appassets.androidplatform.net/ext/...) are served from assets
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val r = request ?: return null
            if (!r.url.toString().startsWith(ContentInjector.EXT_ORIGIN)) {
                // S8: extension page -> network goes through the engine proxy (CORS + header rules); web page -> sniff
                if (tab.isExt) return EngineHost.proxyFor(r, tab.id)
                Sniffer.onRequest(tab, r)
                return null
            }
            if (!r.isForMainFrame && !tab.isExt) return null
            return EngineHost.ext(r.url)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            tab.url = url ?: tab.url
            tab.progress = 0
            TabsApi.onUpdated(tab, JSONObject().put("status", "loading").put("url", tab.url)) // S6
            listener.onPageInfo(tab)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            tab.url = url ?: tab.url
            tab.progress = 100
            TabsApi.onUpdated(tab, JSONObject().put("status", "complete")) // S6
            listener.onPageInfo(tab)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            tab.url = url ?: tab.url
            listener.onPageInfo(tab)
        }
    }

    private fun chrome(tab: Tab) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            tab.progress = newProgress
            listener.onPageInfo(tab)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            tab.title = title ?: ""
            TabsApi.onUpdated(tab, JSONObject().put("title", tab.title)) // S6
            listener.onPageInfo(tab)
        }

        // window.open / target=_blank: only on a real tap (blocks script popups)
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val t = newTab(null, tab.id, true) ?: return false
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = t.web
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            tabs.firstOrNull { it.web === window }?.let { close(it) }
        }
    }

    private fun openExternal(url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            ctx.startActivity(i)
        } catch (_: Exception) {
            Util.toast(ctx, "No app to open this link")
        }
    }

    // ---------- tab switcher ----------

    fun showSwitcher() {
        val dlg = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_tabs, null)
        val list = v.findViewById<LinearLayout>(R.id.tabs_list)
        val title = v.findViewById<TextView>(R.id.tabs_title)
        v.findViewById<View>(R.id.tabs_new).setOnClickListener {
            newTab(HOME)
            dlg.dismiss()
        }
        fun render() {
            list.removeAllViews()
            title.text = "Tabs (${tabs.size})"
            for (t in tabs.toList()) list.addView(row(t, dlg) { render() })
        }
        render()
        dlg.setContentView(v)
        dlg.behavior.skipCollapsed = true
        dlg.show()
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun titleOf(t: Tab): String =
        t.title.ifBlank { Util.host(t.url) }.ifBlank { "New tab" }

    private fun row(t: Tab, dlg: BottomSheetDialog, onChanged: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Util.dp(ctx, 60)
            setPadding(Util.dp(ctx, 16), Util.dp(ctx, 6), Util.dp(ctx, 4), Util.dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            if (t === active) setBackgroundColor(ctx.getColor(R.color.mg_surface_variant))
            val out = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                foreground = ctx.getDrawable(out.resourceId)
            }
            setOnClickListener { switchTo(t); dlg.dismiss() }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = titleOf(t)
            setTextColor(ctx.getColor(R.color.mg_text))
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = Util.host(t.url)
            setTextColor(ctx.getColor(R.color.mg_text_dim))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val x = TextView(ctx).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.mg_text_dim))
            layoutParams = LinearLayout.LayoutParams(Util.dp(ctx, 48), Util.dp(ctx, 48))
            setOnClickListener { close(t); onChanged() }
        }
        row.addView(col)
        row.addView(x)
        return row
    }
}
