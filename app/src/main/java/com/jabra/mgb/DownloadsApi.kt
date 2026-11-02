package com.jabra.mgb

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock

/** FileProvider with its own class name, so it can never clash with another provider in the manifest. */
class MgbFileProvider : FileProvider()

/** One download (same fields chrome.downloads.DownloadItem has, plus a few private ones). */
class DlItem(val id: Int, val url: String) {
    @Volatile var finalUrl = url
    var referrer = ""
    var rel = ""            // relative path below the Downloads folder, e.g. "MGB/Title.mp4"
    var filename = ""       // full display path once known
    var uri = ""            // content:// uri (Android 10+) or absolute path
    var mime = ""
    @Volatile var state = "in_progress"
    @Volatile var paused = false
    @Volatile var canResume = false
    var error = ""
    @Volatile var received = 0L
    @Volatile var total = -1L
    var fileSize = -1L
    var start = System.currentTimeMillis()
    var end = 0L
    var exists = true
    var validator = ""
    var conflict = "uniquify"
    var headers = JSONArray()
    var method = "GET"
    var body: String? = null
    var src: WeakReference<WebView>? = null          // page that owns a blob: url
    val lock = ReentrantLock()
    @Volatile var call: okhttp3.Call? = null
}

/**
 * S7: chrome.downloads on top of ChromeBridge. Items live here (persisted in prefs mgb_downloads);
 * the transfer itself is Downloader (DownloadService.kt). S8 hook: headerProvider.
 */
object DownloadsApi {
    private const val PREFS = "mgb_downloads"
    private const val CH_RUN = "mgb_dl"
    private const val CH_DONE = "mgb_dl_done"
    private const val MAX_ITEMS = 600

    lateinit var app: Context
        private set
    private val items = LinkedHashMap<Int, DlItem>()
    private var nextId = 1
    private var inited = false
    private val main = Handler(Looper.getMainLooper())
    private var top: WeakReference<Activity>? = null

    /** S8 hook: extra request headers (Referer, Origin, Cookie...) for a download url. */
    var headerProvider: ((String) -> Map<String, String>?)? = null

    private fun handle(api: String, h: (ChromeBridge.Call) -> Unit) {
        ChromeBridge.on(api) { c ->
            main.post {
                try { h(c) } catch (e: Exception) { c.fail(e.message ?: e.toString()) }
            }
        }
    }

    // ---------- small helpers ----------

    fun publicDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun cleanRel(raw: String): String {
        val bad = Regex("[\\u0000-\\u001f:*?\"<>|]")
        val out = ArrayList<String>()
        for (p0 in raw.replace('\\', '/').split('/')) {
            var p = p0.replace(bad, "_").trim().trimEnd('.', ' ')
            if (p.isEmpty() || p == "." || p == "..") continue
            if (p.length > 120) {
                val dot = p.lastIndexOf('.')
                p = if (dot > 0 && p.length - dot <= 8) p.substring(0, 120 - (p.length - dot)) + p.substring(dot) else p.take(120)
            }
            out.add(p)
        }
        return out.take(8).joinToString("/")
    }

    private fun iso(t: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(t))
    }

    private fun displayPath(i: DlItem): String =
        if (i.filename.isNotEmpty()) i.filename else publicDir().absolutePath + "/" + i.rel

    private fun checkExists(i: DlItem): Boolean {
        if (i.state != "complete") return true
        if (i.uri.isEmpty()) return false
        return try {
            if (i.uri.startsWith("content:"))
                app.contentResolver.query(Uri.parse(i.uri), arrayOf("_id"), null, null, null)?.use { it.count > 0 } ?: false
            else File(i.uri).exists()
        } catch (e: Exception) { false }
    }

    fun toJson(i: DlItem, withExists: Boolean = true): JSONObject {
        val o = JSONObject()
            .put("id", i.id).put("url", i.url).put("finalUrl", i.finalUrl).put("referrer", i.referrer)
            .put("filename", displayPath(i)).put("incognito", false).put("danger", "safe")
            .put("mime", i.mime).put("startTime", iso(i.start))
            .put("state", i.state).put("paused", i.paused).put("canResume", i.canResume)
            .put("bytesReceived", i.received).put("totalBytes", i.total)
            .put("fileSize", if (i.state == "complete") i.fileSize else i.total)
            .put("exists", if (withExists) checkExists(i) else i.exists)
        if (i.end > 0) o.put("endTime", iso(i.end))
        if (i.error.isNotEmpty()) o.put("error", i.error)
        return o
    }

    private fun emit(event: String, vararg args: Any?) {
        val a = JSONArray()
        for (x in args) a.put(x ?: JSONObject.NULL)
        main.post { ChromeBridge.emit(event, a) { it.kind == ChromeBridge.Kind.EXT } }
    }

    // ---------- store ----------

    private fun toStore(i: DlItem): JSONObject = JSONObject()
        .put("id", i.id).put("url", i.url).put("finalUrl", i.finalUrl).put("rel", i.rel)
        .put("filename", i.filename).put("uri", i.uri).put("mime", i.mime).put("state", i.state)
        .put("paused", i.paused).put("canResume", i.canResume).put("error", i.error)
        .put("received", i.received).put("total", i.total).put("fileSize", i.fileSize)
        .put("start", i.start).put("end", i.end).put("validator", i.validator)
        .put("conflict", i.conflict).put("headers", i.headers).put("method", i.method)
        .put("body", i.body ?: JSONObject.NULL)

    private fun fromStore(o: JSONObject): DlItem {
        val i = DlItem(o.getInt("id"), o.getString("url"))
        i.finalUrl = o.optString("finalUrl", i.url); i.rel = o.optString("rel"); i.filename = o.optString("filename")
        i.uri = o.optString("uri"); i.mime = o.optString("mime"); i.state = o.optString("state", "interrupted")
        i.paused = o.optBoolean("paused"); i.canResume = o.optBoolean("canResume"); i.error = o.optString("error")
        i.received = o.optLong("received"); i.total = o.optLong("total", -1); i.fileSize = o.optLong("fileSize", -1)
        i.start = o.optLong("start"); i.end = o.optLong("end"); i.validator = o.optString("validator")
        i.conflict = o.optString("conflict", "uniquify"); i.headers = o.optJSONArray("headers") ?: JSONArray()
        i.method = o.optString("method", "GET"); i.body = if (o.isNull("body")) null else o.optString("body")
        return i
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(items) { for (i in items.values) arr.put(toStore(i)) }
        app.getSharedPreferences(PREFS, 0).edit().putString("items", arr.toString()).putInt("next", nextId).apply()
    }

    private fun load() {
        val p = app.getSharedPreferences(PREFS, 0)
        var maxId = 0
        try {
            val arr = JSONArray(p.getString("items", "[]"))
            for (n in 0 until arr.length()) {
                val i = fromStore(arr.getJSONObject(n))
                val http = i.url.startsWith("http", true)
                val part = Downloader.partFile(i.id)
                if (i.state == "in_progress") {
                    if (!(http && i.paused)) {   // a paused http download stays paused, everything else was cut by the process death
                        i.state = "interrupted"; i.error = "CRASH"; i.paused = false
                    }
                    i.canResume = http && part.exists()
                    if (!http) part.delete()
                }
                if (i.state != "in_progress" && i.state != "interrupted" && part.exists()) part.delete()
                items[i.id] = i
                if (i.id > maxId) maxId = i.id
            }
        } catch (e: Exception) { /* broken prefs: start empty */ }
        nextId = maxOf(p.getInt("next", 1), maxId + 1)
    }

    /** Change an item and tell the extension about it (chrome.downloads.onChanged delta). */
    fun change(item: DlItem, block: DlItem.() -> Unit) {
        val d = JSONObject()
        synchronized(items) {
            val st = item.state; val pa = item.paused; val er = item.error; val fn = item.filename
            val mi = item.mime; val tb = item.total; val fs = item.fileSize; val ex = item.exists
            val cr = item.canResume; val en = item.end
            item.block()
            fun diff(name: String, a: Any, b: Any) { if (a != b) d.put(name, JSONObject().put("previous", a).put("current", b)) }
            diff("state", st, item.state); diff("paused", pa, item.paused); diff("error", er, item.error)
            diff("filename", fn, item.filename); diff("mime", mi, item.mime); diff("totalBytes", tb, item.total)
            diff("fileSize", fs, item.fileSize); diff("exists", ex, item.exists); diff("canResume", cr, item.canResume)
            if (en != item.end) d.put("endTime", JSONObject().put("previous", if (en > 0) iso(en) else "").put("current", iso(item.end)))
            if (d.length() > 0) { d.put("id", item.id); save() }
        }
        if (d.length() > 0) emit("downloads.onChanged", d)
    }

    private fun newItem(url: String): DlItem = synchronized(items) {
        val n = DlItem(nextId++, url)
        items[n.id] = n
        if (items.size > MAX_ITEMS) {   // forget the oldest finished ones
            val drop = items.values.filter { it.state != "in_progress" }.take(items.size - MAX_ITEMS)
            for (d in drop) { items.remove(d.id); Downloader.partFile(d.id).delete() }
        }
        n
    }

    /**
     * S14: a plain link / file download started by a browser tab (WebView DownloadListener). Main thread.
     * Saved as Downloads/<name>; Referer, User-Agent and Cookie of the page are sent. src = the page WebView (needed for blob: urls).
     * Returns null when the url scheme is not supported.
     */
    fun startPlain(url: String, userAgent: String?, disposition: String?, mime: String?, referer: String?, src: WebView?): DlItem? {
        if (!listOf("http://", "https://", "data:", "blob:").any { url.startsWith(it, true) }) return null
        val item = newItem(url)
        item.rel = cleanRel(android.webkit.URLUtil.guessFileName(url, disposition, mime))
        if (!mime.isNullOrBlank()) item.mime = mime
        val http = url.startsWith("http", true)
        val h = JSONArray()
        if (http) {
            if (!referer.isNullOrBlank() && referer.startsWith("http", true))
                h.put(JSONObject().put("name", "Referer").put("value", referer))
            if (!userAgent.isNullOrBlank()) h.put(JSONObject().put("name", "User-Agent").put("value", userAgent))
            val ck = try { android.webkit.CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }
            if (!ck.isNullOrBlank()) h.put(JSONObject().put("name", "Cookie").put("value", ck))
        }
        item.headers = h
        item.canResume = http
        if (url.startsWith("blob:", true) && src != null) item.src = WeakReference(src)
        save()
        emit("downloads.onCreated", toJson(item, false))
        askPerms()
        Downloader.start(item)
        return item
    }

    fun active(): List<DlItem> = synchronized(items) { items.values.filter { it.state == "in_progress" && !it.paused } }

    // ---------- notifications ----------

    @SuppressLint("MissingPermission")
    private fun notifyItem(item: DlItem, ok: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val name = displayPath(item).substringAfterLast('/')
            val b = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "Download complete" else "Download failed")
                .setContentText(name)
                .setAutoCancel(true)
            val open = openIntent(item)
            if (ok && open != null) {
                b.setContentIntent(PendingIntent.getActivity(app, item.id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            NotificationManagerCompat.from(app).notify(10000 + item.id, b.build())
        } catch (e: Exception) { /* notifications are optional */ }
    }

    fun notifyDone(item: DlItem) = notifyItem(item, true)
    fun notifyFail(item: DlItem) = notifyItem(item, false)

    /** Notification shown while the foreground service runs. */
    fun progressNote(): android.app.Notification {
        val act = active()
        var got = 0L; var tot = 0L; var known = true
        for (i in act) { got += i.received; if (i.total > 0) tot += i.total else known = false }
        val b = NotificationCompat.Builder(app, CH_RUN)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(if (act.size <= 1) "Downloading" else "Downloading ${act.size} files")
        if (act.size == 1) b.setContentText(displayPath(act[0]).substringAfterLast('/'))
        if (known && tot > 0 && act.isNotEmpty()) b.setProgress(100, (got * 100 / tot).toInt().coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            b.setContentIntent(PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    // ---------- opening files ----------

    private fun contentUri(item: DlItem): Uri? {
        if (item.state != "complete" || item.uri.isEmpty()) return null
        return try {
            if (item.uri.startsWith("content:")) Uri.parse(item.uri)
            else FileProvider.getUriForFile(app, app.packageName + ".dlfiles", File(item.uri))
        } catch (e: Exception) { null }
    }

    private fun openIntent(item: DlItem): Intent? {
        val u = contentUri(item) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(u, item.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ---------- S28: built-in player ----------

    private val MEDIA_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

    private fun isMedia(i: DlItem): Boolean {
        val m = i.mime.lowercase()
        if (m.startsWith("video/") || m.startsWith("audio/")) return true
        return i.rel.substringAfterLast('.', "").lowercase() in MEDIA_EXT
    }

    /** Plays a finished video / audio download in PlayerScreen. false = not media or not complete (caller opens it the old way). */
    fun playIfMedia(a: Activity, id: Int): Boolean {
        val i = synchronized(items) { items[id] } ?: return false
        if (!isMedia(i)) return false
        val u = contentUri(i) ?: return false
        PlayerScreen.open(a, u, i.rel.substringAfterLast('/').ifEmpty { "Video" })
        return true
    }

    // ---------- permissions (asked once, on the first download) ----------

    private fun askPerms() {
        val a = top?.get() ?: return
        val p = app.getSharedPreferences(PREFS, 0)
        if (p.getBoolean("asked", false)) return
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.isEmpty()) return
        p.edit().putBoolean("asked", true).apply()
        ActivityCompat.requestPermissions(a, need.toTypedArray(), 7001)
    }

    // ---------- query ----------

    private fun matches(i: DlItem, q: JSONObject): Boolean {
        if (q.has("id") && q.optInt("id") != i.id) return false
        if (q.has("url") && q.optString("url") != i.url) return false
        if (q.has("urlRegex") && !Regex(q.optString("urlRegex")).containsMatchIn(i.url)) return false
        if (q.has("filename") && q.optString("filename") != displayPath(i)) return false
        if (q.has("filenameRegex") && !Regex(q.optString("filenameRegex")).containsMatchIn(displayPath(i))) return false
        if (q.has("state") && q.optString("state") != i.state) return false
        if (q.has("paused") && q.optBoolean("paused") != i.paused) return false
        if (q.has("mime") && q.optString("mime") != i.mime) return false
        if (q.has("error") && q.optString("error") != i.error) return false
        if (q.has("exists") && q.optBoolean("exists") != checkExists(i)) return false
        val words = q.optJSONArray("query")
        if (words != null) {
            val hay = (i.url + " " + displayPath(i)).lowercase()
            for (n in 0 until words.length()) {
                val w = words.optString(n).lowercase()
                if (w.startsWith("-")) { if (w.length > 1 && hay.contains(w.substring(1))) return false }
                else if (!hay.contains(w)) return false
            }
        }
        return true
    }

    private fun find(q: JSONObject): List<DlItem> =
        synchronized(items) { items.values.filter { matches(it, q) } }

    private fun byId(c: ChromeBridge.Call): DlItem =
        synchronized(items) { items[c.args.optInt(0, -1)] } ?: throw IllegalArgumentException("Invalid download id.")

    // ---------- init ----------

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_RUN, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }
        synchronized(items) { load() }
        (app as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        handle("downloads.download") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val url = o.optString("url")
            val ok = listOf("http://", "https://", "data:", "blob:").any { url.startsWith(it, true) }
            if (!ok) { c.fail("Invalid URL"); return@handle }
            val item = newItem(url)
            item.rel = cleanRel(o.optString("filename"))
            item.conflict = o.optString("conflictAction", "uniquify")
            item.method = o.optString("method", "GET").uppercase()
            if (o.has("body") && !o.isNull("body")) item.body = o.optString("body")
            item.headers = o.optJSONArray("headers") ?: JSONArray()
            item.canResume = url.startsWith("http", true)
            if (url.startsWith("blob:", true)) item.src = WeakReference(c.ctx.web)
            save()
            emit("downloads.onCreated", toJson(item, false))
            askPerms()
            Downloader.start(item)
            c.ok(item.id)
        }

        handle("downloads.search") { c ->
            val q = c.args.optJSONObject(0) ?: JSONObject()
            var list = find(q)
            if (q.has("limit")) list = list.take(q.optInt("limit", 1000))
            val arr = JSONArray()
            for (i in list) arr.put(toJson(i))
            c.ok(arr)
        }

        handle("downloads.pause") { c ->
            val i = byId(c)
            if (i.state != "in_progress") { c.fail("Download must be in progress."); return@handle }
            change(i) { paused = true }
            Downloader.abort(i)
            c.ok(null)
        }

        handle("downloads.resume") { c ->
            val i = byId(c)
            if (i.state == "in_progress" && i.paused) {
                change(i) { paused = false }
                Downloader.start(i)
            } else if (i.state == "interrupted" && i.canResume) {
                change(i) { state = "in_progress"; paused = false; error = ""; end = 0L }
                Downloader.start(i)
            } else { c.fail("Download must be paused or resumable."); return@handle }
            c.ok(null)
        }

        handle("downloads.cancel") { c ->
            val i = byId(c)
            if (i.state == "in_progress" || (i.state == "interrupted" && i.canResume)) {
                change(i) { state = "interrupted"; error = "USER_CANCELED"; paused = false; canResume = false; end = System.currentTimeMillis() }
                Downloader.abort(i)
                Downloader.partFile(i.id).delete()
            }
            c.ok(null)
        }

        handle("downloads.show") { c ->
            byId(c)
            try {
                app.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { /* no downloads app */ }
            c.ok(null)
        }
        ChromeBridge.onSync("downloads.showDefaultFolder") {
            try { app.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            null
        }

        handle("downloads.open") { c ->
            val i = byId(c)
            val pa = top?.get() // S28: media opens in the built-in player
            if (pa != null && i.state == "complete" && isMedia(i) && contentUri(i) != null) {
                pa.runOnUiThread { playIfMedia(pa, i.id) }
                c.ok(null)
                return@handle
            }
            val intent = openIntent(i)
            if (intent == null) { c.fail("Download is not complete."); return@handle }
            try { app.startActivity(intent) } catch (e: Exception) { c.fail("No app can open this file."); return@handle }
            c.ok(null)
        }

        handle("downloads.removeFile") { c ->
            val i = byId(c)
            if (i.state != "complete") { c.fail("Download must be complete."); return@handle }
            try {
                if (i.uri.startsWith("content:")) app.contentResolver.delete(Uri.parse(i.uri), null, null)
                else File(i.uri).delete()
            } catch (e: Exception) { /* already gone */ }
            change(i) { exists = false }
            c.ok(null)
        }

        handle("downloads.erase") { c ->
            val q = c.args.optJSONObject(0) ?: JSONObject()
            val gone = JSONArray()
            for (i in find(q)) {
                if (i.state == "in_progress") continue
                synchronized(items) { items.remove(i.id) }
                Downloader.partFile(i.id).delete()
                gone.put(i.id)
                emit("downloads.onErased", i.id)
            }
            save()
            c.ok(gone)
        }

        // harmless stubs
        for (api in listOf("downloads.acceptDanger", "downloads.setShelfEnabled", "downloads.setUiOptions",
            "downloads.getFileIcon", "downloads.drag")) ChromeBridge.onSync(api) { null }
    }
}
