package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * T6: one full-screen "Hosts and filters" page (skeleton of SiteSettings, card style of Settings).
 * Only calls the T5 backend: FilterList (lists, user rules) and AdBlock (user hosts, allow list, built-in hosts, explain).
 * No storage of its own.
 */
object AdblockManager {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val K_USER = 0
    private const val K_ALLOW = 1
    private const val K_BUILTIN = 2

    internal class Ui(val act: Activity) { // T8: also used by UserscriptsScreen
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cOnPrimary = act.getColor(R.color.mg_on_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        val cBg = act.getColor(R.color.mg_bg)
        private val night = (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val tint = TabPrefs.tint("privacy", night)

        fun dp(n: Int) = Util.dp(act, n)

        fun card() = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cCard); cornerRadius = dp(22).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(cLine, 0x99))
            }
        }

        fun label(t: String) = TextView(act).apply {
            text = t.uppercase(); setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            setPadding(dp(8), dp(16), 0, dp(6))
        }

        fun divider() = View(act).apply { setBackgroundColor(ColorUtils.setAlphaComponent(cLine, 0x99)) }

        fun addTo(card: LinearLayout, v: View, divider: Boolean = true) {
            if (divider && card.childCount > 0) card.addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(64) })
            card.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fun badge(icon: Int) = FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(tint, 0x26)) }
            addView(ImageView(act).apply { setImageResource(icon); setColorFilter(tint) }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }

        fun chevron() = ImageView(act).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(cDim) }.also { it.layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)) }

        fun switchFor(on: Boolean) = MaterialSwitch(act).apply {
            isChecked = on; isClickable = false; isFocusable = false
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            trackTintList = ColorStateList(st, intArrayOf(cPrimary, act.getColor(R.color.mg_surface_variant)))
            thumbTintList = ColorStateList(st, intArrayOf(cOnPrimary, cDim))
        }

        fun row(icon: Int, title: String, hint: String = "", trail: View? = null, click: (() -> Unit)? = null, long: (() -> Unit)? = null): LinearLayout {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56); setPadding(dp(14), dp(8), dp(14), dp(8))
                if (click != null || long != null) {
                    isClickable = true; isFocusable = true
                    val out = TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) foreground = act.getDrawable(out.resourceId)
                    if (click != null) setOnClickListener { click() }
                    if (long != null) setOnLongClickListener { long(); true }
                }
            }
            r.addView(badge(icon), LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(act).apply { text = title; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 2 })
            if (hint.isNotEmpty()) box.addView(TextView(act).apply { text = hint; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 2 })
            r.addView(box, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (trail != null) r.addView(trail, (trail.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(WRAP, WRAP)).apply { marginStart = dp(8) })
            return r
        }

        fun pill(text: String, click: () -> Unit) = TextView(act).apply {
            this.text = text; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cOnPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply { setColor(cPrimary); cornerRadius = dp(22).toFloat() }
            isClickable = true; setOnClickListener { click() }
        }

        fun fullDialog() = Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)

        fun header(dlg: Dialog, title: String, right: View? = null, onBack: (() -> Unit)? = null): LinearLayout {
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back); setColorFilter(cText); setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"; setOnClickListener { if (onBack != null) onBack() else dlg.dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
                setTextColor(cText); gravity = Gravity.CENTER_VERTICAL or Gravity.START; setPadding(dp(6), 0, 0, 0); maxLines = 1
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (right != null) head.addView(right, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(10) })
            return head
        }

        fun textBtn(t: String, click: () -> Unit) = TextView(act).apply {
            text = t; setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)); isClickable = true; setOnClickListener { click() }
        }

        fun showFull(dlg: Dialog, root: View) {
            dlg.setContentView(root)
            dlg.window?.let {
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                it.setBackgroundDrawable(ColorDrawable(cBg))
            }
            dlg.show()
        }

        fun box(hint: String, uri: Boolean = true) = EditText(act).apply {
            this.hint = hint; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or (if (uri) InputType.TYPE_TEXT_VARIATION_URI else 0)
            setTextColor(cText); setHintTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
    }

    // ---------- helpers ----------
    private fun cleanHost(s: String): String {
        var t = s.trim().lowercase()
        t = if (t.contains("://")) (try { Uri.parse(t).host ?: "" } catch (_: Exception) { "" })
        else t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return t.removePrefix("www.")
    }

    private fun clipText(act: Activity): String = try {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager // read only when the user taps
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString() ?: ""
    } catch (_: Exception) { "" }

    private fun addHost(kind: Int, h: String): Boolean = if (kind == K_USER) AdBlock.addUserHost(h) else AdBlock.addAllow(h)

    private fun ruleLines(): Int = FilterList.userRules().lines().count { val l = it.trim(); l.isNotEmpty() && l[0] != '!' && l[0] != '[' }

    private fun ageText(): String {
        val ts = FilterList.updatedAt()
        if (ts == 0L) return "not updated yet"
        val days = (System.currentTimeMillis() - ts) / 86_400_000L
        return if (days <= 0) "updated today" else "updated $days day" + (if (days == 1L) "" else "s") + " ago"
    }

    // ---------- main page ----------
    /** [pageHost] = host of the open tab (used by "Test a URL"), may be null. [done] runs when the page closes. */
    fun open(act: Activity, pageHost: String? = null, done: () -> Unit = {}) {
        val ui = Ui(act)
        val dlg = ui.fullDialog()
        val h = Handler(Looper.getMainLooper())
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.cBg) }
        root.addView(ui.header(dlg, "Hosts and filters"), LinearLayout.LayoutParams(MATCH, ui.dp(56)))
        val dyn = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(24)) }
        col.addView(dyn, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(ScrollView(act).apply { addView(col) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // list switches are collected and applied once (every apply reloads the rules)
        val pending = HashMap<String, Boolean>()
        var render: () -> Unit = { }
        val applyRun = Runnable {
            val m = HashMap(pending); pending.clear()
            if (m.isNotEmpty()) FilterList.setEnabledAll(act, m) { msg -> act.runOnUiThread { Util.toast(act, msg); if (dlg.isShowing) render() } }
        }
        fun later() { h.postDelayed({ if (dlg.isShowing) render() }, 1500) }

        render = {
            dyn.removeAllViews()
            // 1. filter lists
            dyn.addView(ui.label("Filter lists"))
            val c1 = ui.card()
            for (li in FilterList.lists()) {
                val sw = ui.switchFor(li.on)
                var on = li.on
                ui.addTo(c1, ui.row(R.drawable.ic_shield, li.title, if (li.custom) "Custom list. Long press to remove" else "Built-in list", sw, {
                    on = !on; sw.isChecked = on
                    sw.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    pending[li.name] = on; h.removeCallbacks(applyRun); h.postDelayed(applyRun, 900)
                }, if (li.custom) ({ askRemove(act, li.name, li.title) { render() } }) else null))
            }
            val info = TextView(act).apply {
                setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(2))
                text = if (FilterList.loading) "Loading rules..." else "${FilterList.ruleCount} rules, ${ageText()}"
            }
            c1.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
            val upd = ui.pill("Update filters now") { }
            upd.setOnClickListener {
                upd.text = "Updating..."; upd.isEnabled = false
                FilterList.update(act) { msg -> act.runOnUiThread { Util.toast(act, msg); upd.text = "Update filters now"; upd.isEnabled = true; later() } }
            }
            c1.addView(upd, LinearLayout.LayoutParams(MATCH, ui.dp(44)).apply { setMargins(ui.dp(14), ui.dp(8), ui.dp(14), 0) })
            c1.addView(ui.textBtn("Add list by address") {
                val inp = ui.box("https://example.com/list.txt")
                val fl = FrameLayout(act).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(inp) }
                MaterialAlertDialogBuilder(act).setTitle("Add filter list").setMessage("EasyList / uBlock syntax, https only.").setView(fl)
                    .setPositiveButton("Add") { _, _ ->
                        FilterList.addCustom(act, inp.text.toString()) { m -> act.runOnUiThread { Util.toast(act, m); if (dlg.isShowing) render(); later() } }?.let { Util.toast(act, it) }
                    }.setNegativeButton("Cancel", null).show()
            }, LinearLayout.LayoutParams(MATCH, ui.dp(44)))
            dyn.addView(c1, LinearLayout.LayoutParams(MATCH, WRAP))

            // 2. my rules
            dyn.addView(ui.label("My rules"))
            val c2 = ui.card()
            val n = ruleLines()
            ui.addTo(c2, ui.row(R.drawable.ic_code, "Edit rules", if (n == 0) "None yet. One rule per line" else "$n rules", ui.chevron(), { editRules(ui) { render() } }))
            dyn.addView(c2, LinearLayout.LayoutParams(MATCH, WRAP))

            // 3. my blocked hosts
            dyn.addView(ui.label("My blocked hosts"))
            val c3 = ui.card()
            ui.addTo(c3, ui.row(R.drawable.ic_list, "Blocked hosts", "${AdBlock.userHosts().size} hosts", ui.chevron(), { hostsDialog(ui, K_USER) { render() } }))
            ui.addTo(c3, ui.row(R.drawable.ic_add, "Add host", "", null, { addDialog(ui, K_USER) { render() } }))
            ui.addTo(c3, ui.row(R.drawable.ic_list, "Add from clipboard", "Hosts or a hosts file", null, { fromClip(ui, K_USER) { render() } }))
            dyn.addView(c3, LinearLayout.LayoutParams(MATCH, WRAP))

            // 4. allowed hosts
            dyn.addView(ui.label("Allowed hosts"))
            val c4 = ui.card()
            ui.addTo(c4, ui.row(R.drawable.ic_check, "Allowed hosts", "${AdBlock.allowHosts().size} hosts, never blocked", ui.chevron(), { hostsDialog(ui, K_ALLOW) { render() } }))
            ui.addTo(c4, ui.row(R.drawable.ic_add, "Allow a host", "", null, { addDialog(ui, K_ALLOW) { render() } }))
            ui.addTo(c4, ui.row(R.drawable.ic_list, "Sites with ads allowed", "Per-site switch", ui.chevron(), { CosmeticFilter.allowList(act) { render() } }))
            dyn.addView(c4, LinearLayout.LayoutParams(MATCH, WRAP))

            // 5. built-in hosts
            dyn.addView(ui.label("Built-in hosts"))
            val c5 = ui.card()
            ui.addTo(c5, ui.row(R.drawable.ic_shield, "Built-in host list", "Read only. Long press to allow", TextView(act).apply {
                text = AdBlock.builtinHosts().size.toString(); setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }, { hostsDialog(ui, K_BUILTIN) { render() } }))
            dyn.addView(c5, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        render()

        // 6. test a URL (built once, so the typed address survives a refresh)
        col.addView(ui.label("Test a URL"))
        val c6 = ui.card().apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }
        val inp = ui.box("https://ads.example.com/banner.js")
        val out = TextView(act).apply { setTextColor(ui.cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD; setPadding(0, ui.dp(10), 0, 0) }
        val ph = pageHost?.takeIf { it.isNotEmpty() }
        val note = TextView(act).apply {
            setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = if (ph != null) "Checked as a request from $ph" else "Checked as a request from any page"
        }
        c6.addView(inp, LinearLayout.LayoutParams(MATCH, WRAP))
        c6.addView(note, LinearLayout.LayoutParams(MATCH, WRAP))
        c6.addView(ui.pill("Test") {
            val u = inp.text.toString().trim()
            if (u.isNotEmpty()) out.text = AdBlock.explain(if (u.contains("://")) u else "https://$u", ph)
        }, LinearLayout.LayoutParams(MATCH, ui.dp(44)).apply { topMargin = ui.dp(10) })
        c6.addView(out, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(c6, LinearLayout.LayoutParams(MATCH, WRAP))

        dlg.setOnDismissListener {
            h.removeCallbacks(applyRun); applyRun.run()
            done()
        }
        ui.showFull(dlg, root)
    }

    private fun askRemove(act: Activity, name: String, title: String, changed: () -> Unit) {
        MaterialAlertDialogBuilder(act).setMessage("Remove \"" + title + "\"?")
            .setPositiveButton("Remove") { _, _ -> FilterList.removeCustom(act, name); Util.toast(act, "Removed"); changed() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- add host / clipboard ----------
    private fun addDialog(ui: Ui, kind: Int, changed: () -> Unit) {
        val act = ui.act
        val inp = ui.box("example.com")
        val fl = FrameLayout(act).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(inp) }
        MaterialAlertDialogBuilder(act).setTitle(if (kind == K_USER) "Block a host" else "Allow a host")
            .setMessage("Also covers its subdomains.").setView(fl)
            .setPositiveButton("Add") { _, _ ->
                val hs = cleanHost(inp.text.toString())
                if (hs.isNotEmpty() && addHost(kind, hs)) { Util.toast(act, "Added $hs. Reload the page"); changed() }
                else Util.toast(act, "Not a valid host (or list full)")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun fromClip(ui: Ui, kind: Int, changed: () -> Unit) {
        val act = ui.act
        val parts = clipText(act).split(Regex("[\\s,;]+")).map { cleanHost(it) }
            .filter { it.isNotEmpty() && it != "localhost" && !it.matches(Regex("[0-9.]+")) }.distinct()
        if (parts.isEmpty()) { Util.toast(act, "No hosts in the clipboard"); return }
        var ok = 0
        for (p in parts.take(300)) if (addHost(kind, p)) ok++
        Util.toast(act, "Added $ok of ${minOf(parts.size, 300)} hosts" + (if (parts.size > 300) " (first 300)" else ""))
        changed()
    }

    // ---------- host list (user hosts, allowed hosts, built-in hosts) ----------
    private class VH(v: TextView) : RecyclerView.ViewHolder(v)

    private fun hostsDialog(ui: Ui, kind: Int, closed: () -> Unit) {
        val act = ui.act
        val dlg = ui.fullDialog()
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.cBg) }
        root.addView(ui.header(dlg, when (kind) { K_USER -> "Blocked hosts"; K_ALLOW -> "Allowed hosts"; else -> "Built-in hosts" }), LinearLayout.LayoutParams(MATCH, ui.dp(56)))
        val search = EditText(act).apply {
            hint = "Search hosts"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setSingleLine(true)
            setTextColor(ui.cText); setHintTextColor(ui.cDim); setPadding(ui.dp(16), 0, ui.dp(16), 0)
            background = GradientDrawable().apply { setColor(ui.cCard); cornerRadius = ui.dp(28).toFloat() }
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH, ui.dp(48)).apply { setMargins(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(4)) })
        val info = TextView(act).apply { setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(ui.dp(22), ui.dp(4), ui.dp(22), ui.dp(6)) }
        root.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
        val empty = TextView(act).apply { setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(ui.dp(22), ui.dp(12), ui.dp(22), 0); visibility = View.GONE }
        root.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))

        fun source(): List<String> = when (kind) { K_USER -> AdBlock.userHosts(); K_ALLOW -> AdBlock.allowHosts(); else -> AdBlock.builtinHosts() }
        var all = source()
        var shown = all
        var refresh: (Boolean) -> Unit = { }
        val rv = RecyclerView(act).apply { layoutManager = LinearLayoutManager(act); setPadding(0, 0, 0, ui.dp(24)); clipToPadding = false }
        rv.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
                val tv = TextView(act).apply {
                    setTextColor(ui.cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(ui.dp(22), 0, ui.dp(18), 0)
                    layoutParams = RecyclerView.LayoutParams(MATCH, ui.dp(48))
                    val out = TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) background = act.getDrawable(out.resourceId)
                    if (kind != K_BUILTIN) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_trash, 0)
                        compoundDrawableTintList = ColorStateList.valueOf(ui.cDim)
                    }
                }
                return VH(tv)
            }
            override fun getItemCount() = shown.size
            override fun onBindViewHolder(vh: VH, i: Int) {
                val host = shown[i]
                (vh.itemView as TextView).text = host
                vh.itemView.setOnClickListener {
                    if (kind == K_BUILTIN) Util.toast(act, "Long press to allow this host")
                    else MaterialAlertDialogBuilder(act).setMessage("Remove $host?")
                        .setPositiveButton("Remove") { _, _ -> if (kind == K_USER) AdBlock.removeUserHost(host) else AdBlock.removeAllow(host); refresh(true) }
                        .setNegativeButton("Cancel", null).show()
                }
                vh.itemView.setOnLongClickListener {
                    if (kind == K_BUILTIN) MaterialAlertDialogBuilder(act).setTitle(host).setMessage("Allow this host? It will never be blocked.")
                        .setPositiveButton("Allow") { _, _ -> if (AdBlock.addAllow(host)) Util.toast(act, "Allowed. Reload the page") else Util.toast(act, "Could not add (list full)") }
                        .setNegativeButton("Cancel", null).show()
                    kind == K_BUILTIN
                }
            }
        }
        root.addView(rv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        refresh = { reload ->
            if (reload) all = source()
            val q = search.text.toString().trim().lowercase()
            shown = if (q.isEmpty()) all else all.filter { it.contains(q) }
            rv.adapter?.notifyDataSetChanged()
            info.text = "${all.size} hosts" + (if (q.isNotEmpty()) ", ${shown.size} match" else "") + (if (kind == K_BUILTIN) ". Long press a host to allow it" else "")
            empty.text = if (all.isEmpty()) "None yet." else "No host matches."
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh(false) }
            override fun afterTextChanged(s: Editable?) { }
        })
        dlg.setOnDismissListener { closed() }
        refresh(false)
        ui.showFull(dlg, root)
    }

    // ---------- my rules editor ----------
    private fun editRules(ui: Ui, closed: () -> Unit) {
        val act = ui.act
        val dlg = ui.fullDialog()
        val saved = FilterList.userRules()
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.cBg) }
        val ed = EditText(act).apply {
            setText(saved); typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ui.cText); setHintTextColor(ui.cDim); hint = "||ads.example.com^\nexample.com##.banner\n! a comment"
            gravity = Gravity.TOP or Gravity.START; setHorizontallyScrolling(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = GradientDrawable().apply { setColor(ui.cCard); cornerRadius = ui.dp(22).toFloat(); setStroke(ui.dp(1), ColorUtils.setAlphaComponent(ui.cLine, 0x99)) }
        }
        val status = TextView(act).apply { setTextColor(ui.cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(4)); text = "One rule per line. Max 1500 lines." }
        val tools = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(ui.textBtn("Copy") {
            try { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("rules", ed.text.toString())); Util.toast(act, "Rules copied") } catch (_: Exception) { }
        })
        tools.addView(ui.textBtn("Paste") {
            val t = clipText(act)
            if (t.isEmpty()) Util.toast(act, "Clipboard is empty") else { ed.setText(t); ed.setSelection(ed.text.length) }
        })
        val askDiscard: () -> Unit = {
            if (ed.text.toString() != saved) MaterialAlertDialogBuilder(act).setMessage("Discard your changes?")
                .setPositiveButton("Discard") { _, _ -> dlg.dismiss() }.setNegativeButton("Keep editing", null).show()
            else dlg.dismiss()
        }
        root.addView(ui.header(dlg, "My rules", tools) { askDiscard() }, LinearLayout.LayoutParams(MATCH, ui.dp(56)))
        root.addView(ed, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(ui.dp(14), ui.dp(2), ui.dp(14), 0) })
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(14), 0, ui.dp(14), 0) })
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(12)) }
        bar.addView(ui.textBtn("Validate") { status.text = FilterList.checkRules(ed.text.toString()) }, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        val apply = ui.pill("Apply") { }
        apply.setOnClickListener {
            apply.text = "Applying..."; apply.isEnabled = false
            FilterList.setUserRules(act, ed.text.toString()) { msg ->
                act.runOnUiThread {
                    apply.text = "Apply"; apply.isEnabled = true
                    status.text = msg; Util.toast(act, msg)
                    if (msg.contains("ok,")) { dlg.setOnDismissListener { closed() }; dlg.dismiss() }
                }
            }
        }
        bar.addView(apply, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        dlg.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) { askDiscard(); true } else false
        }
        dlg.setOnDismissListener { closed() }
        ui.showFull(dlg, root)
    }
}
