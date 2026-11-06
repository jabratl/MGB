package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * V14: "How to use" guide for new users. Full-screen page: Quick start + 10 short topics, each with
 * numbered steps and a tip. Plain text only (no images), mg_* colours, dark and light, 560dp max width.
 * Entry points: 3-dot menu, Command Center, Settings > About, and a one-time offer on the first start.
 * State lives in this object (openIdx), the Dialog is rebuilt from scratch on every open, so rotation is safe.
 */
object HowToUse {
    private class Topic(val icon: Int, val title: String, val sum: String, val steps: List<String>, val tip: String = "")

    private val QUICK = listOf(
        "Type a web address or a search in the bar at the top and press Go.",
        "Tap the 3-dot menu for everything else: bookmarks, downloads, Vault, Settings.",
        "Tap the tab counter to switch tabs. Menu > New private tab leaves no history."
    )

    private val TOPICS = listOf(
        Topic(R.drawable.ic_screen, "Browse and tabs", "Open, switch and close tabs",
            listOf(
                "Type a site or a search in the address bar. Thazir uses the search engine you chose in Settings > General.",
                "Tap the tab counter to see all tabs and groups. Open one by tapping it.",
                "Close a tab with its X. If Settings > Tabs and motion has swipe on, swipe the tab card instead.",
                "Closed one by mistake? Menu > Recently closed tabs brings it back.",
                "Menu > Bookmark all tabs saves every open web tab into one folder."
            ), "Menu > New private tab keeps no history and uses separate cookies."),
        Topic(R.drawable.ic_search, "Menu and Command Center", "Find any action fast",
            listOf(
                "Open the 3-dot menu. The top part is for the app, the lower part is for the page you are on.",
                "Page actions: Find on page, Desktop site, Reader view, Translate page, Text size, Page info, Save page.",
                "Menu > Command Center: type a few letters to find an action, an open tab or a bookmark.",
                "Menu > Customise menu lets you hide rows you never use."
            ), "Command Center is the fastest way when you do not remember where something is."),
        Topic(R.drawable.ic_bookmark, "Bookmarks and history", "Save pages, find them again",
            listOf(
                "On a page, use the bookmark button in the menu bar to save it. Tap it again to remove it.",
                "Menu > Bookmarks shows your saved pages and folders. Menu > History shows where you have been.",
                "Select rows in the list to open, move or delete many at once.",
                "Settings > Data and backup can clear history, cookies and cache."
            )),
        Topic(R.drawable.ic_download, "Downloads and Grabber", "Get videos, files and pages",
            listOf(
                "When a page has a file or video, Menu > Detected files lists what Thazir found.",
                "Menu > Downloads shows progress, finished files and lets you open, share, rename or delete them.",
                "Menu > Grabber and Menu > Queue help with bigger or many downloads.",
                "Menu > Media Library collects detected, downloaded and saved media in one place.",
                "Novel grabber (in the menu) turns web novels into EPUB, DOCX or TXT."
            ), "Settings > Downloads has quality, folder and speed options."),
        Topic(R.drawable.ic_save, "Saved pages", "Read without internet",
            listOf(
                "Open a page, then Menu > Save page. It is stored on your phone.",
                "Menu > Saved pages lists them. Tap one to open it offline.",
                "Use the row menu to share, rename or delete a saved page."
            ), "Private tabs cannot be saved on purpose."),
        Topic(R.drawable.ic_play, "Video and audio player", "Built-in player with gestures",
            listOf(
                "Open a video or audio file from Downloads or the Media Library and it plays in the built-in player.",
                "Double-tap the left or right side to jump back or forward. Hold to speed up.",
                "Slide on the screen for volume and brightness, if gestures are on.",
                "Settings > Player: gestures, speed, default fit, quality and subtitles."
            )),
        Topic(R.drawable.ic_shield, "Privacy and security", "Blocking, locking, passwords",
            listOf(
                "Menu > Privacy Guard shows ads and trackers blocked on this page and lets you switch it.",
                "Settings > Privacy and security: app lock, HTTPS only, permissions.",
                "Menu > Passwords stores logins. On a login page use Menu > Fill password.",
                "Menu > Email alias makes a throw-away address for a site."
            ), "App lock asks for your fingerprint or screen lock when you come back to the app."),
        Topic(R.drawable.ic_vault, "Vault", "Encrypted copy of your data",
            listOf(
                "Menu > Vault > Create. Pick a folder you can find again (make a new one, for example ThazirVault).",
                "Choose a passphrase. Write down the recovery key shown once and keep it safe.",
                "Use Move to vault or Copy to vault in Bookmarks, History, Saved pages and Downloads.",
                "Before you uninstall: Vault > Exit vault / Prepare for uninstall, then choose what to keep.",
                "On a new install: Vault > I already have a vault, pick the same folder and unlock."
            ), "Android deletes app data when you uninstall. Your vault stays only because it lives in your own folder."),
        Topic(R.drawable.ic_settings, "Settings and backup", "Make Thazir yours",
            listOf(
                "Menu > Settings. Tap a card to open it. Use the search box at the top to jump to a setting.",
                "Menu > Settings > Appearance changes theme, address bar and home page.",
                "Reduce motion (Tabs and motion) turns animations off.",
                "Settings > Data and backup: Export data saves your bookmarks, history and settings to a file; Import brings them back."
            ), "Export data does not include the Vault. The Vault keeps its own folder."),
        Topic(R.drawable.ic_ext, "Extensions and extras", "Add more features",
            listOf(
                "Open the 3-dot menu and tap the extensions icon to open the Extensions tray.",
                "Settings > Extensions: add, turn on or off and remove them.",
                "Menu > Userscripts shows scripts that run on the page you are on.",
                "Something wrong? Menu > Report center helps you collect details to report it."
            ))
    )

    private var current: Screen? = null
    private var openIdx = -1

    fun open(host: Activity, topic: Int = -1) {
        if (host.isFinishing || host.isDestroyed) return
        try { current?.dismiss() } catch (_: Exception) { }
        openIdx = if (topic in TOPICS.indices) topic else -1
        try {
            val s = Screen(host)
            current = s
            s.setOnDismissListener { if (current === s) current = null }
            s.show()
        } catch (e: Exception) { current = null }
    }

    /** One-time offer on the very first start. Safe to call every start. */
    fun maybeOffer(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        val p = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
        if (p.getBoolean("howto_seen", false)) return
        p.edit().putBoolean("howto_seen", true).apply()
        try {
            MaterialAlertDialogBuilder(act)
                .setTitle("New to Thazir?")
                .setMessage("Take a one-minute tour of tabs, downloads, privacy and the Vault. You can find it later in Menu > How to use.")
                .setPositiveButton("Show me") { _, _ -> open(act) }
                .setNegativeButton("Not now", null)
                .show()
        } catch (_: Exception) { }
    }

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var content: LinearLayout? = null
        private var titleTv: TextView? = null
        private var sv: ScrollView? = null

        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(res: Int) = act.getColor(res)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(c(R.color.mg_bg)))
                if (!TabPrefs.reduce(act)) it.setWindowAnimations(R.style.MgbSettingsWindow)
            }
            render(false)
        }

        override fun onBackPressed() { goBack() }

        private fun goBack() {
            if (openIdx >= 0) { openIdx = -1; render(true) } else dismiss()
        }

        private fun build() {
            val root = FrameLayout(act).apply { setBackgroundColor(c(R.color.mg_bg)) }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val w = minOf(act.resources.displayMetrics.widthPixels, dp(560))
            val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val back = ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(c(R.color.mg_text))
                contentDescription = "Back"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { goBack() }
            }
            val t = VaultScreen.tv(act, "How to use", 20f, R.color.mg_text, true).also { ViewCompat.setAccessibilityHeading(it, true) }
            titleTv = t
            bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(56)))
            val scroll = ScrollView(act).apply { isFillViewport = true }
            sv = scroll
            val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(28)) }
            content = body
            scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
            col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            root.addView(col, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL))
            setContentView(root)
        }

        private fun render(animate: Boolean) {
            val body = content ?: return
            body.removeAllViews()
            if (openIdx < 0) home(body) else topic(body, openIdx)
            titleTv?.text = if (openIdx < 0) "How to use" else TOPICS[openIdx].title
            sv?.scrollTo(0, 0)
            if (animate && !TabPrefs.reduce(act)) {
                body.animate().cancel()
                body.alpha = 0f
                body.translationX = dp(if (openIdx >= 0) 28 else -28).toFloat()
                body.animate().alpha(1f).translationX(0f).setDuration(240)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            }
        }

        private fun dash(): View = View(act).apply {
            background = GradientDrawable().apply { setColor(c(R.color.mg_primary)); cornerRadius = dp(2).toFloat() }
        }

        private fun header(text: String) = VaultScreen.tv(act, text.uppercase(), 11f, R.color.mg_primary, true).apply {
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            ViewCompat.setAccessibilityHeading(this, true)
        }

        private fun num(n: Int): View = TextView(act).apply {
            text = n.toString(); gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(c(R.color.mg_on_primary)); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(R.color.mg_primary)) }
        }

        private fun stepRow(n: Int, text: String): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(num(n), LinearLayout.LayoutParams(dp(26), dp(26)).apply { topMargin = dp(1) })
            addView(VaultScreen.tv(act, text, 15f, R.color.mg_text).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }

        private fun home(b: LinearLayout) {
            b.addView(dash(), VaultScreen.lp(dp(4), dp(18), dp(3)))
            b.addView(VaultScreen.tv(act, "Welcome to Thazir", 24f, R.color.mg_text, true), VaultScreen.lp(dp(10)))
            b.addView(VaultScreen.tv(act, "Short steps for the main things. Start with the quick start, then open any topic.", 14f, R.color.mg_text_dim)
                .apply { setLineSpacing(0f, 1.15f) }, VaultScreen.lp(dp(8)))
            b.addView(header("Quick start"), VaultScreen.lp(dp(22)))
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = VaultScreen.box(act, 20)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            QUICK.forEachIndexed { i, s -> card.addView(stepRow(i + 1, s), VaultScreen.lp(if (i == 0) 0 else dp(12))) }
            b.addView(card, VaultScreen.lp(dp(10)))
            b.addView(header("Topics"), VaultScreen.lp(dp(24)))
            TOPICS.forEachIndexed { i, t -> b.addView(topicRow(i, t), VaultScreen.lp(dp(10))) }
        }

        private fun topicRow(i: Int, t: Topic): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            background = VaultScreen.box(act, 20)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            isClickable = true; isFocusable = true
            contentDescription = t.title + ". " + t.sum
            setOnClickListener { openIdx = i; render(true) }
            addView(ImageView(act).apply { setImageResource(t.icon); setColorFilter(c(R.color.mg_primary)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(24), dp(24)))
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(VaultScreen.tv(act, t.title, 16f, R.color.mg_text, true))
            txt.addView(VaultScreen.tv(act, t.sum, 12f, R.color.mg_text_dim), VaultScreen.lp(dp(2)))
            addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
            addView(ImageView(act).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(c(R.color.mg_text_dim)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(20), dp(20)))
        }

        private fun topic(b: LinearLayout, i: Int) {
            val t = TOPICS[i]
            b.addView(dash(), VaultScreen.lp(dp(4), dp(18), dp(3)))
            b.addView(VaultScreen.tv(act, t.sum, 14f, R.color.mg_text_dim), VaultScreen.lp(dp(10)))
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = VaultScreen.box(act, 20)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            t.steps.forEachIndexed { k, s -> card.addView(stepRow(k + 1, s), VaultScreen.lp(if (k == 0) 0 else dp(14))) }
            b.addView(card, VaultScreen.lp(dp(14)))
            if (t.tip.isNotEmpty()) {
                val tip = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = VaultScreen.box(act, 16, R.color.mg_surface_variant)
                    setPadding(dp(16), dp(12), dp(16), dp(14))
                }
                tip.addView(header("Tip"))
                tip.addView(VaultScreen.tv(act, t.tip, 14f, R.color.mg_text).apply { setLineSpacing(0f, 1.15f) }, VaultScreen.lp(dp(6)))
                b.addView(tip, VaultScreen.lp(dp(12)))
            }
            val nav = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            if (i > 0) nav.addView(VaultScreen.btn(act, "Previous", false) { openIdx = i - 1; render(true) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
            val last = i == TOPICS.size - 1
            nav.addView(VaultScreen.btn(act, if (last) "All topics" else "Next: " + TOPICS[i + 1].title, true) {
                if (last) { openIdx = -1; render(true) } else { openIdx = i + 1; render(true) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            b.addView(nav, VaultScreen.lp(dp(20)))
            b.addView(VaultScreen.link(act, "All topics") { openIdx = -1; render(true) }, VaultScreen.lp(dp(6)))
        }
    }
}
