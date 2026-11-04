package com.jabra.mgb

import android.annotation.SuppressLint
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * S3: owns all tabs (one WebView each), the tab switcher sheet, popup (window.open) handling
 * and session save/restore. `ctx` must be the Activity.
 * S13: a "home" tab (Tab.home, url HOME_PAGE) shows the native speed dial (HomeView) over a blank WebView;
 * visited pages are written to Bookmarks history.
 * S14: desktop mode per tab, fullscreen video, file chooser / permissions (BrowserExtras), plain link downloads.
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
        /** 0.0.106: opened by window.open / target=_blank: its first pages are checked against the ad lists. */
        var popup: Boolean = false
        var openerUrl: String? = null
        var title: String = ""
        /** S41: HTTPS-only: the http address we upgraded (guards a redirect loop, used for the fallback question). */
        var httpsFrom: String? = null
        var httpsAllow: String? = null
        var progress: Int = 100
        /** Restored tabs are loaded lazily, the first time they are selected. */
        var pending: String? = null
        /** S13: speed dial is shown over this tab until the first navigation. */
        var home: Boolean = false
        /** S14: desktop user agent + wide viewport (not saved in the session). */
        var desktop: Boolean = false
        /** 0.0.109: page of an installed extension: desktop user agent + wide viewport (extensions like WebToEpub refuse the mobile one). */
        var extWide: Boolean = false
        /** S26: private tab: own WebView profile (cookies / storage), no history, not saved in the session. */
        var priv: Boolean = false
        val loading: Boolean get() = progress < 100
        /** Extension page (downloader.html, player.html ...) served by EngineHost. */
        val isExt: Boolean get() = url.startsWith(ContentInjector.EXT_ORIGIN)
    }

    companion object {
        const val HOME = "https://www.google.com"
        const val PRIVATE_PROFILE = "mgb_private" // S26
        /** S13: marker url of a speed-dial tab (never loaded into the WebView). */
        const val HOME_PAGE = "about:home"
        const val MAX_TABS = 30
        private const val PREFS = "mgb_session"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var nextId = 1 // never reused while the app runs (the engine keys its state by tab id)
    }

    private val tabs = ArrayList<Tab>()
    private var homeView: HomeView? = null // S13
    /** S28: set by MainActivity: tap on the home search pill focuses the address bar. */
    var onHomeSearch: (() -> Unit)? = null
    private var customView: android.view.View? = null // S14: fullscreen video
    private var customHolder: FrameLayout? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fsPortrait = true // S29: orientation before fullscreen video

    init { Bookmarks.init(ctx); ClosedTabs.init(ctx) } // S13, S29

    var active: Tab? = null
        private set
    val count: Int get() = tabs.size

    /** The browser Activity (S10: extension windows are shown over it). */
    fun host(): Context = ctx

    fun all(): List<Tab> = tabs.toList()
    fun byId(id: Int): Tab? = tabs.firstOrNull { it.id == id }

    /** S20: Dark pages switch changed (takes effect on the next page load). */
    fun applyDarkPages() {
        val on = UiMode.darkPages(ctx)
        for (t in tabs) UiMode.applyPage(t.web, on && !t.isExt)
    }

    // ---------- tabs ----------

    fun newTab(url: String?, openerId: Int = 0, select: Boolean = true, priv: Boolean = false): Tab? {
        if (tabs.size >= MAX_TABS) {
            Util.toast(ctx, "Too many tabs")
            return null
        }
        // S26: a tab opened from a private tab stays private
        val isPriv = priv || (openerId != 0 && tabs.any { it.id == openerId && it.priv })
        if (isPriv && tabs.none { it.priv }) purgePrivate() // drop data left from an earlier run
        val web = createWebView(isPriv)
        val tab = Tab(nextId++, web, openerId)
        tab.priv = isPriv
        web.webViewClient = client(tab)
        web.webChromeClient = chrome(tab)
        web.setOnLongClickListener(longPress(tab)) // S21
        tabs.add(tab)
        ChromeBridge.bindTab(web, tab.id)
        if (url != null) tab.url = url
        if (url != null && ExtScreen.extIdOf(url) != null) { // 0.0.109
            tab.extWide = true
            web.settings.userAgentString = BrowserExtras.desktopUa(ctx)
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = true
        }
        UiMode.applyPage(web, UiMode.darkPages(ctx) && !tab.isExt) // S20
        if (url == HOME_PAGE) tab.home = true // S13: speed dial, nothing to load
        TabsApi.onCreated(tab) // S6
        if (select) switchTo(tab)
        if (url != null && !tab.home) web.loadUrl(url)
        listener.onTabsChanged()
        return tab
    }

    fun switchTo(tab: Tab) {
        if (active === tab) return
        exitFullscreen() // S14
        active?.let { TabSwitcher.snap(it); container.removeView(it.web) } // S32: thumbnail of the tab we leave
        active = tab
        container.addView(tab.web, FrameLayout.LayoutParams(MATCH, MATCH))
        tab.web.requestFocus()
        TabsApi.onActivated(tab) // S6
        tab.pending?.let { tab.pending = null; tab.web.loadUrl(it) }
        syncHome() // S13
        listener.onActiveChanged(tab)
    }

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasActive = active === tab
        if (wasActive) exitFullscreen() // S14
        if (!tab.priv && !tab.isExt && !tab.home) { // S29: recently closed tabs
            val cu = tab.web.url ?: tab.url
            if (cu.startsWith("http")) ClosedTabs.add(tab.title, cu)
        }
        tabs.removeAt(idx)
        TabsApi.onRemoved(tab) // S6
        if (wasActive) {
            container.removeView(tab.web)
            active = null
        }
        tab.web.stopLoading()
        ChromeBridge.unbind(tab.web)
        tab.web.webChromeClient = null
        container.post {
            tab.web.destroy()
            if (tab.priv && tabs.none { it.priv }) purgePrivate() // S26: last private tab closed = wipe its data
        }
        if (wasActive) {
            val next = tabs.firstOrNull { it.id == tab.openerId }
                ?: tabs.getOrNull(minOf(idx, tabs.size - 1))
            if (next != null) switchTo(next) else newTab(HOME_PAGE) // S13
        }
        listener.onTabsChanged()
    }

    fun destroyAll() {
        exitFullscreen() // S14
        ExtScreen.closeAll() // S10
        BookmarksScreen.closeAll() // S13
        homeView?.let { container.removeView(it) }
        homeView = null
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
        val idx = HashMap<Int, Int>() // S41: tab id -> position in the saved list (groups)
        var activeIdx = 0
        for (t in tabs) {
            if (t.priv) continue // S26: private tabs are never saved
            val u = if (t.home) HOME_PAGE else (t.web.url ?: t.url)
            if (u.isBlank() || u == "about:blank" || u.startsWith(ContentInjector.EXT_ORIGIN)) continue
            if (t === active) activeIdx = arr.length()
            idx[t.id] = arr.length()
            arr.put(u)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tabs", arr.toString())
            .putString("groups", TabSwitcher.exportGroups(idx))
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
        val ids = ArrayList<Int>()
        for (i in 0 until arr.length()) {
            val t = newTab(null, 0, false) ?: break
            ids.add(t.id)
            val u = arr.getString(i)
            t.url = u
            if (u == HOME_PAGE) t.home = true else t.pending = u // S13
            if (i == act) target = t
        }
        sp.getString("groups", null)?.let { g -> TabSwitcher.importGroups(g) { i -> ids.getOrNull(i) } } // S41
        val sel = target ?: tabs.firstOrNull() ?: return false
        switchTo(sel)
        return true
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    /** S26: delete the private WebView profile (cookies, storage, cache). No-op when multi profile is not supported. */
    private fun purgePrivate() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
            }
        } catch (e: Exception) { /* profile still in use or missing: ignore */ }
    }

    private fun createWebView(priv: Boolean = false): WebView {
        val w = WebView(ctx)
        if (priv) { // S26: must happen before settings / first load
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try { WebViewCompat.setProfile(w, PRIVATE_PROFILE) } catch (e: Exception) { Util.toast(ctx, "Private tab: cookies are shared with normal tabs") }
            } else Util.toast(ctx, "Private tab: this WebView shares cookies with normal tabs")
        }
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
        w.setOnScrollChangeListener { _, _, y, _, oy -> onScroll?.invoke(y - oy) } // S33: hide bar on scroll
        PullRefresh.attach(w, container) { w.reload() } // S45: pull down at the top = reload
        ContentInjector.attach(ctx, w) // S5: V77 content scripts + chrome shim (before first load)
        if (!priv) w.addJavascriptInterface(PasswordStore.Bridge(this), "MGBPW") // S30: save-login hook (before first load)
        if (!priv) w.addJavascriptInterface(WebVideo.Bridge(this), "MGBVID") // S45: in-page video button
        // S14: plain download links (and blob:/data: links) in a tab go to the native downloader
        w.setDownloadListener { url, ua, disp, mime, _ ->
            try {
                val item = DownloadsApi.startPlain(url, ua, disp, mime, w.url, w)
                if (item == null) Util.toast(ctx, "Cannot download this link")
                else Util.toast(ctx, "Downloading " + item.rel.substringAfterLast('/'))
            } catch (e: Exception) {
                Util.toast(ctx, "Download failed to start")
            }
        }
        return w
    }

    private fun client(tab: Tab) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return false
            // S6: a web page may not navigate to the extension origin (only our own code opens those)
            if (u.toString().startsWith(ContentInjector.EXT_ORIGIN) && !tab.isExt) return true
            // 0.0.106: a script / server redirect (no tap) to an ad host is cancelled; an ad pop-up tab is closed
            if (!tab.isExt && request.isForMainFrame && !request.hasGesture() &&
                AdBlock.blocksPage(u.toString(), if (tab.popup) tab.openerUrl else tab.url)) {
                Util.toast(ctx, "Redirect blocked")
                if (tab.popup) container.post { close(tab) }
                return true
            }
            // S41: HTTPS-only mode
            if (!tab.isExt && request.isForMainFrame && u.scheme == "http" && HttpsOnly.on(ctx) && tab.httpsAllow != u.host && tab.httpsFrom != u.toString()) {
                val up = HttpsOnly.upgrade(u.toString())
                if (up != null) { tab.httpsFrom = u.toString(); view?.loadUrl(up); return true }
            }
            // S39: a link / redirect to a video file or stream opens the built-in player (not Chromium's media page)
            if (!tab.isExt && request.isForMainFrame && playLink(tab, u.toString())) return true
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
                if (tab.isExt) {
                    val xid = ExtScreen.extIdOf(tab.url)
                    // S8: an installed extension page only gets the proxy (CORS-free + its header rules) for the sites in its host permissions; others load as a normal page would
                    if (xid != null && !ExtSafety.proxyAllowed(xid, r.url.toString())) return null
                    return EngineHost.proxyFor(r, tab.id, xid ?: ContentInjector.EXT_ID) // S7: rules of the extension that owns this page
                }
                if (AdBlock.shouldBlock(r, tab.url)) return AdBlock.emptyResponse() // S19
                Sniffer.onRequest(tab, r)
                return null
            }
            if (!r.isForMainFrame && !tab.isExt) return EngineHost.extPublic(r.url, tab.url, r.requestHeaders?.get("Referer")) // 0.0.108: only web_accessible_resources of installed extensions
            return EngineHost.ext(r.url)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
            super.onReceivedError(view, request, error)
            val r = request ?: return
            if (r.isForMainFrame) Diag.w("page", "load error " + (error?.errorCode ?: 0) + " " + (error?.description ?: ""), if (tab.priv) "(private tab)" else r.url.toString()) // S83
            val from = tab.httpsFrom ?: return
            if (!r.isForMainFrame || r.url.scheme != "https" || android.net.Uri.parse(from).host != r.url.host) return
            container.post { // S41: no https on this site: ask once
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("No secure connection")
                    .setMessage(r.url.host + " does not open with https. Open the unsecured http page?")
                    .setPositiveButton("Open http") { _, _ -> tab.httpsAllow = r.url.host; tab.web.loadUrl(from) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) { // S83
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true) Diag.w("page", "http " + (errorResponse?.statusCode ?: 0), if (tab.priv) "(private tab)" else request.url.toString())
        }

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean { // S83: same result as the default (false), only logged
            Diag.e("tab", if (detail?.didCrash() == true) "tab crashed (render process)" else "tab killed by the system (low memory?)", if (tab.priv) "(private tab)" else tab.url)
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (tab.popup && !tab.isExt && url != null && AdBlock.blocksPage(url, tab.openerUrl)) { // 0.0.106: ad pop-up
                Util.toast(ctx, "Pop-up blocked")
                container.post { close(tab) }
                return
            }
            tab.url = url ?: tab.url
            tab.progress = 0
            if (tab.home) { tab.home = false; syncHome() } // S13
            if (!tab.isExt && view != null) { // S41: text size of this site
                val z = TextZoom.get(ctx, Util.host(url ?: ""))
                if (view.settings.textZoom != z) view.settings.textZoom = z
            }
            if (!tab.isExt && view != null) { // S29: per-site switches
                val jsOff = SiteConfig.has(url, SiteConfig.JS_OFF)
                if (view.settings.javaScriptEnabled == jsOff) view.settings.javaScriptEnabled = !jsOff
                if (SiteConfig.has(url, SiteConfig.DESKTOP) && !tab.desktop) container.post { setDesktop(tab, true) }
            }
            if (!tab.isExt && view != null) YtAds.flag(view, url) // 0.0.105
            TabsApi.onUpdated(tab, JSONObject().put("status", "loading").put("url", tab.url)) // S6
            listener.onPageInfo(tab)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            tab.url = url ?: tab.url
            tab.progress = 100
            tab.popup = false // 0.0.106: the pop-up passed the check
            if (tab.desktop && !tab.isExt) view?.evaluateJavascript(BrowserExtras.DESKTOP_JS, null) // S14
            if (tab.extWide && tab.isExt) view?.evaluateJavascript(BrowserExtras.DESKTOP_JS, null) // 0.0.109
            if (!tab.isExt && !tab.priv && tab.url.startsWith("http")) view?.evaluateJavascript(PasswordStore.HOOK_JS, null) // S30
            if (!tab.isExt && !tab.priv && tab.url.startsWith("http") && WebVideo.onFor(ctx, tab.url)) view?.evaluateJavascript(WebVideo.JS, null) // S45
            if (!tab.isExt) YtAds.flag(view, tab.url) // 0.0.105
            if (!tab.isExt) CosmeticFilter.inject(ctx, view, tab.url) // S42
            // S13: browsing history (web pages only, not extension pages)
            if (!tab.isExt && !tab.priv && (tab.url.startsWith("http://") || tab.url.startsWith("https://")))
                Bookmarks.addHistory(view?.title ?: tab.title, tab.url)
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
            if (!tab.isExt && newProgress in 35..99) CosmeticFilter.inject(ctx, view, tab.url) // S42: early, so the banner does not flash
            listener.onPageInfo(tab)
        }

        // S45: real favicon per host (not for private tabs / extension pages)
        override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
            if (icon == null || tab.isExt || tab.priv) return
            val u = view?.url ?: tab.url
            if (u.startsWith("http", true)) Favicons.save(Util.host(u), icon)
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
            t.popup = true; t.openerUrl = tab.url // 0.0.106
            transport.webView = t.web
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            tabs.firstOrNull { it.web === window }?.let { close(it) }
        }

        // S14: fullscreen video
        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
            if (view == null) { callback?.onCustomViewHidden(); return }
            showFullscreen(view, callback)
        }

        override fun onHideCustomView() { exitFullscreen() }

        // S14: <input type=file>
        override fun onShowFileChooser(
            webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
        ): Boolean = BrowserExtras.chooseFile(filePathCallback, fileChooserParams)

        // S14: camera / microphone / location (asks the user first)
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            BrowserExtras.webPermission(request)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            BrowserExtras.geoPermission(origin, callback)
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

    // ---------- S39: video / audio links go to the built-in player ----------

    /** True (and the player starts) when url is a direct media file / playlist and the setting is ON. The tab is left as it is. */
    fun playLink(tab: Tab?, url: String): Boolean {
        if (!WebPlay.isMediaUrl(url) || !WebPlay.linksEnabled(ctx)) return false
        val ref = tab?.url?.takeIf { it.startsWith("http") }
        return WebPlay.play(ctx, url, null, ref, tab?.web?.settings?.userAgentString)
    }

    // ---------- S21: long press on a link / image, copy, share ----------

    private fun longPress(tab: Tab) = View.OnLongClickListener { v ->
        val w = v as? WebView ?: return@OnLongClickListener false
        if (tab.isExt || tab.home) return@OnLongClickListener false
        val r = w.hitTestResult
        val type = r.type
        val extra = r.extra
        when (type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val h = android.os.Handler(android.os.Looper.getMainLooper(), android.os.Handler.Callback { m ->
                    val link = m.data?.getString("url") ?: extra
                    val img = if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) m.data?.getString("src") else null
                    showLinkMenu(tab, link, img, m.data?.getString("title"))
                    true
                })
                w.requestFocusNodeHref(h.obtainMessage())
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra != null) showLinkMenu(tab, null, extra)
                true
            }
            else -> false
        }
    }

    private fun showLinkMenu(tab: Tab, link: String?, img: String?, text: String? = null) {
        val l = link ?: ""
        val i = img ?: ""
        val rows = ArrayList<MenuSheet.Item>()
        val ua = tab.web.settings.userAgentString
        val ref = tab.url.takeIf { it.startsWith("http") }
        if (l.startsWith("http")) {
            rows.add(MenuSheet.Item("Open in new tab", R.drawable.ic_add) { newTab(l, tab.id) })
            rows.add(MenuSheet.Item("Preview page", R.drawable.ic_search) { PagePreview.show(ctx, l) { newTab(l, tab.id) } }) // S42
            if (!tab.priv) rows.add(MenuSheet.Item("Open in new tab in group", R.drawable.ic_folder) { TabSwitcher.openInGroup(ctx, this, l, tab.id) })
            rows.add(MenuSheet.Item("Open in New Private Tab", R.drawable.ic_lock) { newTab(l, 0, true, true) })
            if (WebPlay.isMediaUrl(l)) rows.add(MenuSheet.Item("Play in player", R.drawable.ic_play) { WebPlay.play(ctx, l, null, ref, ua) })
            if (!tab.priv) rows.add(MenuSheet.Item(if (Bookmarks.isBookmarked(l)) "Remove bookmark" else "Bookmark link",
                if (Bookmarks.isBookmarked(l)) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border) {
                if (Bookmarks.isBookmarked(l)) { Bookmarks.removeBookmark(l); Util.toast(ctx, "Bookmark removed") }
                else { Bookmarks.addBookmark(text?.trim().orEmpty(), l); Util.toast(ctx, "Bookmark added") }
            })
            rows.add(MenuSheet.Item("Copy link", R.drawable.ic_list) { copyText(l) })
            if (!text.isNullOrBlank()) rows.add(MenuSheet.Item("Copy text", R.drawable.ic_edit) { copyText(text.trim()) })
            rows.add(MenuSheet.Item("Download link", R.drawable.ic_download) { downloadImage(tab, l) })
            rows.add(MenuSheet.Item("Share...", R.drawable.ic_share) { shareText(l) })
        }
        if (i.startsWith("http") || i.startsWith("data:")) {
            if (i.startsWith("http")) {
                rows.add(MenuSheet.Item("Open image in new tab", R.drawable.ic_add) { newTab(i, tab.id) })
                rows.add(MenuSheet.Item("Copy image address", R.drawable.ic_list) { copyText(i) })
            }
            rows.add(MenuSheet.Item("Download image", R.drawable.ic_download) { downloadImage(tab, i) })
            if (i.startsWith("http")) rows.add(MenuSheet.Item("Share image address", R.drawable.ic_share) { shareText(i) })
        }
        if (rows.isEmpty()) return
        val shown = if (l.isNotEmpty()) l else i
        MenuSheet.showLink(ctx, text?.trim().orEmpty().ifBlank { if (l.isEmpty()) "Image" else "" }, shown, rows)
    }

    private fun downloadImage(tab: Tab, url: String) {
        try {
            val item = DownloadsApi.startPlain(url, tab.web.settings.userAgentString, "", "application/octet-stream", tab.url, tab.web)
            if (item == null) Util.toast(ctx, "Cannot download this image")
            else Util.toast(ctx, "Downloading " + item.rel.substringAfterLast('/'))
        } catch (e: Exception) {
            Util.toast(ctx, "Download failed to start")
        }
    }

    fun copyText(s: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("MGB", s))
        Util.toast(ctx, "Copied")
    }

    fun shareText(s: String) {
        try {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), null))
        } catch (e: Exception) {
            Util.toast(ctx, "Cannot share")
        }
    }

    // ---------- S14: desktop mode + fullscreen video ----------

    /** Desktop site on / off for one tab: user agent, wide viewport, reload. Not for extension pages or the speed dial. */
    fun setDesktop(tab: Tab, on: Boolean) {
        if (tab.isExt || tab.home) return
        tab.desktop = on
        val s = tab.web.settings
        s.userAgentString = if (on) BrowserExtras.desktopUa(ctx) else null // null = WebView default
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        val p = tab.pending
        if (p != null) { tab.pending = null; tab.web.loadUrl(p) } else tab.web.reload()
    }

    val inFullscreen: Boolean get() = customView != null

    private fun showFullscreen(v: android.view.View, cb: WebChromeClient.CustomViewCallback?) {
        val act = ctx as? Activity
        if (customView != null || act == null) { cb?.onCustomViewHidden(); return }
        val holder = FrameLayout(act)
        holder.setBackgroundColor(Color.BLACK)
        holder.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        (act.window.decorView as ViewGroup).addView(holder, FrameLayout.LayoutParams(MATCH, MATCH))
        fsPortrait = Orient.isPortrait(act) // S29
        Orient.forVideo(act) // S35: browser is portrait-locked; fullscreen video may rotate
        customView = v
        customHolder = holder
        customCb = cb
        val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Leave fullscreen video (Back button, tab switch, page asked for it). Does nothing when not fullscreen. */
    fun exitFullscreen() {
        val v = customView ?: return
        val holder = customHolder
        val cb = customCb
        customView = null
        customHolder = null
        customCb = null
        if (holder != null) {
            holder.removeView(v)
            (holder.parent as? ViewGroup)?.removeView(holder)
        }
        (ctx as? Activity)?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            Orient.restore(it, fsPortrait) // S29 FIX: browser stayed landscape after the video
        }
        cb?.onCustomViewHidden()
    }

    // ---------- S13: speed dial ----------

    /** Show the speed dial over the active tab when it is a home tab, hide it otherwise. */
    fun syncHome() {
        var hv = homeView
        if (hv != null) container.removeView(hv)
        val a = active
        if (a == null || !a.home) return
        if (hv == null) {
            hv = HomeView(
                ctx,
                onOpen = { openUrl(it) },
                onOpenNew = { newTab(it, 0, true, active?.priv == true) }, // S26
                onBookmarks = { (ctx as? android.app.Activity)?.let { BookmarksScreen.open(it, BookmarksScreen.Mode.BOOKMARKS, this) } },
                onHistory = { (ctx as? android.app.Activity)?.let { BookmarksScreen.open(it, BookmarksScreen.Mode.HISTORY, this) } }
            )
            homeView = hv
        }
        hv.priv = a.priv // S26
        hv.onSearch = { onHomeSearch?.invoke() } // S28
        hv.showTop()
        container.addView(hv, FrameLayout.LayoutParams(MATCH, MATCH))
        hv.refresh()
    }

    /** The tab is about to navigate: drop the speed dial now (not when the page starts). */
    fun leaveHome(t: Tab) {
        if (!t.home) return
        t.home = false
        if (t === active) syncHome()
    }

    /** Load url in the active tab (an extension page tab gets a new tab instead). */
    fun openUrl(url: String) {
        val t = active
        if (t != null && !t.isExt && playLink(t, url)) return // S39
        if (t == null || t.isExt) { newTab(url); return }
        t.pending = null
        t.url = url
        leaveHome(t)
        t.web.loadUrl(url)
        listener.onPageInfo(t)
    }

    // ---------- tab switcher ----------

    fun allTabs(): List<Tab> = tabs.toList() // S31

    /** S33: vertical scroll of any tab WebView (positive = page moves up, finger swipes up). */
    var onScroll: ((Int) -> Unit)? = null
    /** S45: tap on the in-page video button (address of the video or "" / blob:, page title). Set by MainActivity. */
    var onWebVideo: ((String, String) -> Unit)? = null

    fun showSwitcher() {
        (ctx as? android.app.Activity)?.let { TabSwitcher.show(it, this); return } // S31: Thazir switcher; old sheet below is the fallback
        val dlg = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_tabs, null)
        val list = v.findViewById<LinearLayout>(R.id.tabs_list)
        val title = v.findViewById<TextView>(R.id.tabs_title)
        v.findViewById<View>(R.id.tabs_new).setOnClickListener {
            newTab(HOME_PAGE) // S13
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
        if (t.home) (if (t.priv) "Private home" else "Home") else t.title.ifBlank { Util.host(t.url) }.ifBlank { "New tab" }

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
            text = (if (t.priv) "Private · " else "") + (if (t.home) "Speed dial" else Util.host(t.url)) // S13, S26
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
