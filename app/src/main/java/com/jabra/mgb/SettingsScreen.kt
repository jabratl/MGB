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
import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionSet
import android.transition.TransitionManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/**
 * S27: native Settings screen (full-screen Dialog, no manifest entry). The V77 options page stays reachable
 * as "Grabber settings".
 * S46: categories are collapsed cards (accordion, one open at a time). Inside a category the rows are
 * grouped by small sub labels, ON/OFF rows use a switch. Open category is kept while a toggle rebuilds.
 * S48: search box under the title (filters rows by label, matching categories open); a category with more
 * than SUB_FOLD rows gets collapsible sub groups.
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
        private var query = "" // S48 search text
        // 0.0.136 (Step A): big categories open as their own page inside this Dialog
        private var subPage: String? = null
        private var savedY = 0
        private var forceY = -1
        private var titleTv: TextView? = null
        private var sboxView: View? = null
        private var searchEdit: EditText? = null
        private val BIG = mapOf("downloads" to "Downloads", "grabber" to "Grabber and buttons")
        private val NO_RECENT = setOf("Forget removed links")
        private var flashLabel: String? = null // Step B: row that was just toggled
        /** Step C: the ONLY mgb_ui keys each card's "Reset to default" may remove. Nothing else is ever touched. */
        private val RESET = mapOf(
            "general" to listOf("night", "dark_pages", "engine", "tr_lang", "hide_bar", "pull_refresh", "rotate_browser"),
            "tabs" to listOf("swipe_close", "close_confirm", "reduce_motion"),
            "downloads" to listOf("auto_diag"),
            "grabber" to listOf("web_play", "bg_audio", "v77_backup", "v77_prev_mode", "dl_mode", "native_btn", "video_btn"),
            "privacy" to listOf("https_only", "hide_ad_space", "hide_cookie"),
            "data" to listOf("auto_backup")
        )
        private val RESTART = setOf("Theme", "Rotate browser with phone", "Extra native download button") // Step C: restart icon
        private val RESTART_BAR = setOf("Rotate browser with phone", "Extra native download button") // Snackbar (Theme recreates itself)
        private var flashAt = 0L
        /** Step B: one short hint line for rows that have none (text only, kept under about 34 characters). */
        private val HINTS = mapOf(
            "Theme" to "Light, dark or follow phone",
            "Dark pages" to "Darken websites (reload page)",
            "Search engine" to "Used in the address bar",
            "Translate to" to "Language for page translation",
            "Hide bar while scrolling" to "Bottom bar hides on scroll",
            "Pull down to refresh" to "Swipe down to reload the page",
            "Rotate browser with phone" to "Landscape follows the phone",
            "Swipe a tab card to close it" to "In the tab switcher",
            "Convert .ts to .mp4" to "Merge HLS pieces into MP4",
            "Sub-folders in the chosen folder" to "Use sub-folders for new files",
            "Play video links in built-in player" to "Open video links in MGB player",
            "Video button on pages" to "Video button shown on web pages",
            "Background audio in player" to "Keep playing with screen off",
            "Button mode" to "Which download buttons to show",
            "Grabber button on pages" to "Floating Grabber button",
            "Grabber button opens Files detected panel" to "Opens the native detected list",
            "Grabber button on video players" to "Button on top of videos",
            "Forget removed links" to "Brings back hidden links",
            "Extra native download button" to "Second button, native downloader",
            "App lock" to "Ask for fingerprint or PIN",
            "Lock after" to "Time before the app locks",
            "Site permissions" to "Camera, mic, location per site",
            "HTTPS-only mode" to "Prefer secure https pages",
            "Ad blocker" to "Blocks ads and trackers",
            "Hide ad spaces on pages" to "Removes empty ad boxes",
            "Sites with ads allowed" to "Pages where ads stay on",
            "Hide cookie banners" to "Hides cookie consent popups",
            "Auto backup (daily)" to "Saves a backup file each day",
            "Export data" to "Save bookmarks and settings",
            "Import data" to "Restore from a backup file",
            "Recently closed tabs" to "Reopen tabs you closed",
            "Clear browsing history" to "Deletes the visited pages list",
            "Clear cookies and site data" to "Logs you out of sites",
            "Clear cache" to "Frees stored page files",
            "Saved passwords" to "View and edit saved logins",
            "Import passwords (file or paste)" to "Add logins from CSV or text",
            "Export passwords (CSV)" to "Save logins to a CSV file",
            "Password generator" to "Make a strong random password",
            "addy.io API key" to "Needed to create aliases",
            "Create email alias" to "New hidden email address",
            "My email aliases" to "List, edit or delete aliases",
            "Check for updates" to "Look for a newer version",
            "Engine status" to "Shows the web engine state"
        )
        /** S21f: hidden search words per row label (the row is found by these too). */
        private val KW = mapOf(
            "Theme" to "dark light night mode appearance colour",
            "Default browser" to "links open http https set app phone settings",
            "Dark pages" to "dark mode night websites black",
            "Search engine" to "google bing duckduckgo default address bar",
            "Translate to" to "language translation",
            "Hide bar while scrolling" to "bottom bar toolbar fullscreen immersive",
            "Pull down to refresh" to "reload swipe",
            "Rotate browser with phone" to "landscape portrait auto rotate orientation",
            "Swipe a tab card to close it" to "gesture tab switcher remove",
            "Ask before Close all" to "confirm dialog tabs",
            "Reduce motion" to "animation animations effects fade transitions speed",
            "Download folder" to "storage location save path sd card",
            "Download quality" to "resolution 1080 720 best height",
            "Convert .ts to .mp4" to "hls merge remux",
            "Subtitles and file names" to "srt vtt naming template language",
            "Play video links in built-in player" to "external player exoplayer",
            "Background audio in player" to "music screen off",
            "App lock" to "fingerprint biometric pin password security",
            "Site permissions" to "camera microphone location mic",
            "HTTPS-only mode" to "secure ssl http upgrade",
            "Ad blocker" to "ads popup pop-up tracker adblock block",
            "Hide ad spaces on pages" to "ads empty space cosmetic",
            "Hide cookie banners" to "consent gdpr popup cookies",
            "Auto backup (daily)" to "save restore",
            "Export data" to "backup save file bookmarks",
            "Import data" to "restore backup file",
            "Recently closed tabs" to "reopen history undo",
            "Clear browsing history" to "delete history privacy",
            "Clear cookies and site data" to "logout delete storage",
            "Clear cache" to "delete temporary files storage space",
            "Saved passwords" to "logins credentials vault",
            "Password generator" to "random strong",
            "Create email alias" to "addy.io mask spam",
            "Extensions" to "addons plugins chrome crx zip",
            "Check for updates" to "upgrade version new release",
            "Novel grabber" to "epub docx web2epub book",
            "Report center" to "diagnose problem error crash log"
        )

        private val SUB_FOLD = 14 // S48: rows per category before sub groups fold
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
                it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
                setOnClickListener { goBack() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = "Settings"
                textSize = 22f // 0.0.129: left aligned larger title
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, 0, 0)
                titleTv = this
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            // S48: search box (outside the ScrollView so it survives fill())
            val sbox = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(act.getColor(R.color.mg_surface), 28) // 0.0.129: pill
                setPadding(dp(14), 0, dp(6), 0)
            }
            sbox.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_search)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            val clear = ImageView(act).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Clear search"
                visibility = View.GONE
            }
            val edit = EditText(act).apply {
                hint = "Search settings"
                textSize = 15f
                setSingleLine(true)
                background = null
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setTextColor(act.getColor(R.color.mg_text))
                setHintTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(0, 0, 0, 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString() ?: ""
                        clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                        if (query.isNotBlank() && subPage != null) { subPage = null; applyPage() }
                        fill()
                        sv?.scrollTo(0, 0)
                    }
                })
                setOnEditorActionListener { v, _, _ ->
                    v.clearFocus()
                    (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                }
            }
            searchEdit = edit
            sboxView = sbox
            clear.setOnClickListener { edit.setText("") }
            sbox.addView(edit, LinearLayout.LayoutParams(0, dp(48), 1f))
            sbox.addView(clear, LinearLayout.LayoutParams(dp(40), dp(40)))
            root.addView(sbox, LinearLayout.LayoutParams(MATCH, dp(48)).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(2); bottomMargin = dp(8)
            })

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

        private class Row(val icon: Int, val label: String, val value: String = "", val hint: String = "", val onClick: (() -> Unit)? = null)

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
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) // 0.0.129: accent group label
            setTextColor(act.getColor(R.color.mg_primary))
            setPadding(dp(18), dp(14), dp(16), dp(4))
        }

        private fun hintOf(r: Row): String = r.hint.ifEmpty { HINTS[r.label] ?: "" } // Step B

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
                    setOnClickListener {
                        if (r.value == "ON" || r.value == "OFF") it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) // S21f
                        if (r.value.isNotEmpty() && r.label !in NO_RECENT) noteRecent(r.label) // Step A
                        if (r.value == "ON" || r.value == "OFF") { flashLabel = r.label; flashAt = android.os.SystemClock.uptimeMillis() } // Step B
                        r.onClick.invoke()
                        if (r.label in RESTART_BAR) restartBar() // Step C
                    }
                }
            }
            line.addView(ImageView(act).apply {
                setImageResource(r.icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            val labelBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            labelBox.addView(TextView(act).apply {
                text = r.label
                textSize = 15f
                maxLines = 2
                setTextColor(act.getColor(R.color.mg_text))
            })
            val hintText = hintOf(r)
            if (hintText.isNotEmpty()) labelBox.addView(TextView(act).apply { // S64: one line of help under the title
                text = hintText
                textSize = 12f
                maxLines = 2
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            line.addView(labelBox, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (r.label in RESTART) line.addView(ImageView(act).apply { // Step C: restart needed
                setImageResource(R.drawable.ic_reload)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                alpha = 0.7f
                contentDescription = "Restart needed"
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })
            if (r.value == "ON" || r.value == "OFF") {
                val sw = switchFor(r.value == "ON")
                line.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                // Step B: the row just toggled gets a short highlight that fades, and the thumb slides
                if (flashLabel == r.label && android.os.SystemClock.uptimeMillis() - flashAt < 1500 && !TabPrefs.reduce(act)) {
                    flashLabel = null
                    val d = ColorDrawable(act.getColor(R.color.mg_primary))
                    d.alpha = 0x40
                    line.background = d
                    ValueAnimator.ofInt(0x40, 0).setDuration(700).apply {
                        addUpdateListener { d.alpha = it.animatedValue as Int }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) { line.background = null }
                        })
                    }.start()
                    val on = r.value == "ON"
                    sw.isChecked = !on
                    sw.post { sw.isChecked = on }
                }
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
            // S48: search filter. A title match keeps the whole category, else only rows whose label matches.
            val q = query.trim()
            val searching = q.isNotEmpty()
            val sub = !searching && subPage == key // Step A: this category is the open page
            if (!searching && subPage != null && !sub) return
            val link = !searching && !sub && BIG.containsKey(key) // Step A: big category = one row that opens a page
            var shown = items
            if (searching && !title.contains(q, true)) {
                val out = ArrayList<Any>()
                var pend: Sub? = null
                for (item in items) {
                    when (item) {
                        is Sub -> pend = item
                        is Row -> if (item.label.contains(q, true) || hintOf(item).contains(q, true) || (KW[item.label]?.contains(q, true) == true)) { // S21f: search keywords
                            pend?.let { out.add(it) }
                            pend = null
                            out.add(item)
                        }
                    }
                }
                if (out.isEmpty()) return
                shown = out
            }
            val fold = !searching && !sub && shown.count { it is Row } > SUB_FOLD
            val isOpen = searching || sub || (!link && openCat == key)
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 24).apply { // 0.0.129: hairline outline
                    setStroke(dp(1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                }
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
            if (!sub) content.addView(View(act).apply { setBackgroundColor(act.getColor(R.color.mg_outline)) },
                LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) })
            var box: LinearLayout? = null // S48: current folded sub group
            var firstSub = true
            for (item in (if (link) emptyList<Any>() else shown)) {
                when (item) {
                    is Sub -> {
                        if (fold) {
                            val t = item.title.uppercase()
                            val b = LinearLayout(act).apply {
                                orientation = LinearLayout.VERTICAL
                                visibility = if (firstSub) View.VISIBLE else View.GONE
                            }
                            val lbl = subLabel(item.title)
                            lbl.text = (if (firstSub) "\u25BE " else "\u25B8 ") + t
                            lbl.setOnClickListener {
                                val open = b.visibility != View.VISIBLE
                                b.visibility = if (open) View.VISIBLE else View.GONE
                                lbl.text = (if (open) "\u25BE " else "\u25B8 ") + t
                            }
                            content.addView(lbl, LinearLayout.LayoutParams(MATCH, WRAP))
                            content.addView(b, LinearLayout.LayoutParams(MATCH, WRAP))
                            box = b
                            firstSub = false
                        } else {
                            content.addView(subLabel(item.title), LinearLayout.LayoutParams(MATCH, WRAP))
                        }
                    }
                    is Row -> (box ?: content).addView(rowView(item), LinearLayout.LayoutParams(MATCH, WRAP))
                }
            }
            if (!link && !searching && RESET.containsKey(key)) { // Step C: Reset to default, last row of the card
                content.addView(rowView(Row(R.drawable.ic_reload, "Reset to default", "", "Only the settings of this card") { resetCard(key, title) }),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }
            val header = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = title
                val out = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    foreground = act.getDrawable(out.resourceId)
                }
                setOnClickListener { if (link) openPage(key) else toggle(key, card) }
            }
            val night = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val tint = TabPrefs.tint(key, night) // Step B: own soft tint per category
            val badge = FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE // 0.0.129: squircle badge
                    cornerRadius = dp(13).toFloat()
                    setColor(ColorUtils.setAlphaComponent(tint, 0x2B))
                }
            }
            badge.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(tint)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            header.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
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
            if (!sub) card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            if (!link) card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            if (!link) cats[key] = Pair(content, chev)
        }

        // ---- Step C: utility rows ----
        private fun resetCard(key: String, title: String) {
            val keys = RESET[key] ?: return
            confirm("Reset \"$title\" to default? Only the settings of this card change. Bookmarks, passwords and files are not touched.", "Reset") {
                try {
                    val themeBefore = UiMode.mode(act)
                    if (key == "grabber" && V77Alt.on(act)) V77Alt.set(act, false) // restores the saved mode first
                    val ed = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).edit()
                    for (k in keys) ed.remove(k)
                    ed.apply()
                    if (key == "grabber") DlMode.apply(act, DlMode.SMART) // writes the default button switches
                    if (key == "general") {
                        Orient.apply(act)
                        UiMode.apply(act)
                        BookmarksScreen.tabsRef?.applyDarkPages()
                    }
                    Util.toast(act, "$title reset to default")
                    if (key == "general" && themeBefore != 0) { dismiss(); act.recreate() } else fill()
                } catch (e: Exception) {
                    Log.e(TAG, "reset failed", e)
                    Util.toast(act, "Could not reset")
                }
            }
        }

        /** Shortcut to the Data card actions: Export / Import. */
        private fun backupCard(): View {
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 24).apply {
                    setStroke(dp(1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                }
            }
            card.addView(rowView(Row(R.drawable.ic_share, "Backup", "", "Export or import your data") {
                MaterialAlertDialogBuilder(act).setTitle("Backup")
                    .setItems(arrayOf("Export data", "Import data")) { _, i -> if (i == 0) Backup.exportData(act) else Backup.importData(act) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }), LinearLayout.LayoutParams(MATCH, WRAP))
            return card
        }

        private fun isDefaultBrowser(): Boolean = try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://example.com"))
            act.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == act.packageName
        } catch (_: Exception) { false }

        /** Android 10+: the "Default apps" page of the phone, else the app details page. */
        private fun openDefaultBrowser() {
            val details = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + act.packageName))
            val first = if (android.os.Build.VERSION.SDK_INT >= 29)
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) else details
            try { act.startActivity(first) } catch (e: Exception) {
                try { act.startActivity(details) } catch (e2: Exception) { Util.toast(act, "Could not open phone settings") }
            }
        }

        private fun restartBar() {
            try {
                val v = findViewById<View>(android.R.id.content) ?: return
                Snackbar.make(v, "Restart needed to apply this fully", Snackbar.LENGTH_LONG)
                    .setAction("Apply now") { dismiss(); act.recreate() }
                    .show()
            } catch (_: Exception) { }
        }

        // ---- Step A: sub-pages and Recent ----
        private fun applyPage() {
            titleTv?.text = subPage?.let { BIG[it] } ?: "Settings"
            sboxView?.visibility = if (subPage == null) View.VISIBLE else View.GONE
        }

        private fun openPage(key: String) {
            savedY = sv?.scrollY ?: 0
            subPage = key
            forceY = 0
            applyPage()
            fill()
        }

        /** Back order: sub-page -> Settings -> close. */
        private fun goBack() {
            if (subPage != null) {
                subPage = null
                forceY = savedY
                applyPage()
                fill()
            } else dismiss()
        }

        override fun onBackPressed() { goBack() }

        private fun recents(prefs: android.content.SharedPreferences): List<String> =
            (prefs.getString("recent_settings", "") ?: "").split("\n").filter { it.isNotBlank() }

        private fun noteRecent(label: String) {
            try {
                val prefs = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
                val list = ArrayList<String>()
                list.add(label)
                for (x in recents(prefs)) if (x != label && list.size < 5) list.add(x)
                prefs.edit().putString("recent_settings", list.joinToString("\n")).apply()
            } catch (_: Exception) { }
        }

        private fun recentRow(prefs: android.content.SharedPreferences): View? {
            val list = recents(prefs)
            if (list.isEmpty()) return null
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(act).apply {
                text = "RECENT"
                textSize = 11f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_primary))
                setPadding(dp(4), 0, 0, dp(6))
            })
            val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
            val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (l in list) chips.addView(TextView(act).apply {
                text = l
                textSize = 13f
                maxLines = 1
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = round(act.getColor(R.color.mg_surface), 20).apply {
                    setStroke(dp(1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                }
                isClickable = true
                setOnClickListener { searchEdit?.setText(l) } // shows that setting through search
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            hs.addView(chips)
            box.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))
            return box
        }

        /** S46: open one category, close the others (animated). */
        private fun toggle(key: String, card: View) {
            val col = body ?: return
            if (query.isNotBlank()) return // S48: all matches stay open while searching
            val opening = openCat != key
            val calm = TabPrefs.reduce(act) // Step B: Reduce motion = no slide, no rotate animation
            if (!calm) try {
                // one transition owns the layout change: card bounds + content slide (chevron uses its own rotate below)
                val slide = Slide(Gravity.TOP)
                for ((_, v) in cats) slide.addTarget(v.first)
                val ts = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds())
                    addTransition(slide)
                    duration = 220
                    interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                }
                TransitionManager.beginDelayedTransition(col, ts)
            } catch (_: Exception) { }
            openCat = if (opening) key else null
            for ((k, v) in cats) {
                val show = k == openCat
                v.first.visibility = if (show) View.VISIBLE else View.GONE
                val deg = if (show) 90f else 0f
                v.second.animate().cancel()
                if (calm) v.second.rotation = deg else v.second.animate().rotation(deg).setDuration(220).start()
            }
            if (opening) card.postDelayed({
                sv?.smoothScrollTo(0, (card.top - dp(8)).coerceAtLeast(0))
            }, if (calm) 0L else 240L)
        }

        private fun hideBar(): Boolean =
            act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).getBoolean("hide_bar", false)

        // MGB S51: V77 settings (chrome.storage.sync) read / written from native
        private fun v77On(key: String, def: Boolean): Boolean =
            try { ChromeBridge.sync.get(key).optBoolean(key, def) } catch (e: Exception) { def }

        private fun v77Set(key: String, v: Boolean) { ChromeBridge.storageSet("sync", org.json.JSONObject().put(key, v)) }

        private fun nativeBtn(): Boolean =
            act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).getBoolean("native_btn", false)

        /** S64: one-line summaries of V77 settings for the rows. */
        private fun syncStr(k: String, def: String): String = try { ChromeBridge.sync.get(k).optString(k, def) } catch (e: Exception) { def }
        private fun syncInt(k: String, def: Int): Int = try { ChromeBridge.sync.get(k).optInt(k, def) } catch (e: Exception) { def }
        private fun subSummary(): String =
            if (!v77On("autoSubs", true)) "OFF" else syncStr("subLangs", "en").ifBlank { "any" } + " · " + syncStr("subFmt", "srt").uppercase()
        private fun engSummary(): String {
            val sp = syncInt("speedLimitKBps", 0)
            return syncInt("maxJobs", 2).toString() + " at a time · " + (if (sp <= 0) "no limit" else if (sp >= 1024) "${sp / 1024} MB/s" else "$sp KB/s")
        }

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
        /** S21f: four quick switches above the categories (hidden while searching). */
        private fun quickRow(prefs: android.content.SharedPreferences): View {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            fun chip(icon: Int, label: String, on: Boolean, tap: () -> Unit) {
                val c = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(12), dp(4), dp(10))
                    background = round(if (on) ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 0x2A) else act.getColor(R.color.mg_surface), 18).apply {
                        setStroke(dp(1), if (on) act.getColor(R.color.mg_primary) else ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                    }
                    isClickable = true
                    setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); noteRecent(if (label == "Hide bar on scroll") "Hide bar while scrolling" else label); tap(); fill() }
                }
                c.addView(ImageView(act).apply {
                    setImageResource(icon)
                    setColorFilter(act.getColor(if (on) R.color.mg_primary else R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                c.addView(TextView(act).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(0, dp(6), 0, 0)
                    setTextColor(act.getColor(if (on) R.color.mg_primary else R.color.mg_text))
                })
                row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            chip(R.drawable.ic_shield, "Ad blocker", AdBlock.enabled) { AdBlock.toggle() }
            chip(R.drawable.ic_theme, "Dark pages", UiMode.darkPages(act)) {
                UiMode.setDarkPages(act, !UiMode.darkPages(act))
                BookmarksScreen.tabsRef?.applyDarkPages()
            }
            chip(R.drawable.ic_screen, "Hide bar on scroll", prefs.getBoolean("hide_bar", false)) { prefs.edit().putBoolean("hide_bar", !prefs.getBoolean("hide_bar", false)).apply() }
            chip(R.drawable.ic_play, "Reduce motion", TabPrefs.reduceSwitch(act)) { TabPrefs.set(act, "reduce_motion", !TabPrefs.reduceSwitch(act)) }
            return row
        }

        private fun fill() {
            val col = body ?: return
            val keepY = if (forceY >= 0) forceY else (sv?.scrollY ?: 0)
            forceY = -1
            col.removeAllViews()
            cats.clear()
            val tabs = BookmarksScreen.tabsRef
            val prefs = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
            val fa = act as? androidx.fragment.app.FragmentActivity

            if (query.trim().isEmpty() && subPage == null) {
                recentRow(prefs)?.let { col.addView(it, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }) } // Step A
                col.addView(quickRow(prefs), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }) // S21f
                col.addView(backupCard(), LinearLayout.LayoutParams(MATCH, WRAP)) // Step C
            }
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
                },
                Row(R.drawable.ic_screen, "Default browser", if (isDefaultBrowser()) "Default" else "", "Make MGB open web links") { openDefaultBrowser() } // Step C
            ))
            category("tabs", R.drawable.ic_list, "Tabs and motion", "Swipe, close, animations", listOf( // S21f
                Sub("Tabs"),
                Row(R.drawable.ic_list, "Swipe a tab card to close it", if (TabPrefs.swipeClose(act)) "ON" else "OFF") {
                    TabPrefs.set(act, "swipe_close", !TabPrefs.swipeClose(act))
                    fill()
                },
                Row(R.drawable.ic_close, "Ask before Close all", if (TabPrefs.confirmClose(act)) "ON" else "OFF", "OFF = tabs close at once and Undo brings them back") {
                    TabPrefs.set(act, "close_confirm", !TabPrefs.confirmClose(act))
                    fill()
                },
                Sub("Motion"),
                Row(R.drawable.ic_play, "Reduce motion", if (TabPrefs.reduceSwitch(act)) "ON" else "OFF", "Turns off the tab fade, close, count and download animations") {
                    TabPrefs.set(act, "reduce_motion", !TabPrefs.reduceSwitch(act))
                    fill()
                }
            ))
            category("downloads", R.drawable.ic_download, "Downloads", "Library, quality, files", listOf( // S64: arranged by what you do
                Sub("Library"),
                Row(R.drawable.ic_download, "Downloads", "", "Files saved on this phone") { dismiss(); DownloadsScreen.open(act) },
                Row(R.drawable.ic_book, "Novel grabber", "", "Web novels and chapters to EPUB, DOCX, TXT") { ext("novel/studio.html") }, // N1
                Row(R.drawable.ic_list, "Queue", "", "Running, waiting and failed jobs") { ext("queue.html") },
                Row(R.drawable.ic_history, "Download history", "", "Everything Grabber saved") { ext("history.html") },
                Row(R.drawable.ic_info, "Report center", "", "Why something does not work: report, copy, share") { dismiss(); DiagScreen.open(act, null, -1) }, // S85
                Row(R.drawable.ic_list, "Report log size", DiagScreen.logSizeText(), "Events kept for the report (no page text)"), // S85
                Row(R.drawable.ic_close, "Clear report log", "", "Delete the diagnose log and the Grabber log") { DiagScreen.askClearLog(act) { fill() } }, // S85
                Row(R.drawable.ic_info, "Auto Diagnose pill", if (AutoDiag.on(act)) "ON" else "OFF", "Small Diagnose button after a tab crash, a download that fails twice or an empty grabber") { // S87.2
                    AutoDiag.setOn(act, !AutoDiag.on(act))
                    fill()
                },
                Row(R.drawable.ic_bookmark, "Followed manga", Follow.count().let { if (it > 0) "$it" else "" }, "Alerts for new chapters") { Follow.open(act, "", null) { u -> dismiss(); BookmarksScreen.tabsRef?.openUrl(u) } },
                Row(R.drawable.ic_history, "Scheduled downloads", Sched.count().let { if (it > 0) "$it" else "" }, "Start at a time you choose") { Sched.open(act) { emptyList() } },
                Sub("Quality and files"),
                Row(R.drawable.ic_download, "Download quality", DetectedPanel.maxQLabel(), "Used when you press Download or Download all") { DetectedPanel.maxQDialog(act) { fill() } },
                Row(R.drawable.ic_pl_cc, "Subtitles and file names", subSummary(), "Languages, format, Plex / Jellyfin names") { SubName.open(act) },
                Row(R.drawable.ic_download, "Convert .ts to .mp4", if (DetectedPanel.convertOn()) "ON" else "OFF") { DetectedPanel.convertDialog(act) { fill() } },
                Row(R.drawable.ic_folder, "Download folder", DownloadFolder.label(act), "Where new files are saved") { DownloadFolder.showDialog(act) { fill() } },
                Row(R.drawable.ic_folder, "Sub-folders in the chosen folder", if (DownloadFolder.subfolders(act)) "ON" else "OFF") {
                    DownloadFolder.setSubfolders(act, !DownloadFolder.subfolders(act))
                    fill()
                }
            ))
            category("grabber", R.drawable.ic_settings, "Grabber and buttons", "Engine, buttons, player", listOf(
                Sub("V77 backup"), // S65
                Row(R.drawable.ic_play, "V77 Grabber (backup)", if (V77Alt.on(act)) "ON" else "OFF", "Original Media Grabber instead of the native downloader, for when it is under maintenance") {
                    V77Alt.set(act, !V77Alt.on(act))
                    Util.toast(act, if (V77Alt.on(act)) "V77 Grabber ON. Reload open pages" else "Native downloader is main again. Reload open pages")
                    fill()
                },
                Sub("Engine"),
                Row(R.drawable.ic_settings, "Download engine", engSummary(), "Speed, retries, limits, live recording") { DlEngine.open(act) },
                Row(R.drawable.ic_settings, "Grabber settings", "", "Sites, theme, backup, reset") { GrabSet.open(act) },
                Row(R.drawable.ic_screen, "All V77 options page", "", "The full original settings page") { ext("options.html") },
                Sub("Player and buttons"),
                Row(R.drawable.ic_play, "Play video links in built-in player", if (WebPlay.linksEnabled(act)) "ON" else "OFF") { // S39
                    WebPlay.setLinksEnabled(act, !WebPlay.linksEnabled(act))
                    fill()
                },
                Row(R.drawable.ic_play, "Video button on pages", if (WebVideo.on(act)) "ON" else "OFF") { // S45
                    DlMode.manual(act); WebVideo.setOn(act, !WebVideo.on(act))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_play, "Background audio in player", if (PlayerService.on(act)) "ON" else "OFF") { // S45
                    PlayerService.setOn(act, !PlayerService.on(act))
                    fill()
                },
                Sub("Download buttons"), // MGB S51
                Row(R.drawable.ic_download, "Button mode", DlMode.label(act)) { DlMode.dialog(act) { fill() } }, // S54
                Row(R.drawable.ic_download, "Grabber button on pages", if (v77On("showPill", true)) "ON" else "OFF") {
                    DlMode.manual(act); v77Set("showPill", !v77On("showPill", true)) // S54
                    Util.toast(act, "Applies at once on open pages")
                    fill()
                },
                Row(R.drawable.ic_download, "Grabber button opens Files detected panel", if (v77On("nativePanel", true)) "ON" else "OFF") {
                    DlMode.manual(act); v77Set("nativePanel", !v77On("nativePanel", true))
                    fill()
                },
                Row(R.drawable.ic_play, "Grabber button on video players", if (v77On("showOverlay", true)) "ON" else "OFF") {
                    DlMode.manual(act); v77Set("showOverlay", !v77On("showOverlay", true))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_trash, "Forget removed links", "${DetectedPanel.rmCount(act)} saved") {
                    confirm("Show every link you removed from the Files detected list again?", "Forget") {
                        DetectedPanel.rmForget(act)
                        fill()
                    }
                },
                Row(R.drawable.ic_download, "Extra native download button", if (nativeBtn()) "ON" else "OFF") {
                    DlMode.manual(act); prefs.edit().putBoolean("native_btn", !nativeBtn()).apply()
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
            category("extensions", R.drawable.ic_folder, "Extensions", "Add, turn on or off, remove", listOf( // S1
                Row(R.drawable.ic_folder, "Extensions", ExtListScreen.summary(act), "Add .zip or .crx files") { dismiss(); ExtListScreen.open(act) }
            ))
            category("about", R.drawable.ic_info, "About", "Updates, engine, version", listOf(
                Row(R.drawable.ic_reload, "Check for updates") { UpdateManager.check(act, false) },
                Row(R.drawable.ic_info, "Engine status") { EngineHost.showStatus(act) },
                Row(R.drawable.ic_info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            ))
            if (query.isNotBlank() && col.childCount == 0) { // S48
                col.addView(TextView(act).apply {
                    text = "No setting found"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(act.getColor(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(40) })
            }
            sv?.post { sv?.scrollTo(0, keepY) }
        }
    }
}
