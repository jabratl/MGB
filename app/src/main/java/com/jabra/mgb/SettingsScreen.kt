package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S27: native Settings screen (full-screen Dialog, no manifest entry). Grouped rounded cards with small
 * section labels: General, Downloads, Privacy and security, About. The V77 options page stays reachable
 * as "Grabber settings".
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
            sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill()
        }

        private class Row(val icon: Int, val label: String, val value: String = "", val onClick: (() -> Unit)? = null)

        private fun section(title: String, rows: List<Row>) {
            val col = body ?: return
            col.addView(TextView(act).apply {
                text = title.uppercase()
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(8), dp(20), 0, dp(8))
            })
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 24)
                setPadding(0, dp(6), 0, dp(6))
            }
            for (r in rows) {
                val line = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
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
                    textSize = 16f
                    maxLines = 1
                    setTextColor(act.getColor(R.color.mg_text))
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (r.value.isNotEmpty()) line.addView(TextView(act).apply {
                    text = r.value
                    textSize = 13f
                    setTextColor(act.getColor(if (r.value.startsWith("ON")) R.color.mg_primary else R.color.mg_text_dim))
                })
                card.addView(line, LinearLayout.LayoutParams(MATCH, dp(52)))
            }
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
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

        /** (Re)build the sections, used at start and after a toggle. */
        private fun fill() {
            val col = body ?: return
            col.removeAllViews()
            val tabs = BookmarksScreen.tabsRef

            section("General", listOf(
                Row(R.drawable.ic_theme, "Theme", UiMode.label(act)) { dismiss(); UiMode.pick(act) },
                Row(R.drawable.ic_search, "Search engine", Util.engineName(act)) {
                    val names = Util.ENGINES.map { it.first }
                    MaterialAlertDialogBuilder(act).setTitle("Search engine")
                        .setItems(names.toTypedArray()) { _, i -> Util.setEngine(act, names[i]); fill() }.show()
                },
                Row(R.drawable.ic_screen, "Hide bar while scrolling", if (hideBar()) "ON" else "OFF") {
                    act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("hide_bar", !hideBar()).apply()
                    fill()
                },
                Row(R.drawable.ic_screen, "Rotate browser with phone", if (Orient.rotateBrowser(act)) "ON" else "OFF") { // S35
                    Orient.setRotateBrowser(act, !Orient.rotateBrowser(act))
                    Orient.apply(act)
                    fill()
                },
                Row(R.drawable.ic_lock, "HTTPS-only mode", if (HttpsOnly.on(act)) "ON" else "OFF") { // S41
                    HttpsOnly.set(act, !HttpsOnly.on(act))
                    fill()
                },
                Row(R.drawable.ic_search, "Translate to", Translate.langName(act)) { Translate.pickLang(act) { fill() } }, // S41
                Row(R.drawable.ic_theme, "Dark pages", if (UiMode.darkPages(act)) "ON" else "OFF") {
                    val on = !UiMode.darkPages(act)
                    UiMode.setDarkPages(act, on)
                    tabs?.applyDarkPages()
                    Util.toast(act, if (on) "Dark pages ON (reload the page)" else "Dark pages OFF (reload the page)")
                    fill()
                }
            ))
            section("Downloads", listOf(
                Row(R.drawable.ic_download, "Downloads") { dismiss(); DownloadsScreen.open(act) },
                Row(R.drawable.ic_list, "Queue") { ext("queue.html") },
                Row(R.drawable.ic_history, "Download history") { ext("history.html") },
                Row(R.drawable.ic_settings, "Grabber settings") { ext("options.html") },
                Row(R.drawable.ic_play, "Play video links in built-in player", if (WebPlay.linksEnabled(act)) "ON" else "OFF") { // S39
                    WebPlay.setLinksEnabled(act, !WebPlay.linksEnabled(act))
                    fill()
                },
                Row(R.drawable.ic_download, "Extra native download button", if (nativeBtn()) "ON" else "OFF") {
                    act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("native_btn", !nativeBtn()).apply()
                    Util.toast(act, "Changes in a few seconds")
                    fill()
                }
            ))
            section("Data", listOf(
                Row(R.drawable.ic_history, "Auto backup (daily)", if (Backup.autoOn(act)) "ON" else "OFF") { // S42
                    Backup.setAuto(act, !Backup.autoOn(act))
                    if (Backup.autoOn(act)) Util.toast(act, "Saves Downloads/mgb-auto-backup.json once a day")
                    fill()
                },
                Row(R.drawable.ic_share, "Export data") { Backup.exportData(act) },
                Row(R.drawable.ic_download, "Import data") { Backup.importData(act) },
                Row(R.drawable.ic_history, "Recently closed tabs") { dismiss(); Backup.closedTabs(act, tabs) }
            ))
            val fa = act as? androidx.fragment.app.FragmentActivity
            section("Passwords and email", listOf(
                Row(R.drawable.ic_lock, "Saved passwords") { fa?.let { PasswordStore.screen(it) } },
                Row(R.drawable.ic_share, "addy.io API key", if (EmailAlias.hasKey(act)) "set" else "not set") { EmailAlias.keyDialog(act) { fill() } },
                Row(R.drawable.ic_download, "Import passwords (file or paste)") { fa?.let { PasswordStore.importChoice(it) } },
                Row(R.drawable.ic_share, "Export passwords (CSV)") { fa?.let { PasswordStore.exportCsv(it) } },
                Row(R.drawable.ic_lock, "Password generator") { fa?.let { PasswordStore.generatorDialog(it) } },
                Row(R.drawable.ic_add, "Create email alias") { EmailAlias.create(act, "") },
                Row(R.drawable.ic_list, "My email aliases") { EmailAlias.manage(act) }
            ))
            section("Privacy and security", listOf(
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
                },
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
            section("About", listOf(
                Row(R.drawable.ic_reload, "Check for updates") { UpdateManager.check(act, false) },
                Row(R.drawable.ic_info, "Engine status") { EngineHost.showStatus(act) },
                Row(R.drawable.ic_info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            ))
        }
    }
}
