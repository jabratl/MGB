package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * T8: full-screen "Userscripts" (skeleton of SiteSettings via AdblockManager.Ui, so cards / rows / buttons look like the rest).
 * Search, one card row per script with a switch, tap = detail (code editor, Save, Check update, Delete),
 * Add menu: clipboard / https address / blank template / import + export all through the clipboard.
 * Storage and rules stay in Userscripts.
 */
object UserscriptsScreen {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun open(act: Activity, done: () -> Unit = {}) {
        val ui = AdblockManager.Ui(act)
        val dlg = ui.fullDialog()
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.cBg) }
        var render: () -> Unit = { }
        root.addView(ui.header(dlg, "Userscripts", ui.textBtn("Add") { addMenu(act, ui) { render() } }), LinearLayout.LayoutParams(MATCH, ui.dp(56)))
        val search = EditText(act).apply {
            hint = "Search scripts"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setSingleLine(true)
            setTextColor(ui.cText); setHintTextColor(ui.cDim); setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = GradientDrawable().apply { setColor(ui.cCard); cornerRadius = ui.dp(28).toFloat() }
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH, ui.dp(48)).apply { setMargins(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(4)) })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(24)) }
        root.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        render = {
            list.removeAllViews()
            val all = Userscripts.all(act)
            val q = search.text.toString().trim().lowercase()
            val shown = all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.meta.first("description").lowercase().contains(q) }
            val info = if (!Userscripts.masterOn(act)) "Userscripts are OFF (Settings > Extensions)" else "${all.size} scripts, ${all.count { it.on }} on"
            list.addView(TextView(act).apply {
                text = info; setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(ui.dp(8), ui.dp(4), 0, ui.dp(8))
            })
            if (shown.isEmpty()) {
                list.addView(TextView(act).apply {
                    text = if (all.isEmpty()) "No userscripts yet. Tap Add to paste one, install from an https address, or open a .user.js link in a tab." else "No script matches."
                    setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0)
                })
            } else {
                val card = ui.card()
                for (s in shown) {
                    val sw = ui.switchFor(s.on).apply {
                        isClickable = true; isFocusable = true
                        setOnCheckedChangeListener { v, c ->
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            Userscripts.setOn(act, s.id, c); Userscripts.refreshAll(act)
                        }
                    }
                    val pat = (s.meta.all("match") + s.meta.all("include")).firstOrNull() ?: "no pattern"
                    val hint = (if (s.meta.first("version").isEmpty()) "" else "v" + s.meta.first("version") + " · ") + pat
                    ui.addTo(card, ui.row(R.drawable.ic_code, s.name, hint, sw, { detail(act, ui, s) { render() } }))
                }
                list.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { render() }
            override fun afterTextChanged(s: Editable?) { }
        })
        dlg.setOnDismissListener { Userscripts.refreshAll(act); done() }
        render()
        ui.showFull(dlg, root)
    }

    private fun addMenu(act: Activity, ui: AdblockManager.Ui, changed: () -> Unit) {
        val items = arrayOf("From clipboard", "From https address", "Blank template", "Import all (clipboard)", "Export all (copy)")
        MaterialAlertDialogBuilder(act).setTitle("Add userscript").setItems(items) { _, i ->
            when (i) {
                0 -> { // clipboard is read only because the user tapped this
                    val t = Userscripts.clip(act)
                    if (t.contains("==UserScript==")) Userscripts.promptInstall(act, t, "", changed) else Util.toast(act, "No userscript in the clipboard")
                }
                1 -> {
                    val inp = ui.box("https://example.com/script.user.js")
                    val fl = FrameLayout(act).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(inp) }
                    MaterialAlertDialogBuilder(act).setTitle("Install from address").setView(fl)
                        .setPositiveButton("Download") { _, _ ->
                            val u = inp.text.toString().trim()
                            Userscripts.fetch(u) { text, err -> act.runOnUiThread { if (text == null) Util.toast(act, err ?: "Failed") else Userscripts.promptInstall(act, text, u, changed) } }
                        }.setNegativeButton("Cancel", null).show()
                }
                2 -> detail(act, ui, null, changed)
                3 -> importAll(act, changed)
                else -> exportAll(act)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun exportAll(act: Activity) {
        val ls = Userscripts.all(act)
        if (ls.isEmpty()) { Util.toast(act, "Nothing to export"); return }
        val a = JSONArray()
        for (s in ls) a.put(JSONObject().put("c", s.code).put("on", s.on).put("u", s.url))
        try {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("userscripts", a.toString()))
            Util.toast(act, "${ls.size} scripts copied")
        } catch (_: Exception) { Util.toast(act, "Could not copy") }
    }

    private fun importAll(act: Activity, changed: () -> Unit) {
        val a = try { JSONArray(Userscripts.clip(act).trim()) } catch (_: Exception) { null }
        if (a == null) { Util.toast(act, "Clipboard has no exported scripts"); return }
        var ok = 0; var bad = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val code = o.optString("c")
            if (Userscripts.add(act, code, o.optString("u")) == null) {
                ok++
                Userscripts.parse(code)?.let { Userscripts.setOn(act, Userscripts.idOf(it), o.optBoolean("on", true)) }
            } else bad++
        }
        Userscripts.refreshAll(act)
        Util.toast(act, "Imported $ok" + (if (bad > 0) ", skipped $bad" else ""))
        changed()
    }

    /** s == null: new script from the template. */
    private fun detail(act: Activity, ui: AdblockManager.Ui, s: Userscripts.Script?, changed: () -> Unit) {
        val dlg = ui.fullDialog()
        val start = s?.code ?: Userscripts.TEMPLATE
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.cBg) }
        val ed = EditText(act).apply {
            setText(start); typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ui.cText); gravity = Gravity.TOP or Gravity.START; setHorizontallyScrolling(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = GradientDrawable().apply { setColor(ui.cCard); cornerRadius = ui.dp(22).toFloat(); setStroke(ui.dp(1), ColorUtils.setAlphaComponent(ui.cLine, 0x99)) }
        }
        val meta = Userscripts.parse(start)
        val info = TextView(act).apply {
            setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(ui.dp(22), ui.dp(2), ui.dp(22), ui.dp(6))
            text = if (s == null || meta == null) "New script. Edit the header and the code." else listOf(
                if (meta.first("version").isEmpty()) "" else "v" + meta.first("version"),
                "runs at " + meta.first("run-at").ifEmpty { "document-idle" },
                (meta.all("match").size + meta.all("include").size).toString() + " patterns"
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val status = TextView(act).apply { setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(2)) }
        val askClose: () -> Unit = {
            if (ed.text.toString() != start) MaterialAlertDialogBuilder(act).setMessage("Discard your changes?")
                .setPositiveButton("Discard") { _, _ -> dlg.dismiss() }.setNegativeButton("Keep editing", null).show()
            else dlg.dismiss()
        }
        val right = if (s == null) null else ui.textBtn("Check update") { Userscripts.checkUpdate(act, s) { changed(); dlg.dismiss() } }
        root.addView(ui.header(dlg, s?.name ?: "New script", right) { askClose() }, LinearLayout.LayoutParams(MATCH, ui.dp(56)))
        root.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
        if (s != null) root.addView(ui.textBtn("Network and libraries") { Userscripts.netDialog(act, s) }, LinearLayout.LayoutParams(WRAP, ui.dp(40)).apply { setMargins(ui.dp(14), 0, ui.dp(14), 0) }) // T10
        root.addView(ed, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(ui.dp(14), 0, ui.dp(14), 0) })
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(14), 0, ui.dp(14), 0) })
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(12)) }
        if (s != null) bar.addView(ui.textBtn("Delete") {
            MaterialAlertDialogBuilder(act).setMessage("Delete \"" + s.name + "\"?")
                .setPositiveButton("Delete") { _, _ -> Userscripts.remove(act, s.id); Util.toast(act, "Deleted"); changed(); dlg.dismiss() }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        bar.addView(ui.pill("Save") {
            val code = ed.text.toString()
            val err = Userscripts.add(act, code, s?.url ?: "")
            if (err != null) status.text = err
            else {
                val nm = Userscripts.parse(code)
                if (s != null && nm != null && Userscripts.idOf(nm) != s.id) Userscripts.remove(act, s.id) // renamed = new id
                Util.toast(act, "Saved. Reload the page"); changed(); dlg.dismiss()
            }
        }, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        dlg.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) { askClose(); true } else false
        }
        ui.showFull(dlg, root)
    }
}
