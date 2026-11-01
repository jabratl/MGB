package com.jabra.mgb

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/** S2: browser screen (URL bar, progress, back/forward/reload). Tabs live in TabManager (S3). */
class MainActivity : AppCompatActivity(), TabManager.Listener {

    private lateinit var tabs: TabManager
    private lateinit var urlBar: EditText
    private lateinit var btnHome: ImageView // S22
    private lateinit var btnTabs: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var backCb: OnBackPressedCallback
    private lateinit var floating: FloatingDl // S11
    private var lastNight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        UiMode.apply(this) // S20: saved theme (System / Light / Dark), before the Activity is created
        AdBlock.init(this) // S19
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) // S22: bottom bar rides above the keyboard
        lastNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        CrashLog.install(this) // FIX: save crash trace for the next start
        BrowserExtras.init(this) // S14: file chooser + permission launchers (before STARTED)

        urlBar = findViewById(R.id.url_bar)
        btnHome = findViewById(R.id.btn_home)
        btnTabs = findViewById(R.id.btn_tabs)
        progress = findViewById(R.id.progress)
        EngineHost.start(this) // S6: background.js engine (hidden WebView)
        tabs = TabManager(this, findViewById<FrameLayout>(R.id.web_container), this)
        TabsApi.attach(tabs)
        BookmarksScreen.tabsRef = tabs // S25
        floating = FloatingDl(this, findViewById<FrameLayout>(R.id.web_container).parent as FrameLayout, tabs) // S11

        btnHome.setOnClickListener { if (tabs.active?.home != true) tabs.newTab(TabManager.HOME_PAGE) } // S22
        btnTabs.setOnClickListener { tabs.showSwitcher() }
        findViewById<ImageView>(R.id.btn_menu).setOnClickListener { showMenu() } // S22

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submitUrl()
            go
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            if (focused) {
                urlBar.setText(if (tabs.active?.home == true) "" else (tabs.active?.url ?: "")) // S13
                urlBar.post { urlBar.selectAll() }
            } else refreshUrlField()
        }

        backCb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = tabs.active
                when {
                    tabs.inFullscreen -> tabs.exitFullscreen() // S14
                    urlBar.hasFocus() -> leaveUrlBar()
                    t != null && t.web.canGoBack() -> t.web.goBack()
                    t != null && tabs.count > 1 -> tabs.close(t)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCb)

        if (!tabs.restoreSession()) tabs.newTab(TabManager.HOME_PAGE) // S13
        UpdateManager.autoCheck(this)
        CrashLog.showLast(this) // FIX
    }

    /** S22: reload, or stop when the page is still loading (was the top bar button). */
    private fun reloadOrStop() {
        tabs.active?.let {
            if (it.loading) {
                it.web.stopLoading()
                it.progress = 100
                onPageInfo(it)
            } else it.web.reload()
        }
    }

    /** S22: the 3-dot menu as a bottom sheet (MenuSheet). Same actions as the old PopupMenu, same ids/handlers. */
    private fun showMenu() {
        val cur = tabs.active
        val web = cur?.web
        val canMark = cur != null && !cur.home && !cur.isExt && cur.url.startsWith("http")
        val marked = cur != null && canMark && Bookmarks.isBookmarked(cur.url)
        val quick = listOf(
            MenuSheet.Quick(R.drawable.ic_bookmark, "Bookmarks") { BookmarksScreen.open(this, BookmarksScreen.Mode.BOOKMARKS, tabs) },
            MenuSheet.Quick(R.drawable.ic_history, "History") { BookmarksScreen.open(this, BookmarksScreen.Mode.HISTORY, tabs) },
            MenuSheet.Quick(R.drawable.ic_download, "Downloads") { DownloadsScreen.open(this) },
            MenuSheet.Quick(R.drawable.ic_play, "Grabber") { openExt("popup.html") }
        )
        val acts = listOf(
            MenuSheet.Act(R.drawable.ic_back, web?.canGoBack() == true) { web?.goBack() },
            MenuSheet.Act(R.drawable.ic_forward, web?.canGoForward() == true) { web?.goForward() },
            MenuSheet.Act(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border, canMark) { toggleBookmark() },
            MenuSheet.Act(if (cur?.loading == true) R.drawable.ic_close else R.drawable.ic_reload, cur != null) { reloadOrStop() }
        )
        val items = listOf(
            MenuSheet.Item("New private tab") { tabs.newTab(TabManager.HOME_PAGE, 0, true, true) }, // S26
            MenuSheet.Item("Queue") { openExt("queue.html") },
            MenuSheet.Item("Download history") { openExt("history.html") },
            MenuSheet.Item("Find in page", canMark) { FindBar.open(this, tabs.active?.web) },
            MenuSheet.Item("Share page", canMark) { tabs.active?.let { if (it.url.startsWith("http")) tabs.shareText(it.url) } },
            MenuSheet.Item(if (cur?.desktop == true) "Mobile site" else "Desktop site", cur != null && !cur.home && !cur.isExt) {
                tabs.active?.let { tabs.setDesktop(it, !it.desktop) }
            },
            MenuSheet.Item(if (AdBlock.enabled) "Ad blocker: ON (${AdBlock.blocked} blocked)" else "Ad blocker: OFF") { // S19
                Util.toast(this, if (AdBlock.toggle()) "Ad blocker ON (reload the page)" else "Ad blocker OFF (reload the page)")
            },
            MenuSheet.Item("Theme: ${UiMode.label(this)}") { UiMode.pick(this) }, // S20
            MenuSheet.Item(if (UiMode.darkPages(this)) "Dark pages: ON" else "Dark pages: OFF") { // S20
                val on = !UiMode.darkPages(this)
                UiMode.setDarkPages(this, on)
                tabs.applyDarkPages()
                Util.toast(this, if (on) "Dark pages ON (reload the page)" else "Dark pages OFF (reload the page)")
            },
            MenuSheet.Item("Settings") { openExt("options.html") },
            MenuSheet.Item("Check for updates") { UpdateManager.check(this, false) },
            MenuSheet.Item("Engine status") { EngineHost.showStatus(this) }
        )
        MenuSheet.show(this, quick, acts, items, "MGB ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
    }

    private fun openExt(page: String) {
        if (!ExtScreen.open(this, ContentInjector.EXT_BASE + page)) Util.toast(this, "Could not open $page")
    }

    /** S13: add / remove the bookmark of the page in the active tab. */
    private fun toggleBookmark() {
        val t = tabs.active ?: return
        if (t.home || t.isExt || !t.url.startsWith("http")) return
        if (Bookmarks.isBookmarked(t.url)) {
            Bookmarks.removeBookmark(t.url)
            Util.toast(this, "Bookmark removed")
        } else {
            Bookmarks.addBookmark(t.title.ifBlank { Util.host(t.url) }, t.url)
            Util.toast(this, "Bookmark added")
        }
    }

    // ---------- URL bar ----------

    private fun submitUrl() {
        val target = Util.toUrlOrSearch(urlBar.text.toString())
        leaveUrlBar()
        val t = tabs.active ?: tabs.newTab(null) ?: return
        if (t.home && target == "about:blank") return // S13: empty input on the speed dial = stay
        tabs.leaveHome(t) // S13
        t.url = target
        t.web.loadUrl(target)
    }

    private fun leaveUrlBar() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
        tabs.active?.web?.requestFocus()
    }

    private fun refreshUrlField() {
        if (urlBar.hasFocus()) return
        val u = if (tabs.active?.home == true) "" else (tabs.active?.url ?: "") // S13
        val s = when {
            u == "about:blank" -> ""
            u.startsWith("http") -> Util.host(u)
            else -> u
        }
        if (urlBar.text.toString() != s) urlBar.setText(s)
    }

    private fun refreshAll(tab: TabManager.Tab?) {
        val loading = tab?.loading == true
        if (loading) {
            progress.visibility = android.view.View.VISIBLE
            progress.progress = tab!!.progress
        } else progress.visibility = android.view.View.GONE
        btnTabs.setTextColor(getColor(if (tab?.priv == true) R.color.mg_primary else R.color.mg_text)) // S26
        refreshUrlField()
    }

    // ---------- TabManager.Listener ----------

    override fun onTabsChanged() {
        btnTabs.text = tabs.count.toString()
    }

    override fun onActiveChanged(tab: TabManager.Tab?) {
        FindBar.close() // S21
        refreshAll(tab)
        floating.poke() // S11
    }

    override fun onPageInfo(tab: TabManager.Tab) {
        if (tab === tabs.active) {
            refreshAll(tab)
            if (tab.progress >= 100) floating.poke() // S11
        }
    }

    // ---------- lifecycle ----------

    override fun onResume() {
        super.onResume()
        backCb.isEnabled = true
        tabs.active?.web?.onResume()
        floating.start() // S11
    }

    override fun onPause() {
        floating.stop() // S11
        tabs.active?.web?.onPause()
        tabs.saveSession()
        super.onPause()
    }

    override fun onDestroy() {
        floating.stop() // S11
        DownloadsScreen.closeAll() // S12
        BookmarksScreen.closeAll() // S13
        BookmarksScreen.tabsRef = null // S25
        FindBar.close() // S21
        tabs.destroyAll()
        TabsApi.attach(null)
        super.onDestroy()
    }

    // uiMode is in configChanges (no WebView reload on rotate); on light/dark switch we
    // recreate so the XML colours re-resolve. Tabs come back from the saved session.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val n = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (n != lastNight) {
            lastNight = n
            recreate()
        }
    }
}
