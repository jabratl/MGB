package com.jabra.mgb

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/** S2: browser screen (URL bar, progress, back/forward/reload). Tabs live in TabManager (S3). */
class MainActivity : AppCompatActivity(), TabManager.Listener {

    private lateinit var tabs: TabManager
    private lateinit var urlBar: EditText
    private lateinit var btnBack: TextView
    private lateinit var btnFwd: TextView
    private lateinit var btnReload: TextView
    private lateinit var btnTabs: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var backCb: OnBackPressedCallback
    private lateinit var floating: FloatingDl // S11
    private var lastNight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lastNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        urlBar = findViewById(R.id.url_bar)
        btnBack = findViewById(R.id.btn_back)
        btnFwd = findViewById(R.id.btn_fwd)
        btnReload = findViewById(R.id.btn_reload)
        btnTabs = findViewById(R.id.btn_tabs)
        progress = findViewById(R.id.progress)
        EngineHost.start(this) // S6: background.js engine (hidden WebView)
        tabs = TabManager(this, findViewById<FrameLayout>(R.id.web_container), this)
        TabsApi.attach(tabs)
        floating = FloatingDl(this, findViewById<FrameLayout>(R.id.web_container).parent as FrameLayout, tabs) // S11

        btnBack.setOnClickListener { tabs.active?.web?.let { if (it.canGoBack()) it.goBack() } }
        btnFwd.setOnClickListener { tabs.active?.web?.let { if (it.canGoForward()) it.goForward() } }
        btnReload.setOnClickListener {
            tabs.active?.let {
                if (it.loading) {
                    it.web.stopLoading()
                    it.progress = 100
                    onPageInfo(it)
                } else it.web.reload()
            }
        }
        btnTabs.setOnClickListener { tabs.showSwitcher() }
        findViewById<TextView>(R.id.btn_menu).setOnClickListener { anchor ->
            val m = PopupMenu(this, anchor)
            // S10b: extension windows (V77 popup, queue, history, options)
            m.menu.add(0, 4, -4, "Media Grabber")
            m.menu.add(0, 5, -3, "Queue")
            m.menu.add(0, 8, -3, "Downloads") // S12
            m.menu.add(0, 6, -2, "History")
            m.menu.add(0, 7, -1, "Settings")
            m.menu.add(0, 1, 0, "Check for updates")
            m.menu.add(0, 2, 1, "MGB ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})").isEnabled = false
            m.menu.add(0, 3, 2, "Engine status")
            m.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { UpdateManager.check(this, false); true }
                    3 -> { EngineHost.showStatus(this); true }
                    4 -> { openExt("popup.html"); true }
                    5 -> { openExt("queue.html"); true }
                    6 -> { openExt("history.html"); true }
                    7 -> { openExt("options.html"); true }
                    8 -> { DownloadsScreen.open(this); true }
                    else -> false
                }
            }
            m.show()
        }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submitUrl()
            go
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            if (focused) {
                urlBar.setText(tabs.active?.url ?: "")
                urlBar.post { urlBar.selectAll() }
            } else refreshUrlField()
        }

        backCb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = tabs.active
                when {
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

        if (!tabs.restoreSession()) tabs.newTab(TabManager.HOME)
        UpdateManager.autoCheck(this)
    }

    private fun openExt(page: String) {
        if (!ExtScreen.open(this, ContentInjector.EXT_BASE + page)) Util.toast(this, "Could not open $page")
    }

    // ---------- URL bar ----------

    private fun submitUrl() {
        val target = Util.toUrlOrSearch(urlBar.text.toString())
        leaveUrlBar()
        val t = tabs.active ?: tabs.newTab(null) ?: return
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
        val u = tabs.active?.url ?: ""
        val s = when {
            u == "about:blank" -> ""
            u.startsWith("http") -> Util.host(u)
            else -> u
        }
        if (urlBar.text.toString() != s) urlBar.setText(s)
    }

    private fun refreshAll(tab: TabManager.Tab?) {
        btnBack.alpha = if (tab?.web?.canGoBack() == true) 1f else 0.35f
        btnFwd.alpha = if (tab?.web?.canGoForward() == true) 1f else 0.35f
        val loading = tab?.loading == true
        btnReload.text = if (loading) "×" else "↻"
        if (loading) {
            progress.visibility = android.view.View.VISIBLE
            progress.progress = tab!!.progress
        } else progress.visibility = android.view.View.GONE
        refreshUrlField()
    }

    // ---------- TabManager.Listener ----------

    override fun onTabsChanged() {
        btnTabs.text = tabs.count.toString()
    }

    override fun onActiveChanged(tab: TabManager.Tab?) {
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
