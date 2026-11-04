package com.jabra.mgb

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * S5: injects the V77 extension scripts into every page, like manifest "content_scripts".
 *  - reads assets/ext/manifest.json (V77 as is) and builds one document-start script per entry
 *  - world MAIN  -> plain scripts (hook.js ...); default world -> content.js etc. run in one private
 *    function scope that receives the `chrome` shim as a parameter (the page never sees `chrome`)
 *  - all frames / run_at (start, end, idle) / match patterns (turned into origin rules) are honoured
 *  - attachExt(): for extension pages (popup, queue ...) served from EXT_ORIGIN: defines window.chrome
 */
object ContentInjector {
    const val EXT_ORIGIN = "https://appassets.androidplatform.net"
    const val EXT_BASE = "$EXT_ORIGIN/ext/"
    const val EXT_ID = "mgbextensionidmgbextensionid0"
    private const val TAG = "MGB.inject"

    lateinit var token: String
        private set

    /** S3: per-extension bridge token. V77 keeps the plain token; an installed extension gets a derived one, so it cannot speak as another. */
    fun tokenFor(id: String): String {
        if (id == EXT_ID) return token
        val d = java.security.MessageDigest.getInstance("SHA-256").digest((token + ":" + id).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private class Entry(val origins: Set<String>, val script: String)

    private var inited = false
    private var warned = false
    private lateinit var shim: String
    private val entries = ArrayList<Entry>()
    private var extScript = ""
    private var ytScript = "" // 0.0.105: YouTube ad blocker (shim/yt-adblock.js)
    private val YT_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com", "https://music.youtube.com")
    private var v77Cfg = "" // S4: cfg of the built-in extension pages (dispatcher in extPageScript)

    // S2: content scripts of installed extensions (files/exts/<id>/), cached per extension
    private class ExtCache(val sig: String, val entries: List<Entry>)
    private val extCache = HashMap<String, ExtCache>()

    private fun read(ctx: Context, path: String): String =
        ExtUpdater.readText(path) ?: ctx.assets.open(path).bufferedReader().use { it.readText() } // S16: hot ext files first

    @Synchronized fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        ExtUpdater.init(app)
        ExtRegistry.init(app) // S4: installed extensions must be known at start (content scripts, /ext/<id>/ files), not only after Settings > Extensions was opened
        ExtSafety.sweep(app) // S8: an installed manifest v2 / nativeMessaging extension (older install) is turned off
        ChromeBridge.init(app)
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        token = b.joinToString("") { "%02x".format(it) }
        shim = read(app, "shim/chrome-shim.js").trim().trimEnd(';')
        try { ytScript = app.assets.open("shim/yt-adblock.js").bufferedReader().use { it.readText() } } catch (ex: Exception) { Log.e(TAG, "yt-adblock.js missing", ex) }
        val manifest = JSONObject(read(app, "ext/manifest.json"))

        fun cfg(kind: String) = JSONObject()
            .put("token", token).put("kind", kind).put("base", EXT_BASE)
            .put("id", EXT_ID).put("manifest", manifest).toString()

        val cs = manifest.optJSONArray("content_scripts") ?: JSONArray()
        for (i in 0 until cs.length()) {
            try {
                val e = cs.getJSONObject(i)
                val files = e.getJSONArray("js")
                val src = StringBuilder()
                for (j in 0 until files.length()) {
                    val f = files.getString(j)
                    src.append("\n/* ").append(f).append(" */\n").append(read(app, "ext/$f")).append("\n;\n")
                }
                val origins = originsOf(e.optJSONArray("matches"))
                if (origins.isEmpty()) continue
                entries.add(
                    Entry(
                        origins,
                        wrap(
                            src.toString(), e.optString("world") == "MAIN",
                            e.optString("run_at", "document_idle"), e.optBoolean("all_frames", false), cfg("content")
                        )
                    )
                )
            } catch (ex: Exception) {
                Log.e(TAG, "content_scripts[$i] skipped", ex)
            }
        }
        v77Cfg = cfg("ext")
        extScript = "(function(){try{var c=" + shim + "(window," + cfg("ext") + ");" +
            "if(c)Object.defineProperty(window,'chrome',{value:c,configurable:true,writable:true});}catch(e){}})();"
    }

    private fun wrap(src: String, main: Boolean, runAt: String, allFrames: Boolean, cfgJson: String, pre: String = ""): String {
        val sb = StringBuilder("(function(){try{\n")
        if (!allFrames) sb.append("if(window.top!==window)return;\n")
        // S6: never run page scripts inside extension pages that are open as tabs
        sb.append("if(location.origin==='").append(EXT_ORIGIN).append("')return;\n")
        sb.append(pre) // S2: url filter + css of an installed extension (empty for V77)
        if (main) {
            sb.append("var __run=function(){try{\n").append(src).append("\n}catch(e){}};\n")
        } else {
            sb.append("var chrome=").append(shim).append("(window,").append(cfgJson).append(");if(!chrome)return;\n")
            sb.append("var __run=function(){try{(function(chrome){\n").append(src)
                .append("\n})(chrome);}catch(e){try{chrome.__mgbLog('content: '+(e&&e.stack||e));}catch(_){}}};\n")
        }
        when (runAt) {
            "document_start" -> sb.append("__run();\n")
            "document_end" -> sb.append("if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__run,{once:true});else __run();\n")
            else -> sb.append("var __go=function(){setTimeout(__run,0)};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__go,{once:true});else __go();\n")
        }
        return sb.append("}catch(e){}})();").toString()
    }

    /** S2: manifest match pattern -> JS regex source (full url check, the origin rule alone ignores the path). */
    private fun patRegex(p: String): String? {
        if (p == "<all_urls>") return "^https?://.*$"
        val m = PATTERN.find(p) ?: return null
        val scheme = if (m.groupValues[1] == "*") "https?" else m.groupValues[1]
        val host = m.groupValues[2]
        val path = m.groupValues[3].ifEmpty { "/*" }
        fun esc(t: String) = t.replace(Regex("[.+?^\${}()|\\[\\]\\\\]")) { "\\" + it.value }
        fun glob(t: String) = t.split("*").joinToString(".*") { esc(it) }
        val h = when {
            host == "*" -> "[^/]*"
            host.startsWith("*.") -> "([^/]*\\.)?" + esc(host.substring(2))
            else -> esc(host)
        }
        return "^" + scheme + "://" + h + glob(path) + "(#.*)?$"
    }

    private fun regexList(arr: JSONArray?): JSONArray {
        val out = JSONArray()
        if (arr != null) for (i in 0 until arr.length()) patRegex(arr.optString(i))?.let { out.put(it) }
        return out
    }

    private fun extFile(dir: File, rel: String): File? {
        if (rel.isBlank() || rel.contains("..")) return null
        val f = File(dir, rel.trimStart('/'))
        val ok = try { f.canonicalPath.startsWith(dir.canonicalPath + File.separator) } catch (e: Exception) { false }
        return if (ok && f.isFile && f.length() <= 2_000_000L) f else null
    }

    // 0.0.108: chrome.i18n messages (_locales) of an installed extension, cached per extension + manifest signature
    private val i18nCache = HashMap<String, Pair<String, JSONObject>>()

    private fun readMessages(dir: File, lang: String, into: JSONObject) {
        if (lang.isBlank() || lang.contains("..") || lang.contains('/') || lang.contains('\\')) return
        val f = extFile(dir, "_locales/$lang/messages.json") ?: return
        val j = try { JSONObject(f.readText(Charsets.UTF_8).removePrefix("\uFEFF")) } catch (_: Exception) { return }
        for (k in j.keys().asSequence().toList()) {
            val e = j.optJSONObject(k) ?: continue
            val o = JSONObject().put("m", e.optString("message", ""))
            val ph = e.optJSONObject("placeholders")
            if (ph != null) {
                val p = JSONObject()
                for (n in ph.keys().asSequence().toList()) p.put(n.lowercase(), ph.optJSONObject(n)?.optString("content", "") ?: "")
                o.put("p", p)
            }
            into.put(k.lowercase(), o)
        }
    }

    /** { key(lowercase): {m: message, p: {placeholder: content}} } : default_locale first, then en, then the phone language on top. */
    private fun messagesFor(id: String, dir: File, manifest: JSONObject): JSONObject {
        val mf = File(dir, "manifest.json")
        val sig = "${mf.lastModified()}:${mf.length()}:${java.util.Locale.getDefault()}"
        i18nCache[id]?.let { if (it.first == sig) return it.second }
        val out = JSONObject()
        try {
            if (File(dir, "_locales").isDirectory) {
                val loc = java.util.Locale.getDefault()
                val langs = LinkedHashSet<String>()
                val def = manifest.optString("default_locale", "")
                if (def.isNotEmpty()) langs.add(def)
                langs.add("en")
                langs.add(loc.language)
                langs.add(loc.language + "_" + loc.country)
                langs.add(loc.language + "-" + loc.country)
                for (l in langs) readMessages(dir, l, out)
                if (out.toString().length > 600_000) { Log.w(TAG, "$id: messages too big, i18n off"); out.keys().asSequence().toList().forEach { out.remove(it) } }
            }
        } catch (ex: Exception) { Log.e(TAG, "$id messages", ex) }
        i18nCache[id] = Pair(sig, out)
        return out
    }

    /** 0.0.108: css of an extension points to its files as chrome-extension://__MSG_@@extension_id__/x (or the real id) -> served url. */
    private fun fixExtUrls(id: String, css: String): String =
        css.replace("chrome-extension://__MSG_@@extension_id__/", EXT_BASE + id + "/").replace("chrome-extension://$id/", EXT_BASE + id + "/")

    private fun buildExt(id: String, dir: File, mf: File): List<Entry> {
        val manifest = JSONObject(mf.readText())
        val cs = manifest.optJSONArray("content_scripts") ?: return emptyList()
        val cfgJson = JSONObject().put("token", tokenFor(id)).put("kind", "content").put("base", EXT_BASE + id + "/")
            .put("id", id).put("manifest", manifest).put("messages", messagesFor(id, dir, manifest)).toString()
        val out = ArrayList<Entry>()
        for (i in 0 until cs.length()) {
            try {
                val e = cs.getJSONObject(i)
                val js = e.optJSONArray("js") ?: JSONArray()
                val css = e.optJSONArray("css") ?: JSONArray()
                val src = StringBuilder()
                for (j in 0 until js.length()) {
                    val f = extFile(dir, js.optString(j))
                    if (f == null) { Log.w(TAG, "$id: js missing/too big: ${js.optString(j)}"); continue }
                    src.append("\n/* ").append(js.optString(j)).append(" */\n").append(f.readText()).append("\n;\n")
                }
                val cssSrc = StringBuilder()
                for (j in 0 until css.length()) extFile(dir, css.optString(j))?.let { cssSrc.append(fixExtUrls(id, it.readText())).append("\n") }
                if (src.isEmpty() && cssSrc.isEmpty()) continue
                val matches = e.optJSONArray("matches")
                val origins = originsOf(matches)
                if (origins.isEmpty()) continue
                val pre = StringBuilder()
                pre.append("var __u=location.href;var __m=").append(regexList(matches)).append(";var __x=")
                    .append(regexList(e.optJSONArray("exclude_matches"))).append(";")
                    .append("if(!__m.some(function(r){return new RegExp(r).test(__u)})||__x.some(function(r){return new RegExp(r).test(__u)}))return;\n")
                if (cssSrc.isNotEmpty()) {
                    pre.append("try{var __s=document.createElement('style');__s.textContent=").append(JSONObject.quote(cssSrc.toString()))
                        .append(";(document.head||document.documentElement).appendChild(__s);}catch(_){}\n")
                }
                val main = e.optString("world") == "MAIN" || src.isEmpty()
                out.add(Entry(origins, wrap(src.toString(), main, e.optString("run_at", "document_idle"),
                    e.optBoolean("all_frames", false), cfgJson, pre.toString())))
            } catch (ex: Exception) {
                Log.e(TAG, "$id content_scripts[$i] skipped", ex)
            }
        }
        return out
    }

    /** S2: entries of every enabled extension. One broken extension never affects the others. */
    @Synchronized private fun extEntries(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val live = HashSet<String>()
        val all = try { ExtRegistry.enabled() } catch (e: Exception) { Log.e(TAG, "registry", e); return out }
        for (info in all) {
            val id = info.id
            try {
                if (!ExtRegistry.isValidId(id)) continue
                val dir = File(File(ctx.filesDir, "exts"), id)
                val mf = File(dir, "manifest.json")
                if (!mf.isFile) continue
                live.add(id)
                val sig = "${info.version}:${mf.lastModified()}:${mf.length()}:${dir.lastModified()}"
                val c = extCache[id]
                if (c != null && c.sig == sig) { out.addAll(c.entries); continue }
                val built = buildExt(id, dir, mf)
                extCache[id] = ExtCache(sig, built)
                out.addAll(built)
            } catch (ex: Exception) {
                Log.e(TAG, "ext $id skipped", ex)
            }
        }
        extCache.keys.retainAll(live)
        return out
    }

    private val PATTERN = Regex("^(\\*|https?)://([^/]*)(/.*)?$")

    /** manifest match patterns -> WebView origin rules (path part is ignored). */
    private fun originsOf(arr: JSONArray?): Set<String> {
        val out = LinkedHashSet<String>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val p = arr.getString(i)
            if (p == "<all_urls>") return setOf("*")
            val m = PATTERN.find(p) ?: continue
            val scheme = m.groupValues[1]
            val host = m.groupValues[2]
            if (host == "*") return setOf("*")
            val schemes = if (scheme == "*") listOf("http", "https") else listOf(scheme)
            for (s in schemes) out.add("$s://$host")
        }
        return out
    }

    private fun supported(): Boolean {
        val ok = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!ok && !warned) { warned = true; Log.e(TAG, "WebView too old: extension scripts disabled") }
        return ok
    }

    /** S4: true if url is allowed by manifest host patterns (<all_urls>, or a scheme + host + path pattern). Same matcher as content scripts. */
    fun urlAllowed(patterns: List<String>, url: String): Boolean {
        for (p in patterns) {
            if (p == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://")
            val rx = patRegex(p) ?: continue
            try { if (Regex(rx).containsMatchIn(url)) return true } catch (_: Exception) { }
        }
        return false
    }

    /**
     * S4: window.chrome for extension origin pages. One script, chosen at run time by the page path:
     * /ext/<id>/... of an enabled installed extension -> that extension's cfg (own token, id, base, manifest);
     * /ext/<id>/... of an unknown or disabled one -> no chrome at all; any other /ext/... page -> V77 cfg (as before).
     * Built when a WebView is attached (a toggle / install applies to windows and tabs opened afterwards).
     */
    private fun extPageScript(ctx: Context): String {
        val cfgs = JSONObject()
        try {
            for (info in ExtRegistry.enabled()) {
                try {
                    if (!ExtRegistry.isValidId(info.id)) continue
                    val mf = File(info.dir, "manifest.json")
                    if (!mf.isFile || mf.length() > 2_000_000L) continue
                    val man = JSONObject(mf.readText())
                    cfgs.put(info.id, JSONObject().put("token", tokenFor(info.id)).put("kind", "ext")
                        .put("base", EXT_BASE + info.id + "/").put("id", info.id).put("manifest", man)
                        .put("messages", messagesFor(info.id, info.dir, man))) // 0.0.108: chrome.i18n
                } catch (ex: Exception) { Log.e(TAG, "ext page cfg ${info.id} skipped", ex) }
            }
        } catch (ex: Exception) { Log.e(TAG, "registry", ex) }
        if (cfgs.length() == 0 && ExtRegistry.all().isEmpty()) return extScript
        return "(function(){try{var m=/^\\/ext\\/([a-p]{32})\\//.exec(location.pathname);var C=" + cfgs.toString() + ";var f;" +
            "if(m){f=C[m[1]];if(!f)return;}else{f=" + v77Cfg + ";}" +
            "var c=" + shim + "(window,f);" +
            "if(c)Object.defineProperty(window,'chrome',{value:c,configurable:true,writable:true});}catch(e){}})();"
    }

    /**
     * S16: scripting.executeScript(files) of an installed extension: "var chrome=...;" so the injected file can use chrome.*
     * (same cfg as its content scripts). null = no chrome (unknown / disabled / bad id).
     */
    fun injectPrelude(id: String): String? {
        return try {
            if (!ExtRegistry.isValidId(id)) return null
            val info = ExtRegistry.get(id) ?: return null
            val mf = File(info.dir, "manifest.json")
            if (!mf.isFile || mf.length() > 2_000_000L) return null
            val man = JSONObject(mf.readText())
            val cfg = JSONObject().put("token", tokenFor(id)).put("kind", "content").put("base", EXT_BASE + id + "/")
                .put("id", id).put("manifest", man).put("messages", messagesFor(id, info.dir, man)).toString()
            "var chrome=" + shim + "(window," + cfg + ");\n"
        } catch (e: Exception) { Log.e(TAG, "injectPrelude $id", e); null }
    }

    // 0.0.108: script handles of installed extensions per tab WebView, so install / enable / disable / remove / update
    // reach tabs that are already open (before, only tabs created afterwards saw a change)
    private val extHandles = java.util.WeakHashMap<WebView, ArrayList<ScriptHandler>>()

    private fun addScript(web: WebView, script: String, origins: Set<String>, keep: ArrayList<ScriptHandler>?) {
        val h = try {
            WebViewCompat.addDocumentStartJavaScript(web, script, origins)
        } catch (ex: Exception) {
            // an origin rule the WebView refuses (odd host pattern): the script has its own full url check, so "*" is safe
            if (origins.size == 1 && origins.first() == "*") throw ex
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        }
        keep?.add(h)
    }

    private fun addExtScripts(ctx: Context, web: WebView) {
        val keep = ArrayList<ScriptHandler>()
        // S2: installed extensions (a failing entry is skipped, others still run)
        for (e in extEntries(ctx)) {
            try { addScript(web, e.script, e.origins, keep) } catch (ex: Exception) { Log.e(TAG, "ext entry failed", ex) }
        }
        // S6: extension pages opened as tabs (tabs.create) get window.chrome too
        try { addScript(web, extPageScript(ctx), setOf(EXT_ORIGIN), keep) } catch (ex: Exception) { Log.e(TAG, "ext page script failed", ex) }
        synchronized(extHandles) { extHandles[web] = keep }
    }

    /** 0.0.108: call (UI thread) after an extension was added / removed / switched / updated: open tabs get the new scripts on their next page load. */
    fun refresh(ctx: Context) {
        if (!inited || !supported()) return
        val webs = synchronized(extHandles) { extHandles.keys.toList() }
        for (w in webs) {
            try {
                val old = synchronized(extHandles) { extHandles.remove(w) } ?: continue
                for (h in old) try { h.remove() } catch (_: Exception) { }
                addExtScripts(ctx, w)
            } catch (ex: Exception) { Log.e(TAG, "refresh failed", ex) }
        }
    }

    /** Browser tab WebView: call before the first loadUrl. */
    fun attach(ctx: Context, web: WebView) {
        init(ctx)
        if (!supported()) return
        try {
            ChromeBridge.install(web, setOf("*"))
            if (ytScript.isNotEmpty()) try { WebViewCompat.addDocumentStartJavaScript(web, ytScript, YT_ORIGINS) } catch (ex: Exception) { Log.e(TAG, "yt script failed", ex) }
            for (e in entries) WebViewCompat.addDocumentStartJavaScript(web, e.script, e.origins)
            addExtScripts(ctx, web)
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
        }
    }

    /** Keeps only chrome.runtime (S9): what a Chrome offscreen document really has. V77 offscreen-shim.js adds the rest. */
    private const val OFFSCREEN_STRIP =
        "\n(function(){try{var c=window.chrome;if(!c)return;Object.keys(c).forEach(function(k){" +
            "if(k!=='runtime'&&k!=='__mgbLog'){try{delete c[k];}catch(e){}}});}catch(e){}})();"

    /** Extension page / engine WebView (S6, S10): trusted origin only, window.chrome is defined. offscreen = S9 document. */
    fun attachExt(ctx: Context, web: WebView, offscreen: Boolean = false) {
        init(ctx)
        if (!supported()) return
        try {
            ChromeBridge.install(web, setOf(EXT_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(web, if (offscreen) extScript + OFFSCREEN_STRIP else extPageScript(ctx), setOf(EXT_ORIGIN))
        } catch (e: Exception) {
            Log.e(TAG, "attachExt failed", e)
        }
    }
}
