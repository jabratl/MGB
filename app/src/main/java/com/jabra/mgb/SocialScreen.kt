package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.content.res.ColorStateList
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * SOCIAL SITES PAGE (0.0.228, P4): full-screen page (same chrome as Appearance): master switch (SocialMode.on), one switch row per
 * site of SocialProfiles.all (key sm_s_<id>) with an "Open" pill, and a short "what does not work" text.
 * 0.0.236 (U2): card "Site fix" = per site extra banner texts + CSS (SocialProfiles.setFix, key sm_x_<id>) in a small edit dialog.
 * Entry: Settings > General > Social sites. Back = Settings again. State lives in the prefs, so a rotation just rebuilds the page.
 */
object SocialScreen {
    private const val TAG = "MGB.social"
    private var current: Screen? = null

    fun open(host: Activity, onClose: (() -> Unit)? = null) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host, onClose)
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

    /** LinearLayout that never gets wider than [cap] px (560 dp cap on tablets / landscape). */
    private class CapColumn(ctx: Context, private val cap: Int) : LinearLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val size = MeasureSpec.getSize(w)
            super.onMeasure(if (size > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else w, h)
        }
    }

    private class Screen(private val act: Activity, private val onClose: (() -> Unit)?) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val siteRows = ArrayList<View>()
        private var skipClose = false

        private fun dp(v: Int) = Util.dp(act, v)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
            setOnDismissListener {
                if (current === this) current = null
                if (!skipClose) try { onClose?.invoke() } catch (_: Exception) { }
            }
        }

        private fun round(color: Int, r: Int, strokeDp: Float = 0f, stroke: Int = 0) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (strokeDp > 0f) setStroke((strokeDp * act.resources.displayMetrics.density).toInt(), stroke)
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
                text = "Social sites"
                textSize = 22f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            val col = CapColumn(act, dp(560)).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(32))
            }
            root.addView(ScrollView(act).apply {
                isFillViewport = false
                addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill(col)
        }

        override fun onBackPressed() { dismiss() }

        private fun fill(col: LinearLayout) {
            siteRows.clear()
            col.removeAllViews()

            col.addView(sectionLabel("Master switch"))
            val master = card()
            switchRow(master, R.drawable.ic_desktop, "Social sites", "Mobile or phone-fit page, app links and Open-app bars blocked",
                null, { SocialMode.on(act) }) { on -> SocialMode.setOn(act, on); applyDim() }
            col.addView(master, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Sensitive media"))
            val blur = blurCard()
            col.addView(blur, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(blur)

            col.addView(sectionLabel("AI picture check (experimental)")) // 0.0.233 (P8b)
            val ai = aiCard()
            col.addView(ai, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(ai)

            col.addView(sectionLabel("Tabs and data")) // 0.0.231 (P7)
            val extra = card()
            switchRow(extra, R.drawable.ic_info, "Unread number on tab cards", "Shows the \"(3)\" of the page title in the tab list",
                null, { SocialBadge.badgeOn(act) }) { on -> SocialBadge.setBadgeOn(act, on) }
            switchRow(extra, R.drawable.ic_shield, "Data saver", "Stops video autoplay on social pages (applies from the next page load)",
                null, { SocialBadge.saveOn(act) }) { on -> SocialBadge.setSaveOn(act, on) }
            switchRow(extra, R.drawable.ic_shield, "Skip \"Use the app\" screens", "Taps \"Staying in the browser\" or \"Not now\" on a full-screen app nag (applies from the next page load)",
                null, { SocialMode.intOn(act) }) { on -> SocialMode.setIntOn(act, on) }
            switchRow(extra, R.drawable.ic_screen, "Full screen on social sites", "Hides the address bar and phone bars on social pages. Tap the small handle or press Back to show them",
                null, { SocialMode.fullOn(act) }) { on -> SocialMode.setFullOn(act, on); if (!on) SocialFull.sync(act, BookmarksScreen.tabsRef?.active) }
            switchRow(extra, R.drawable.ic_screen, "Match phone bars to the site", "Status bar and navigation bar take the colour of the social site (back to normal on other pages)",
                null, { SocialMode.barsOn(act) }) { on -> SocialMode.setBarsOn(act, on); SocialBars.sync(act, BookmarksScreen.tabsRef?.active) }
            switchRow(extra, R.drawable.ic_shield, "Ask before closing a social tab", "On the first page of a social site, Back shows \"Press Back again to close\" so one slip does not close your feed",
                null, { SocialMode.backOn(act) }) { on -> SocialMode.setBackOn(act, on) }
            switchRow(extra, R.drawable.ic_screen, "Bigger buttons (Reddit, X, LinkedIn)", "Makes the small like / reply / vote buttons at least 44 px so they are easy to hit (experimental, applies from the next page load)",
                null, { SocialMode.bigOn(act) }) { on -> SocialMode.setBigOn(act, on) }
            switchRow(extra, R.drawable.ic_screen, "Splash when opening from a shortcut", "Shows the site letter and name for a moment when a home-screen shortcut opens it (off with Reduce motion)",
                null, { SocialMode.splashOn(act) }) { on -> SocialMode.setSplashOn(act, on) }
            col.addView(extra, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(extra)

            val feeds = SocialProfiles.all.filter { it.defaultKind != SocialMode.Kind.CHAT }
            val chats = SocialProfiles.all.filter { it.defaultKind == SocialMode.Kind.CHAT }
            for ((title, list) in listOf("Feed sites" to feeds, "Chat sites (desktop page, fits the phone)" to chats)) {
                if (list.isEmpty()) continue
                col.addView(sectionLabel(title))
                val c = card()
                for (p in list) {
                    val row = switchRow(c, R.drawable.ic_desktop, p.label, hintOf(p), { openSite(p) },
                        { SocialProfiles.siteOn(act, p.id) }) { on -> SocialProfiles.setSiteOn(act, p.id, on) }
                    siteRows.add(row)
                }
                col.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            col.addView(sectionLabel("Chat comfort")) // 0.0.243 (A9) + 0.0.244 (A8)
            val chatC = chatCard()
            col.addView(chatC, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(chatC)

            col.addView(sectionLabel("Site fix")) // 0.0.236 (U2)
            val fixC = card()
            for (p in SocialProfiles.all) {
                lateinit var hintTv: TextView
                val row = actionRow(fixC, R.drawable.ic_settings, p.label, fixHint(p)) { fixDialog(p) { hintTv.text = fixHint(p) } }
                hintTv = ((row as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            }
            col.addView(fixC, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(fixC)

            col.addView(sectionLabel("Logins")) // 0.0.234 (P9)
            val sep = card()
            val supported = SocialMode.sepSupported()
            val sepRow = switchRow(sep, R.drawable.ic_shield, "Keep social logins separate",
                if (supported) "Each site gets its own cookies, apart from normal tabs. Applies to tabs opened from now on; log in again once"
                else "Not supported on this phone",
                null, { supported && SocialMode.sepOn(act) }) { on -> if (supported) SocialMode.setSepOn(act, on) }
            if (!supported) sepRow.alpha = 0.5f else siteRows.add(sepRow)
            col.addView(sep, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Home"))
            val home = card() // 0.0.230 (P6): not dimmed, a shortcut turns Social sites ON when tapped
            actionRow(home, R.drawable.ic_add, "Add a site to the phone home screen", "Icon opens it in a Social tab") { SocialShortcuts.pickPin(act) }
            actionRow(home, R.drawable.ic_add, "Add tiles to the browser Home page", "Tick the sites you want") { SocialShortcuts.pickTiles(act) }
            col.addView(home, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Good to know"))
            val info = card()
            info.addView(TextView(act).apply {
                text = "The AI picture check is experimental: it needs a model file inside the app, it can miss things or blur harmless pictures, and it does not see video.\n\n" +
                    "No push notifications or sounds, no badge on the launcher icon: a browser tab cannot receive them when it is closed. The unread number on tab cards only updates while the tab is open.\n\n" +
                    "Separate logins: a tab that is already open keeps the cookies it has; a link that leaves a social site stays in that tab's profile.\n\n" +
                    "\"Continue with Google\" is often blocked in a browser tab. Use email and password.\n\n" +
                    "A site may show a security check after the browser identity changes. Hiding the Open-app bars depends on the live " +
                    "site and may need a small fix when the site changes.\n\n" +
                    "Site fix: type a button text or a line of CSS when a site changes and an Open-app bar comes back. It is only stored on this phone.\n\n" +
                    "A site that is switched off here opens like any other page."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(18), dp(8), dp(16), dp(10))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
            applyDim()
        }

        /** 0.0.229 (P5): blur card = 3 choices (Off / Flagged only / All until tap) + strength slider 4..24 px. Prefs: SocialBlur. */
        /** 0.0.243 (A9) chat size chips 100 / 125 / 150 / 175 % + 0.0.244 (A8) keyboard switch. Size applies from the next page load of a chat site. */
        private fun chatCard(): LinearLayout {
            val c = card()
            c.addView(TextView(act).apply {
                text = "Chat size"; textSize = 15f; setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(16), dp(12), dp(16), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
            val chips = ArrayList<TextView>()
            fun paint() {
                val cur = SocialMode.chatScale(act)
                for ((i, t) in chips.withIndex()) {
                    val sel = SocialMode.chatSizes[i] == cur
                    t.setTextColor(act.getColor(if (sel) R.color.mg_on_primary else R.color.mg_text))
                    t.background = round(act.getColor(if (sel) R.color.mg_primary else R.color.mg_surface_variant), 14, 1f,
                        act.getColor(if (sel) R.color.mg_primary else R.color.mg_outline))
                    t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            }
            for ((i, v) in SocialMode.chatSizes.withIndex()) {
                val t = TextView(act).apply {
                    text = "$v%"; textSize = 12f; maxLines = 1; gravity = Gravity.CENTER; isClickable = true
                    contentDescription = "Chat size $v percent"
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); SocialMode.setChatScale(act, v); paint() }
                }
                chips.add(t)
                seg.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            paint()
            c.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(TextView(act).apply {
                text = "Bigger text and buttons in chat sites (Facebook chat, Messenger, Discord, WhatsApp Web, Telegram Web, Snapchat). 100% = as before. Applies from the next page load; a very large size can break a site layout."
                textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(dp(16), dp(4), dp(16), dp(8))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            switchRow(c, R.drawable.ic_screen, "Keep message box above keyboard", "In chat sites the box you tap is scrolled into view when the keyboard opens (applies from the next page load)",
                null, { SocialMode.kbOn(act) }) { on -> SocialMode.setKbOn(act, on) }
            return c
        }

        private fun blurCard(): LinearLayout {
            val c = card()
            val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
            val labels = listOf("Off", "Flagged only", "All until tap")
            val chips = ArrayList<TextView>()
            fun paint() {
                val m = SocialBlur.mode(act)
                for ((i, t) in chips.withIndex()) {
                    val sel = i == m
                    t.setTextColor(act.getColor(if (sel) R.color.mg_on_primary else R.color.mg_text))
                    t.background = round(act.getColor(if (sel) R.color.mg_primary else R.color.mg_surface_variant), 14, 1f,
                        act.getColor(if (sel) R.color.mg_primary else R.color.mg_outline))
                    t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            }
            for ((i, l) in labels.withIndex()) {
                val t = TextView(act).apply {
                    text = l
                    textSize = 12f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    isClickable = true
                    contentDescription = "Blur: $l"
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        SocialBlur.setMode(act, i)
                        paint()
                    }
                }
                chips.add(t)
                seg.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            paint()
            c.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(TextView(act).apply {
                text = "Flagged only: media the site marks as NSFW / sensitive, or whose post says so. All until tap: every picture and " +
                    "video. Tap an item to show it; Reveal all shows the rest. Small pictures (avatars) stay clear."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val lab = TextView(act).apply {
                textSize = 13f
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(16), dp(6), dp(16), 0)
            }
            fun setLab() { lab.text = "Blur strength: " + SocialBlur.px(act) + " px" }
            setLab()
            c.addView(lab, LinearLayout.LayoutParams(MATCH, WRAP))
            val tint = ColorStateList.valueOf(act.getColor(R.color.mg_toggle_on))
            c.addView(SeekBar(act).apply {
                max = 20 // 4..24 px
                progress = SocialBlur.px(act) - 4
                progressTintList = tint
                thumbTintList = tint
                contentDescription = "Blur strength"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        SocialBlur.setPx(act, p + 4)
                        setLab()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { }
                    override fun onStopTrackingTouch(sb: SeekBar?) { }
                })
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(dp(12), 0, dp(12), dp(4)) })
            return c
        }

        /** 0.0.233 (P8b): AI picture check card = switch + Low / Normal / Strict + status + counter. Prefs: SocialNsfw. Pictures only, not video. */
        private fun aiCard(): LinearLayout {
            val c = card()
            val chips = ArrayList<TextView>()
            val status = TextView(act).apply { textSize = 12f; setPadding(dp(16), dp(4), dp(16), 0) }
            val count = TextView(act).apply { textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(dp(16), dp(2), dp(16), dp(6)) }
            fun paint() {
                val lv = SocialNsfw.level(act)
                val en = SocialNsfw.on(act)
                for ((i, t) in chips.withIndex()) {
                    val sel = i == lv
                    t.alpha = if (en) 1f else 0.5f
                    t.setTextColor(act.getColor(if (sel) R.color.mg_on_primary else R.color.mg_text))
                    t.background = round(act.getColor(if (sel) R.color.mg_primary else R.color.mg_surface_variant), 14, 1f,
                        act.getColor(if (sel) R.color.mg_primary else R.color.mg_outline))
                    t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                val ready = SocialNsfw.modelReady(act)
                status.text = "Model: " + SocialNsfw.status(act)
                status.setTextColor(act.getColor(if (ready) R.color.mg_text_dim else R.color.mg_error))
                count.text = SocialNsfw.blurred.get().toString() + " pictures blurred since the app started"
            }
            switchRow(c, R.drawable.ic_shield, "Check pictures with AI", "A model on this phone looks at pictures on social pages and blurs the ones it rates as sensitive",
                null, { SocialNsfw.on(act) }) { on -> SocialNsfw.setOn(act, on); paint() }
            val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
            for ((i, l) in SocialNsfw.levelNames.withIndex()) {
                val t = TextView(act).apply {
                    text = l
                    textSize = 12f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    isClickable = true
                    contentDescription = "AI check sensitivity: $l"
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        SocialNsfw.setLevel(act, i)
                        paint()
                    }
                }
                chips.add(t)
                seg.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            c.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(TextView(act).apply {
                text = "Sensitivity: Low blurs only clear cases, Strict blurs more (and more wrongly). Pictures only: videos are not checked. " +
                    "Not 100 % accurate. Pictures are fetched once by the app and nothing is sent anywhere else."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(count, LinearLayout.LayoutParams(MATCH, WRAP))
            paint()
            return c
        }

        private fun hintOf(p: SocialProfiles.Profile): String = when {
            p.sub.isNotBlank() -> p.sub
            p.id == "facebook" -> "Feed, chat pages fit the phone"
            p.id == "messenger" -> "Chat that fits the phone"
            p.id == "instagram" -> "Feed and messages without the app nag"
            else -> ""
        }

        /** Site rows look dimmed while the master switch is OFF (they can still be changed). */
        private fun applyDim() {
            val a = if (SocialMode.on(act)) 1f else 0.5f
            for (r in siteRows) r.alpha = a
        }

        /** "Open" pill: opens the site start page in a new tab (switches the site and the master switch ON). */
        private fun openSite(p: SocialProfiles.Profile) {
            val tabs = BookmarksScreen.tabsRef
            if (tabs == null) { Util.toast(act, "Open a tab first"); return }
            if (!SocialProfiles.siteOn(act, p.id)) SocialProfiles.setSiteOn(act, p.id, true)
            SocialMode.open(act, tabs, p.startUrl)
            skipClose = true
            SettingsScreen.closeAll()
            dismiss()
        }

        /** Plain tap row (>= 56 dp): icon + label + hint + chevron-less, whole row is the touch target. */
        private fun actionRow(parent: LinearLayout, icon: Int, label: String, hint: String, onTap: () -> Unit): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(6), dp(12), dp(6))
                isClickable = true
                rippleBg(this)
                setOnClickListener { onTap() }
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            txt.addView(TextView(act).apply {
                text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0)
            })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            return row
        }

        private fun fixHint(p: SocialProfiles.Profile): String =
            if (SocialProfiles.hasFix(act, p.id)) "Your fix is on" else "Hide an Open-app bar or an element yourself"

        /** 0.0.236 (U2): edit dialog. Words = extra texts of the Open-app button (comma separated), CSS = one line. Save / Clear / Cancel; applies from the next page load. */
        private fun fixDialog(p: SocialProfiles.Profile, onDone: () -> Unit) {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            fun lab(t: String) = TextView(act).apply {
                text = t; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(10), 0, dp(4))
            }
            fun field(h: String, v: String) = EditText(act).apply {
                hint = h; setText(v); textSize = 14f; minLines = 2; maxLines = 6
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setTextColor(act.getColor(R.color.mg_text)); setHintTextColor(act.getColor(R.color.mg_text_dim))
                background = round(act.getColor(R.color.mg_surface_variant), 12, 1f, act.getColor(R.color.mg_outline))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            box.addView(lab("Extra Open-app button texts, separated by commas. Built-in app names: " +
                (if (p.appWords.isEmpty()) "none" else p.appWords.joinToString(", ")) + "."))
            val w = field("Try the app, Open " + p.label, SocialProfiles.fixWords(act, p.id).joinToString(", "))
            box.addView(w, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(lab("CSS to hide something (one line, no url or @import)."))
            val c = field(".promo-bar{display:none!important}", SocialProfiles.fixCss(act, p.id))
            box.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(lab("Applies from the next page load of this site. Empty = built-in only. Clear removes your fix."))
            MaterialAlertDialogBuilder(act)
                .setTitle(p.label + " fix")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Save") { _, _ ->
                    SocialProfiles.setFix(act, p.id, w.text.toString(), c.text.toString())
                    Util.toast(act, "Saved. Reload the site page")
                    onDone()
                }
                .setNeutralButton("Clear") { _, _ -> SocialProfiles.clearFix(act, p.id); onDone() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun sectionLabel(t: String) = TextView(act).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(act.getColor(R.color.mg_primary))
            setPadding(dp(8), dp(16), 0, dp(6))
            setCompoundDrawablesWithIntrinsicBounds(GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_toggle_on)); cornerRadius = dp(2).toFloat(); setSize(dp(4), dp(12))
            }, null, null, null)
            compoundDrawablePadding = dp(8)
        }

        private fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(act.getColor(R.color.mg_surface), 20, 1f, ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        private fun rippleBg(v: View) {
            val tv = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) v.setBackgroundResource(tv.resourceId)
        }

        /** Switch row (>= 52 dp): icon + label + hint + optional "Open" pill + ThazirSwitch. Row tap toggles. Returns the row. */
        private fun switchRow(parent: LinearLayout, icon: Int, label: String, hint: String, onOpen: (() -> Unit)?,
                              get: () -> Boolean, set: (Boolean) -> Unit): View {
            val sw = ThazirSwitch(act).apply { isChecked = get(); isClickable = false; isFocusable = false }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(6), dp(12), dp(6))
                isClickable = true
                rippleBg(this)
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            if (hint.isNotBlank()) txt.addView(TextView(act).apply {
                text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0)
            })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (onOpen != null) {
                val pill = TextView(act).apply {
                    text = "Open"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(R.color.mg_primary))
                    gravity = Gravity.CENTER
                    background = round(act.getColor(R.color.mg_surface_variant), 16, 1f, act.getColor(R.color.mg_outline))
                    contentDescription = "Open $label"
                }
                val touch = FrameLayout(act).apply { isClickable = true; setOnClickListener { onOpen() } }
                touch.addView(pill, FrameLayout.LayoutParams(dp(56), dp(32), Gravity.CENTER))
                row.addView(touch, LinearLayout.LayoutParams(dp(64), dp(44)).apply { marginStart = dp(6) })
            }
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                set(!get())
                sw.isChecked = get()
            }
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            return row
        }
    }
}
