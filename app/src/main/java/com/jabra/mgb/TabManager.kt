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
        var title: String = ""
        var progress: Int = 100
        /** Restored tabs are loaded lazily, the first time they are selected. */
        var pending: String? = null
        /** S13: speed dial is shown over this tab until the first navigation. */
        var home: Boolean = false
        /** S14: desktop user agent + wide viewport (not saved in the session). */
        var desktop: Boolean = false
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
        active?.let { container.removeView(it.web) }
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
        var activeIdx = 0
        for (t in tabs) {
            if (t.priv) continue // S26: private tabs are never saved
            val u = if (t.home) HOME_PAGE else (t.web.url ?: t.url)
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
            if (u == HOME_PAGE) t.home = true else t.pending = u // S13
            if (i == act) target = t
        }
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
        ContentInjector.attach(ctx, w) // S5: V77 content scripts + chrome shim (before first load)
        if (!priv) w.addJavascriptInterface(PasswordStore.Bridge(this), "MGBPW") // S30: save-login hook (before first load)
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
                if (AdBlock.shouldBlock(r, tab.url)) return AdBlock.emptyResponse() // S19
                Sniffer.onRequest(tab, r)
                return null
            }
            if (!r.isForMainFrame && !tab.isExt) return null
            return EngineHost.ext(r.url)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            tab.url = url ?: tab.url
            tab.progress = 0
            if (tab.home) { tab.home = false; syncHome() } // S13
            if (!tab.isExt && view != null) { // S29: per-site switches
                val jsOff = SiteConfig.has(url, SiteConfig.JS_OFF)
                if (view.settings.javaScriptEnabled == jsOff) view.settings.javaScriptEnabled = !jsOff
                if (SiteConfig.has(url, SiteConfig.DESKTOP) && !tab.desktop) container.post { setDesktop(tab, true) }
            }
            TabsApi.onUpdated(tab, JSONObject().put("status", "loading").put("url", tab.url)) // S6
            listener.onPageInfo(tab)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            tab.url = url ?: tab.url
            tab.progress = 100
            if (tab.desktop && !tab.isExt) view?.evaluateJavascript(BrowserExtras.DESKTOP_JS, null) // S14
            if (!tab.isExt && !tab.priv && tab.url.startsWith("http")) view?.evaluateJavascript(PasswordStore.HOOK_JS, null) // S30
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
                    showLinkMenu(tab, link, img)
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

    private fun showLinkMenu(tab: Tab, link: String?, img: String?) {
        val l = link ?: ""
        val i = img ?: ""
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (l.startsWith("http")) {
            labels.add("Open in new tab"); acts.add { newTab(l, tab.id) }
            labels.add("Copy link"); acts.add { copyText(l) }
            labels.add("Share link"); acts.add { shareText(l) }
        }
        if (i.startsWith("http") || i.startsWith("data:")) {
            if (i.startsWith("http")) {
                labels.add("Open image in new tab"); acts.add { newTab(i, tab.id) }
                labels.add("Copy image address"); acts.add { copyText(i) }
            }
            labels.add("Download image"); acts.add { downloadImage(tab, i) }
        }
        if (labels.isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(Util.host(if (l.isNotEmpty()) l else i).ifBlank { "Link" })
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .show()
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
        if (t == null || t.isExt) { newTab(url); return }
        t.pending = null
        t.url = url
        leaveHome(t)
        t.web.loadUrl(url)
        listener.onPageInfo(t)
    }

    // ---------- tab switcher ----------

    fun allTabs(): List<Tab> = tabs.toList() // S31

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
