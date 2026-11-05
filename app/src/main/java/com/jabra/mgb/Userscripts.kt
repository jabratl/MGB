package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
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
 * T9: a script with a real @grant gets a GM shim (GM_info, values, addStyle, setClipboard, openInTab, notification, menu commands + GM.* promises)
 *     as function parameters (the page never sees GM_ names). Native side = WebMessageListener "MGBUS" (no replies), per-script token
 *     ContentInjector.tokenFor("us:<id>"). Values: prefs mgb_us_values, key "v:<id>" = JSON {name: serialized json}, embedded as a snapshot at page start.
 */
object Userscripts {
    private const val TAG = "Userscripts"
    private const val PREFS = "mgb_userscripts"
    const val MAX_ONE = 300_000
    const val MAX_ALL = 2_000_000
    const val MAX_N = 50
    const val MAX_VALS = 1_000_000 // T9: per script, keys + serialized values
    private const val VPREFS = "mgb_us_values"

    class Meta(private val keys: Map<String, List<String>>) {
        fun all(k: String): List<String> = keys[k] ?: emptyList()
        fun first(k: String): String = keys[k]?.firstOrNull() ?: ""
        fun has(k: String) = keys.containsKey(k)
    }

    class Script(val id: String, val code: String, val on: Boolean, val meta: Meta, val url: String, val ts: Long) {
        val name: String get() = meta.first("name").ifEmpty { "Script $id" }
    }

    private class Handles(val priv: Boolean, val list: HashMap<String, ScriptHandler>)
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

    fun idOf(m: Meta): String = Integer.toHexString((m.first("name") + "|" + m.first("namespace")).hashCode())

    /** Returns an error text, or null when saved. Same @name + @namespace = update of that script. */
    fun add(c: Context, code: String, url: String = ""): String? {
        if (code.length > MAX_ONE) return "Too big (max 300 KB)"
        val meta = parse(code) ?: return "No ==UserScript== header found"
        if (meta.all("match").isEmpty() && meta.all("include").isEmpty()) return "No @match or @include: it would never run"
        val id = idOf(meta)
        val cur = all(c)
        val old = cur.firstOrNull { it.id == id }
        if (old == null && cur.size >= MAX_N) return "At most $MAX_N scripts"
        if (cur.filter { it.id != id }.sumOf { it.code.length } + code.length > MAX_ALL) return "Total size over 2 MB"
        save(c, id, code, old?.on ?: true, url)
        refreshAll(c)
        fetchDeps(c, id, meta, false) // T10: @require / @resource in the background
        return null
    }

    fun setOn(c: Context, id: String, on: Boolean) {
        val s = all(c).firstOrNull { it.id == id } ?: return
        save(c, id, s.code, on, s.url)
    }

    fun remove(c: Context, id: String) {
        p(c).edit().remove("s:$id").apply()
        synchronized(vals) { vals.remove(id); dirty.remove(id) }
        c.getSharedPreferences(VPREFS, Context.MODE_PRIVATE).edit().remove("v:$id").apply() // T9
        p(c).edit().remove("r:$id").remove("n:$id").apply() // T10: libraries + network permissions
        refreshAll(c)
    }

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

    private fun lists(m: Meta): Pair<JSONArray, JSONArray> {
        val inc = JSONArray(); val exc = JSONArray()
        for (x in m.all("match")) matchRe(x)?.let { inc.put(it) }
        for (x in m.all("include")) globRe(x)?.let { inc.put(it) }
        for (x in m.all("exclude")) globRe(x)?.let { exc.put(it) }
        for (x in m.all("exclude-match")) matchRe(x)?.let { exc.put(it) }
        return Pair(inc, exc)
    }

    /** T8: same patterns as the injected script, tested in Kotlin (for the page row). */
    fun matchesUrl(s: Script, url: String): Boolean {
        val (inc, exc) = lists(s.meta)
        fun hit(a: JSONArray): Boolean {
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                try { if (Regex(r.getString(0), if (r.getString(1) == "i") setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(url)) return true } catch (_: Exception) { }
            }
            return false
        }
        return inc.length() > 0 && hit(inc) && !hit(exc)
    }

    fun matchingNow(c: Context, url: String): List<Script> = if (!url.startsWith("http")) emptyList() else all(c).filter { matchesUrl(it, url) }

    /** The document-start JS of one script, or null when it has no usable pattern. */
    private fun build(c: Context, s: Script): String? {
        val m = s.meta
        val (inc, exc) = lists(m)
        if (inc.length() == 0) return null
        val sb = StringBuilder()
        sb.append("(function(){try{var u=location.href;function m(l){for(var i=0;i<l.length;i++){try{if(new RegExp(l[i][0],l[i][1]).test(u))return true}catch(e){}}return false}")
        sb.append("if(!m(").append(inc).append(")||m(").append(exc).append("))return;")
        if (m.has("noframes")) sb.append("if(window!==window.top)return;")
        val g = grants(m)
        val pre = deps(c, s.id).q.filter { it.isNotEmpty() }.joinToString("") { it + "\n;\n" } // T10: @require texts
        if (g.isEmpty()) sb.append("var __run=function(){try{(function(){\n").append(pre).append(s.code).append("\n}).call(window)}catch(e){try{console.error('[userscript]',e)}catch(_){}}};")
        else sb.append(shim(c, s, g)).append("var __run=function(){try{(function(GM_info,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,GM_addStyle,GM_setClipboard,GM_openInTab,GM_notification,GM_registerMenuCommand,GM_unregisterMenuCommand,GM_xmlhttpRequest,GM_getResourceText,GM_getResourceURL,GM,unsafeWindow){\n").append(pre).append(s.code)
            .append("\n}).call(window,__info,__g('getValue',__get),__g('setValue',__set),__g('deleteValue',__del),__g('listValues',__list),__g('addStyle',__style),__g('setClipboard',__clip),__g('openInTab',__open),__g('notification',__note),__g('registerMenuCommand',__reg),__g('unregisterMenuCommand',__unreg),__g('xmlhttpRequest',__xhr),__g('getResourceText',__rtext),__g('getResourceURL',__rurl),__GM,window)}catch(e){try{console.error('[userscript]',e)}catch(_){}}};")
        when (m.first("run-at").lowercase()) {
            "document-start" -> sb.append("__run();")
            "document-end" -> sb.append("if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__run,{once:true});else __run();")
            else -> sb.append("var __f=function(){setTimeout(__run,30)};if(document.readyState==='complete')__f();else window.addEventListener('load',__f,{once:true});")
        }
        return sb.append("}catch(e){}})();").toString()
    }

    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private fun addTo(c: Context, web: WebView, priv: Boolean): HashMap<String, ScriptHandler> {
        val list = HashMap<String, ScriptHandler>()
        if (!masterOn(c) || (priv && !privOn(c)) || !supported()) return list
        for (s in all(c)) addOne(c, web, s, list)
        return list
    }

    private fun addOne(c: Context, web: WebView, s: Script, into: HashMap<String, ScriptHandler>) {
        if (!s.on) return
        val js = build(c, s) ?: return
        try { into[s.id] = WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*")) } catch (e: Exception) { Log.e(TAG, "add failed: " + s.name, e) }
    }

    /** Browser tab WebView: call before the first loadUrl (also when a suspended tab gets a new WebView). */
    fun attach(c: Context, web: WebView, priv: Boolean) {
        try {
            if (supported() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && (!priv || privOn(c))) { // T9: GM bridge (before the first load)
                ContentInjector.init(c)
                try { WebViewCompat.addWebMessageListener(web, "MGBUS", setOf("*"), listener) } catch (e: Exception) { Log.e(TAG, "bridge failed", e) }
            }
            synchronized(handles) { handles[web] = Handles(priv, addTo(c, web, priv)) }
        } catch (e: Exception) { Log.e(TAG, "attach failed", e) }
    }

    /** UI thread: after a script or a switch changed, open tabs get the new set on their next page load. */
    fun refreshAll(c: Context) {
        val webs = synchronized(handles) { handles.keys.toList() }
        for (w in webs) {
            try {
                val h = synchronized(handles) { handles.remove(w) } ?: continue
                for (x in h.list.values) try { x.remove() } catch (_: Exception) { }
                synchronized(handles) { handles[w] = Handles(h.priv, addTo(c, w, h.priv)) }
            } catch (e: Exception) { Log.e(TAG, "refresh failed", e) }
        }
    }

    // ---------- T9: GM_ API without network ----------
    /** Real grants only: "none" and an empty list mean page world as in T7. */
    private fun grants(m: Meta): Set<String> {
        val g = m.all("grant").map { it.trim() }.filter { it.isNotEmpty() && it != "none" }.toMutableSet()
        if ("GM.xmlHttpRequest" in g || "GM.xmlhttpRequest" in g) g.add("GM_xmlhttpRequest") // T10: GM4 spelling
        if ("GM.getResourceUrl" in g) g.add("GM_getResourceURL")
        return g
    }

    private fun can(s: Script, name: String): Boolean { val g = grants(s.meta); return ("GM_$name" in g) || ("GM.$name" in g) }

    private fun evtName(sid: String) = "__u" + ContentInjector.tokenFor("us:$sid").take(24)

    private fun jsSafe(t: String) = t.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    // values: id -> (name -> serialized json). Written to prefs 1.5 s after the last change.
    private val vals = HashMap<String, LinkedHashMap<String, String>>()
    private val dirty = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    private fun valsOf(c: Context, id: String): LinkedHashMap<String, String> = synchronized(vals) {
        vals.getOrPut(id) {
            val m = LinkedHashMap<String, String>()
            try {
                val o = JSONObject(c.getSharedPreferences(VPREFS, Context.MODE_PRIVATE).getString("v:$id", "{}") ?: "{}")
                for (k in o.keys()) m[k] = o.optString(k)
            } catch (_: Exception) { }
            m
        }
    }

    private fun valSize(m: Map<String, String>): Int = m.entries.sumOf { it.key.length + it.value.length }

    private val flush = Runnable {
        val c = appCtx ?: return@Runnable
        val ids = synchronized(vals) { val l = dirty.toList(); dirty.clear(); l }
        for (id in ids) {
            val m = synchronized(vals) { vals[id]?.let { LinkedHashMap(it) } } ?: continue
            val o = JSONObject(); for ((k, v) in m) o.put(k, v)
            c.getSharedPreferences(VPREFS, Context.MODE_PRIVATE).edit().putString("v:$id", o.toString()).apply()
            refreshOne(c, id) // later page loads get the new snapshot (a page that is open keeps its own copy)
        }
    }

    private fun markDirty(c: Context, id: String) {
        appCtx = c.applicationContext
        synchronized(vals) { dirty.add(id) }
        ui.removeCallbacks(flush); ui.postDelayed(flush, 1500)
    }

    /** Rebuild only the handler of one script on every tab that has it. */
    private fun refreshOne(c: Context, id: String) {
        val s = all(c).firstOrNull { it.id == id } ?: return
        val webs = synchronized(handles) { handles.keys.toList() }
        for (w in webs) {
            try {
                val h = synchronized(handles) { handles[w] } ?: continue
                val old = h.list.remove(id) ?: continue
                try { old.remove() } catch (_: Exception) { }
                addOne(c, w, s, h.list)
            } catch (e: Exception) { Log.e(TAG, "refreshOne failed", e) }
        }
    }

    private fun one(c: Context, id: String): Script? {
        val v = p(c).getString("s:$id", null) ?: return null
        return try {
            val o = JSONObject(v); val code = o.optString("c")
            Script(id, code, o.optBoolean("on", true), parse(code) ?: Meta(emptyMap()), o.optString("u"), o.optLong("t"))
        } catch (_: Exception) { null }
    }

    // menu commands: per tab WebView, cleared by the script itself at every page start (top frame)
    class Cmd(val sid: String, val id: Int, val name: String)
    private val cmds = java.util.WeakHashMap<WebView, ArrayList<Cmd>>()
    private val opens = HashMap<String, ArrayList<Long>>() // rate limit for tab / clipboard / toast per script

    private fun allowRate(sid: String): Boolean {
        val now = System.currentTimeMillis()
        val l = opens.getOrPut(sid) { ArrayList() }
        l.removeAll { now - it > 10_000 }
        if (l.size >= 5) return false
        l.add(now); return true
    }

    /** Tap in the page sheet: runs the callback in the page (main frame only). */
    fun runCmd(web: WebView, sid: String, id: Int) {
        val e = evtName(sid)
        web.evaluateJavascript("(function(){try{var f=window['$e'];if(typeof f==='function')f($id)}catch(x){}})()", null)
    }

    private val listener = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
            try { onMsg(view, message.data, isMainFrame) } catch (e: Exception) { Log.e(TAG, "msg failed", e) }
        }
    }

    private fun onMsg(view: WebView, data: String?, isMain: Boolean) {
        if (data == null || data.length > MAX_VALS + 5000) return
        val m = try { JSONObject(data) } catch (_: Exception) { return }
        val sid = m.optString("s")
        val c = view.context.applicationContext
        ContentInjector.init(c)
        if (m.optString("t") != ContentInjector.tokenFor("us:$sid")) return // not our shim
        if (!masterOn(c)) return
        val s = one(c, sid) ?: return
        if (!s.on) return
        val a = m.optString("a")
        val args = m.optJSONArray("p") ?: JSONArray()
        when (a) {
            "set" -> {
                if (!can(s, "setValue")) return
                val k = args.optString(0); val v = args.optString(1)
                if (k.isEmpty() || k.length > 200) return
                val mp = valsOf(c, sid)
                synchronized(vals) {
                    val n = valSize(mp) - (mp[k]?.let { k.length + it.length } ?: 0) + k.length + v.length
                    if (n > MAX_VALS) { Util.toast(c, s.name + ": stored values are full"); return }
                    mp[k] = v
                }
                markDirty(c, sid)
            }
            "del" -> {
                if (!can(s, "deleteValue")) return
                val k = args.optString(0)
                val mp = valsOf(c, sid)
                if (synchronized(vals) { mp.remove(k) } != null) markDirty(c, sid)
            }
            "clip" -> {
                if (!can(s, "setClipboard") || !allowRate(sid)) return
                val t = args.optString(0)
                if (t.length > 100_000) return
                (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", t))
                Util.toast(c, "Copied")
            }
            "open" -> {
                if (!can(s, "openInTab") || !allowRate(sid)) return
                val u = args.optString(0)
                val sch = try { Uri.parse(u).scheme?.lowercase() } catch (_: Exception) { null }
                if (sch != "http" && sch != "https") return // never file: / intent: / javascript:
                val tm = BookmarksScreen.tabsRef ?: return
                val src = tm.all().firstOrNull { it.web === view }
                tm.newTab(u, src?.id ?: 0, args.optBoolean(1, true), src?.priv == true)
            }
            "toast" -> {
                if (!can(s, "notification") || !allowRate(sid)) return
                val t = args.optString(0); val x = args.optString(1)
                Util.toast(c, (if (t.isEmpty()) x else if (x.isEmpty()) t else "$t: $x").take(160))
            }
            "xhr" -> {
                if (!isMain || !can(s, "xmlhttpRequest")) return
                val r = try { JSONObject(args.optString(1)) } catch (_: Exception) { return }
                startXhr(view, c, s, args.optInt(0), r)
            }
            "xhrabort" -> { val k = sid + ":" + args.optInt(0); aborted.add(k); aborts[k]?.let { try { it.disconnect() } catch (_: Exception) { } } }
            "cmd", "cmdclear", "cmdoff" -> {
                if (!isMain || !can(s, "registerMenuCommand")) return
                synchronized(cmds) {
                    val l = cmds.getOrPut(view) { ArrayList() }
                    when (a) {
                        "cmdclear" -> l.removeAll { it.sid == sid }
                        "cmdoff" -> l.removeAll { it.sid == sid && it.id == args.optInt(0) }
                        else -> if (l.size < 30 && l.count { it.sid == sid } < 10) l.add(Cmd(sid, args.optInt(0), args.optString(1).take(60))) else Unit
                    }
                    Unit
                }
            }
        }
    }

    private val SHIM = """
var __T='%T%',__S='%S%',__G=%G%,__M=%MAX%,__P=null,__info=%INFO%;
try{__P=window.MGBUS.postMessage.bind(window.MGBUS)}catch(e){}
function __send(a,p){try{if(__P)__P(JSON.stringify({t:__T,s:__S,a:a,p:p}))}catch(e){}}
var __V=Object.create(null),__sz=0;
%V%.forEach(function(q){__V[q[0]]=q[1];__sz+=q[0].length+q[1].length});
function __has(k){return k in __V}
function __del(k){k=String(k);if(__has(k)){__sz-=k.length+__V[k].length;delete __V[k];__send('del',[k])}}
function __get(k,d){k=String(k);if(__has(k)){try{return JSON.parse(__V[k])}catch(e){}}return d}
function __set(k,v){k=String(k);var s;if(v===undefined)return __del(k);try{s=JSON.stringify(v)}catch(e){return}if(s===undefined)return __del(k);var n=__sz-(__has(k)?k.length+__V[k].length:0)+k.length+s.length;if(n>__M||k.length>200)return;__sz=n;__V[k]=s;__send('set',[k,s])}
function __list(){return Object.keys(__V)}
function __style(css){var s=document.createElement('style');s.textContent=String(css);var a=function(){var r=document.head||document.documentElement;if(!r)return false;r.appendChild(s);return true};if(!a()){var o=new MutationObserver(function(){if(a())o.disconnect()});o.observe(document,{childList:true,subtree:true})}return s}
function __clip(t){__send('clip',[String(t)])}
function __open(u,o){try{u=new URL(u,location.href).href}catch(e){}var sel=!(o===true||(o&&typeof o==='object'&&(o.active===false||o.loadInBackground===true)));__send('open',[String(u),sel]);return{closed:false,close:function(){},onclose:null}}
function __note(a,b){var o=(a&&typeof a==='object')?a:{text:a,title:b};__send('toast',[String(o.title||''),String(o.text||'')])}
var __C={},__ci=0;
if(window===window.top&&(__G.indexOf('GM_registerMenuCommand')>=0||__G.indexOf('GM.registerMenuCommand')>=0)){try{Object.defineProperty(window,'%E%',{value:function(i){var f=__C[i];if(typeof f==='function'){try{f()}catch(e){try{console.error('[userscript]',e)}catch(_){}}}},configurable:true});__send('cmdclear',[])}catch(e){}}
function __reg(n,f){if(window!==window.top||typeof f!=='function')return;var i=++__ci;__C[i]=f;__send('cmd',[i,String(n)]);return i}
function __unreg(i){if(__C[i]){delete __C[i];__send('cmdoff',[i])}}
var __R=%RES%;
function __rtext(n){if(!Object.prototype.hasOwnProperty.call(__R,n))return undefined;var b=__R[n][1];try{return decodeURIComponent(escape(atob(b)))}catch(e){try{return atob(b)}catch(_){return undefined}}}
function __rurl(n){if(!Object.prototype.hasOwnProperty.call(__R,n))return undefined;return 'data:'+__R[n][0]+';base64,'+__R[n][1]}
var __X={},__xi=0;
function __xdone(i,r){var d=__X[i];if(!d)return;delete __X[i];var ev=r.ev,o={readyState:4,status:r.status||0,statusText:r.statusText||'',responseHeaders:r.headers||'',finalUrl:r.url||d.url,context:d.context,responseText:'',response:null};
if(ev==='load'){var rt=d.responseType||'';if(rt==='arraybuffer'||rt==='blob'){try{var bs=atob(r.b64||''),u=new Uint8Array(bs.length);for(var k=0;k<bs.length;k++)u[k]=bs.charCodeAt(k);o.response=rt==='blob'?new Blob([u],{type:r.type||''}):u.buffer}catch(e){}}else{o.responseText=r.text||'';o.response=o.responseText;if(rt==='json'){try{o.response=JSON.parse(o.responseText)}catch(e){o.response=null}}}}else o.error=r.err||ev;
var cb=ev==='load'?d.onload:ev==='timeout'?d.ontimeout:ev==='abort'?d.onabort:d.onerror;
try{if(d.onreadystatechange)d.onreadystatechange(o)}catch(e){}
try{if(cb)cb(o)}catch(e){try{console.error('[userscript]',e)}catch(_){}}}
if(window===window.top&&__G.indexOf('GM_xmlhttpRequest')>=0){try{Object.defineProperty(window,'%R%',{value:function(i,r){__xdone(i,r)}})}catch(e){}}
function __xhr(d){d=d||{};if(window!==window.top){try{if(d.onerror)d.onerror({error:'GM_xmlhttpRequest works in the top frame only'})}catch(e){}return{abort:function(){}}}
var u=d.url;try{u=new URL(u,location.href).href}catch(e){}var i=++__xi;__X[i]=d;var h={};if(d.headers)for(var k in d.headers)h[k]=String(d.headers[k]);
__send('xhr',[i,JSON.stringify({m:d.method||'GET',u:String(u),h:h,d:typeof d.data==='string'?d.data:null,to:d.timeout||0,rt:d.responseType||'',an:!!d.anonymous})]);
return{abort:function(){if(__X[i]){__send('xhrabort',[i]);__xdone(i,{ev:'abort'})}}}}
function __g(n,f){return(__G.indexOf('GM_'+n)>=0||__G.indexOf('GM.'+n)>=0)?f:undefined}
function __pr(f){return function(){var a=arguments;return new Promise(function(r,j){try{r(f.apply(null,a))}catch(e){j(e)}})}}
var __GM={info:__info};
[['getValue',__get],['setValue',__set],['deleteValue',__del],['listValues',__list],['addStyle',__style],['setClipboard',__clip],['openInTab',__open],['notification',__note],['registerMenuCommand',__reg],['unregisterMenuCommand',__unreg],['getResourceText',__rtext],['getResourceURL',__rurl]].forEach(function(q){if(__g(q[0],1))__GM[q[0]]=__pr(q[1])});
if(__GM.getResourceURL)__GM.getResourceUrl=__GM.getResourceURL;
if(__g('xmlhttpRequest',1))__GM.xmlHttpRequest=function(d){return new Promise(function(res,rej){var o={};for(var k in d)o[k]=d[k];o.onload=function(r){res(r)};o.onerror=o.ontimeout=o.onabort=function(r){rej(r)};__xhr(o)})};
"""

    private fun shim(c: Context, s: Script, g: Set<String>): String {
        val m = s.meta
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val hdr = Regex("//\\s*==UserScript==[\\s\\S]*?//\\s*==/UserScript==").find(s.code)?.value ?: ""
        val sc = JSONObject().put("name", m.first("name")).put("namespace", m.first("namespace")).put("version", m.first("version"))
            .put("description", m.first("description")).put("author", m.first("author"))
            .put("matches", JSONArray(m.all("match"))).put("includes", JSONArray(m.all("include")))
            .put("excludes", JSONArray(m.all("exclude") + m.all("exclude-match"))).put("runAt", m.first("run-at").ifEmpty { "document-idle" })
            .put("grant", JSONArray(g.toList())).put("noframes", m.has("noframes"))
        val info = JSONObject().put("script", sc).put("scriptMetaStr", hdr).put("scriptHandler", "MGB").put("version", ver).put("scriptWillUpdate", false)
        // the values snapshot is only needed when the script can read them
        val pairs = JSONArray()
        if (can(s, "getValue") || can(s, "listValues")) for ((k, v) in valsOf(c, s.id)) pairs.put(JSONArray().put(k).put(v))
        val res = JSONObject()
        if (can(s, "getResourceText") || can(s, "getResourceURL")) for (x in deps(c, s.id).x) if (x[2].isNotEmpty()) res.put(x[0], JSONArray().put(x[1]).put(x[2]))
        return SHIM.replace("%RES%", jsSafe(res.toString())).replace("%R%", rspName(s.id)).replace("%T%", ContentInjector.tokenFor("us:" + s.id)).replace("%S%", s.id).replace("%G%", jsSafe(JSONArray(g.toList()).toString()))
            .replace("%MAX%", MAX_VALS.toString()).replace("%INFO%", jsSafe(info.toString())).replace("%V%", jsSafe(pairs.toString())).replace("%E%", evtName(s.id))
    }

    // ---------- T10: network + @require / @resource ----------
    private const val MAX_DEP = 500_000
    private const val MAX_RESP = 5_000_000
    private const val MAX_BODY = 1_000_000
    private val exec = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val aborts = java.util.concurrent.ConcurrentHashMap<String, java.net.HttpURLConnection>()
    private val aborted: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val asking = HashMap<String, ArrayList<(Boolean) -> Unit>>()

    private fun rspName(sid: String) = "__x" + ContentInjector.tokenFor("us:$sid").take(24)

    /** Per script network permissions, prefs key "n:<id>": {all, ok[], no[], log[[host, time]]} */
    class Net(var all: Boolean, val ok: LinkedHashSet<String>, val no: LinkedHashSet<String>, val log: ArrayList<Pair<String, Long>>)

    fun net(c: Context, id: String): Net {
        val n = Net(false, LinkedHashSet(), LinkedHashSet(), ArrayList())
        try {
            val o = JSONObject(p(c).getString("n:$id", "{}") ?: "{}")
            n.all = o.optBoolean("all")
            o.optJSONArray("ok")?.let { for (i in 0 until it.length()) n.ok.add(it.optString(i)) }
            o.optJSONArray("no")?.let { for (i in 0 until it.length()) n.no.add(it.optString(i)) }
            o.optJSONArray("log")?.let { for (i in 0 until it.length()) it.optJSONArray(i)?.let { x -> n.log.add(Pair(x.optString(0), x.optLong(1))) } }
        } catch (_: Exception) { }
        return n
    }

    fun saveNet(c: Context, id: String, n: Net) {
        val o = JSONObject().put("all", n.all).put("ok", JSONArray(n.ok.toList())).put("no", JSONArray(n.no.toList()))
        val lg = JSONArray(); for ((h, t) in n.log) lg.put(JSONArray().put(h).put(t))
        p(c).edit().putString("n:$id", o.put("log", lg).toString()).apply()
    }

    private fun hostIn(set: Collection<String>, host: String) = set.any { host == it || host.endsWith(".$it") }

    /** 1 = allowed, 0 = ask, -1 = denied earlier. @connect "*" never allows by itself. */
    private fun hostState(s: Script, n: Net, host: String, pageHost: String): Int {
        if (host == pageHost || n.all) return 1
        val con = s.meta.all("connect").map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "*" }
        if (con.any { (it == "self" && host == pageHost) || host == it || host.endsWith(".$it") }) return 1
        if (hostIn(n.ok, host)) return 1
        if (hostIn(n.no, host)) return -1
        return 0
    }

    private fun logHost(c: Context, id: String, host: String) {
        val n = net(c, id)
        n.log.removeAll { it.first == host }
        n.log.add(0, Pair(host, System.currentTimeMillis()))
        while (n.log.size > 15) n.log.removeAt(n.log.size - 1)
        saveNet(c, id, n)
    }

    // ----- cached @require / @resource: prefs "r:<id>" {qu[], q[], xu[], x[[name, mime, base64]]}
    class Deps(val qu: List<String>, val q: List<String>, val xu: List<String>, val x: List<Array<String>>)

    fun deps(c: Context, id: String): Deps {
        try {
            val o = JSONObject(p(c).getString("r:$id", null) ?: return Deps(emptyList(), emptyList(), emptyList(), emptyList()))
            fun sl(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
            val xs = o.optJSONArray("x")
            val x = if (xs == null) emptyList() else (0 until xs.length()).mapNotNull { i -> xs.optJSONArray(i)?.let { arrayOf(it.optString(0), it.optString(1), it.optString(2)) } }
            return Deps(sl(o.optJSONArray("qu")), sl(o.optJSONArray("q")), sl(o.optJSONArray("xu")), x)
        } catch (_: Exception) { return Deps(emptyList(), emptyList(), emptyList(), emptyList()) }
    }

    private fun httpsOf(u: String): String = u.trim().let { if (it.startsWith("//")) "https:$it" else it }.substringBefore('#')

    private fun wantedReq(m: Meta) = m.all("require").map { httpsOf(it) }.filter { it.startsWith("https://") }.take(5)
    private fun wantedRes(m: Meta): List<Pair<String, String>> = m.all("resource").mapNotNull { l ->
        val parts = l.trim().split(Regex("\\s+"), 2)
        if (parts.size == 2 && httpsOf(parts[1]).startsWith("https://")) Pair(parts[0], httpsOf(parts[1])) else null
    }.take(10)

    private fun getBytes(url: String): Pair<ByteArray, String>? = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
        if (c.responseCode != 200) null else {
            val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var ok = true
            c.inputStream.use { ins -> while (true) { val n = ins.read(buf); if (n < 0) break; bo.write(buf, 0, n); if (bo.size() > MAX_DEP) { ok = false; break } } }
            if (ok) Pair(bo.toByteArray(), (c.contentType ?: "").substringBefore(';').trim()) else null
        }
    } catch (_: Exception) { null }

    /** Background download of @require / @resource (https only, 500 KB each). Rebuilds that script's handler when done. */
    fun fetchDeps(c: Context, id: String, meta: Meta, force: Boolean) {
        val rq = wantedReq(meta); val rs = wantedRes(meta)
        val app = c.applicationContext
        if (rq.isEmpty() && rs.isEmpty()) { if (p(app).contains("r:$id")) p(app).edit().remove("r:$id").apply(); return }
        val cur = deps(app, id)
        if (!force && cur.qu == rq && cur.xu == rs.map { it.second } && cur.q.size == rq.size) return
        Thread {
            val o = JSONObject(); val q = JSONArray(); val x = JSONArray(); var fail = 0
            for (u in rq) { val b = getBytes(u); if (b == null) { fail++; q.put("") } else q.put(String(b.first, Charsets.UTF_8)) }
            for ((nm, u) in rs) { val b = getBytes(u); if (b == null) { fail++; x.put(JSONArray().put(nm).put("").put("")) } else x.put(JSONArray().put(nm).put(b.second.ifEmpty { "application/octet-stream" }).put(android.util.Base64.encodeToString(b.first, android.util.Base64.NO_WRAP))) }
            o.put("qu", JSONArray(rq)).put("q", q).put("xu", JSONArray(rs.map { it.second })).put("x", x)
            p(app).edit().putString("r:$id", o.toString()).apply()
            ui.post {
                refreshOne(app, id)
                if (fail > 0) Util.toast(app, "$fail library file(s) could not be downloaded")
            }
        }.start()
    }

    private fun xReply(view: WebView, sid: String, i: Int, r: JSONObject) {
        val js = "(function(){try{var f=window['" + rspName(sid) + "'];if(typeof f==='function')f(" + i + "," + jsSafe(r.toString()) + ")}catch(x){}})()"
        ui.post { try { view.evaluateJavascript(js, null) } catch (_: Exception) { } }
    }

    private fun xErr(view: WebView, sid: String, i: Int, ev: String, msg: String) = xReply(view, sid, i, JSONObject().put("ev", ev).put("err", msg))

    private fun startXhr(view: WebView, c: Context, s: Script, i: Int, r: JSONObject) {
        val url = r.optString("u")
        val uri = try { Uri.parse(url) } catch (_: Exception) { null }
        val sch = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase() ?: ""
        if ((sch != "http" && sch != "https") || host.isEmpty()) { xErr(view, s.id, i, "error", "Bad address"); return }
        val pageHost = try { Uri.parse(view.url ?: "").host?.lowercase() ?: "" } catch (_: Exception) { "" }
        val priv = BookmarksScreen.tabsRef?.all()?.firstOrNull { it.web === view }?.priv == true
        val ua = try { view.settings.userAgentString } catch (_: Exception) { "" }
        val cnt = inflight.getOrPut(s.id) { java.util.concurrent.atomic.AtomicInteger() }
        if (cnt.get() >= 4) { xErr(view, s.id, i, "error", "Too many requests at once"); return }
        val go = {
            cnt.incrementAndGet()
            exec.execute { try { doXhr(view, c, s, i, r, pageHost, priv, ua) } finally { cnt.decrementAndGet() } }
        }
        val n = net(c, s.id)
        when (hostState(s, n, host, pageHost)) {
            1 -> go()
            -1 -> xErr(view, s.id, i, "error", "Blocked: you denied $host for this script")
            else -> {
                val act = view.context as? Activity
                if (act == null) { xErr(view, s.id, i, "error", "No permission screen"); return }
                val key = s.id + "|" + host
                val q = asking.getOrPut(key) { ArrayList() }
                q.add { ok -> if (ok) go() else xErr(view, s.id, i, "error", "Blocked: $host was denied") }
                if (q.size > 1) return
                fun done(mode: Int) { // 0 deny, 1 host, 2 all
                    val nn = net(c, s.id)
                    when (mode) { 1 -> { nn.ok.add(host); nn.no.remove(host) }; 2 -> nn.all = true; else -> nn.no.add(host) }
                    saveNet(c, s.id, nn)
                    val l = asking.remove(key) ?: return
                    for (f in l) f(mode != 0)
                }
                MaterialAlertDialogBuilder(act).setTitle("Allow network access?")
                    .setMessage(s.name + " wants to connect to\n" + host + "\n\nPage: " + pageHost + "\nIt can send data there and read the answer. Page cookies are only sent to the page's own site.")
                    .setCancelable(false)
                    .setPositiveButton("Allow this host") { _, _ -> done(1) }
                    .setNeutralButton("Allow all hosts") { _, _ -> done(2) }
                    .setNegativeButton("Deny") { _, _ -> done(0) }.show()
            }
        }
    }

    private fun doXhr(view: WebView, c: Context, s: Script, i: Int, r: JSONObject, pageHost: String, priv: Boolean, ua: String) {
        val key = s.id + ":" + i
        var cur = r.optString("u")
        var method = r.optString("m", "GET").uppercase().let { if (it == "POST" || it == "HEAD") it else "GET" }
        var body: ByteArray? = if (method == "POST" && !r.isNull("d")) r.optString("d").toByteArray(Charsets.UTF_8) else null
        if (body != null && body.size > MAX_BODY) { xErr(view, s.id, i, "error", "Request body too big (max 1 MB)"); return }
        val to = r.optInt("to", 0).let { if (it <= 0) 30000 else minOf(it, 60000) }
        val rt = r.optString("rt")
        val anon = r.optBoolean("an")
        val hdr = r.optJSONObject("h")
        val sn = net(c, s.id)
        try {
            var hops = 0
            lateinit var conn: java.net.HttpURLConnection
            while (true) {
                val u = java.net.URL(cur)
                val h = u.host.lowercase()
                if (hops > 0 && hostState(s, net(c, s.id), h, pageHost) != 1) { xErr(view, s.id, i, "error", "Redirect to $h is not allowed"); return }
                if (hops == 0) logHost(c, s.id, h)
                conn = u.openConnection() as java.net.HttpURLConnection
                aborts[key] = conn
                conn.instanceFollowRedirects = false; conn.connectTimeout = to; conn.readTimeout = to; conn.requestMethod = method
                if (ua.isNotEmpty()) conn.setRequestProperty("User-Agent", ua)
                if (hdr != null) for (k in hdr.keys()) {
                    val kl = k.lowercase(); val v = hdr.optString(k)
                    if (kl in setOf("host", "content-length", "cookie", "connection", "transfer-encoding") || k.any { it == '\r' || it == '\n' } || v.any { it == '\r' || it == '\n' }) continue
                    conn.setRequestProperty(k, v)
                }
                if (!priv && !anon && (h == pageHost || sn.all)) android.webkit.CookieManager.getInstance().getCookie(cur)?.let { conn.setRequestProperty("Cookie", it) }
                if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body!!) } }
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308) && hops < 5) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc == null) { xErr(view, s.id, i, "error", "Bad redirect"); return }
                    cur = java.net.URL(u, loc).toString()
                    if (!cur.startsWith("http://") && !cur.startsWith("https://")) { xErr(view, s.id, i, "error", "Bad redirect"); return }
                    if (code == 303 || ((code == 301 || code == 302) && method == "POST")) { method = if (method == "HEAD") "HEAD" else "GET"; body = null }
                    hops++
                    continue
                }
                break
            }
            val code = conn.responseCode
            val stream = if (method == "HEAD") null else (if (code >= 400) conn.errorStream else conn.inputStream)
            val bo = java.io.ByteArrayOutputStream()
            if (stream != null) stream.use { ins ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bo.write(buf, 0, n)
                    if (bo.size() > MAX_RESP) { xErr(view, s.id, i, "error", "Response too big (max 5 MB)"); return }
                }
            }
            val hs = StringBuilder()
            for ((k, vs) in conn.headerFields) if (k != null) for (v in vs) hs.append(k).append(": ").append(v).append("\r\n")
            val ct = conn.contentType ?: ""
            val out = JSONObject().put("ev", "load").put("status", code).put("statusText", conn.responseMessage ?: "").put("headers", hs.toString()).put("url", cur).put("type", ct)
            if (rt == "arraybuffer" || rt == "blob") out.put("b64", android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP))
            else {
                val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
                out.put("text", try { String(bo.toByteArray(), if (cs != null) java.nio.charset.Charset.forName(cs) else Charsets.UTF_8) } catch (_: Exception) { String(bo.toByteArray(), Charsets.UTF_8) })
            }
            xReply(view, s.id, i, out)
        } catch (e: java.net.SocketTimeoutException) {
            xErr(view, s.id, i, "timeout", "Timeout")
        } catch (e: Exception) {
            if (aborted.remove(key)) xErr(view, s.id, i, "abort", "Aborted") else xErr(view, s.id, i, "error", "Network error")
        } finally { aborts.remove(key) }
    }

    /** Detail screen: libraries + network permissions of one script (tap an entry to revoke it). */
    fun netDialog(act: Activity, s: Script) {
        val n = net(act, s.id); val d = deps(act, s.id)
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        val kb = (d.q.sumOf { it.length } + d.x.sumOf { it[2].length * 3 / 4 }) / 1024
        labels.add("Libraries: ${wantedReq(s.meta).size} @require, ${wantedRes(s.meta).size} @resource, $kb KB (tap: download again)")
        acts.add { fetchDeps(act, s.id, s.meta, true); Util.toast(act, "Downloading...") }
        labels.add("Allow all hosts: " + (if (n.all) "ON (tap to turn off)" else "OFF"))
        acts.add { n.all = !n.all; saveNet(act, s.id, n); Util.toast(act, if (n.all) "Allowed for all hosts" else "Back to asking") }
        for (h in n.ok) { labels.add("Allowed: $h (tap to revoke)"); acts.add { n.ok.remove(h); saveNet(act, s.id, n); Util.toast(act, "Revoked") } }
        for (h in n.no) { labels.add("Denied: $h (tap to forget)"); acts.add { n.no.remove(h); saveNet(act, s.id, n); Util.toast(act, "Will ask again") } }
        val con = s.meta.all("connect")
        if (con.isNotEmpty()) { labels.add("@connect: " + con.take(8).joinToString(", ")); acts.add { } }
        for ((h, t) in n.log.take(8)) { labels.add("Recent: $h, " + ((System.currentTimeMillis() - t) / 60000) + " min ago"); acts.add { } }
        MaterialAlertDialogBuilder(act).setTitle("Network and libraries").setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("Close", null).show()
    }

    // ---------- temporary UI (T8 replaces it with a screen) ----------
    fun clip(act: Activity): String = try {
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

    // ---------- T8: download, install prompt, update check, page sheet ----------
    const val TEMPLATE = "// ==UserScript==\n// @name         New script\n// @namespace    mgb\n// @version      0.1\n// @description  What it does\n// @match        https://example.com/*\n// @run-at       document-idle\n// @grant        none\n// ==/UserScript==\n\n(function () {\n  'use strict';\n  // your code here\n})();\n"

    fun isUserJs(url: String): Boolean = try {
        val u = android.net.Uri.parse(url); u.scheme == "https" && (u.path ?: "").lowercase().endsWith(".user.js")
    } catch (_: Exception) { false }

    /** https only, 300 KB. [done] (text, error) runs on a background thread. */
    fun fetch(url: String, done: (String?, String?) -> Unit) {
        Thread {
            try {
                if (!url.startsWith("https://")) { done(null, "Use an https address"); return@Thread }
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000; c.instanceFollowRedirects = true
                if (c.responseCode != 200) { done(null, "Server said " + c.responseCode); return@Thread }
                val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                c.inputStream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        bo.write(buf, 0, n)
                        if (bo.size() > MAX_ONE) { done(null, "Too big (max 300 KB)"); return@Thread }
                    }
                }
                done(bo.toString("UTF-8"), null)
            } catch (e: Exception) { done(null, "Could not download") }
        }.start()
    }

    fun promptInstall(act: Activity, code: String, url: String, changed: () -> Unit = {}) {
        val meta = parse(code)
        if (meta == null) { Util.toast(act, "No ==UserScript== header found"); return }
        val old = all(act).firstOrNull { it.id == idOf(meta) }
        val pats = meta.all("match") + meta.all("include")
        val msg = StringBuilder(meta.first("name").ifEmpty { "Unnamed script" })
        if (meta.first("version").isNotEmpty()) msg.append(" ").append(meta.first("version"))
        if (old != null) msg.append("\nInstalled: ").append(old.meta.first("version").ifEmpty { "no version" })
        msg.append("\n\nRuns on:\n").append(if (pats.isEmpty()) "(no pattern: it would never run)" else pats.take(6).joinToString("\n"))
        if (pats.size > 6) msg.append("\n...")
        val con = meta.all("connect")
        if (con.isNotEmpty()) msg.append("\n\nNetwork: ").append(con.take(6).joinToString(", ")).append(if (con.any { it.trim() == "*" }) " (asks for every new host)" else "")
        val libs = wantedReq(meta).size + wantedRes(meta).size
        if (libs > 0) msg.append("\nDownloads ").append(libs).append(" file(s) from the web")
        msg.append("\n\nOnly install scripts you trust. A script can read and change every page it matches.")
        MaterialAlertDialogBuilder(act).setTitle(if (old != null) "Update userscript?" else "Install userscript?").setMessage(msg.toString())
            .setPositiveButton(if (old != null) "Update" else "Install") { _, _ ->
                val e = add(act, code, url)
                Util.toast(act, e ?: "Saved. Reload the page")
                if (e == null) changed()
            }.setNegativeButton("Cancel", null).show()
    }

    /** A tab opened a https .user.js link: download it, then ask. */
    fun offerInstall(act: Activity, url: String) {
        Util.toast(act, "Userscript found...")
        fetch(url) { text, err -> act.runOnUiThread { if (text == null) Util.toast(act, err ?: "Failed") else promptInstall(act, text, url) } }
    }

    fun checkUpdate(act: Activity, s: Script, changed: () -> Unit) {
        val u = listOf(s.meta.first("downloadurl"), s.url, s.meta.first("updateurl")).firstOrNull { it.isNotEmpty() && !it.endsWith(".meta.js") }
        if (u == null) { Util.toast(act, "No update address in this script"); return }
        Util.toast(act, "Checking...")
        fetch(u) { text, err ->
            act.runOnUiThread {
                if (text == null) Util.toast(act, err ?: "Failed")
                else if (parse(text) == null) Util.toast(act, "Not a userscript")
                else if (text == s.code) { fetchDeps(act, s.id, s.meta, true); Util.toast(act, "Up to date") }
                else promptInstall(act, text, u, changed)
            }
        }
    }

    /** Page menu row: the scripts that match this page with a switch each. */
    fun pageSheet(act: Activity, url: String) {
        val ms = matchingNow(act, url)
        val web = BookmarksScreen.tabsRef?.active?.web // T9: menu commands of this tab
        val items = if (ms.isEmpty()) listOf(ToolsSheet.Item(R.drawable.ic_info, "No script matches this page", "") { })
        else ms.map { s -> ToolsSheet.Item(R.drawable.ic_code, s.name, if (s.on) "ON" else "OFF") {
            setOn(act, s.id, !s.on); refreshAll(act); Util.toast(act, if (s.on) "Turned off. Reload the page" else "Turned on. Reload the page")
        } }
        val groups = ArrayList<ToolsSheet.Group>()
        groups.add(ToolsSheet.Group("On this page", items))
        if (web != null) for (s in ms) {
            if (!s.on) continue
            val cs = synchronized(cmds) { cmds[web]?.filter { it.sid == s.id } } ?: continue
            if (cs.isEmpty()) continue
            groups.add(ToolsSheet.Group(s.name, cs.map { cm -> ToolsSheet.Item(R.drawable.ic_play, cm.name, "") { runCmd(web, s.id, cm.id) } }))
        }
        ToolsSheet.show(act, "Userscripts", Util.host(url), groups + listOf(
            ToolsSheet.Group("Manage", listOf(
                ToolsSheet.Item(R.drawable.ic_list, "Manage userscripts", "${count(act)} scripts") { UserscriptsScreen.open(act) }
            ))
        ))
    }
}
