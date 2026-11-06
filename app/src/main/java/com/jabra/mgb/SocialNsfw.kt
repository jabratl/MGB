package com.jabra.mgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SOCIAL NSFW (0.0.232, P8a) - EXPERIMENTAL, ENGINE ONLY (no screen yet: P8b adds the switch + threshold).
 *
 * Checks PICTURES (not video) on social pages with a small on-device model that the PERSON supplies:
 *   app/src/main/assets/nsfw/model.tflite  (+ optional assets/nsfw/labels.txt, one label per line)
 * No model in the app = [modelReady] false = this file does nothing (fail-open: the page loads exactly as before).
 *
 * How: TabManager.shouldInterceptRequest calls [intercept] for every request of a tab. It answers null (= WebView loads it itself) unless
 *  - pref sm_nsfw is ON (default OFF), Social sites is ON, the tab is on a social site whose switch is ON,
 *  - the request is a GET for a picture (not main frame, not gif / svg) and the model is present.
 * Then the picture is downloaded here (same cookies / referer / user agent as the page), decoded at low resolution and classified
 * (input size and type read from the model; 224 x 224 RGB, float 0..1 or uint8). Pictures under 128 px are skipped. Flagged = score >= [threshold]:
 * a heavily blurred JPEG is returned instead. Not flagged: the downloaded bytes are returned and the verdict is cached by address
 * (next time null, the WebView loads it itself). Any error, timeout, huge file (> 4 MB) = null (fail-open). Max 2 checks at the same time.
 *
 * Score: labels.txt present -> sum of labels porn / hentai / sexy / nsfw / explicit / unsafe. No labels.txt: 1 output = it, 2 outputs = index 1,
 * 5 outputs (porn / hentai / sexy model order drawings, hentai, neutral, porn, sexy) = indexes 1 + 3 + 4. Other shapes = engine disabled.
 * NOT covered: video frames, live video, pictures drawn by a canvas, CSS images loaded from a data: address. Not 100 % accurate (misses + false blur).
 * Sends nothing anywhere except the normal request for the picture itself.
 */
object SocialNsfw {
    private const val K_ON = "sm_nsfw"
    private const val K_TH = "sm_nsfw_th"
    /** Keys for the SettingsScreen RESET list. */
    val keys: List<String> = listOf(K_ON, K_TH)

    private const val MODEL = "nsfw/model.tflite"
    private const val LABELS = "nsfw/labels.txt"
    private const val MAX_BYTES = 4_000_000L
    private const val MIN_PX = 128

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(K_ON, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_ON, v).apply() }
    /** Score limit 0.30..0.95, default 0.60 (P8b adds Low / Normal / Strict on top of this). */
    fun threshold(c: Context): Float = sp(c).getFloat(K_TH, 0.60f).coerceIn(0.30f, 0.95f)
    fun setThreshold(c: Context, v: Float) { sp(c).edit().putFloat(K_TH, v.coerceIn(0.30f, 0.95f)).apply() }

    /** 0.0.233 (P8b): sensitivity level 0 Low (score >= 0.85), 1 Normal (0.60), 2 Strict (0.40). Stored as the threshold float. */
    private val LEVEL_TH = floatArrayOf(0.85f, 0.60f, 0.40f)
    val levelNames: List<String> = listOf("Low", "Normal", "Strict")
    fun level(c: Context): Int { val t = threshold(c); return if (t >= 0.75f) 0 else if (t <= 0.50f) 2 else 1 }
    fun setLevel(c: Context, i: Int) {
        setThreshold(c, LEVEL_TH[i.coerceIn(0, 2)])
        clearCaches() // old verdicts were made with another limit
    }
    /** Forget cached verdicts (not the counter). */
    fun clearCaches() { synchronized(seen) { seen.clear() }; synchronized(flagged) { flagged.clear() } }

    /** Pictures replaced by a blur since the app started (P8b shows it). */
    val blurred = AtomicInteger(0)

    // ---- model (loaded once, on the first picture) ----
    private class Model(val interp: Interpreter, val w: Int, val h: Int, val float: Boolean, val unsafe: IntArray)
    private val lock = Any()
    @Volatile private var model: Model? = null
    @Volatile private var tried = false
    @Volatile private var lastError: String = ""
    private val gate = Semaphore(2)

    /** true = assets/nsfw/model.tflite is in the app. */
    fun modelReady(c: Context): Boolean = try { c.assets.open(MODEL).close(); true } catch (_: Exception) { false }

    /** One line for a status text / Diag: "No model", "Ready" or the load error. */
    fun status(c: Context): String = when {
        !modelReady(c) -> "No model in the app (assets/nsfw/model.tflite)"
        model != null -> "Ready"
        lastError.isNotEmpty() -> "Model error: $lastError"
        else -> "Model present, loads on the first picture"
    }

    private fun load(c: Context): Model? {
        model?.let { return it }
        if (tried) return null
        synchronized(lock) {
            model?.let { return it }
            if (tried) return null
            tried = true
            try {
                val bytes = c.assets.open(MODEL).use { it.readBytes() }
                val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                bb.put(bytes); bb.rewind()
                val ip = Interpreter(bb, Interpreter.Options().setNumThreads(2))
                val shape = ip.getInputTensor(0).shape() // [1, h, w, 3]
                if (shape.size != 4 || shape[3] != 3) { lastError = "input shape not 1xHxWx3"; return null }
                val isFloat = ip.getInputTensor(0).dataType() == DataType.FLOAT32
                val n = ip.getOutputTensor(0).shape().lastOrNull() ?: 0
                val labels = try { c.assets.open(LABELS).bufferedReader().readLines().map { l -> l.trim().lowercase() }.filter { l -> l.isNotEmpty() } } catch (_: Exception) { emptyList() }
                val bad = setOf("porn", "hentai", "sexy", "nsfw", "explicit", "unsafe")
                val unsafe: IntArray = when {
                    labels.size == n && labels.isNotEmpty() -> labels.indices.filter { i -> labels[i] in bad }.toIntArray()
                    n == 1 -> intArrayOf(0)
                    n == 2 -> intArrayOf(1)
                    n == 5 -> intArrayOf(1, 3, 4)
                    else -> IntArray(0)
                }
                if (unsafe.isEmpty()) { lastError = "cannot tell which of the $n outputs is unsafe (add assets/nsfw/labels.txt)"; return null }
                val m = Model(ip, shape[2], shape[1], isFloat, unsafe)
                model = m
                return m
            } catch (e: Throwable) {
                lastError = (e.message ?: e.javaClass.simpleName).take(120)
                Diag.w("social", "nsfw model: $lastError", "")
                return null
            }
        }
    }

    // ---- caches ----
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) { // address -> already checked and NOT flagged
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    }
    private val flagged = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) { // address -> blurred JPEG
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray>?) = size > 150
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private fun looksLikePicture(r: WebResourceRequest): Boolean {
        if (r.isForMainFrame || !r.method.equals("GET", true)) return false
        val path = (r.url.path ?: "").lowercase()
        if (path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".js") || path.endsWith(".css")) return false
        val accept = r.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
        if (accept.contains("image/", true) && !accept.contains("text/html", true)) return true
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")
    }

    private fun response(bytes: ByteArray, mime: String) = WebResourceResponse(mime, null, 200, "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=300"), ByteArrayInputStream(bytes))

    /** Called from TabManager.shouldInterceptRequest. null = do nothing (the WebView loads the request itself). */
    fun intercept(ctx: Context, tabUrl: String, r: WebResourceRequest): WebResourceResponse? {
        try {
            if (!on(ctx) || !SocialMode.on(ctx)) return null
            val p = SocialProfiles.of(tabUrl) ?: return null
            if (!SocialProfiles.siteOn(ctx, p.id)) return null
            if (!looksLikePicture(r)) return null
            val u = r.url.toString()
            synchronized(seen) { if (seen[u] == true) return null }
            synchronized(flagged) { flagged[u]?.let { return response(it, "image/jpeg") } }
            if (!modelReady(ctx)) return null
            val m = load(ctx) ?: return null
            if (!gate.tryAcquire(3, TimeUnit.SECONDS)) return null
            try { return check(ctx, tabUrl, m, u, r) } finally { gate.release() }
        } catch (_: Throwable) {
            return null // fail-open
        }
    }

    private fun check(ctx: Context, tabUrl: String, m: Model, u: String, r: WebResourceRequest): WebResourceResponse? {
        val rb = Request.Builder().url(u)
        r.requestHeaders?.forEach { (k, v) -> if (k.equals("Referer", true) || k.equals("User-Agent", true) || k.equals("Accept", true) || k.equals("Accept-Language", true)) rb.header(k, v) }
        try { SocialMode.cookies(ctx, tabUrl, u)?.let { rb.header("Cookie", it) } } catch (_: Throwable) { }
        http.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val len = body.contentLength()
            if (len > MAX_BYTES) { mark(u); return null }
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) { mark(u); return null }
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "image/jpeg"
            // size first, without decoding the pixels
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outWidth < MIN_PX || o.outHeight < MIN_PX) { mark(u); return response(bytes, mime) }
            var ss = 1
            while (o.outWidth / (ss * 2) >= m.w * 2 && o.outHeight / (ss * 2) >= m.h * 2) ss *= 2
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss }) ?: return null
            val score = score(m, src)
            if (score >= threshold(ctx)) {
                val b = blur(src)
                val out = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, 55, out)
                val jpg = out.toByteArray()
                synchronized(flagged) { flagged[u] = jpg }
                blurred.incrementAndGet()
                return response(jpg, "image/jpeg")
            }
            mark(u)
            return response(bytes, mime)
        }
    }

    private fun mark(u: String) { synchronized(seen) { seen[u] = true } }

    private fun score(m: Model, src: Bitmap): Float {
        val bm = Bitmap.createScaledBitmap(src, m.w, m.h, true)
        val px = IntArray(m.w * m.h)
        bm.getPixels(px, 0, m.w, 0, 0, m.w, m.h)
        val buf = ByteBuffer.allocateDirect(m.w * m.h * 3 * (if (m.float) 4 else 1)).order(ByteOrder.nativeOrder())
        for (c in px) {
            val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
            if (m.float) { buf.putFloat(rr / 255f); buf.putFloat(gg / 255f); buf.putFloat(bb / 255f) }
            else { buf.put(rr.toByte()); buf.put(gg.toByte()); buf.put(bb.toByte()) }
        }
        buf.rewind()
        val n = m.interp.getOutputTensor(0).shape().lastOrNull() ?: 1
        val out = Array(1) { FloatArray(n) }
        synchronized(m) { m.interp.run(buf, out) } // one Interpreter = one run at a time
        var s = 0f
        for (i in m.unsafe) if (i in 0 until n) s += out[0][i]
        return s
    }

    /** Strong blur: shrink to ~1/24 then stretch back with filtering (cheap, no RenderScript). */
    private fun blur(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val small = Bitmap.createScaledBitmap(src, (w / 24).coerceAtLeast(2), (h / 24).coerceAtLeast(2), true)
        return Bitmap.createScaledBitmap(small, w, h, true)
    }
}
