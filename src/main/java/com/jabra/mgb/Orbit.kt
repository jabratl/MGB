package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.302: "Your Orbit" = the top block of Settings (Chrome's "You and Google" idea, own name + look).
 *  - Passport card: local profile (name, e-mail, avatar colour). Lives on this phone only, no sign-in, no server.
 *  - Passport page: what goes into a backup (Bookmarks / Home tiles / History / Recent tabs / Settings) + data actions.
 *  - Smart switches page: the everyday privacy / comfort switches in one place (they are the same switches as in the
 *    other Settings cards, just reachable from the top).
 * Keys live in "mgb_ui" (orb_*), all inside Backup > Settings and the General reset.
 */
object Orbit {
    const val NAME = "orb_name"
    const val MAIL = "orb_mail"
    private const val HUE = "orb_hue"
    const val S_BM = "orb_s_bm"; const val S_HOME = "orb_s_home"; const val S_HIST = "orb_s_hist"
    const val S_TABS = "orb_s_tabs"; const val S_SET = "orb_s_set"
    val KEYS = listOf(NAME, MAIL, HUE, S_BM, S_HOME, S_HIST, S_TABS, S_SET)

    private val HUES = listOf(
        "Violet" to 0xFF6C5CE7.toInt(), "Ocean" to 0xFF0984E3.toInt(), "Emerald" to 0xFF00A884.toInt(),
        "Sunset" to 0xFFE17055.toInt(), "Rose" to 0xFFD63384.toInt(), "Amber" to 0xFFE3A008.toInt(),
        "Slate" to 0xFF5F6B7A.toInt(), "Orchid" to 0xFFA855C7.toInt()
    )

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun name(c: Context): String = sp(c).getString(NAME, "") ?: ""
    fun mail(c: Context): String = sp(c).getString(MAIL, "") ?: ""
    private fun hue(c: Context): Int = HUES[sp(c).getInt(HUE, 0).coerceIn(0, HUES.size - 1)].second
    private fun flag(c: Context, k: String) = sp(c).getBoolean(k, true)
    private fun setFlag(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    private fun shownName(c: Context) = name(c).ifBlank { "Guest" }

    /** Backup parts in Backup.kt order: bookmarks, home tiles, history, settings, recent tabs. */
    fun parts(c: Context): BooleanArray = booleanArrayOf(flag(c, S_BM), flag(c, S_HOME), flag(c, S_HIST), flag(c, S_SET), flag(c, S_TABS))

    // ---------------------------------------------------------------- small view helpers
    private fun dp(c: Context, v: Int) = Util.dp(c, v)

    private fun round(c: Context, color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, r).toFloat()
    }

    private fun card(act: Activity) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = round(act, act.getColor(R.color.mg_surface), 20).apply {
            setStroke(dp(act, 1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
        }
    }

    private fun hairline(act: Activity, inset: Int) = View(act).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
    }.let { v -> FrameLayout(act).apply { addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(act, inset) }) } }

    private fun label(act: Activity, t: String) = TextView(act).apply {
        text = t.uppercase(); textSize = 11.5f; letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(act.getColor(R.color.mg_primary))
        setPadding(dp(act, 6), dp(act, 18), dp(act, 6), dp(act, 8))
    }

    private fun press(act: Activity, v: View) {
        val o = TypedValue()
        if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, o, true)) v.foreground = act.getDrawable(o.resourceId)
        v.isClickable = true; v.isFocusable = true
    }

    private fun avatar(act: Activity, size: Int): View {
        val c = act
        val pf = CloudSync.photoFile(c)
        if (CloudSync.signedIn(c) && pf.exists()) {
            val bmp = try { android.graphics.BitmapFactory.decodeFile(pf.path) } catch (_: Throwable) { null }
            if (bmp != null) {
                val iv = ImageView(c).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
                    }
                    clipToOutline = true
                }
                val fl = FrameLayout(c)
                fl.addView(iv, FrameLayout.LayoutParams(dp(c, size), dp(c, size)))
                return fl
            }
        }
        val tv = TextView(c).apply {
            text = shownName(c).trim().take(1).uppercase()
            gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            textSize = size * 0.42f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(hue(c))
                setStroke(dp(c, 2), ColorUtils.setAlphaComponent(hue(c), 0x55))
            }
        }
        val f = FrameLayout(c)
        f.addView(tv, FrameLayout.LayoutParams(dp(c, size), dp(c, size)))
        return f
    }

    private fun row(act: Activity, icon: Int?, title: String, hint: String, trailing: View?, onTap: (() -> Unit)?): View {
        val line = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(act, 56); setPadding(dp(act, 18), dp(act, 8), dp(act, 16), dp(act, 8))
        }
        if (icon != null) line.addView(ImageView(act).apply {
            setImageResource(icon); setColorFilter(act.getColor(R.color.mg_text_dim))
        }, LinearLayout.LayoutParams(dp(act, 22), dp(act, 22)).apply { marginEnd = dp(act, 16) })
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(act).apply { text = title; textSize = 15.5f; setTextColor(act.getColor(R.color.mg_text)) })
        if (hint.isNotEmpty()) box.addView(TextView(act).apply {
            text = hint; textSize = 12.5f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(act, 2), 0, 0)
        })
        line.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) line.addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(act, 10) })
        if (onTap != null) { press(act, line); line.setOnClickListener { onTap() } }
        return line
    }

    private fun chevron(act: Activity) = ImageView(act).apply {
        setImageResource(R.drawable.ic_chevron); setColorFilter(act.getColor(R.color.mg_text_dim))
    }

    /** Switch row: tapping anywhere flips the switch and calls [set]. */
    private fun toggleRow(act: Activity, title: String, hint: String, on: Boolean, set: (Boolean) -> Unit): View {
        val sw = ThazirSwitch(act).apply { isChecked = on; isClickable = false; isFocusable = false }
        return row(act, null, title, hint, sw) {
            sw.isChecked = !sw.isChecked
            sw.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            set(sw.isChecked)
        }
    }

    // ---------------------------------------------------------------- Settings top block
    /** The "Your Orbit" block for the top of Settings. [changed] is called when the card must be drawn again. */
    fun block(act: Activity, changed: () -> Unit): View {
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(label(act, "Your Orbit"))
        val c = card(act)
        val mail = mail(act)
        val ppl = row(act, null, shownName(act), if (mail.isNotEmpty()) mail else "Sign in with Google to keep your Passport on Drive", chevron(act)) {
            openPassport(act, changed)
        }
        (ppl as LinearLayout).addView(avatar(act, 44), 0, LinearLayout.LayoutParams(dp(act, 44), dp(act, 44)).apply { marginEnd = dp(act, 14) })
        ppl.minimumHeight = dp(act, 72)
        c.addView(ppl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(hairline(act, 18), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        c.addView(row(act, R.drawable.ic_settings, "Smart switches", switchSummary(act), chevron(act)) {
            openSwitches(act, changed)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun switchSummary(act: Activity): String {
        val n = listOf(Backup.autoOn(act), HttpsOnly.on(act), AutoDiag.on(act), PullRefresh.enabled(act)).count { it }
        return "$n of 4 on. Backup, secure-only, rescue pill"
    }

    // ---------------------------------------------------------------- pages
    private class Page(private val act: Activity, private val heading: String, private val build: (LinearLayout, () -> Unit) -> Unit, private val closed: () -> Unit) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {
        private var col: LinearLayout? = null
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(act.getColor(R.color.mg_bg)) }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(act, 4), 0, dp(act, 4), 0) }
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back); setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10)); contentDescription = "Back"
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(act, 44), dp(act, 48)))
            head.addView(TextView(act).apply {
                text = heading; textSize = 22f; setTextColor(act.getColor(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(dp(act, 6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56)))
            val c = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(act, 16), dp(act, 4), dp(act, 16), dp(act, 32)) }
            col = c
            val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(root)
            window?.let {
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
            setOnDismissListener { closed() }
            redraw()
        }
        fun redraw() {
            val c = col ?: return
            c.removeAllViews()
            build(c) { redraw() }
        }
    }

    fun openPassport(act: Activity, changed: () -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        Page(act, "Passport", { col, redraw -> fillPassport(act, col, redraw, changed) }, changed).show()
    }

    fun openSwitches(act: Activity, changed: () -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        Page(act, "Smart switches", { col, redraw -> fillSwitches(act, col, redraw) }, changed).show()
    }

    private fun fillPassport(act: Activity, col: LinearLayout, redraw: () -> Unit, changed: () -> Unit) {
        val M = ViewGroup.LayoutParams.MATCH_PARENT
        val W = ViewGroup.LayoutParams.WRAP_CONTENT
        // identity header
        val id = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(act, 10), 0, dp(act, 6)) }
        val av = avatar(act, 84)
        av.setOnClickListener { pickHue(act, redraw) }
        id.addView(av, LinearLayout.LayoutParams(dp(act, 84), dp(act, 84)))
        id.addView(TextView(act).apply {
            text = shownName(act); textSize = 19f; gravity = Gravity.CENTER; setTextColor(act.getColor(R.color.mg_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, dp(act, 12), 0, 0)
        })
        id.addView(TextView(act).apply {
            text = mail(act).ifEmpty { "No e-mail added" }; textSize = 13f; gravity = Gravity.CENTER; setTextColor(act.getColor(R.color.mg_text_dim))
        })
        if (!CloudSync.signedIn(act)) id.addView(TextView(act).apply {
            text = "Edit name and e-mail"; textSize = 13.5f; setTextColor(act.getColor(R.color.mg_primary))
            setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12)); press(act, this)
            setOnClickListener { editIdentity(act, redraw) }
        })
        col.addView(id, LinearLayout.LayoutParams(M, W))

        if (!CloudSync.signedIn(act)) {
            val g = card(act)
            g.addView(row(act, R.drawable.ic_share, "Sign in with Google", "Keep your Passport in a private folder of your own Google Drive", chevron(act)) {
                CloudSync.signIn(act) { err ->
                    if (err != null) Util.toast(act, err) else Util.toast(act, "Signed in")
                    redraw(); changed()
                }
            }, LinearLayout.LayoutParams(M, W))
            col.addView(g, LinearLayout.LayoutParams(M, W).apply { topMargin = dp(act, 8) })
        }

        // carried in the Passport = what a backup holds
        col.addView(label(act, "Carried in your Passport"))
        val c = card(act)
        val bms = Bookmarks.bookmarks().size
        val tiles = Bookmarks.home().size
        val hist = Bookmarks.history().size
        val closedN = ClosedTabs.list().size
        val items = listOf(
            Triple(S_BM, "Bookmarks", "$bms saved"),
            Triple(S_HOME, "Home tiles", "$tiles on your home page"),
            Triple(S_HIST, "History", "$hist pages"),
            Triple(S_TABS, "Recent tabs", "$closedN closed tabs"),
            Triple(S_SET, "Settings", "Appearance, privacy, sites, scripts")
        )
        items.forEachIndexed { i, it ->
            if (i > 0) c.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
            c.addView(toggleRow(act, it.second, it.third, flag(act, it.first)) { v -> setFlag(act, it.first, v) }, LinearLayout.LayoutParams(M, W))
        }
        col.addView(c, LinearLayout.LayoutParams(M, W))
        col.addView(TextView(act).apply {
            text = "These switches choose what Auto-save Passport and Back up now put into the backup file. Nothing leaves this phone unless you share the file yourself."
            textSize = 12.5f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(dp(act, 8), dp(act, 10), dp(act, 8), 0)
        })

        // data
        col.addView(label(act, "Control your data"))
        val d = card(act)
        d.addView(row(act, R.drawable.ic_folder, "Passport summary", "What is stored on this phone", chevron(act)) {
            MaterialAlertDialogBuilder(act).setTitle("Passport summary")
                .setMessage("Bookmarks: $bms\nHome tiles: $tiles\nHistory: $hist\nRecent tabs: $closedN\nSaved passwords: " + (try { PasswordStore.all(act).size } catch (_: Throwable) { 0 }).toString())
                .setPositiveButton("OK", null).show()
        }, LinearLayout.LayoutParams(M, W))
        d.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
        d.addView(row(act, R.drawable.ic_lock, "Encryption", "How your data is protected", chevron(act)) {
            MaterialAlertDialogBuilder(act).setTitle("Encryption")
                .setMessage("Your Passport never leaves the phone by itself. A backup file is plain text, so keep it somewhere private. The Vault has its own lock and is never part of a backup.")
                .setPositiveButton("OK", null).show()
        }, LinearLayout.LayoutParams(M, W))
        d.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
        if (CloudSync.signedIn(act)) {
            val at = CloudSync.lastSync(act)
            val when_ = if (at > 0L) "Last saved " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at)) else "Not saved yet"
            d.addView(row(act, R.drawable.ic_save, "Save to Google Drive", when_, null) {
                Util.toast(act, "Saving...")
                CloudSync.upload(act, Backup.passportJson(act), true) { err ->
                    Util.toast(act, err ?: "Saved to your Google Drive")
                    redraw()
                }
            }, LinearLayout.LayoutParams(M, W))
            d.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
            d.addView(row(act, R.drawable.ic_download, "Restore from Google Drive", "Choose what to bring back", null) {
                CloudSync.restore(act) { err -> if (err != null) Util.toast(act, err) }
            }, LinearLayout.LayoutParams(M, W))
            d.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
        }
        d.addView(row(act, R.drawable.ic_save, "Back up now", "Export the parts switched on above", null) {
            Backup.exportData(act)
        }, LinearLayout.LayoutParams(M, W))
        d.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
        d.addView(row(act, R.drawable.ic_download, "Restore from file", "Bring a Thazir backup back", null) {
            Backup.importData(act)
        }, LinearLayout.LayoutParams(M, W))
        d.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
        d.addView(row(act, R.drawable.ic_shield, "Manage on this device", "Camera, microphone, location, notifications", chevron(act)) {
            DevicePerms.open(act)
        }, LinearLayout.LayoutParams(M, W))
        col.addView(d, LinearLayout.LayoutParams(M, W))

        col.addView(TextView(act).apply {
            text = if (CloudSync.signedIn(act)) "Sign out" else "Clear Passport"; textSize = 14.5f; gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.mg_primary)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(act, 16), dp(act, 20), dp(act, 16), dp(act, 20)); press(act, this)
            setOnClickListener {
                val out = CloudSync.signedIn(act)
                MaterialAlertDialogBuilder(act).setTitle(if (out) "Sign out?" else "Clear Passport?")
                    .setMessage(if (out) "Disconnects this phone from your Google account in Thazir and removes your name, e-mail and photo here. Your file on Google Drive stays. Bookmarks, history and settings stay."
                        else "Removes your name, e-mail and avatar colour from this phone. Bookmarks, history and settings stay.")
                    .setPositiveButton(if (out) "Sign out" else "Clear") { _, _ ->
                        if (out) CloudSync.signOut(act)
                        sp(act).edit().remove(NAME).remove(MAIL).remove(HUE).apply()
                        redraw(); changed()
                    }.setNegativeButton("Cancel", null).show()
            }
        }, LinearLayout.LayoutParams(M, W))
    }

    private fun fillSwitches(act: Activity, col: LinearLayout, redraw: () -> Unit) {
        val M = ViewGroup.LayoutParams.MATCH_PARENT
        val W = ViewGroup.LayoutParams.WRAP_CONTENT
        val c = card(act)
        val rows = listOf(
            toggleRow(act, "Auto-save Passport", if (CloudSync.signedIn(act)) "Once a day: file in Downloads and your Google Drive" else "Once a day, Downloads/mgb-auto-backup.json", Backup.autoOn(act)) { Backup.setAuto(act, it) },
            toggleRow(act, "Secure-only browsing", "Upgrades links to https and warns before plain http", HttpsOnly.on(act)) { HttpsOnly.set(act, it) },
            toggleRow(act, "Rescue pill", "A small Diagnose button after a tab crash or a failed download", AutoDiag.on(act)) { AutoDiag.setOn(act, it) },
            toggleRow(act, "Pull to refresh", "Pull a page down to reload it", PullRefresh.enabled(act)) { PullRefresh.setEnabled(act, it) },
            toggleRow(act, "Focus bar", "Hide the address bar while you scroll", sp(act).getBoolean("hide_bar", false)) { sp(act).edit().putBoolean("hide_bar", it).apply() }
        )
        rows.forEachIndexed { i, r ->
            if (i > 0) c.addView(hairline(act, 18), LinearLayout.LayoutParams(M, W))
            c.addView(r, LinearLayout.LayoutParams(M, W))
        }
        col.addView(c, LinearLayout.LayoutParams(M, W))
        col.addView(TextView(act).apply {
            text = "Same switches as in General, Privacy and Data. Changing one here changes it there."
            textSize = 12.5f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(dp(act, 8), dp(act, 10), dp(act, 8), 0)
        })
    }

    // ---------------------------------------------------------------- dialogs
    private fun editIdentity(act: Activity, redraw: () -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(act, 24), dp(act, 8), dp(act, 24), 0) }
        val n = EditText(act).apply { hint = "Name"; setText(name(act)); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val m = EditText(act).apply { hint = "E-mail (optional)"; setText(mail(act)); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        box.addView(n); box.addView(m)
        MaterialAlertDialogBuilder(act).setTitle("Your Passport").setView(box)
            .setPositiveButton("Save") { _, _ ->
                sp(act).edit().putString(NAME, n.text.toString().trim().take(40)).putString(MAIL, m.text.toString().trim().take(80)).apply()
                redraw()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pickHue(act: Activity, redraw: () -> Unit) {
        MaterialAlertDialogBuilder(act).setTitle("Avatar colour")
            .setItems(HUES.map { it.first }.toTypedArray()) { _, i -> sp(act).edit().putInt(HUE, i).apply(); redraw() }
            .setNegativeButton("Cancel", null).show()
    }
}
