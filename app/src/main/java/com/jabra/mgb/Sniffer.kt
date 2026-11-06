package com.jabra.mgb

import android.content.Context
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8: replaces chrome.webRequest. A WebView cannot show response headers of page requests, so:
 *  - onBeforeSendHeaders: sent for every page request that can matter (media, xhr/fetch, other), with the
 *    headers the WebView sends (Referer filled in when missing), exactly what V77 records per host.
 *  - onHeadersReceived: playlists and subtitles by file extension get a synthetic 200 (no extra request);
 *    media files and URLs that look like a stream get a real probe (HEAD, or GET with Range 0-0) so
 *    V77 sees status, content-type and size.
 * Events go to the engine page only. Requests of extension pages are never sniffed.
 */
object Sniffer {
    private val PLAYLIST = setOf("m3u8", "mpd", "vtt", "srt", "ass", "ssa", "ttml", "dfxp")
    private val MEDIA = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "avi", "ogv", "3gp",
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val SEGMENT = setOf("ts", "m4s", "cmfv", "cmfa", "key")
    private val SKIP = setOf("js", "css", "html", "htm", "svg", "ico", "woff", "woff2", "ttf", "otf", "map", "wasm",
        "png", "jpg", "jpeg", "gif", "webp", "avif", "bmp")
    private val SUB_URL = Regex("(subtitle|caption|timedtext|/subs?/|/cc/)", RegexOption.IGNORE_CASE)
    private val HINT = Regex("(m3u8|mpd|manifest|playlist|master|video|stream|media|hls|dash)", RegexOption.IGNORE_CASE)
    private val DROP_REQ = setOf("x-requested-with", "host", "content-length", "connection", "accept-encoding")

    private class TabState {
        var guesses = 0
        val dirs = HashMap<String, Int>()
        val seen = LinkedHashMap<String, Long>()
    }

    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(40), ThreadPoolExecutor.DiscardPolicy())
    private val states = ConcurrentHashMap<Int, TabState>()
    private val hostSeen = ConcurrentHashMap<String, Long>()
    private val lastRef = ConcurrentHashMap<String, String>()   // host -> Referer the page used for it
    private val seq = AtomicInteger(1)
    private val sentBefore = AtomicInteger()
    private val sentAfter = AtomicInteger()
    private val probes = AtomicInteger()
    private val probeFail = AtomicInteger()
    private var inited = false

    private class Probe(val code: Int, val headers: List<Pair<String, String>>)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        // direct downloads (downloads.download of a media url) get the Referer the page used for that host
        DownloadsApi.headerProvider = { url ->
            val h = hostOf(url)
            val ref = if (h.isEmpty()) null else lastRef[h]
            if (ref.isNullOrEmpty()) null else mapOf("Referer" to ref)
        }
    }

    fun status(): String = "\nSniffer: before-send $sentBefore, headers-received $sentAfter, probes $probes (failed $probeFail)"

    private fun hostOf(url: String): String = try { URL(url).host.lowercase() } catch (e: Exception) { "" }

    private fun extOf(url: String): String {
        val p = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val e = p.substringAfterLast('.', "").lowercase()
        return if (e.length in 1..5 && e.all { it.isLetterOrDigit() }) e else ""
    }

    private fun originOf(url: String): String = try {
        val u = URL(url); u.protocol + "://" + u.host + (if (u.port > 0 && u.port != u.defaultPort) ":" + u.port else "")
    } catch (e: Exception) { "" }

    private fun mimeOf(ext: String): String = when (ext) {
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        "vtt" -> "text/vtt"
        "srt" -> "application/x-subrip"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun emit(event: String, d: JSONObject) {
        ChromeBridge.emit(event, JSONArray().put(d)) { it.kind == ChromeBridge.Kind.EXT && EngineHost.isEngine(it.web) }
    }

    /** Called from TabManager.shouldInterceptRequest (any thread) for every request of a web page tab. */
    fun onRequest(tab: TabManager.Tab, r: WebResourceRequest) {
        try { inspect(tab, r) } catch (e: Exception) { Diag.e("sniffer", "inspect crashed", e) /* S83; sniffing must never break page loading */ }
    }

    private fun inspect(tab: TabManager.Tab, r: WebResourceRequest) {
        val url = r.url.toString()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (!TabsApi.isEngineReady()) TabsApi.noteMissed(tab.id) // 0.0.166: engine not up yet, this request is lost
        if (r.isForMainFrame) {
            states.remove(tab.id)
            if (states.size > 100) states.clear()
        }
        if (r.method != "GET") return
        val rh: Map<String, String> = r.requestHeaders ?: emptyMap()
        val dest = rh.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value?.lowercase()
        val ext = extOf(url)
        val type = if (r.isForMainFrame) "main_frame" else when (dest) {
            "video", "audio", "track" -> "media"
            "empty" -> "xmlhttprequest"
            null, "", "object", "embed" -> "other"
            else -> return
        }
        val playlist = ext in PLAYLIST
        val media = ext in MEDIA || (dest == "video" || dest == "audio") && ext !in SEGMENT
        val segment = ext in SEGMENT
        val subJson = (ext == "json" || ext == "xml" || ext == "txt") && SUB_URL.containsMatchIn(url)
        if (r.isForMainFrame && !(playlist || ext in MEDIA)) return
        if (ext in SKIP && !(ext == "css" || ext == "ico") ) return
        if ((ext == "css" || ext == "ico") && type != "media" && type != "xmlhttprequest") return
        val st = states.getOrPut(tab.id) { TabState() }
        val now = System.currentTimeMillis()
        val host = hostOf(url)
        var guess = false
        if (!playlist && !media && !segment && !subJson) {
            // unknown extension: only look at it when the url smells like a stream, and not too often
            if (type == "main_frame" || !HINT.containsMatchIn(url)) {
                if (type == "main_frame") return
                // still tell the engine about the headers once per host (V77 records them from any xhr/media request)
                if (!hostDue(host, now)) return
            } else {
                val dir = url.substringBefore('?').substringBeforeLast('/')
                synchronized(st) {
                    if (st.guesses >= 40 || (st.dirs[dir] ?: 0) >= 2) return
                    st.guesses++; st.dirs[dir] = (st.dirs[dir] ?: 0) + 1
                }
                guess = true
            }
        }
        if (segment && !hostDue(host, now)) return
        synchronized(st) {
            val t = st.seen[url]
            if (t != null && now - t < 20000) return
            st.seen[url] = now
            if (st.seen.size > 200) { val ei = st.seen.entries.iterator(); ei.next(); ei.remove() }
        }

        val tabUrl = tab.url
        // 0.0.221: a request from a player iframe (kwik.cx) has no Referer here; the top page (animepahe.pw) is the WRONG one. A browser sends the frame's origin cross-site.
        val ref = rh.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            ?: rh.entries.firstOrNull { it.key.equals("Origin", true) }?.value?.takeIf { it.startsWith("http") }?.let { it.trimEnd('/') + "/" }
            ?: tabUrl
        if (host.isNotEmpty() && ref.isNotEmpty()) lastRef[host] = ref
        val headers = JSONArray()
        for ((k, v) in rh) if (k.lowercase() !in DROP_REQ) headers.put(JSONObject().put("name", k).put("value", v))
        if (rh.keys.none { it.equals("Referer", true) } && ref.isNotEmpty()) headers.put(JSONObject().put("name", "Referer").put("value", ref))
        val id = seq.getAndIncrement().toString()
        val d = JSONObject()
            .put("requestId", id).put("url", url).put("method", "GET")
            .put("frameId", if (r.isForMainFrame) 0 else 1).put("parentFrameId", if (r.isForMainFrame) -1 else 0)
            .put("tabId", tab.id).put("type", type).put("timeStamp", now.toDouble())
            .put("initiator", originOf(tabUrl)).put("documentUrl", tabUrl)
            .put("requestHeaders", headers)
        emit("webRequest.onBeforeSendHeaders", d)
        sentBefore.incrementAndGet()

        // segments only matter for the header sample above
        if (segment || (!playlist && !media && !guess && !subJson)) return
        if (playlist || subJson) {
            val res = JSONArray().put(JSONObject().put("name", "Content-Type").put("value", if (subJson) "application/json" else mimeOf(ext)))
            emit("webRequest.onHeadersReceived", response(d, 200, res))
            sentAfter.incrementAndGet()
            return
        }
        pool.execute {
            probes.incrementAndGet()
            val p = probe(url, rh, ref)
            if (p == null) {
                probeFail.incrementAndGet()
                if (!guess) Diag.w("sniffer", "probe failed", url) // S83
                if (guess) return@execute
                // could not ask the server: report a plain 200 so V77 still lists the file
                val res = JSONArray().put(JSONObject().put("name", "Content-Type").put("value", mimeOf(ext)))
                emit("webRequest.onHeadersReceived", response(d, 200, res))
            } else {
                val res = JSONArray()
                for ((k, v) in p.headers) res.put(JSONObject().put("name", k).put("value", v))
                emit("webRequest.onHeadersReceived", response(d, p.code, res))
            }
            sentAfter.incrementAndGet()
        }
    }

    private fun hostDue(host: String, now: Long): Boolean {
        val t = hostSeen[host]
        if (t != null && now - t < 300000) return false
        hostSeen[host] = now
        if (hostSeen.size > 300) hostSeen.clear()
        return true
    }

    private fun response(req: JSONObject, code: Int, headers: JSONArray): JSONObject {
        val d = JSONObject(req.toString())
        d.remove("requestHeaders")
        return d.put("statusCode", code).put("statusLine", "HTTP/1.1 $code").put("responseHeaders", headers)
    }

    /** Ask the server what the player is about to get: HEAD first, then GET with Range 0-0. */
    private fun probe(url: String, rh: Map<String, String>, ref: String): Probe? {
        for (mode in 0..1) {
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.requestMethod = if (mode == 0) "HEAD" else "GET"
                for ((k, v) in rh) {
                    val lk = k.lowercase()
                    if (lk in DROP_REQ || lk == "range" || lk.startsWith("if-") || lk.startsWith("sec-")) continue
                    c.setRequestProperty(k, v)
                }
                if (rh.keys.none { it.equals("Referer", true) } && ref.isNotEmpty()) c.setRequestProperty("Referer", ref)
                if (rh.keys.none { it.equals("Cookie", true) }) {
                    CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                }
                c.setRequestProperty("Accept-Encoding", "identity")   // real Content-Length, no transparent gzip
                if (mode == 1) c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                if (mode == 0 && (code == 400 || code == 403 || code == 405 || code == 501 || code >= 500)) continue
                val out = ArrayList<Pair<String, String>>()
                for ((k, vs) in c.headerFields) {
                    if (k == null || k.equals("set-cookie", true)) continue
                    out.add(Pair(k, vs.joinToString(", ")))
                }
                return Probe(code, out)
            } catch (e: Exception) {
                if (mode == 1) return null
            } finally {
                try { c?.disconnect() } catch (e: Exception) { }
            }
        }
        return null
    }
}
