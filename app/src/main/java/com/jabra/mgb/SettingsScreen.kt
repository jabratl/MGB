package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * S27: native Settings screen (full-screen Dialog, no manifest entry). The V77 options page stays reachable
 * as "Grabber settings".
 * S46: categories are collapsed cards (accordion, one open at a time). Inside a category the rows are
 * grouped by small sub labels, ON/OFF rows use a switch. Open category is kept while a toggle rebuilds.
 */
object SettingsScreen {
    private const val TAG = "MGB.settings"
    private var current: Screen? = null

    fun open(host: Activity) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    fun closeAll() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var body: LinearLayout? = null
        private var sv: ScrollView? = null
        private var openCat: String? = null // S46
        private val cats = LinkedHashMap<String, Pair<View, View>>() // key -> (content, chevron)

        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener { if (current === this) current = null }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
        }

        private fun round(color: Int, r: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

        private fun build() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(act.getColor(R.color.mg_bg))
            }
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = "Settings"
                textSize = 20f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(View(act), LinearLayout.LayoutParams(dp(44), dp(48)))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(32))
            }
            body = col
            val sv = ScrollView(act)
            this.sv = sv
            sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill()
        }

        private class Row(val icon: Int, val label: String, val value: String = "", val onClick: (() -> Unit)? = null)

        private class Sub(val title: String)

        private fun switchFor(on: Boolean) = MaterialSwitch(act).apply {
            isChecked = on
            isClickable = false
            isFocusable = false
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(st, intArrayOf(act.getColor(R.color.mg_primary), act.getColor(R.color.mg_surface_variant)))
            thumbTintList = ColorStateList(st, intArrayOf(act.getColor(R.color.mg_on_primary), act.getColor(R.color.mg_text_dim)))
        }

        private fun subLabel(title: String) = TextView(act).apply {
            text = title.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(act.getColor(R.color.mg_text_dim))
            setPadding(dp(18), dp(14), dp(16), dp(4))
        }

        private fun rowView(r: Row): View {
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(18), dp(6), dp(16), dp(6))
                if (r.onClick != null) {
                    isClickable = true
                    isFocusable = true
                    val out = TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                        foreground = act.getDrawable(out.resourceId)
                    }
                    setOnClickListener { r.onClick.invoke() }
                }
            }
            line.addView(ImageView(act).apply {
                setImageResource(r.icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            line.addView(TextView(act).apply {
                text = r.label
                textSize = 15f
                maxLines = 2
                setTextColor(act.getColor(R.color.mg_text))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (r.value == "ON" || r.value == "OFF") {
                line.addView(switchFor(r.value == "ON"), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            } else if (r.value.isNotEmpty()) {
                line.addView(TextView(act).apply {
                    text = r.value
                    textSize = 13f
                    setPadding(dp(8), 0, 0, 0)
                    setTextColor(act.getColor(if (r.value.startsWith("ON")) R.color.mg_primary else R.color.mg_text_dim))
                })
            }
            return line
        }

        /** S46: one collapsible category card. items = Row or Sub (small group label). */
        private fun category(key: String, icon: Int, title: String, subtitle: String, items: List<Any>) {
            val col = body ?: return
            val isOpen = openCat == key
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 22)
            }
            val chev = ImageView(act).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                rotation = if (isOpen) 90f else 0f
            }
            val content = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(8))
                visibility = if (isOpen) View.VISIBLE else View.GONE
            }
            content.addView(View(act).apply { setBackgroundColor(act.getColor(R.color.mg_outline)) },
                LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) })
            for (item in items) {
                when (it) {
                    is Sub -> content.addView(subLabel(item.title), LinearLayout.LayoutParams(MATCH, WRAP))
                    is Row -> content.addView(rowView(item), LinearLayout.LayoutParams(MATCH, WRAP))
                }
            }
            val header = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = title
                val out = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    foreground = act.getDrawable(out.resourceId)
                }
                setOnClickListener { toggle(key, card) }
            }
            val badge = FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 0x26))
                }
            }
            badge.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_primary))
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            header.addView(badge, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(act).apply {
                text = title
                textSize = 16f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
            })
            texts.addView(TextView(act).apply {
                text = subtitle
                textSize = 12f
                maxLines = 1
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            header.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            header.addView(chev, LinearLayout.LayoutParams(dp(24), dp(24)))
            card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            cats[key] = Pair(content, chev)
        }

        /** S46: open one category, close the others (animated). */
        private fun toggle(key: String, card: View) {
            val col = body ?: return
            val opening = openCat != key
            try {
                TransitionManager.beginDelayedTransition(col, AutoTransition().setDuration(200))
            } catch (_: Exception) { }
            openCat = if (opening) key else null
            for ((k, v) in cats) {
                val show = k == openCat
                v.first.visibility = if (show) View.VISIBLE else View.GONE
                v.second.animate().rotation(if (show) 90f else 0f).setDuration(200).start()
            }
            if (opening) card.postDelayed({
                sv?.smoothScrollTo(0, (card.top - dp(8)).coerceAtLeast(0))
            }, 240)
        }

        private fun hideBar(): Boolean =
            act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).getBoolean("hide_bar", false)

        private fun nativeBtn(): Boolean =
            act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).getBoolean("native_btn", false)

        private fun ext(page: String) {
            if (!ExtScreen.open(act, ContentInjector.EXT_BASE + page)) Util.toast(act, "Could not open $page")
        }

        private fun confirm(msg: String, yes: String, run: () -> Unit) {
            MaterialAlertDialogBuilder(act)
                .setMessage(msg)
                .setPositiveButton(yes) { _, _ -> run() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        /** (Re)build the categories, used at start and after a toggle. S46: accordion + sub groups. */
        private fun fill() {
            val col = body ?: return
            val keepY = sv?.scrollY ?: 0
            col.removeAllViews()
            cats.clear()
            val tabs = BookmarksScreen.tabsRef
            val prefs = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
            val fa = act as? androidx.fragment.app.FragmentActivity

            category("general", R.drawable.ic_settings, "General", "Theme, search, pages, display", listOf(
                Sub("Appearance"),
                Row(R.drawable.ic_theme, "Theme", UiMode.label(act)) { dismiss(); UiMode.pick(act) },
                Row(R.drawable.ic_theme, "Dark pages", if (UiMode.darkPages(act)) "ON" else "OFF") {
                    val on = !UiMode.darkPages(act)
                    UiMode.setDarkPages(act, on)
                    tabs?.applyDarkPages()
                    Util.toast(act, if (on) "Dark pages ON (reload the page)" else "Dark pages OFF (reload the page)")
                    fill()
                },
                Sub("Browsing"),
                Row(R.drawable.ic_search, "Search engine", Util.engineName(act)) {
                    val names = Util.ENGINES.map { it.first }
                    MaterialAlertDialogBuilder(act).setTitle("Search engine")
                        .setItems(names.toTypedArray()) { _, i -> Util.setEngine(act, names[i]); fill() }.show()
                },
                Row(R.drawable.ic_search, "Translate to", Translate.langName(act)) { Translate.pickLang(act) { fill() } }, // S41
                Row(R.drawable.ic_screen, "Hide bar while scrolling", if (hideBar()) "ON" else "OFF") {
                    prefs.edit().putBoolean("hide_bar", !hideBar()).apply()
                    fill()
                },
                Row(R.drawable.ic_reload, "Pull down to refresh", if (PullRefresh.enabled(act)) "ON" else "OFF") { // S45
                    PullRefresh.setEnabled(act, !PullRefresh.enabled(act))
                    fill()
                },
                Row(R.drawable.ic_screen, "Rotate browser with phone", if (Orient.rotateBrowser(act)) "ON" else "OFF") { // S35
                    Orient.setRotateBrowser(act, !Orient.rotateBrowser(act))
                    Orient.apply(act)
                    fill()
                }
            ))
            category("downloads", R.drawable.ic_download, "Downloads", "Grabber, player, buttons", listOf(
                Sub("Manage"),
                Row(R.drawable.ic_download, "Downloads") { dismiss(); DownloadsScreen.open(act) },
                Row(R.drawable.ic_list, "Queue") { ext("queue.html") },
                Row(R.drawable.ic_history, "Download history") { ext("history.html") },
                Row(R.drawable.ic_settings, "Grabber settings") { ext("options.html") },
                Sub("Player and buttons"),
                Row(R.drawable.ic_play, "Play video links in built-in player", if (WebPlay.linksEnabled(act)) "ON" else "OFF") { // S39
                    WebPlay.setLinksEnabled(act, !WebPlay.linksEnabled(act))
                    fill()
                },
                Row(R.drawable.ic_play, "Video button on pages", if (WebVideo.on(act)) "ON" else "OFF") { // S45
                    WebVideo.setOn(act, !WebVideo.on(act))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_play, "Background audio in player", if (PlayerService.on(act)) "ON" else "OFF") { // S45
                    PlayerService.setOn(act, !PlayerService.on(act))
                    fill()
                },
                Row(R.drawable.ic_download, "Extra native download button", if (nativeBtn()) "ON" else "OFF") {
                    prefs.edit().putBoolean("native_btn", !nativeBtn()).apply()
                    Util.toast(act, "Changes in a few seconds")
                    fill()
                }
            ))
            category("privacy", R.drawable.ic_shield, "Privacy and security", "Lock, permissions, ads, HTTPS", listOf(
                Sub("Security"),
                Row(R.drawable.ic_lock, "App lock", if (AppLock.on(act)) "ON · ${AppLock.delayLabel(act)}" else "OFF") { // S45
                    fa?.let { AppLock.toggle(it) { fill() } }
                },
                Row(R.drawable.ic_lock, "Lock after", AppLock.delayLabel(act)) { AppLock.pickDelay(act) { fill() } }, // S45
                Row(R.drawable.ic_shield, "Site permissions", "${SitePerms.count(act)} saved") { // S45
                    fa?.let { SitePerms.screen(it) { fill() } }
                },
                Row(R.drawable.ic_lock, "HTTPS-only mode", if (HttpsOnly.on(act)) "ON" else "OFF") { // S41
                    HttpsOnly.set(act, !HttpsOnly.on(act))
                    fill()
                },
                Sub("Ads and cookies"),
                Row(R.drawable.ic_shield, "Ad blocker", if (AdBlock.enabled) "ON · ${AdBlock.blocked} blocked" else "OFF") {
                    Util.toast(act, if (AdBlock.toggle()) "Ad blocker ON (reload the page)" else "Ad blocker OFF (reload the page)")
                    fill()
                },
                Row(R.drawable.ic_shield, "Hide ad spaces on pages", if (CosmeticFilter.adsOn(act)) "ON" else "OFF") { // S42
                    CosmeticFilter.setAds(act, !CosmeticFilter.adsOn(act))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_list, "Sites with ads allowed") { CosmeticFilter.allowList(act) { fill() } }, // S42
                Row(R.drawable.ic_shield, "Hide cookie banners", if (CosmeticFilter.cookieOn(act)) "ON" else "OFF") { // S42
                    CosmeticFilter.setCookie(act, !CosmeticFilter.cookieOn(act))
                    Util.toast(act, "Reload the page")
                    fill()
                }
            ))
            category("data", R.drawable.ic_folder, "Data and backup", "Backup, history, cookies, cache", listOf(
                Sub("Backup"),
                Row(R.drawable.ic_history, "Auto backup (daily)", if (Backup.autoOn(act)) "ON" else "OFF") { // S42
                    Backup.setAuto(act, !Backup.autoOn(act))
                    if (Backup.autoOn(act)) Util.toast(act, "Saves Downloads/mgb-auto-backup.json once a day")
                    fill()
                },
                Row(R.drawable.ic_share, "Export data") { Backup.exportData(act) },
                Row(R.drawable.ic_download, "Import data") { Backup.importData(act) },
                Sub("Clear data"),
                Row(R.drawable.ic_history, "Recently closed tabs") { dismiss(); Backup.closedTabs(act, tabs) },
                Row(R.drawable.ic_trash, "Clear browsing history") {
                    confirm("Clear all browsing history?", "Clear") { Bookmarks.clearHistory(); Util.toast(act, "History cleared") }
                },
                Row(R.drawable.ic_trash, "Clear cookies and site data") {
                    confirm("Log out of sites and clear site data in normal tabs?", "Clear") {
                        try {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            Util.toast(act, "Cookies and site data cleared")
                        } catch (e: Exception) { Util.toast(act, "Could not clear") }
                    }
                },
                Row(R.drawable.ic_trash, "Clear cache") {
                    try {
                        tabs?.active?.web?.clearCache(true)
                        Util.toast(act, "Cache cleared")
                    } catch (e: Exception) { Util.toast(act, "Could not clear") }
                }
            ))
            category("passwords", R.drawable.ic_lock, "Passwords and email", "Saved logins, generator, aliases", listOf(
                Sub("Passwords"),
                Row(R.drawable.ic_lock, "Saved passwords") { fa?.let { PasswordStore.screen(it) } },
                Row(R.drawable.ic_download, "Import passwords (file or paste)") { fa?.let { PasswordStore.importChoice(it) } },
                Row(R.drawable.ic_share, "Export passwords (CSV)") { fa?.let { PasswordStore.exportCsv(it) } },
                Row(R.drawable.ic_lock, "Password generator") { fa?.let { PasswordStore.generatorDialog(it) } },
                Sub("Email alias"),
                Row(R.drawable.ic_share, "addy.io API key", if (EmailAlias.hasKey(act)) "set" else "not set") { EmailAlias.keyDialog(act) { fill() } },
                Row(R.drawable.ic_add, "Create email alias") { EmailAlias.create(act, "") },
                Row(R.drawable.ic_list, "My email aliases") { EmailAlias.manage(act) }
            ))
            category("about", R.drawable.ic_info, "About", "Updates, engine, version", listOf(
                Row(R.drawable.ic_reload, "Check for updates") { UpdateManager.check(act, false) },
                Row(R.drawable.ic_info, "Engine status") { EngineHost.showStatus(act) },
                Row(R.drawable.ic_info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            ))
            sv?.post { sv?.scrollTo(0, keepY) }
        }
    }
}
