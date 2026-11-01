package com.jabra.mgb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.TreeMap

/**
 * S8: chrome.declarativeNetRequest (modifyHeaders rules only) for requests the extension makes itself.
 * Those are the requests of the engine page and of extension pages opened as tabs: both go through
 * EngineHost.proxyFor(), which calls apply() right before the request is sent (every redirect hop again).
 * Page requests are never modified (V77 rules always carry initiatorDomains = the extension id).
 * Session rules live in memory, dynamic rules are kept in prefs mgb_engine (key dnr_dynamic).
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

        fun matches(url: String, host: String, tabId: Int, type: String, method: String): Boolean {
            if (urlRe != null && !urlRe.containsMatchIn(url)) return false
            if (cond.has("regexFilter") && (reRe == null || !reRe.containsMatchIn(url))) return false
            if (domains.isNotEmpty() && !hostIn(host, domains)) return false
            if (exDomains.isNotEmpty() && hostIn(host, exDomains)) return false
            // every request we modify was started by the extension
            if (initDomains.isNotEmpty() && !initDomains.contains(ContentInjector.EXT_ID.lowercase()) &&
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

    @Volatile private var session: List<Rule> = emptyList()
    @Volatile private var dynamic: List<Rule> = emptyList()
    private var app: Context? = null
    private var inited = false

    // ---------- applying ----------

    /**
     * Change `h` (request headers, case-insensitive map) the way the matching rules say.
     * type = webRequest resource type (xmlhttprequest, media, image, other ...).
     */
    fun apply(url: String, tabId: Int, type: String, method: String, h: TreeMap<String, String>) {
        val all = session + dynamic
        if (all.isEmpty()) return
        val host = try { URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        val hit = all.filter { it.actions.isNotEmpty() && it.matches(url, host, tabId, type, method) }
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

    private fun saveDynamic() {
        val c = app ?: return
        c.getSharedPreferences(PREFS, 0).edit().putString("dnr_dynamic", list(dynamic).toString()).apply()
    }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        try {
            dynamic = parse(JSONArray(app!!.getSharedPreferences(PREFS, 0).getString("dnr_dynamic", "[]")))
        } catch (e: Exception) { dynamic = emptyList() }

        ChromeBridge.onSync("declarativeNetRequest.updateSessionRules") { c ->
            session = update(session, c.args.optJSONObject(0) ?: JSONObject()); null
        }
        ChromeBridge.onSync("declarativeNetRequest.updateDynamicRules") { c ->
            dynamic = update(dynamic, c.args.optJSONObject(0) ?: JSONObject()); saveDynamic(); null
        }
        ChromeBridge.onSync("declarativeNetRequest.getSessionRules") { list(session) }
        ChromeBridge.onSync("declarativeNetRequest.getDynamicRules") { list(dynamic) }
    }

    fun status(): String = "\nHeader rules: session ${session.size}, dynamic ${dynamic.size}"
}
