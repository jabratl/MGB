package com.jabra.mgb

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
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
        // 0.0.119 (S5): block / redirect / allow rules for PAGE requests
        val kind: String = json.optJSONObject("action")?.optString("type") ?: ""
        val redirect: JSONObject? = json.optJSONObject("action")?.optJSONObject("redirect")
        private val initOnly = strs(cond.optJSONArray("initiatorDomains"))
        private val excInit = strs(cond.optJSONArray("excludedInitiatorDomains"))
        private val domType = cond.optString("domainType", "")
        fun regex(): Regex? = reRe

        init {
            val a = json.optJSONObject("action")?.optJSONArray("requestHeaders")
            actions = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            val opt = if (cond.optBoolean("isUrlFilterCaseSensitive", false)) emptySet() else setOf(RegexOption.IGNORE_CASE)
            urlRe = if (cond.has("urlFilter")) urlFilterRegex(cond.optString("urlFilter"), opt) else null
            reRe = if (cond.has("regexFilter")) try { Regex(cond.optString("regexFilter"), opt) } catch (e: Exception) { null } else null
        }

        private fun hostIn(host: String, list: List<String>) = list.any { host == it || host.endsWith(".$it") }

        /** 0.0.119 (S5): a request a web page made. initiator = host of the page (null = browser navigation). Cheap checks first (static rulesets can hold thousands of rules). */
        fun matchesPage(url: String, host: String, tabId: Int, type: String, method: String, initiator: String?): Boolean {
            // no resourceTypes = everything except main_frame; "xmlhttprequest" is also our guess for a request without any hint (fetch, xhr, script without ending), so a rule that lists "other" matches it too
            if (types.isEmpty()) { if (type == "main_frame") return false } else if (!types.contains(type) && !(type == "xmlhttprequest" && types.contains("other"))) return false
            if (exTypes.contains(type)) return false
            if (methods.isNotEmpty() && !methods.contains(method.lowercase())) return false
            if (tabIds != null && !tabIds.contains(tabId)) return false
            if (excTabIds != null && excTabIds.contains(tabId)) return false
            if (domains.isNotEmpty() && !hostIn(host, domains)) return false
            if (exDomains.isNotEmpty() && hostIn(host, exDomains)) return false
            if (initOnly.isNotEmpty() && (initiator == null || !hostIn(initiator, initOnly))) return false
            if (excInit.isNotEmpty() && initiator != null && hostIn(initiator, excInit)) return false
            if (domType == "thirdParty" && (initiator == null || site(host) == site(initiator))) return false
            if (domType == "firstParty" && initiator != null && site(host) != site(initiator)) return false
            if (urlRe != null && !urlRe.containsMatchIn(url)) return false
            if (cond.has("regexFilter") && (reRe == null || !reRe.containsMatchIn(url))) return false
            return true
        }

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
    private val ACT_TYPES = setOf("block", "redirect", "allow", "allowAllRequests", "upgradeScheme") // 0.0.119 (S5)
    private val staticAt = ConcurrentHashMap<String, Long>()
    private val blocked = AtomicInteger()
    private val redirected = AtomicInteger()
    private val lastMain = ConcurrentHashMap<Int, LongArray>() // tabId -> [count, time] (redirect loop guard for main frame)
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
        sessionBy.remove(id); dynamicBy.remove(id); staticBy.remove(id); staticAt.remove(id)
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

    // ---------- 0.0.119 (S5): declarativeNetRequest block / redirect / allow / upgradeScheme for page requests ----------

    /** What a rule decided for one page request. kind = block | redirect | ext (extensionPath). */
    class Verdict(val kind: String, val extId: String, val url: String? = null, val path: String? = null)

    private fun site(h: String): String = h.split('.').takeLast(2).joinToString(".") // rough "same site" (no public suffix list)

    private fun order(k: String): Int = when (k) { "allow", "allowAllRequests" -> 0; "block" -> 1; "upgradeScheme" -> 2; else -> 3 }

    private fun staticQuick(info: ExtRegistry.Info): List<Rule> {
        val now = System.currentTimeMillis()
        val t = staticAt[info.id]
        val c = staticBy[info.id]
        if (t != null && c != null && now - t < 3000L) return c.rules // not a file check on every request
        val r = staticOf(info)
        staticAt[info.id] = now
        return r
    }

    /** webRequest resource type of a page request (WebView gives no exact type: Sec-Fetch-Dest if present, else file ending / Accept). */
    fun typeFor(url: String, main: Boolean, h: Map<String, String>?): String {
        if (main) return "main_frame"
        fun hv(n: String): String? = h?.entries?.firstOrNull { it.key.equals(n, true) }?.value?.lowercase()
        when (hv("Sec-Fetch-Dest")) {
            "iframe", "frame" -> return "sub_frame"
            "script" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "font" -> return "font"
            "video", "audio", "track" -> return "media"
            "object", "embed" -> return "object"
            "empty" -> return "xmlhttprequest"
        }
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "")
        when (ext) {
            "js", "mjs" -> return "script"
            "css" -> return "stylesheet"
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> return "image"
            "woff", "woff2", "ttf", "otf", "eot" -> return "font"
            "mp4", "webm", "m3u8", "mpd", "mp3", "m4a", "m4s", "ogg", "wav", "ts", "mkv" -> return "media"
        }
        val acc = hv("Accept") ?: ""
        if (acc.contains("text/css")) return "stylesheet"
        if (acc.startsWith("image/") || acc.contains("image/webp") || acc.contains("image/avif")) return "image"
        if (acc.contains("text/html")) return "sub_frame"
        return "xmlhttprequest" // no hint: the WebView does not tell fetch / xhr / script / other apart
    }

    /** Origin (scheme://host[:port]) of the page that made a request: Origin header, else Referer, else the tab url. */
    fun initiatorOf(h: Map<String, String>?, tabUrl: String): String? {
        fun hv(n: String): String? = h?.entries?.firstOrNull { it.key.equals(n, true) }?.value
        val src = hv("Origin")?.takeIf { it.startsWith("http") } ?: hv("Referer")?.takeIf { it.startsWith("http") }
            ?: tabUrl.takeIf { it.startsWith("http") } ?: return null
        return try {
            val u = URI(src)
            if (u.host == null) null else u.scheme + "://" + u.host + (if (u.port >= 0) ":" + u.port else "")
        } catch (e: Exception) { null }
    }

    private fun substitute(re: Regex, url: String, sub: String): String? {
        val m = re.find(url) ?: return null
        val sb = StringBuilder()
        var i = 0
        while (i < sub.length) {
            val ch = sub[i]
            if (ch == '\\' && i + 1 < sub.length) {
                val n = sub[i + 1]
                if (n in '0'..'9') { val g = n - '0'; if (g < m.groupValues.size) sb.append(m.groupValues[g]); i += 2; continue }
                if (n == '\\') { sb.append('\\'); i += 2; continue }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun queryTransform(q: String, qt: JSONObject): String {
        val parts = if (q.isEmpty()) ArrayList<String>() else ArrayList(q.split("&"))
        val rm = qt.optJSONArray("removeParams")
        if (rm != null) for (i in 0 until rm.length()) { val k = rm.optString(i); parts.removeAll { it.substringBefore('=') == k } }
        val add = qt.optJSONArray("addOrReplaceParams")
        if (add != null) for (i in 0 until add.length()) {
            val o = add.optJSONObject(i) ?: continue
            val k = o.optString("key")
            val ek = java.net.URLEncoder.encode(k, "UTF-8")
            val enc = ek + "=" + java.net.URLEncoder.encode(o.optString("value"), "UTF-8")
            var found = false
            for (j in parts.indices) { val pk = parts[j].substringBefore('='); if (pk == k || pk == ek) { parts[j] = enc; found = true } }
            if (!found && !o.optBoolean("replaceOnly", false)) parts.add(enc)
        }
        return parts.joinToString("&")
    }

    private fun transform(url: String, tr: JSONObject): String? = try {
        val u = URI(url)
        val scheme = if (tr.has("scheme")) tr.optString("scheme") else (u.scheme ?: "")
        val host = if (tr.has("host")) tr.optString("host") else (u.host ?: "")
        val port = if (tr.has("port")) tr.optString("port") else (if (u.port >= 0) u.port.toString() else "")
        val path = if (tr.has("path")) tr.optString("path") else (u.rawPath ?: "")
        var query = if (tr.has("query")) tr.optString("query").removePrefix("?") else (u.rawQuery ?: "")
        val frag = if (tr.has("fragment")) tr.optString("fragment").removePrefix("#") else (u.rawFragment ?: "")
        val qt = tr.optJSONObject("queryTransform")
        if (qt != null) query = queryTransform(query, qt)
        val user = if (tr.has("username")) tr.optString("username") else (u.rawUserInfo?.substringBefore(':') ?: "")
        val pass = if (tr.has("password")) tr.optString("password") else (u.rawUserInfo?.substringAfter(':', "") ?: "")
        val auth = if (user.isNotEmpty()) user + (if (pass.isNotEmpty()) ":" + pass else "") + "@" else ""
        val p = if (path.isEmpty() || path.startsWith("/")) path else "/" + path
        scheme + "://" + auth + host + (if (port.isNotEmpty()) ":" + port else "") + p + (if (query.isNotEmpty()) "?" + query else "") + (if (frag.isNotEmpty()) "#" + frag else "")
    } catch (e: Exception) { null }

    /** Redirect target of one redirect rule (null = cannot build one). */
    private fun redirectOf(r: Rule, url: String, xid: String): Verdict? {
        val rd = r.redirect ?: return null
        val ep = rd.optString("extensionPath", "")
        if (ep.isNotEmpty()) return Verdict("ext", xid, null, ep)
        val to: String? = when {
            rd.optString("url", "").isNotEmpty() -> rd.optString("url")
            rd.has("regexSubstitution") -> r.regex()?.let { substitute(it, url, rd.optString("regexSubstitution")) }
            rd.has("transform") -> transform(url, rd.optJSONObject("transform") ?: JSONObject())
            else -> null
        }
        if (to == null || to == url) return null
        return Verdict("redirect", xid, to)
    }

    /**
     * Per installed extension: the matching rule with the highest priority wins (same priority: allow, block, upgradeScheme, redirect).
     * An extension with "declarativeNetRequest" may block anywhere; redirect / upgradeScheme (and everything with only ...WithHostAccess) need a host permission for the url.
     * initiator = origin of the page (null for a main frame navigation). Never V77.
     */
    fun decide(url: String, tabId: Int, type: String, method: String, initiator: String?): Verdict? {
        val host = try { URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        val ini = initiator?.let { try { URI(it).host?.lowercase() } catch (e: Exception) { null } }
        for (info in ExtRegistry.enabled()) {
            val full = "declarativeNetRequest" in info.permissions
            if (!full && "declarativeNetRequestWithHostAccess" !in info.permissions) continue
            val rules = sessionOf(info.id) + dynamicOf(info.id) + staticQuick(info)
            if (rules.isEmpty()) continue
            var best: Rule? = null
            for (r in rules) {
                if (r.kind !in ACT_TYPES || !r.matchesPage(url, host, tabId, type, method, ini)) continue
                val b = best
                if (b == null || r.priority > b.priority || (r.priority == b.priority && order(r.kind) < order(b.kind))) best = r
            }
            val w = best ?: continue
            val hostOk = ExtApi.hostAllows(info, url)
            when (w.kind) {
                "block" -> if (full || hostOk) return Verdict("block", info.id)
                "upgradeScheme" -> if (hostOk && url.startsWith("http://")) return Verdict("redirect", info.id, "https://" + url.substring(7))
                "redirect" -> if (hostOk) { val v = redirectOf(w, url, info.id); if (v != null) return v }
            }
        }
        return null
    }

    class RedirectRequest(private val src: WebResourceRequest, private val to: Uri) : WebResourceRequest {
        override fun getUrl(): Uri = to
        override fun isForMainFrame(): Boolean = src.isForMainFrame
        override fun isRedirect(): Boolean = true
        override fun hasGesture(): Boolean = src.hasGesture()
        override fun getMethod(): String = src.method
        override fun getRequestHeaders(): MutableMap<String, String> = src.requestHeaders
    }

    private fun html(code: Int, reason: String, body: String): WebResourceResponse =
        WebResourceResponse("text/html", "utf-8", code, reason, HashMap<String, String>(), ByteArrayInputStream(("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" + body).toByteArray()))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun dataResponse(to: String): WebResourceResponse? {
        val comma = to.indexOf(',')
        if (comma < 0) return null
        val meta = to.substring(5, comma)
        val payload = to.substring(comma + 1)
        val mime = meta.removeSuffix(";base64").substringBefore(';').ifEmpty { "text/plain" }
        val bytes = try {
            if (meta.endsWith(";base64")) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            else java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
        } catch (e: Exception) { return null }
        return WebResourceResponse(mime, "utf-8", 200, "OK", hashMapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
    }

    private fun mainLoop(tabId: Int): Boolean {
        val now = System.currentTimeMillis()
        val a = lastMain.getOrPut(tabId) { longArrayOf(0L, now) }
        if (now - a[1] > 5000L) a[0] = 0L
        a[1] = now
        a[0] = a[0] + 1
        return a[0] > 10L
    }

    private fun respond(v: Verdict, r: WebResourceRequest, tabUrl: String, tabId: Int): WebResourceResponse? {
        val main = r.isForMainFrame
        if (v.kind == "block") {
            blocked.incrementAndGet()
            return if (main) html(403, "Forbidden", "<p>This page was blocked by an extension.</p><p>" + esc(r.url.toString()) + "</p>")
            else WebResourceResponse("text/plain", "utf-8", 404, "Blocked", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))
        }
        if (v.kind == "ext") {
            if (main) return null
            redirected.incrementAndGet()
            return EngineHost.extPublic(Uri.parse(ContentInjector.EXT_BASE + v.extId + "/" + (v.path ?: "").trimStart('/')), tabUrl, null) // must be web_accessible, like in Chrome
        }
        val to = v.url ?: return null
        if (main) {
            if (!to.startsWith("http://") && !to.startsWith("https://")) return null
            if (mainLoop(tabId)) return null
            redirected.incrementAndGet()
            return html(200, "OK", "<script>location.replace(" + JSONObject.quote(to) + ")</script><meta http-equiv=refresh content=\"0;url=" + esc(to) + "\">")
        }
        if (to.startsWith("data:")) { redirected.incrementAndGet(); return dataResponse(to) }
        if (!to.startsWith("http://") && !to.startsWith("https://")) return null
        val res = EngineHost.proxyFor(RedirectRequest(r, Uri.parse(to)), tabId, v.extId) ?: return null
        redirected.incrementAndGet()
        val org = r.requestHeaders?.entries?.firstOrNull { it.key.equals("Origin", true) }?.value
        val h = HashMap<String, String>(res.responseHeaders ?: emptyMap())
        h["Access-Control-Allow-Origin"] = org ?: "*"
        if (org == null) h.remove("Access-Control-Allow-Credentials")
        res.responseHeaders = h
        return res
    }

    /**
     * TabManager.shouldInterceptRequest of a normal (not private) tab: webRequest events for installed extensions, then declarativeNetRequest.
     * null = load as usual. Cheap when no extension asks for either.
     */
    fun pageRequest(r: WebResourceRequest, tabId: Int, tabUrl: String): WebResourceResponse? {
        val url = r.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val dnr = ExtRegistry.enabled().any { "declarativeNetRequest" in it.permissions || "declarativeNetRequestWithHostAccess" in it.permissions }
        val wr = ExtApi.webRequestActive()
        if (!dnr && !wr) return null
        val main = r.isForMainFrame
        val rh: Map<String, String>? = r.requestHeaders
        val type = typeFor(url, main, rh)
        val ini = if (main) null else initiatorOf(rh, tabUrl)
        if (wr) ExtApi.webRequest("webRequest.onBeforeRequest", url, tabId, type, r.method, ini, null, null)
        val v = if (dnr) try { decide(url, tabId, type, r.method, ini) } catch (e: Exception) { Log.e(TAG, "decide", e); null } else null
        if (v == null) {
            if (wr) {
                ExtApi.webRequest("webRequest.onBeforeSendHeaders", url, tabId, type, r.method, ini, rh, null)
                ExtApi.webRequest("webRequest.onSendHeaders", url, tabId, type, r.method, ini, rh, null)
            }
            return null
        }
        if (wr && v.kind == "block") ExtApi.webRequest("webRequest.onErrorOccurred", url, tabId, type, r.method, ini, null, "net::ERR_BLOCKED_BY_CLIENT")
        return try { respond(v, r, tabUrl, tabId) } catch (e: Exception) { Log.e(TAG, "respond", e); null }
    }

    /** TabManager.onReceivedError (normal tab): webRequest.onErrorOccurred. */
    fun pageError(r: WebResourceRequest, tabId: Int, tabUrl: String, error: String) {
        val url = r.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://") || !ExtApi.webRequestActive()) return
        val rh: Map<String, String>? = r.requestHeaders
        ExtApi.webRequest("webRequest.onErrorOccurred", url, tabId, typeFor(url, r.isForMainFrame, rh), r.method,
            if (r.isForMainFrame) null else initiatorOf(rh, tabUrl), null, error)
    }

    // ---------- api ----------

    private fun parse(arr: JSONArray?): List<Rule> {
        val out = ArrayList<Rule>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has("id")) throw IllegalArgumentException("Rule is missing an id.")
            val act = o.optJSONObject("action")
            val at = act?.optString("type") ?: ""
            if (at in ACT_TYPES && actRuleValid(o, act)) { // 0.0.119 (S5): acts on page requests (decide); an incomplete rule is stored inert like before
                out.add(Rule(o.getInt("id"), o))
                continue
            }
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

    private fun actRuleValid(o: JSONObject, act: JSONObject?): Boolean {
        if (act == null || !o.has("condition")) return false
        if (act.optString("type") != "redirect") return true
        val rd = act.optJSONObject("redirect") ?: return false
        if (!(rd.has("url") || rd.has("extensionPath") || rd.has("regexSubstitution") || rd.has("transform"))) return false
        if (rd.has("regexSubstitution") && !(o.optJSONObject("condition")?.has("regexFilter") ?: false)) return false
        return true
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
        ChromeBridge.onSync("declarativeNetRequest.isRegexSupported") { c -> // 0.0.119 (S5)
            val re = c.args.optJSONObject(0)?.optString("regex") ?: ""
            try { Regex(re); JSONObject().put("isSupported", true) } catch (e: Exception) { JSONObject().put("isSupported", false).put("reason", "syntaxError") }
        }
        ChromeBridge.onSync("declarativeNetRequest.setExtensionActionOptions") { null } // 0.0.119 (S5): no badge counter
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
        return "\nHeader rules: session ${sessionOf(v).size}, dynamic ${dynamicOf(v).size}, installed extensions $ext" +
            "\nPage requests (installed extensions): blocked ${blocked.get()}, redirected ${redirected.get()}"
    }
}
