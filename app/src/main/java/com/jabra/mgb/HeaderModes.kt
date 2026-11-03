package com.jabra.mgb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * S8: chrome.declarativeNetRequest (modifyHeaders rules only) for requests the extension makes itself.
 * Those are the requests of the engine page and of extension pages opened as tabs: both go through
 * EngineHost.proxyFor(), which calls apply() right before the request is sent (every redirect hop again).
 * Page requests are never modified (V77 rules always carry initiatorDomains = the extension id).
 * Session rules live in memory, dynamic rules are kept in prefs mgb_engine (key dnr_dynamic).
 * S7: the same for INSTALLED extensions, strictly per extension (c.extId of the bridge call):
 *  - own session / dynamic rules (prefs key dnr_dynamic_<id>) and static rulesets from manifest declarative_net_request.rule_resources
 *    (enabled flags changed by updateEnabledRulesets are kept in prefs key dnr_rs_<id>)
 *  - rules only touch requests made by pages / worker of THAT extension (proxyFor gets its id), never page requests, never another extension
 *  - needs a host permission that matches the request url, and the extension must be turned on
 *  - only modifyHeaders (requestHeaders) acts; block / redirect / allow rules are stored and listed but never match
 */
object HeaderModes {
    private const val PREFS = "mgb_engine"

    private class Rule(val id: Int, val json: JSONObject) {
        val priority = json.optInt("priority", 1)
        private val cond = json.optJSONObject("condition") ?: JSONObject()
        val actions: List<JSONObject>
        private val urlRe: Regex?
        private val reRe: Regex?
        private val domains = strs(cond.optJSONArray("requestDomains") ?: cond.optJSONArray("domains"))
        private val exDomains = strs(cond.optJSONArray("excludedRequestDomains") ?: cond.optJSONArray("excludedDomains"))
        private val initDomains = strs(cond.optJSONArray("initiatorDomains") ?: cond.optJSONArray("domains"))
        private val tabIds: Set<Int>? = cond.optJSONArray("tabIds")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() }
        private val excTabIds: Set<Int>? = cond.optJSONArray("excludedTabIds")?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() }
        private val types = strs(cond.optJSONArray("resourceTypes"))
        private val exTypes = strs(cond.optJSONArray("excludedResourceTypes"))
        private val methods = strs(cond.optJSONArray("requestMethods")).map { it.lowercase() }

        init {
            val a = json.optJSONObject("action")?.optJSONArray("requestHeaders")
            actions = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            val opt = if (cond.optBoolean("isUrlFilterCaseSensitive", false)) emptySet() else setOf(RegexOption.IGNORE_CASE)
            urlRe = if (cond.has("urlFilter")) urlFilterRegex(cond.optString("urlFilter"), opt) else null
            reRe = if (cond.has("regexFilter")) try { Regex(cond.optString("regexFilter"), opt) } catch (e: Exception) { null } else null
        }

        private fun hostIn(host: String, list: List<String>) = list.any { host == it || host.endsWith(".$it") }

        fun matches(url: String, host: String, tabId: Int, type: String, method: String, own: String): Boolean {
            if (urlRe != null && !urlRe.containsMatchIn(url)) return false
            if (cond.has("regexFilter") && (reRe == null || !reRe.containsMatchIn(url))) return false
            if (domains.isNotEmpty() && !hostIn(host, domains)) return false
            if (exDomains.isNotEmpty() && hostIn(host, exDomains)) return false
            // every request we modify was started by the extension
            if (initDomains.isNotEmpty() && !initDomains.contains(own.lowercase()) &&
                !initDomains.contains(Util.host(ContentInjector.EXT_ORIGIN))) return false
            if (tabIds != null && !tabIds.contains(tabId)) return false
            if (excTabIds != null && excTabIds.contains(tabId)) return false
            if (types.isNotEmpty() && !types.contains(type)) return false
            if (exTypes.contains(type)) return false
            if (methods.isNotEmpty() && !methods.contains(method.lowercase())) return false
            return true
        }
    }

    private fun strs(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it).lowercase() }

    /** Chrome urlFilter syntax -> Regex: * any, ^ separator, leading | or || anchors, trailing | end anchor. */
    private fun urlFilterRegex(f: String, opt: Set<RegexOption>): Regex? {
        if (f.isEmpty()) return null
        var s = f
        val sb = StringBuilder()
        if (s.startsWith("||")) { sb.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?"); s = s.substring(2) }
        else if (s.startsWith("|")) { sb.append("^"); s = s.substring(1) }
        var end = false
        if (s.endsWith("|")) { end = true; s = s.dropLast(1) }
        for (ch in s) when (ch) {
            '*' -> sb.append(".*")
            '^' -> sb.append("(?:[^A-Za-z0-9_.%-]|$)")
            else -> sb.append(Regex.escape(ch.toString()))
        }
        if (end) sb.append("$")
        return try { Regex(sb.toString(), opt) } catch (e: Exception) { null }
    }

    // S7: rules per extension id (V77 = ContentInjector.EXT_ID, same behaviour as before). Lists are never changed in place.
    private val sessionBy = ConcurrentHashMap<String, List<Rule>>()
    private val dynamicBy = ConcurrentHashMap<String, List<Rule>>()
    private class Static(val sig: String, val rules: List<Rule>)
    private val staticBy = ConcurrentHashMap<String, Static>()
    private val lock = Any()
    private var app: Context? = null
    private var inited = false
    private const val TAG = "MGB.dnr"

    private fun sessionOf(id: String): List<Rule> = sessionBy[id] ?: emptyList()

    private fun dynKey(id: String) = if (id == ContentInjector.EXT_ID) "dnr_dynamic" else "dnr_dynamic_$id"

    private fun dynamicOf(id: String): List<Rule> {
        dynamicBy[id]?.let { return it }
        val c = app ?: return emptyList()
        val loaded = try { parse(JSONArray(c.getSharedPreferences(PREFS, 0).getString(dynKey(id), "[]"))) } catch (e: Exception) { emptyList() }
        return dynamicBy.putIfAbsent(id, loaded) ?: loaded
    }

    // ---------- static rulesets (installed extensions only) ----------

    private class RS(val id: String, val path: String, val on: Boolean)

    private fun declared(info: ExtRegistry.Info): List<RS> {
        val out = ArrayList<RS>()
        try {
            val mf = JSONObject(File(info.dir, "manifest.json").readText())
            val arr = mf.optJSONObject("declarative_net_request")?.optJSONArray("rule_resources") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val path = o.optString("path")
                if (id.isNotEmpty() && path.isNotEmpty()) out.add(RS(id, path, o.optBoolean("enabled", false)))
            }
        } catch (e: Exception) { Log.w(TAG, "manifest rule_resources", e) }
        return out
    }

    private fun overrideRaw(id: String): String =
        app?.getSharedPreferences(PREFS, 0)?.getString("dnr_rs_$id", "") ?: ""

    /** ids of the static rulesets that are on: manifest default, or the list saved by updateEnabledRulesets. */
    private fun enabledIds(info: ExtRegistry.Info): List<String> {
        val raw = overrideRaw(info.id)
        if (raw.isNotEmpty()) {
            try {
                val a = JSONArray(raw)
                return (0 until a.length()).map { a.optString(it) }
            } catch (e: Exception) { /* fall back to the manifest */ }
        }
        return declared(info).filter { it.on }.map { it.id }
    }

    private fun safeRead(dir: File, rel: String): String? {
        if (rel.isBlank() || rel.contains("..")) return null
        val f = File(dir, rel.trimStart('/'))
        val ok = try { f.canonicalPath.startsWith(dir.canonicalPath + File.separator) } catch (e: Exception) { false }
        return if (ok && f.isFile && f.length() <= 2_000_000L) f.readText() else null
    }

    private fun staticOf(info: ExtRegistry.Info): List<Rule> {
        val mf = File(info.dir, "manifest.json")
        val sig = "${info.version}:${mf.lastModified()}:${mf.length()}:${overrideRaw(info.id)}"
        staticBy[info.id]?.let { if (it.sig == sig) return it.rules }
        val out = ArrayList<Rule>()
        val on = enabledIds(info).toSet()
        for (rs in declared(info)) {
            if (rs.id !in on) continue
            try {
                val txt = safeRead(info.dir, rs.path) ?: continue
                out.addAll(parse(JSONArray(txt)))
            } catch (e: Exception) { Log.w(TAG, "${info.id}: ruleset ${rs.id} skipped", e) }
        }
        staticBy[info.id] = Static(sig, out)
        return out
    }

    /** ExtApi.sync: an extension was removed. */
    fun forget(id: String) {
        if (id == ContentInjector.EXT_ID) return
        sessionBy.remove(id); dynamicBy.remove(id); staticBy.remove(id)
        app?.getSharedPreferences(PREFS, 0)?.edit()?.remove(dynKey(id))?.remove("dnr_rs_$id")?.apply()
    }

    // ---------- applying ----------

    /**
     * Change `h` (request headers, case-insensitive map) the way the matching rules say.
     * type = webRequest resource type (xmlhttprequest, media, image, other ...).
     */
    fun apply(url: String, tabId: Int, type: String, method: String, h: TreeMap<String, String>, extId: String = ContentInjector.EXT_ID) {
        val info: ExtRegistry.Info? = if (extId == ContentInjector.EXT_ID) null else ExtRegistry.get(extId)
        if (extId != ContentInjector.EXT_ID && (info == null || !info.enabled)) return // unknown or turned off extension
        val all = sessionOf(extId) + dynamicOf(extId) + (if (info != null) staticOf(info) else emptyList<Rule>())
        if (all.isEmpty()) return
        if (info != null && !ExtApi.hostAllows(info, url)) return // S7: no host permission for this url = no header change
        val host = try { URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        val hit = all.filter { it.actions.isNotEmpty() && it.matches(url, host, tabId, type, method, extId) }
            .sortedWith(compareByDescending<Rule> { it.priority }.thenBy { it.id })
        if (hit.isEmpty()) return
        val locked = HashSet<String>()   // headers a higher rule already set or removed
        for (r in hit) for (a in r.actions) {
            val name = a.optString("header")
            if (name.isEmpty()) continue
            val lk = name.lowercase()
            when (a.optString("operation")) {
                "remove" -> if (lk !in locked) { h.remove(name); locked.add(lk) }
                "set" -> if (lk !in locked) { h[name] = a.optString("value"); locked.add(lk) }
                "append" -> if (lk !in locked) {
                    val old = h[name]
                    h[name] = if (old.isNullOrEmpty()) a.optString("value") else old + ", " + a.optString("value")
                }
            }
        }
    }

    // ---------- api ----------

    private fun parse(arr: JSONArray?): List<Rule> {
        val out = ArrayList<Rule>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("id")) throw IllegalArgumentException("Rule is missing an id.")
            val act = o.optJSONObject("action")
            if (act == null || act.optString("type") != "modifyHeaders") {
                // only modifyHeaders is meaningful here: store it so get*Rules answers, but it never matches anything
                out.add(Rule(o.optInt("id"), JSONObject().put("id", o.optInt("id")).put("action", JSONObject().put("type", act?.optString("type") ?: "")).put("condition", o.optJSONObject("condition") ?: JSONObject())))
                continue
            }
            val hs = act.optJSONArray("requestHeaders")
            if (hs == null || hs.length() == 0) throw IllegalArgumentException("Rule ${o.optInt("id")} has no headers to modify.")
            for (k in 0 until hs.length()) {
                val x = hs.optJSONObject(k)
                val op = x?.optString("operation") ?: ""
                if (x == null || x.optString("header").isEmpty() || op !in setOf("set", "append", "remove"))
                    throw IllegalArgumentException("Rule ${o.optInt("id")} has an invalid header action.")
                if (op != "remove" && !x.has("value")) throw IllegalArgumentException("Rule ${o.optInt("id")}: value is required.")
            }
            out.add(Rule(o.getInt("id"), o))
        }
        return out
    }

    /** Remove first, then add; an id that already exists is an error and nothing changes (like Chrome). */
    private fun update(cur: List<Rule>, opts: JSONObject): List<Rule> {
        val rm = opts.optJSONArray("removeRuleIds")
        val gone = HashSet<Int>()
        if (rm != null) for (i in 0 until rm.length()) gone.add(rm.optInt(i))
        val keep = cur.filter { it.id !in gone }.toMutableList()
        val add = parse(opts.optJSONArray("addRules"))
        val ids = keep.map { it.id }.toHashSet()
        for (r in add) if (!ids.add(r.id)) throw IllegalArgumentException("Rule with id ${r.id} does not have a unique ID.")
        keep.addAll(add)
        if (keep.size > 5000) throw IllegalArgumentException("Too many rules.")
        return keep
    }

    private fun list(rules: List<Rule>): JSONArray {
        val a = JSONArray()
        for (r in rules) a.put(r.json)
        return a
    }

    private fun saveDynamic(id: String) {
        val c = app ?: return
        c.getSharedPreferences(PREFS, 0).edit().putString(dynKey(id), list(dynamicOf(id)).toString()).apply()
    }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        // V77 dynamic rules are loaded on first use (dynamicOf), like those of every extension

        // c.extId = who is calling (V77 or an installed extension; ExtApi.gate already checked its permission)
        ChromeBridge.onSync("declarativeNetRequest.updateSessionRules") { c ->
            synchronized(lock) { sessionBy[c.extId] = update(sessionOf(c.extId), c.args.optJSONObject(0) ?: JSONObject()) }
            null
        }
        ChromeBridge.onSync("declarativeNetRequest.updateDynamicRules") { c ->
            synchronized(lock) {
                dynamicBy[c.extId] = update(dynamicOf(c.extId), c.args.optJSONObject(0) ?: JSONObject())
                saveDynamic(c.extId)
            }
            null
        }
        ChromeBridge.onSync("declarativeNetRequest.getSessionRules") { c -> list(sessionOf(c.extId)) }
        ChromeBridge.onSync("declarativeNetRequest.getDynamicRules") { c -> list(dynamicOf(c.extId)) }
        ChromeBridge.onSync("declarativeNetRequest.getEnabledRulesets") { c ->
            val a = JSONArray()
            val info = if (c.extId == ContentInjector.EXT_ID) null else ExtRegistry.get(c.extId)
            if (info != null) for (id in enabledIds(info)) a.put(id)
            a
        }
        ChromeBridge.onSync("declarativeNetRequest.updateEnabledRulesets") { c ->
            val info = if (c.extId == ContentInjector.EXT_ID) null else ExtRegistry.get(c.extId)
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val known: List<String> = if (info == null) emptyList<String>() else declared(info).map { it.id }
            val cur = LinkedHashSet<String>(if (info == null) emptyList<String>() else enabledIds(info))
            val on = strsRaw(o.optJSONArray("enableRulesetIds"))
            val off = strsRaw(o.optJSONArray("disableRulesetIds"))
            for (id in on + off) if (id !in known) throw IllegalArgumentException("Invalid ruleset ID: $id.")
            cur.removeAll(off.toSet())
            cur.addAll(on)
            if (info != null) {
                val arr = JSONArray()
                for (id in cur) arr.put(id)
                app?.getSharedPreferences(PREFS, 0)?.edit()?.putString("dnr_rs_${info.id}", arr.toString())?.apply()
                staticBy.remove(info.id)
            }
            null
        }
    }

    private fun strsRaw(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun status(): String {
        val v = ContentInjector.EXT_ID
        var ext = 0
        for ((k, r) in sessionBy) if (k != v) ext += r.size
        for ((k, r) in dynamicBy) if (k != v) ext += r.size
        for ((k, r) in staticBy) if (k != v) ext += r.rules.size
        return "\nHeader rules: session ${sessionOf(v).size}, dynamic ${dynamicOf(v).size}, installed extensions $ext"
    }
}
