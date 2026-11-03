package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * S1: Settings > Extensions. Full-screen Dialog (same style as SettingsScreen, no manifest entry).
 *  - Add: file picker (.zip / .crx) -> ExtRegistry.prepare -> permission summary dialog -> commit or discard
 *  - List: name, version, short info, on/off switch, remove (with confirm)
 *  - Only the list and the flags live here. Running extension code is S2+ (an installed extension does nothing yet).
 */
object ExtListScreen {
    private const val TAG = "MGB.extlist"
    private var current: Screen? = null

    fun open(host: Activity) {
        if (host.isFinishing || host.isDestroyed) return
        close()
        try {
            ExtRegistry.init(host)
            val s = Screen(host)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
            Util.toast(host, "Could not open Extensions")
        }
    }

    fun close() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    /** Short count text for the Settings row, e.g. "2 on / 3". Empty when nothing is installed. */
    fun summary(host: Activity): String {
        return try {
            ExtRegistry.init(host)
            val all = ExtRegistry.all()
            if (all.isEmpty()) "" else "${all.count { it.enabled }} on / ${all.size}"
        } catch (e: Exception) { "" }
    }

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var body: LinearLayout? = null
        private var busy = false

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
                text = "Extensions"
                textSize = 20f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_add)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Add extension"
                setOnClickListener { pick() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(24))
            }
            body = col
            root.addView(ScrollView(act).apply {
                isFillViewport = true
                addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill()
        }

        private fun fill() {
            val col = body ?: return
            col.removeAllViews()
            val list = ExtRegistry.all()
            col.addView(TextView(act).apply {
                text = "Add an extension from a .zip or .crx file. Chrome Web Store install is not available. " +
                    "Some Chrome-only features do not work."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(6), dp(4), dp(6), dp(10))
            })
            if (list.isEmpty()) {
                col.addView(TextView(act).apply {
                    text = "No extensions installed"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(act.getColor(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(28) })
            }
            for (e in list) col.addView(card(e), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            col.addView(TextView(act).apply {
                text = "Add extension"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(act.getColor(R.color.mg_on_primary))
                background = round(act.getColor(R.color.mg_primary), 24)
                isClickable = true
                isFocusable = true
                setOnClickListener { pick() }
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(6) })
        }

        private fun card(e: ExtRegistry.Info): View {
            val c = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(act.getColor(R.color.mg_surface), 16)
                setPadding(dp(14), dp(10), dp(6), dp(10))
                alpha = if (e.enabled) 1f else 0.7f
            }
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply {
                text = e.name
                textSize = 15f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
            })
            txt.addView(TextView(act).apply {
                text = "v" + e.version + " · " + kinds(e)
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            if (e.description.isNotBlank()) txt.addView(TextView(act).apply {
                text = e.description
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            c.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            c.addView(MaterialSwitch(act).apply {
                isChecked = e.enabled
                contentDescription = "Turn " + e.name + " on or off"
                val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                trackTintList = ColorStateList(st, intArrayOf(act.getColor(R.color.mg_primary), act.getColor(R.color.mg_surface_variant)))
                thumbTintList = ColorStateList(st, intArrayOf(act.getColor(R.color.mg_on_primary), act.getColor(R.color.mg_text_dim)))
                setOnCheckedChangeListener { _, on ->
                    if (!ExtRegistry.setEnabled(e.id, on)) Util.toast(act, "Could not change")
                    fill()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            c.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_trash)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Remove " + e.name
                setOnClickListener { askRemove(e) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            c.isClickable = true
            c.isFocusable = true
            c.setOnClickListener { details(e) }
            return c
        }

        private fun kinds(e: ExtRegistry.Info): String {
            val k = ArrayList<String>()
            if (e.contentScriptCount > 0) k.add("page script")
            if (e.popup != null) k.add("popup")
            if (e.options != null) k.add("options")
            if (e.background != null) k.add("background")
            return if (k.isEmpty()) "no UI" else k.joinToString(", ")
        }

        private fun askRemove(e: ExtRegistry.Info) {
            MaterialAlertDialogBuilder(act)
                .setMessage("Remove " + e.name + "? Its files are deleted.")
                .setPositiveButton("Remove") { _, _ ->
                    if (!ExtRegistry.remove(e.id)) Util.toast(act, "Could not remove all files")
                    fill()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun details(e: ExtRegistry.Info) {
            MaterialAlertDialogBuilder(act)
                .setTitle(e.name)
                .setMessage("Version " + e.version + " (manifest v" + e.manifestVersion + ")\n\n" + permText(e))
                .setPositiveButton("Close", null)
                .show()
        }

        private fun pick() {
            if (busy) return
            BrowserExtras.pickExtension { uri -> install(uri) }
        }

        private fun install(uri: Uri) {
            busy = true
            Util.toast(act, "Reading the file...")
            ExtRegistry.prepare(act, uri) { r ->
                busy = false
                val st = r.getOrNull()
                if (st == null) {
                    fail(r.exceptionOrNull()?.message)
                    return@prepare
                }
                if (!isShowing) { // screen was closed while reading
                    ExtRegistry.discard(st)
                    return@prepare
                }
                confirmInstall(st)
            }
        }

        private fun fail(msg: String?) {
            MaterialAlertDialogBuilder(act)
                .setTitle("Could not add extension")
                .setMessage(msg ?: "Unknown error")
                .setPositiveButton("OK", null)
                .show()
        }

        private fun confirmInstall(st: ExtRegistry.Staged) {
            val i = st.info
            val old = st.existing
            val title = if (old != null) "Update " + i.name + "?" else "Add " + i.name + "?"
            val head = if (old != null) "v" + old.version + " -> v" + i.version + "\n\n" else "Version " + i.version + "\n\n"
            var done = false
            MaterialAlertDialogBuilder(act)
                .setTitle(title)
                .setMessage(head + permText(i))
                .setPositiveButton(if (old != null) "Update" else "Add") { _, _ ->
                    done = true
                    ExtRegistry.commit(st) { r ->
                        if (r.isSuccess) {
                            Util.toast(act, if (old != null) "Extension updated" else "Extension added")
                            if (isShowing) fill()
                        } else {
                            fail(r.exceptionOrNull()?.message)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .setOnDismissListener { if (!done) ExtRegistry.discard(st) }
                .show()
        }

        /** Plain words for what the extension asks for. Unknown permission names are shown as they are. */
        private fun permText(e: ExtRegistry.Info): String {
            val sb = StringBuilder()
            val hosts = e.hostPermissions
            if (hosts.isEmpty()) {
                sb.append("Sites: none asked\n")
            } else if (hosts.any { it == "<all_urls>" || it == "*://*/*" || it == "http://*/*" || it == "https://*/*" }) {
                sb.append("Sites: all websites\n")
            } else {
                sb.append("Sites:\n")
                hosts.take(8).forEach { sb.append("  ").append(it).append('\n') }
                if (hosts.size > 8) sb.append("  + ").append(hosts.size - 8).append(" more\n")
            }
            if (e.contentScriptCount > 0) sb.append("\nRuns scripts inside pages (").append(e.contentScriptCount).append(")\n")
            if (e.permissions.isNotEmpty()) {
                sb.append("\nPermissions:\n")
                e.permissions.forEach { sb.append("  ").append(permLabel(it)).append('\n') }
            }
            val bad = e.permissions.filter { it == "nativeMessaging" }
            if (bad.isNotEmpty() || e.manifestVersion == 2) {
                sb.append("\nNote: some features of this extension may not work in MGB")
                if (e.manifestVersion == 2) sb.append(" (older manifest v2)")
                sb.append('.')
            }
            return sb.toString().trimEnd()
        }

        private fun permLabel(p: String): String = when (p) {
            "storage" -> "storage (save its settings)"
            "tabs" -> "tabs (see open tabs)"
            "activeTab" -> "activeTab (current page when you use it)"
            "scripting" -> "scripting (run scripts in pages)"
            "cookies" -> "cookies (read site cookies)"
            "downloads" -> "downloads (start downloads)"
            "webRequest", "webRequestBlocking" -> "$p (watch network requests)"
            "declarativeNetRequest" -> "declarativeNetRequest (block or change requests)"
            "contextMenus" -> "contextMenus (long-press menu items)"
            "notifications" -> "notifications (show notifications)"
            "alarms" -> "alarms (timers)"
            "clipboardWrite" -> "clipboardWrite (copy text)"
            "clipboardRead" -> "clipboardRead (read your clipboard)"
            "history" -> "history (browsing history)"
            "bookmarks" -> "bookmarks"
            "nativeMessaging" -> "nativeMessaging (not supported)"
            else -> p
        }
    }
}
