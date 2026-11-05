package com.jabra.mgb

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.util.Log
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * T7: userscripts part 1 (Tampermonkey style, @grant none, no GM_ API yet).
 * Store: prefs mgb_userscripts, key "s:<id>" = JSON {c code, on, u install url, t time}; "master" (default ON), "priv" (default OFF).
 * Backup copies the prefs file (name added to Backup.SETTINGS).
 * Injection: ONE document-start script per enabled userscript per WebView (a syntax error in one script cannot stop the others).
 * Each one tests location.href against its own @match / @include / @exclude regexes, honours @noframes and @run-at.
 */
object Userscripts {
    private const val TAG = "Userscripts"
    private const val PREFS = "mgb_userscripts"
    const val MAX_ONE = 300_000
    const val MAX_ALL = 2_000_000
    const val MAX_N = 50

    class Meta(private val keys: Map<String, List<String>>) {
        fun all(k: String): List<String> = keys[k] ?: emptyList()
        fun first(k: String): String = keys[k]?.firstOrNull() ?: ""
        fun has(k: String) = keys.containsKey(k)
    }

    class Script(val id: String, val code: String, val on: Boolean, val meta: Meta, val url: String, val ts: Long) {
        val name: String get() = meta.first("name").ifEmpty { "Script $id" }
    }

    private class Handles(val priv: Boolean, val list: ArrayList<ScriptHandler>)
    private val handles = java.util.WeakHashMap<WebView, Handles>()

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun masterOn(c: Context) = p(c).getBoolean("master", true)
    fun setMaster(c: Context, on: Boolean) { p(c).edit().putBoolean("master", on).apply() }
    fun privOn(c: Context) = p(c).getBoolean("priv", false)
    fun setPriv(c: Context, on: Boolean) { p(c).edit().putBoolean("priv", on).apply() }

    // ---------- metadata ----------
    fun parse(code: String): Meta? {
        val blk = Regex("//\\s*==UserScript==([\\s\\S]*?)//\\s*==/UserScript==").find(code) ?: return null
        val m = LinkedHashMap<String, ArrayList<String>>()
        for (l in Regex("^\\s*//\\s*@([\\w:-]+)[ \\t]*(.*)$", RegexOption.MULTILINE).findAll(blk.groupValues[1]))
            m.getOrPut(l.groupValues[1].lowercase()) { ArrayList() }.add(l.groupValues[2].trim())
        return Meta(m)
    }

    // ---------- store ----------
    fun all(c: Context): List<Script> {
        val out = ArrayList<Script>()
        for ((k, v) in p(c).all) {
            if (!k.startsWith("s:") || v !is String) continue
            try {
                val o = JSONObject(v)
                val code = o.optString("c")
                out.add(Script(k.substring(2), code, o.optBoolean("on", true), parse(code) ?: Meta(emptyMap()), o.optString("u"), o.optLong("t")))
            } catch (_: Exception) { }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun count(c: Context): Int = all(c).size

    private fun save(c: Context, id: String, code: String, on: Boolean, url: String) {
        val o = JSONObject().put("c", code).put("on", on).put("u", url).put("t", System.currentTimeMillis())
        p(c).edit().putString("s:$id", o.toString()).apply()
    }

    /** Returns an error text, or null when saved. Same @name + @namespace = update of that script. */
    fun add(c: Context, code: String, url: String = ""): String? {
        if (code.length > MAX_ONE) return "Too big (max 300 KB)"
        val meta = parse(code) ?: return "No ==UserScript== header found"
        if (meta.all("match").isEmpty() && meta.all("include").isEmpty()) return "No @match or @include: it would never run"
        val id = Integer.toHexString((meta.first("name") + "|" + meta.first("namespace")).hashCode())
        val cur = all(c)
        val old = cur.firstOrNull { it.id == id }
        if (old == null && cur.size >= MAX_N) return "At most $MAX_N scripts"
        if (cur.filter { it.id != id }.sumOf { it.code.length } + code.length > MAX_ALL) return "Total size over 2 MB"
        save(c, id, code, old?.on ?: true, url)
        refreshAll(c)
        return null
    }

    fun setOn(c: Context, id: String, on: Boolean) {
        val s = all(c).firstOrNull { it.id == id } ?: return
        save(c, id, s.code, on, s.url)
    }

    fun remove(c: Context, id: String) { p(c).edit().remove("s:$id").apply(); refreshAll(c) }

    // ---------- injection ----------
    private fun esc(t: String): String {
        val sb = StringBuilder()
        for (ch in t) { if (".+?^\$(){}|[]\\".indexOf(ch) >= 0) sb.append('\\'); sb.append(ch) }
        return sb.toString()
    }

    /** @include / @exclude: /regex/ or a glob with * */
    private fun globRe(x: String): JSONArray? {
        val t = x.trim()
        if (t.isEmpty()) return null
        if (t.length > 2 && t[0] == '/' && (t.endsWith("/") || t.endsWith("/i"))) {
            val ci = t.endsWith("/i")
            return JSONArray().put(t.substring(1, t.length - (if (ci) 2 else 1))).put(if (ci) "i" else "")
        }
        return JSONArray().put("^" + t.split("*").joinToString(".*") { esc(it) } + "$").put("i")
    }

    private fun matchRe(x: String): JSONArray? = ContentInjector.patRegex(x.trim())?.let { JSONArray().put(it).put("") }

    /** The document-start JS of one script, or null when it has no usable pattern. */
    private fun build(s: Script): String? {
        val m = s.meta
        val inc = JSONArray(); val exc = JSONArray()
        for (x in m.all("match")) matchRe(x)?.let { inc.put(it) }
        for (x in m.all("include")) globRe(x)?.let { inc.put(it) }
        for (x in m.all("exclude")) globRe(x)?.let { exc.put(it) }
        for (x in m.all("exclude-match")) matchRe(x)?.let { exc.put(it) }
        if (inc.length() == 0) return null
        val sb = StringBuilder()
        sb.append("(function(){try{var u=location.href;function m(l){for(var i=0;i<l.length;i++){try{if(new RegExp(l[i][0],l[i][1]).test(u))return true}catch(e){}}return false}")
        sb.append("if(!m(").append(inc).append(")||m(").append(exc).append("))return;")
        if (m.has("noframes")) sb.append("if(window!==window.top)return;")
        sb.append("var __run=function(){try{(function(){\n").append(s.code).append("\n}).call(window)}catch(e){try{console.error('[userscript]',e)}catch(_){}}};")
        when (m.first("run-at").lowercase()) {
            "document-start" -> sb.append("__run();")
            "document-end" -> sb.append("if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__run,{once:true});else __run();")
            else -> sb.append("var __f=function(){setTimeout(__run,30)};if(document.readyState==='complete')__f();else window.addEventListener('load',__f,{once:true});")
        }
        return sb.append("}catch(e){}})();").toString()
    }

    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private fun addTo(c: Context, web: WebView, priv: Boolean): ArrayList<ScriptHandler> {
        val list = ArrayList<ScriptHandler>()
        if (!masterOn(c) || (priv && !privOn(c)) || !supported()) return list
        for (s in all(c)) {
            if (!s.on) continue
            val js = build(s) ?: continue
            try { list.add(WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*"))) } catch (e: Exception) { Log.e(TAG, "add failed: " + s.name, e) }
        }
        return list
    }

    /** Browser tab WebView: call before the first loadUrl (also when a suspended tab gets a new WebView). */
    fun attach(c: Context, web: WebView, priv: Boolean) {
        try { synchronized(handles) { handles[web] = Handles(priv, addTo(c, web, priv)) } } catch (e: Exception) { Log.e(TAG, "attach failed", e) }
    }

    /** UI thread: after a script or a switch changed, open tabs get the new set on their next page load. */
    fun refreshAll(c: Context) {
        val webs = synchronized(handles) { handles.keys.toList() }
        for (w in webs) {
            try {
                val h = synchronized(handles) { handles.remove(w) } ?: continue
                for (x in h.list) try { x.remove() } catch (_: Exception) { }
                synchronized(handles) { handles[w] = Handles(h.priv, addTo(c, w, h.priv)) }
            } catch (e: Exception) { Log.e(TAG, "refresh failed", e) }
        }
    }

    // ---------- temporary UI (T8 replaces it with a screen) ----------
    private fun clip(act: Activity): String = try {
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString() ?: ""
    } catch (_: Exception) { "" }

    fun screen(act: Activity, done: () -> Unit = {}) {
        val ls = all(act)
        val b = MaterialAlertDialogBuilder(act).setTitle("Userscripts")
        if (ls.isEmpty()) b.setMessage("No userscripts yet. Add one by pasting its code.")
        else {
            val on = BooleanArray(ls.size) { ls[it].on }
            b.setMultiChoiceItems(ls.map { it.name + (if (it.meta.first("version").isEmpty()) "" else " " + it.meta.first("version")) }.toTypedArray(), on) { _, i, c -> on[i] = c; setOn(act, ls[i].id, c) }
            b.setNeutralButton("Delete...") { _, _ ->
                MaterialAlertDialogBuilder(act).setTitle("Delete which script?").setItems(ls.map { it.name }.toTypedArray()) { _, i ->
                    MaterialAlertDialogBuilder(act).setMessage("Delete \"" + ls[i].name + "\"?")
                        .setPositiveButton("Delete") { _, _ -> remove(act, ls[i].id); Util.toast(act, "Deleted"); screen(act, done) }
                        .setNegativeButton("Cancel", null).show()
                }.setNegativeButton("Cancel", null).show()
            }
        }
        b.setPositiveButton("Add (paste code)") { _, _ -> addDialog(act, done) }
        b.setNegativeButton("Close", null)
        b.setOnDismissListener { refreshAll(act); done() }
        b.show()
    }

    private fun addDialog(act: Activity, done: () -> Unit) {
        val ed = EditText(act).apply {
            hint = "// ==UserScript==\n// @name ...\n// @match https://example.com/*\n// ==/UserScript=="
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; minLines = 8; maxLines = 14; gravity = android.view.Gravity.TOP
            val c = clip(act) // read only because the user tapped Add
            if (c.contains("==UserScript==")) setText(c)
        }
        val box = FrameLayout(act).apply { setPadding(Util.dp(act, 20), Util.dp(act, 8), Util.dp(act, 20), 0); addView(ed) }
        MaterialAlertDialogBuilder(act).setTitle("Add userscript")
            .setMessage("Only add scripts you trust. A script can read and change every page it matches.").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val err = add(act, ed.text.toString())
                Util.toast(act, err ?: "Saved. Reload the page")
                screen(act, done)
            }.setNegativeButton("Cancel") { _, _ -> screen(act, done) }.show()
    }
}
