package com.jabra.mgb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * S5 + S6: chrome.* for INSTALLED extensions (the built-in V77 keeps its own handlers in TabsApi / ChromeBridge).
 *  - gate(): which api an extension page / content script may call (permission from its manifest, host check for cookies).
 *    ChromeBridge asks it for every message of a non-V77 extension. Unknown / V77-only apis answer "Not supported in MGB".
 *  - runtime.sendMessage between the pieces of ONE extension (content script, popup, options, worker); wakes the worker.
 *  - alarms (own timers, saved), notifications (system notification, toast if not allowed), contextMenus (stored only),
 *    permissions (read-only view of the manifest), tab events with url / title removed when the extension may not see them.
 *  - sync(): called after install / update / toggle / remove: stops workers, drops alarms, fires runtime.onInstalled.
 * Handlers are registered as "ext.<api>"; ChromeBridge prefers them for a non-V77 extension and falls back to the plain one.
 */
object ExtApi {
    private const val PREFS = "mgb_ext_bg"
    private const val CH = "mgb_ext"
    private const val NO_RECEIVER = "Could not establish connection. Receiving end does not exist."

    private val CONTENT_OK = Regex("^(runtime\\.sendMessage|storage\\.(local|sync)\\.[A-Za-z]+)$")
    private val EXT_OK = Regex(
        "^(runtime\\.(sendMessage|openOptionsPage)|event\\.add|storage\\.(local|sync|session)\\.[A-Za-z]+" +
            "|tabs\\.(sendMessage|create|update|getCurrent|remove|get|query|reload|executeScript)" +
            "|windows\\.(create|get|getCurrent|update)|action\\.[A-Za-z]+|alarms\\.[A-Za-z]+|notifications\\.[A-Za-z]+" +
            "|contextMenus\\.[A-Za-z]+|scripting\\.(executeScript|insertCSS)|cookies\\.[A-Za-z]+|permissions\\.[A-Za-z]+|commands\\.getAll" +
            "|declarativeNetRequest\\.(updateSessionRules|updateDynamicRules|getSessionRules|getDynamicRules|getEnabledRulesets|updateEnabledRulesets))$"
    )
    private val NEEDS = mapOf(
        "storage" to "storage", "alarms" to "alarms", "notifications" to "notifications",
        "contextMenus" to "contextMenus", "scripting" to "scripting", "cookies" to "cookies"
    )

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var inited = false
    private val lastEn = HashMap<String, Boolean>()
    private val mem = HashMap<String, HashSet<String>>() // events the worker listened to in its last run (wake rule)
    private val menus = HashMap<String, LinkedHashMap<String, JSONObject>>()
    private var menuSeq = 0
    private var noteSeq = 0

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- permissions ----------

    fun hostPats(info: ExtRegistry.Info): List<String> =
        info.hostPermissions + info.permissions.filter { it.contains("://") || it == "<all_urls>" }

    fun hostPats(id: String): List<String> = ExtRegistry.get(id)?.let { hostPats(it) } ?: emptyList()

    fun hostAllows(info: ExtRegistry.Info, url: String): Boolean = ContentInjector.urlAllowed(hostPats(info), url)

    /** tabs url / title are visible with the "tabs" permission or a host permission matching that tab. */
    private fun canSee(info: ExtRegistry.Info, url: String): Boolean = "tabs" in info.permissions || hostAllows(info, url)

    private fun scrub(o: JSONObject): JSONObject {
        val c = JSONObject(o.toString())
        for (k in listOf("url", "pendingUrl", "title", "favIconUrl")) c.remove(k)
        return c
    }

    /** A tab object as this extension may see it (V77 sees everything). */
    fun tabFor(xid: String, o: JSONObject): JSONObject {
        if (xid == ContentInjector.EXT_ID) return o
        val info = ExtRegistry.get(xid) ?: return scrub(o)
        return if (canSee(info, o.optString("url", "")) || ("activeTab" in info.permissions && o.optBoolean("active", false))) o else scrub(o) // S15: activeTab shows the tab on screen
    }

    fun tabsFor(xid: String, arr: JSONArray): JSONArray {
        if (xid == ContentInjector.EXT_ID) return arr
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) out.put(tabFor(xid, o))
        }
        return out
    }

    /** null = allowed, else the error text for chrome.runtime.lastError. */
    fun gate(ctx: ChromeBridge.Ctx, xid: String, api: String, args: JSONArray): String? {
        val info = ExtRegistry.get(xid)
        if (info == null || !info.enabled) return "Extension is turned off"
        if (ctx.kind == ChromeBridge.Kind.CONTENT && !CONTENT_OK.matches(api)) return "Not allowed: $api"
        if (ctx.kind == ChromeBridge.Kind.EXT && !EXT_OK.matches(api)) {
            Diag.w("ext", "api not supported", info.name + ": " + api) // S16: so the Report center shows which chrome.* call an extension needs
            return "Not supported in MGB: $api"
        }
        // S7: declarativeNetRequest (or ...WithHostAccess, which still needs a matching host permission to act)
        if (api.startsWith("declarativeNetRequest.") && "declarativeNetRequest" !in info.permissions &&
            "declarativeNetRequestWithHostAccess" !in info.permissions) return "Needs the \"declarativeNetRequest\" permission: $api"
        val need = NEEDS[api.substringBefore('.')]
        if (need != null && need !in info.permissions) return "Needs the \"$need\" permission: $api"
        if (api.startsWith("cookies.") && api != "cookies.getAllCookieStores") {
            val o = args.optJSONObject(0)
            var u = o?.optString("url", "") ?: ""
            if (u.isEmpty()) {
                val d = (o?.optString("domain", "") ?: "").trimStart('.')
                if (d.isNotEmpty()) u = "https://$d/"
            }
            if (u.isNotEmpty() && !hostAllows(info, u)) return "No host permission for $u"
        }
        return null
    }

    // ---------- init ----------

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        registerHandlers()
        loadAlarms()
        doSync(true)
    }

    /** After install / update / toggle / remove (ExtListScreen). */
    fun sync(ctx: Context) {
        if (!inited) init(ctx) else doSync(false)
        ContentInjector.refresh(ctx) // 0.0.108: open tabs get the changed content scripts
    }

    private fun doSync(first: Boolean) {
        val a = app ?: return
        val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = HashSet<String>()
        for (info in ExtRegistry.all()) {
            val id = info.id
            ids.add(id)
            val prev = lastEn[id]
            lastEn[id] = info.enabled
            if (!info.enabled) {
                if (prev != false) {
                    ExtBackground.stop(id, "turned off")
                    cancelAlarms(id)
                    menus.remove(id)
                }
                continue
            }
            if (prev == false) ExtBackground.reset(id)
            val pv = sp.getString("ver_$id", null)
            if (pv != info.version) {
                sp.edit().putString("ver_$id", info.version).apply()
                if (pv != null) { ExtBackground.stop(id, "updated"); ExtBackground.reset(id) }
                if (ExtBackground.has(id)) fireInstalled(id, if (pv == null) "install" else "update", pv)
            }
        }
        for (id in lastEn.keys.toList()) {
            if (id in ids) continue
            lastEn.remove(id)
            ExtBackground.stop(id, "removed")
            ExtBackground.reset(id)
            cancelAlarms(id)
            menus.remove(id)
            HeaderModes.forget(id) // S7: header rules of the removed extension
            mem.remove(id)
            sp.edit().remove("ver_$id").remove("mem_$id").apply()
        }
        if (first) main.postDelayed({ startupWake() }, 1500)
    }

    private fun isWorkerCtx(c: ChromeBridge.Ctx, id: String): Boolean =
        c.kind == ChromeBridge.Kind.EXT && ExtBackground.idOf(c.web) == id

    private fun fireInstalled(id: String, reason: String, prev: String?) {
        ExtBackground.ensure(id) { ok ->
            if (ok) {
                val o = JSONObject().put("reason", reason)
                if (prev != null) o.put("previousVersion", prev)
                ChromeBridge.emit("runtime.onInstalled", JSONArray().put(o), id) {
                    isWorkerCtx(it, id) && TabsApi.listens(it, "runtime.onInstalled")
                }
            }
        }
    }

    /** App start: a worker that listened to runtime.onStartup last time is started now (others stay lazy). */
    private fun startupWake() {
        for (info in ExtRegistry.enabled()) {
            val id = info.id
            if (!ExtBackground.has(id) || !remembers(id, "runtime.onStartup")) continue
            ExtBackground.ensure(id) { ok ->
                if (ok) ChromeBridge.emit("runtime.onStartup", JSONArray(), id) {
                    isWorkerCtx(it, id) && TabsApi.listens(it, "runtime.onStartup")
                }
            }
        }
    }

    // ---------- listener memory (which events wake a stopped worker) ----------

    private fun memOf(id: String): HashSet<String> = mem.getOrPut(id) {
        val s = HashSet<String>()
        try {
            val raw = prefs()?.getString("mem_$id", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) s.add(arr.optString(i))
            }
        } catch (_: Exception) { }
        s
    }

    private fun persistMem(id: String) {
        val arr = JSONArray()
        for (e in memOf(id)) arr.put(e)
        prefs()?.edit()?.putString("mem_$id", arr.toString())?.apply()
    }

    fun remembers(id: String, ev: String): Boolean = ev in memOf(id)

    /** A new run of the worker begins: its listener list is rebuilt. */
    fun bgStarted(id: String) {
        mem[id] = HashSet()
        persistMem(id)
    }

    /** event.add from a frame (TabsApi calls this). */
    fun onListen(c: ChromeBridge.Call, ev: String) {
        if (c.extId == ContentInjector.EXT_ID || !isWorkerCtx(c.ctx, c.extId)) return
        if (memOf(c.extId).add(ev)) persistMem(c.extId)
    }

    // ---------- events ----------

    /** Send an event to the pages of one extension that listen to it; wake = start its worker first if it is stopped. */
    fun emitExt(xid: String, event: String, args: JSONArray, wake: Boolean) {
        fun go() {
            ChromeBridge.emit(event, args, xid) { it.kind == ChromeBridge.Kind.EXT && xid in it.ids && TabsApi.listens(it, event) }
        }
        if (wake && ExtBackground.has(xid) && !ExtBackground.running(xid)) ExtBackground.ensure(xid) { go() } else go()
    }

    /** tabs.onCreated / onUpdated ... for every enabled extension; url / title are removed where it may not see them. */
    fun tabsEvent(event: String, tab: TabManager.Tab?, args: List<Any?>) {
        for (info in ExtRegistry.enabled()) {
            val xid = info.id
            val see = tab == null || canSee(info, tab.url)
            val a = JSONArray()
            for (x in args) a.put(if (x is JSONObject && !see) scrub(x) else (x ?: JSONObject.NULL))
            emitExt(xid, event, a, remembers(xid, event))
        }
    }

    // ---------- messaging inside one extension ----------

    private fun send(c: ChromeBridge.Call) {
        val xid = c.extId
        val msg = c.args.opt(0)
        fun go() {
            val targets = ChromeBridge.contexts().filter {
                it.kind == ChromeBridge.Kind.EXT && it !== c.ctx && xid in it.ids && TabsApi.listens(it, "runtime.onMessage")
            }
            if (targets.isEmpty()) { c.fail(NO_RECEIVER); return }
            TabsApi.fanOut(targets, msg, TabsApi.senderOf(c.ctx, xid), xid) { r -> c.ok(r) }
        }
        if (ExtBackground.has(xid)) ExtBackground.ensure(xid) { go() } else go()
    }

    // ---------- alarms ----------

    private class Alarm(val xid: String, val name: String, var whenMs: Long, val periodMs: Long) {
        var task: Runnable? = null
    }

    private val alarms = LinkedHashMap<String, Alarm>()

    private fun akey(x: String, n: String) = "$x|$n"

    private fun alarmJson(a: Alarm): JSONObject {
        val o = JSONObject().put("name", a.name).put("scheduledTime", a.whenMs)
        if (a.periodMs > 0) o.put("periodInMinutes", a.periodMs / 60000.0)
        return o
    }

    private fun arm(a: Alarm) {
        a.task?.let { main.removeCallbacks(it) }
        val r = Runnable { fireAlarm(a) }
        a.task = r
        main.postDelayed(r, (a.whenMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun fireAlarm(a: Alarm) {
        if (alarms[akey(a.xid, a.name)] !== a) return
        val info = ExtRegistry.get(a.xid)
        if (info == null || !info.enabled) { cancelAlarms(a.xid); return }
        emitExt(a.xid, "alarms.onAlarm", JSONArray().put(alarmJson(a)), true)
        if (a.periodMs > 0) {
            val now = System.currentTimeMillis()
            while (a.whenMs <= now) a.whenMs += a.periodMs
            saveAlarms()
            arm(a)
        } else {
            alarms.remove(akey(a.xid, a.name))
            saveAlarms()
        }
    }

    private fun cancelAlarms(xid: String) {
        val mine = alarms.values.filter { it.xid == xid }
        if (mine.isEmpty()) return
        for (a in mine) {
            a.task?.let { main.removeCallbacks(it) }
            alarms.remove(akey(a.xid, a.name))
        }
        saveAlarms()
    }

    private fun clearAlarm(xid: String, name: String): Boolean {
        val a = alarms.remove(akey(xid, name)) ?: return false
        a.task?.let { main.removeCallbacks(it) }
        saveAlarms()
        return true
    }

    private fun saveAlarms() {
        val arr = JSONArray()
        for (a in alarms.values) arr.put(JSONObject().put("x", a.xid).put("n", a.name).put("w", a.whenMs).put("p", a.periodMs))
        prefs()?.edit()?.putString("alarms", arr.toString())?.apply()
    }

    private fun loadAlarms() {
        val raw = prefs()?.getString("alarms", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val x = o.getString("x")
                if (!ExtRegistry.isValidId(x) || ExtRegistry.get(x)?.enabled != true) continue
                val a = Alarm(x, o.getString("n"), o.getLong("w"), o.optLong("p", 0L))
                alarms[akey(x, a.name)] = a
                arm(a)
            }
        } catch (_: Exception) { }
    }

    // ---------- notifications ----------

    private fun noteId(xid: String, id: String): Int = "$xid|$id".hashCode()

    private fun showNote(xid: String, id: String, o: JSONObject) {
        val a = app ?: return
        val name = ExtRegistry.get(xid)?.name ?: "Extension"
        val title = o.optString("title", name)
        val msg = o.optString("message", "")
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(a, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        if (!allowed) { Util.toast(a, "$name: $title $msg".trim()); return }
        try {
            val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Extensions", NotificationManager.IMPORTANCE_DEFAULT))
            val icon = if (a.applicationInfo.icon != 0) a.applicationInfo.icon else android.R.drawable.ic_dialog_info
            val b = NotificationCompat.Builder(a, CH)
                .setSmallIcon(icon).setContentTitle(title).setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg)).setSubText(name).setAutoCancel(true)
            nm.notify(noteId(xid, id), b.build())
        } catch (e: Exception) {
            Diag.w("engine", "ext notification failed", e.message)
        }
    }

    // ---------- context menus (stored; MGB has no page menu hook yet) ----------

    /** For a later TabManager long-press hook: the items an extension created. */
    fun menuItems(xid: String): List<JSONObject> = menus[xid]?.values?.toList() ?: emptyList()

    /** For a later TabManager long-press hook: tell the extension an item was clicked. */
    fun menuClicked(xid: String, id: String, info: JSONObject, tab: JSONObject?) {
        val inf = ExtRegistry.get(xid) ?: return
        val t = if (tab == null) JSONObject.NULL else tabFor(inf.id, tab)
        emitExt(xid, "contextMenus.onClicked", JSONArray().put(info.put("menuItemId", id)).put(t), true)
    }

    // ---------- handlers ----------

    private fun strs(o: JSONObject?, key: String): List<String> {
        val a = o?.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun registerHandlers() {
        ChromeBridge.on("ext.runtime.sendMessage") { c -> send(c) }

        // permissions: read-only view of the manifest (no run-time grants on Android)
        ChromeBridge.onSync("ext.permissions.getAll") { c ->
            val info = ExtRegistry.get(c.extId)
            JSONObject()
                .put("permissions", JSONArray(info?.permissions?.filter { !it.contains("://") && it != "<all_urls>" } ?: emptyList<String>()))
                .put("origins", JSONArray(info?.let { hostPats(it) } ?: emptyList<String>()))
        }
        ChromeBridge.onSync("ext.permissions.contains") { c ->
            val info = ExtRegistry.get(c.extId)
            val o = c.args.optJSONObject(0)
            if (info == null) false
            else {
                val perms = strs(o, "permissions").all { it in info.permissions }
                val origins = strs(o, "origins").all { it in hostPats(info) }
                perms && origins
            }
        }
        ChromeBridge.onSync("ext.permissions.request") { false }

        // alarms
        ChromeBridge.onSync("ext.alarms.create") { c ->
            val first = c.args.opt(0)
            val name = if (first is String) first else ""
            val info = (if (first is String) c.args.optJSONObject(1) else c.args.optJSONObject(0)) ?: JSONObject()
            var period = (info.optDouble("periodInMinutes", 0.0) * 60000).toLong()
            if (period > 0 && period < 30_000L) period = 30_000L
            val now = System.currentTimeMillis()
            val at = when {
                info.has("when") -> info.optLong("when")
                info.has("delayInMinutes") -> now + (info.optDouble("delayInMinutes") * 60000).toLong()
                period > 0 -> now + period
                else -> now
            }
            alarms.remove(akey(c.extId, name))?.task?.let { main.removeCallbacks(it) }
            val a = Alarm(c.extId, name, at, period)
            alarms[akey(c.extId, name)] = a
            saveAlarms()
            arm(a)
            null
        }
        ChromeBridge.onSync("ext.alarms.get") { c -> alarms[akey(c.extId, c.args.optString(0, ""))]?.let { alarmJson(it) } }
        ChromeBridge.onSync("ext.alarms.getAll") { c ->
            val arr = JSONArray()
            for (a in alarms.values) if (a.xid == c.extId) arr.put(alarmJson(a))
            arr
        }
        ChromeBridge.onSync("ext.alarms.clear") { c -> clearAlarm(c.extId, c.args.optString(0, "")) }
        ChromeBridge.onSync("ext.alarms.clearAll") { c ->
            cancelAlarms(c.extId)
            true
        }

        // notifications (no onClicked: a tap does not come back to the extension yet)
        ChromeBridge.onSync("ext.notifications.create") { c ->
            val first = c.args.opt(0)
            val given = if (first is String) first else ""
            val o = (if (first is String) c.args.optJSONObject(1) else c.args.optJSONObject(0)) ?: JSONObject()
            val id = if (given.isEmpty()) "n" + (++noteSeq) else given
            showNote(c.extId, id, o)
            id
        }
        ChromeBridge.onSync("ext.notifications.clear") { c ->
            val a = app
            if (a != null) (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(noteId(c.extId, c.args.optString(0, "")))
            true
        }
        ChromeBridge.onSync("ext.notifications.update") { false }

        // context menus
        ChromeBridge.onSync("ext.contextMenus.create") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val m = menus.getOrPut(c.extId) { LinkedHashMap() }
            if (m.size >= 100) throw IllegalStateException("Too many context menu items")
            var id = o.optString("id", "")
            if (id.isEmpty()) { id = "menu" + (++menuSeq); o.put("id", id) }
            if (m.containsKey(id)) throw IllegalArgumentException("Cannot create item with duplicate id $id")
            m[id] = JSONObject(o.toString())
            id
        }
        ChromeBridge.onSync("ext.contextMenus.update") { c ->
            val cur = menus[c.extId]?.get(c.args.optString(0, ""))
            val p = c.args.optJSONObject(1)
            if (cur != null && p != null) for (k in p.keys().asSequence().toList()) cur.put(k, p.get(k))
            null
        }
        ChromeBridge.onSync("ext.contextMenus.remove") { c ->
            menus[c.extId]?.remove(c.args.optString(0, ""))
            null
        }
        ChromeBridge.onSync("ext.contextMenus.removeAll") { c ->
            menus.remove(c.extId)
            null
        }
    }
}
