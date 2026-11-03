package com.jabra.mgb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.WeakHashMap

/**
 * S6: chrome.tabs / chrome.runtime messaging / chrome.alarms (+ harmless stubs) on top of ChromeBridge.
 *  - runtime.sendMessage: content frame or extension page -> every other extension page that listens
 *    (background engine, popup ...). First non-empty answer wins. Content frames may only send the
 *    message types content.js really uses (CONTENT_MSG).
 *  - tabs.sendMessage: engine/page -> frames of one tab (frameId 0 = main frame).
 *  - tabs.* handlers + tab events (called by TabManager).
 * N1 (Novel grabber): scripting.executeScript (func source text / files, main frame, 3 min limit) + cookies.* (WebView CookieManager).
 * NOT here: scripting.registerContentScripts, downloads (S7),
 * DNR + webRequest (S8), offscreen (S9), extension page UI (S10).
 */
object TabsApi {
    private const val TAG = "MGB.tabs"
    private const val NO_RECEIVER = "Could not establish connection. Receiving end does not exist."
    private const val PREFS = "mgb_engine"

    private val main = Handler(Looper.getMainLooper())
    private var tm: TabManager? = null
    private var app: Context? = null
    private var inited = false
    private var engineReady = false
    private val listening = WeakHashMap<ChromeBridge.Ctx, HashSet<String>>()
    private val frameIds = WeakHashMap<ChromeBridge.Ctx, Int>()
    private var nextFrame = 1
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** Message types content.js sends (defence in depth: a page frame can never ask for anything else). */
    private val CONTENT_MSG = setOf(
        "batch-ask-answer", "batch-episodes", "batch-stop", "dom-items", "download-all", "dup-check",
        "get-state", "job-cancel", "job-pause", "job-remove", "jobs-get", "notify", "open-manga",
        "native-panel", "open-options", "open-panel", "open-player", "play-scan", "reanalyze", "rescan",
        "schedule-job", "site-probe", "start-job", "subs-all", "subs-capture", "video-info", "whoami"
    )

    var onNativePanel: (() -> Unit)? = null // MGB S51: set by MainActivity

    fun attach(t: TabManager?) { tm = t }

    private fun ui(b: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) b() else main.post { b() }
    }

    /** Register a handler that runs on the main thread; exceptions become chrome errors. */
    private fun handle(api: String, h: (ChromeBridge.Call) -> Unit) {
        ChromeBridge.on(api) { c ->
            ui {
                try { h(c) } catch (e: Exception) { c.fail(e.message ?: e.toString()) }
            }
        }
    }

    // ---------- helpers ----------

    fun tabJson(t: TabManager.Tab): JSONObject {
        val m = tm
        val o = JSONObject()
            .put("id", t.id)
            .put("index", m?.all()?.indexOf(t) ?: 0)
            .put("windowId", 1)
            .put("active", m?.active === t)
            .put("highlighted", m?.active === t)
            .put("pinned", false)
            .put("incognito", false)
            .put("audible", false)
            .put("discarded", false)
            .put("status", if (t.loading) "loading" else "complete")
            .put("url", t.url)
            .put("title", t.title)
            .put("favIconUrl", "")
        if (t.loading) o.put("pendingUrl", t.url)
        if (t.openerId > 0) o.put("openerTabId", t.openerId)
        return o
    }

    private fun frameIdOf(c: ChromeBridge.Ctx): Int {
        if (c.isMainFrame) return 0
        return synchronized(frameIds) { frameIds.getOrPut(c) { nextFrame++ } }
    }

    private fun listens(c: ChromeBridge.Ctx, ev: String): Boolean =
        synchronized(listening) { listening[c]?.contains(ev) == true }

    /** The `sender` object a receiving listener gets. */
    fun senderOf(c: ChromeBridge.Ctx): JSONObject {
        val s = JSONObject().put("id", ContentInjector.EXT_ID).put("origin", c.origin)
        val tab = if (c.tabId > 0) tm?.byId(c.tabId) else null
        if (tab != null) s.put("tab", tabJson(tab))
        s.put("frameId", frameIdOf(c))
        val url = when {
            c.kind == ChromeBridge.Kind.EXT ->
                if (EngineHost.isEngine(c.web)) ContentInjector.EXT_BASE + "_engine.html"
                else if (c.isMainFrame && tab != null && tab.url.startsWith(ContentInjector.EXT_BASE)) tab.url
                else ContentInjector.EXT_BASE
            c.isMainFrame && tab != null -> tab.url
            else -> c.origin + "/"
        }
        return s.put("url", url)
    }

    /** Send to several frames; the first non-empty reply wins, null when all are empty. */
    private fun fanOut(targets: List<ChromeBridge.Ctx>, msg: Any?, sender: JSONObject, done: (Any?) -> Unit) {
        if (targets.isEmpty()) { done(null); return }
        var left = targets.size
        var finished = false
        for (t in targets) {
            ChromeBridge.request(t, msg, sender) { r ->
                if (finished) return@request
                left--
                if (r != null && r != JSONObject.NULL) { finished = true; done(r) }
                else if (left <= 0) { finished = true; done(null) }
            }
        }
    }

    private fun glob(p: String, s: String): Boolean {
        if (p == "<all_urls>") return true
        return Regex(p.split("*").joinToString(".*") { Regex.escape(it) }).matches(s)
    }

    private fun query(q: JSONObject): JSONArray {
        val out = JSONArray()
        val m = tm ?: return out
        val urls: List<String>? = when (val u = q.opt("url")) {
            is String -> listOf(u)
            is JSONArray -> (0 until u.length()).map { u.optString(it) }
            else -> null
        }
        for (t in m.all()) {
            if (q.has("active") && (m.active === t) != q.optBoolean("active")) continue
            if (q.has("status") && (if (t.loading) "loading" else "complete") != q.optString("status")) continue
            if (q.has("title") && !glob(q.optString("title"), t.title)) continue
            if (urls != null && urls.none { glob(it, t.url) }) continue
            out.put(tabJson(t))
        }
        return out
    }

    private fun fullUrl(u: String): String =
        if (u.isBlank()) TabManager.HOME
        else if (SCHEME.containsMatchIn(u)) u
        else ContentInjector.EXT_BASE + u.trimStart('/')

    /** S10: a link opened from an extension window becomes a normal tab. */
    fun openTab(url: String) = ui { tm?.newTab(url, 0, true) }

    // ---------- events (called by TabManager) ----------

    private fun emitTabs(event: String, vararg args: Any?) {
        val a = JSONArray()
        for (x in args) a.put(x ?: JSONObject.NULL)
        ChromeBridge.emit(event, a) { it.kind == ChromeBridge.Kind.EXT }
    }

    fun onCreated(t: TabManager.Tab) = emitTabs("tabs.onCreated", tabJson(t))
    fun onActivated(t: TabManager.Tab) =
        emitTabs("tabs.onActivated", JSONObject().put("tabId", t.id).put("windowId", 1))
    fun onRemoved(t: TabManager.Tab) =
        emitTabs("tabs.onRemoved", t.id, JSONObject().put("windowId", 1).put("isWindowClosing", false))
    fun onUpdated(t: TabManager.Tab, change: JSONObject) = emitTabs("tabs.onUpdated", t.id, change, tabJson(t))

    // ---------- init ----------

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        loadAlarms()

        // frames announce which events they listen to (shim: addListener on an ext page)
        ChromeBridge.onSync("event.add") { c ->
            val n = c.args.optString(0)
            if (n.isNotEmpty()) synchronized(listening) { listening.getOrPut(c.ctx) { HashSet() }.add(n) }
            null
        }

        handle("runtime.sendMessage") { c ->
            val msg = c.args.opt(0)
            if (c.ctx.kind == ChromeBridge.Kind.CONTENT) {
                val type = (msg as? JSONObject)?.optString("type") ?: ""
                if (type !in CONTENT_MSG) { c.fail("Not allowed: message type"); return@handle }
                if (type == "native-panel") { ui { onNativePanel?.invoke() }; c.ok(null); return@handle } // MGB S51: handled here, never forwarded
            }
            val targets = ChromeBridge.contexts().filter {
                it.kind == ChromeBridge.Kind.EXT && it !== c.ctx && listens(it, "runtime.onMessage")
            }
            if (targets.isEmpty()) { c.fail(NO_RECEIVER); return@handle }
            fanOut(targets, msg, senderOf(c.ctx)) { r -> c.ok(r) }
        }

        handle("tabs.sendMessage") { c ->
            val id = c.args.optInt(0, -1)
            val msg = c.args.opt(1)
            val opts = c.args.optJSONObject(2)
            val t = tm?.byId(id)
            var targets: List<ChromeBridge.Ctx> =
                if (t == null) emptyList() else ChromeBridge.contexts().filter { it.web === t.web }
            if (opts != null && opts.has("frameId")) {
                val f = opts.optInt("frameId")
                targets = if (f == 0) targets.filter { it.isMainFrame }.takeLast(1)
                else targets.filter { !it.isMainFrame && frameIdOf(it) == f }
            }
            if (targets.isEmpty()) { c.fail(NO_RECEIVER); return@handle }
            fanOut(targets, msg, senderOf(c.ctx)) { r -> c.ok(r) }
        }

        handle("tabs.get") { c ->
            val t = tm?.byId(c.args.optInt(0, -1))
            if (t == null) c.fail("No tab with id: ${c.args.opt(0)}.") else c.ok(tabJson(t))
        }

        handle("tabs.getCurrent") { c ->
            val t = if (c.ctx.tabId > 0) tm?.byId(c.ctx.tabId) else null
            c.ok(if (t == null) null else tabJson(t))
        }

        handle("tabs.query") { c -> c.ok(query(c.args.optJSONObject(0) ?: JSONObject())) }

        handle("tabs.create") { c ->
            val m = tm
            if (m == null) { c.fail("No browser window"); return@handle }
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val opener = if (o.has("openerTabId")) o.optInt("openerTabId") else maxOf(c.ctx.tabId, 0)
            val u = fullUrl(o.optString("url", ""))
            // S39: V77 "Play" (player.html?url=...) = built-in native player, not the Chromium page player
            if (WebPlay.fromExtPlayerUrl(m.host(), u, (m.byId(opener) ?: m.active)?.web?.settings?.userAgentString)) {
                c.ok(JSONObject().put("id", -1).put("windowId", 1).put("index", 0).put("active", true).put("url", u).put("status", "complete"))
                return@handle
            }
            // S10: popup / queue / manga / history / options open as a full-screen window, not as a tab
            if (ExtScreen.isScreenUrl(u) && ExtScreen.open(m.host(), u)) {
                c.ok(JSONObject().put("id", -1).put("windowId", 1).put("index", 0).put("active", true).put("url", u).put("status", "complete"))
                return@handle
            }
            val t = m.newTab(u, opener, o.optBoolean("active", true))
            if (t == null) c.fail("Too many tabs") else {
                if (o.optBoolean("active", true)) ExtScreen.closeAll() // a new tab must not stay hidden behind a window
                c.ok(tabJson(t))
            }
        }

        handle("tabs.update") { c ->
            val m = tm
            if (m == null) { c.fail("No browser window"); return@handle }
            val first = c.args.opt(0)
            val props = (if (first is JSONObject) first else c.args.optJSONObject(1)) ?: JSONObject()
            val t = if (first is JSONObject || first == null || first == JSONObject.NULL) m.active else m.byId(c.args.optInt(0, -1))
            if (t == null) { c.fail("No tab with id: $first."); return@handle }
            if (props.has("url")) {
                val u = fullUrl(props.optString("url"))
                t.pending = null
                t.url = u
                t.web.loadUrl(u)
            }
            if (props.optBoolean("active", false)) m.switchTo(t)
            c.ok(tabJson(t))
        }

        handle("tabs.remove") { c ->
            val m = tm
            val a = c.args.opt(0)
            val ids = ArrayList<Int>()
            if (a is JSONArray) {
                for (i in 0 until a.length()) ids.add(a.optInt(i))
            } else {
                ids.add(c.args.optInt(0, -1))
            }
            if (m != null) for (id in ids) m.byId(id)?.let { m.close(it) }
            c.ok(null)
        }

        handle("tabs.reload") { c ->
            val m = tm
            val a = c.args.opt(0)
            val t = if (a is Number) m?.byId(a.toInt()) else m?.active
            if (t == null) { c.fail("No tab to reload"); return@handle }
            val p = t.pending
            if (p != null) { t.pending = null; t.web.loadUrl(p) } else t.web.reload()
            c.ok(null)
        }

        // ---- N1: chrome.scripting.executeScript -> evaluateJavascript in the tab, result polled back ----
        handle("scripting.executeScript") { c -> runScript(c) }

        // ---- N1: chrome.cookies on top of the WebView CookieManager (name/value only: Android gives no flags) ----
        ChromeBridge.onSync("cookies.getAll") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = cookieUrl(o)
            if (u == null) JSONArray() else cookieList(u, o.optString("name").ifEmpty { null })
        }
        ChromeBridge.onSync("cookies.get") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = cookieUrl(o)
            if (u == null) null else cookieList(u, o.optString("name").ifEmpty { null }).optJSONObject(0)
        }
        ChromeBridge.onSync("cookies.set") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = o.optString("url")
            if (u.isEmpty()) throw IllegalArgumentException("cookies.set: url required")
            val sb = StringBuilder(o.optString("name")).append('=').append(o.optString("value"))
            sb.append("; Path=").append(o.optString("path", "/"))
            if (o.optString("domain").isNotEmpty()) sb.append("; Domain=").append(o.optString("domain"))
            if (o.optBoolean("secure", false)) sb.append("; Secure")
            if (o.has("expirationDate")) sb.append("; Max-Age=").append(maxOf(0L, (o.optDouble("expirationDate") - System.currentTimeMillis() / 1000.0).toLong()))
            CookieManager.getInstance().setCookie(u, sb.toString())
            o.optString("name").let { n -> cookieList(u, n).optJSONObject(0) }
        }
        ChromeBridge.onSync("cookies.remove") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = o.optString("url")
            if (u.isNotEmpty()) CookieManager.getInstance().setCookie(u, o.optString("name") + "=; Path=/; Max-Age=0")
            JSONObject().put("url", u).put("name", o.optString("name")).put("storeId", "0")
        }
        ChromeBridge.onSync("cookies.getAllCookieStores") { JSONArray().put(JSONObject().put("id", "0").put("tabIds", JSONArray())) }

        handle("runtime.openOptionsPage") { c ->
            val u = ContentInjector.EXT_BASE + "options.html"
            val m = tm
            if (m != null && !ExtScreen.open(m.host(), u)) m.newTab(u, 0, true) // S10: window, tab as fallback
            c.ok(null)
        }
        // runtime.getContexts + offscreen.*: OffscreenHost (S9)

        // windows: one window; chrome.windows.create opens a tab
        handle("windows.create") { c ->
            val m = tm
            if (m == null) { c.fail("No browser window"); return@handle }
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val t = m.newTab(fullUrl(o.optString("url", "")), 0, o.optBoolean("focused", true))
            if (t == null) { c.fail("Too many tabs"); return@handle }
            c.ok(windowJson().put("type", o.optString("type", "normal")).put("tabs", JSONArray().put(tabJson(t))))
        }
        ChromeBridge.onSync("windows.get") { windowJson() }
        ChromeBridge.onSync("windows.getCurrent") { windowJson() }
        ChromeBridge.onSync("windows.update") { windowJson() }

        // alarms
        handle("alarms.create") { c ->
            val first = c.args.opt(0)
            val name = if (first is String) first else ""
            val info = (if (first is String) c.args.optJSONObject(1) else c.args.optJSONObject(0)) ?: JSONObject()
            val period = (info.optDouble("periodInMinutes", 0.0) * 60000).toLong()
            val now = System.currentTimeMillis()
            val at = when {
                info.has("when") -> info.optLong("when")
                info.has("delayInMinutes") -> now + (info.optDouble("delayInMinutes") * 60000).toLong()
                period > 0 -> now + period
                else -> now
            }
            alarms.remove(name)?.task?.let { main.removeCallbacks(it) }
            val a = Alarm(name, at, period)
            alarms[name] = a
            saveAlarms()
            if (engineReady) arm(a)
            c.ok(null)
        }
        ChromeBridge.onSync("alarms.get") { c -> alarms[c.args.optString(0, "")]?.let { alarmJson(it) } }
        ChromeBridge.onSync("alarms.getAll") { JSONArray().also { arr -> for (a in alarms.values) arr.put(alarmJson(a)) } }
        ChromeBridge.onSync("alarms.clear") { c -> clearAlarm(c.args.optString(0, "")) }
        ChromeBridge.onSync("alarms.clearAll") {
            for (n in alarms.keys.toList()) clearAlarm(n)
            true
        }

        // cosmetic / not-applicable APIs: accept and ignore so V77 never fails on them
        for (api in listOf(
            "action.setBadgeText", "action.setBadgeBackgroundColor", "action.setIcon", "action.setTitle",
            "sidePanel.open", "sidePanel.setOptions", "sidePanel.setPanelBehavior",
            "contextMenus.update", "contextMenus.remove", "contextMenus.removeAll",
            "notifications.create", "notifications.clear", "notifications.update"
        )) ChromeBridge.onSync(api) { null }
        ChromeBridge.onSync("contextMenus.create") { c -> c.args.optJSONObject(0)?.optString("id") ?: "" }
        ChromeBridge.onSync("commands.getAll") { JSONArray() }
        ChromeBridge.onSync("permissions.contains") { true }
        ChromeBridge.onSync("permissions.request") { true }
        ChromeBridge.onSync("permissions.getAll") {
            JSONObject().put("permissions", JSONArray()).put("origins", JSONArray().put("<all_urls>"))
        }
    }

    // ---------- N1: executeScript / cookies helpers ----------

    private var nextXid = 1

    private fun runScript(c: ChromeBridge.Call) {
        val inj = c.args.optJSONObject(0) ?: JSONObject()
        val tid = inj.optJSONObject("target")?.optInt("tabId", -1) ?: -1
        val t = tm?.byId(tid)
        if (t == null) { c.fail("No tab with id: $tid."); return }
        val fn = inj.optString("func", "")
        val files = inj.optJSONArray("files")
        val body: String = when {
            fn.isNotEmpty() -> "(" + fn + ").apply(null," + (inj.optJSONArray("args") ?: JSONArray()).toString() + ")"
            files != null && files.length() > 0 -> {
                val sb = StringBuilder()
                for (i in 0 until files.length()) {
                    val p = files.optString(i).trimStart('/')
                    try { sb.append(app?.assets?.open("ext/$p")?.bufferedReader()?.use { it.readText() } ?: "").append("\n;") }
                    catch (e: Exception) { c.fail("Could not load file: $p"); return }
                }
                "(function(){" + sb + "\n})()"
            }
            else -> { c.fail("executeScript: func or files required"); return }
        }
        val id = nextXid++
        val start = "(function(){var R=window.__mgbx=window.__mgbx||{};" +
            "Promise.resolve().then(function(){return " + body + "}).then(" +
            "function(v){R[" + id + "]={r:v===undefined?null:v}}," +
            "function(e){R[" + id + "]={e:String(e&&e.message||e)}});return 1})()"
        val poll = "(function(){var R=window.__mgbx;if(!R)return 'gone';var x=R[" + id + "];if(!x)return null;delete R[" + id + "];" +
            "try{return JSON.stringify(x)}catch(e){return JSON.stringify({e:'result is not serializable'})}})()"
        val deadline = System.currentTimeMillis() + 180_000L
        fun pollOnce() {
            if (tm?.byId(tid) == null) { c.fail("The tab was closed."); return }
            t.web.evaluateJavascript(poll) { v ->
                var s: String? = null
                if (v != null && v != "null") s = try { JSONTokener(v).nextValue() as? String } catch (e: Exception) { null }
                when {
                    s == "gone" -> c.fail("The page was reloaded before the script finished.")
                    s != null -> try {
                        val x = JSONObject(s)
                        if (x.has("e")) c.fail(x.optString("e"))
                        else c.ok(JSONArray().put(JSONObject().put("frameId", 0).put("result", if (x.isNull("r")) JSONObject.NULL else x.get("r"))))
                    } catch (e: Exception) { c.fail("Bad script result") }
                    System.currentTimeMillis() > deadline -> c.fail("Script timed out.")
                    else -> main.postDelayed({ pollOnce() }, 200)
                }
            }
        }
        t.web.evaluateJavascript(start) { pollOnce() }
    }

    private fun cookieUrl(o: JSONObject): String? {
        val u = o.optString("url")
        if (u.isNotEmpty()) return u
        val d = o.optString("domain").trimStart('.')
        return if (d.isNotEmpty()) "https://$d/" else null
    }

    private fun cookieList(url: String, name: String?): JSONArray {
        val out = JSONArray()
        val raw = (try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }) ?: return out
        val host = try { android.net.Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
        for (part in raw.split(";")) {
            val kv = part.trim()
            if (kv.isEmpty()) continue
            val i = kv.indexOf('=')
            val n = if (i >= 0) kv.substring(0, i) else kv
            val v = if (i >= 0) kv.substring(i + 1) else ""
            if (name != null && name != n) continue
            out.put(JSONObject().put("name", n).put("value", v).put("domain", host).put("hostOnly", true).put("path", "/")
                .put("secure", url.startsWith("https")).put("httpOnly", false).put("session", true).put("storeId", "0").put("sameSite", "unspecified"))
        }
        return out
    }

    private fun windowJson(): JSONObject = JSONObject()
        .put("id", 1).put("focused", true).put("incognito", false).put("type", "normal")
        .put("state", "normal").put("alwaysOnTop", false)

    // ---------- alarms ----------

    private class Alarm(val name: String, var whenMs: Long, val periodMs: Long) {
        var task: Runnable? = null
    }

    private val alarms = LinkedHashMap<String, Alarm>()

    private fun alarmJson(a: Alarm): JSONObject {
        val o = JSONObject().put("name", a.name).put("scheduledTime", a.whenMs)
        if (a.periodMs > 0) o.put("periodInMinutes", a.periodMs / 60000.0)
        return o
    }

    private fun arm(a: Alarm) {
        a.task?.let { main.removeCallbacks(it) }
        val r = Runnable { fire(a) }
        a.task = r
        main.postDelayed(r, (a.whenMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun fire(a: Alarm) {
        if (alarms[a.name] !== a) return
        ChromeBridge.emit("alarms.onAlarm", JSONArray().put(alarmJson(a))) { it.kind == ChromeBridge.Kind.EXT }
        if (a.periodMs > 0) {
            val now = System.currentTimeMillis()
            while (a.whenMs <= now) a.whenMs += a.periodMs
            saveAlarms()
            arm(a)
        } else {
            alarms.remove(a.name)
            saveAlarms()
        }
    }

    private fun clearAlarm(name: String): Boolean {
        val a = alarms.remove(name) ?: return false
        a.task?.let { main.removeCallbacks(it) }
        saveAlarms()
        return true
    }

    private fun saveAlarms() {
        val ctx = app ?: return
        val arr = JSONArray()
        for (a in alarms.values) arr.put(JSONObject().put("n", a.name).put("w", a.whenMs).put("p", a.periodMs))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("alarms", arr.toString()).apply()
    }

    private fun loadAlarms() {
        val ctx = app ?: return
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("alarms", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                alarms[o.getString("n")] = Alarm(o.getString("n"), o.getLong("w"), o.optLong("p", 0L))
            }
        } catch (e: Exception) { Log.e(TAG, "alarms load", e) }
    }

    // ---------- engine start (called by EngineHost when the page has loaded) ----------

    fun engineStarted() {
        main.postDelayed({ deliverStart(0) }, 300)
    }

    private fun deliverStart(attempt: Int) {
        val ctx = app ?: return
        val eng = ChromeBridge.contexts().firstOrNull {
            EngineHost.isEngine(it.web) && it.kind == ChromeBridge.Kind.EXT && listens(it, "runtime.onStartup")
        }
        if (eng == null) {
            if (attempt < 20) main.postDelayed({ deliverStart(attempt + 1) }, 300)
            else EngineHost.addLog("engine: background.js did not register onStartup (see log above)")
            return
        }
        val ver = try {
            JSONObject(ctx.assets.open("ext/manifest.json").bufferedReader().use { it.readText() }).optString("version")
        } catch (e: Exception) { "" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = sp.getString("ext_version", null)
        if (prev != ver) {
            sp.edit().putString("ext_version", ver).apply()
            val info = JSONObject().put("reason", if (prev == null) "install" else "update")
            if (prev != null) info.put("previousVersion", prev)
            ChromeBridge.emit("runtime.onInstalled", JSONArray().put(info)) { it === eng }
        }
        ChromeBridge.emit("runtime.onStartup", JSONArray()) { it === eng }
        engineReady = true
        for (a in alarms.values) arm(a)
        EngineHost.addLog("engine: background.js started (ext $ver)")
    }

    fun status(): String {
        val eng = ChromeBridge.contexts().firstOrNull { EngineHost.isEngine(it.web) }
        return "\nBackground script: " + (if (engineReady) "started" else "not started") +
            "\nListens runtime.onMessage: " + (eng != null && listens(eng, "runtime.onMessage")) +
            "\nTabs: " + (tm?.count ?: 0) + "   Alarms: " + alarms.size
    }
}
