package com.jabra.mgb

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference

/**
 * LIVE SCRIPT COUNTER + PAGE CHECK (0.0.277, U5 leftover). Nothing is stored on disk and nothing leaves the phone.
 *  - note(): called by SocialMode.inject; keeps per site how many times the page scripts were started since the app started, when, the page type
 *    and a weak pointer to the last WebView used (so the check below can ask it).
 *  - check(): asks each of those pages (read-only script, no changes) which of our scripts left their guard flag, the page width and
 *    whether the page looks like a phone or desktop page. Answers within 1.5 s, pages that do not answer are listed as "no answer".
 */
object SocialProbe {
    private class Rec { var n = 0; var t = 0L; var kind = ""; var view: WeakReference<WebView>? = null }
    private val recs = LinkedHashMap<String, Rec>()

    fun note(id: String, kind: String, view: WebView) {
        synchronized(recs) {
            val r = recs.getOrPut(id) { Rec() }
            r.n++; r.t = System.currentTimeMillis(); r.kind = kind; r.view = WeakReference(view)
        }
    }

    /** One short line for the summary: "scripts started 12 times, last 5 s ago (feed page)" or "no page opened yet". */
    fun line(id: String): String = synchronized(recs) {
        val r = recs[id] ?: return "no page opened since the app started"
        val s = ((System.currentTimeMillis() - r.t) / 1000L).coerceAtLeast(0L)
        "scripts started " + r.n + " times, last " + (if (s < 120L) "$s s" else (s / 60L).toString() + " min") + " ago (" + r.kind + " page)"
    }

    private const val PROBE_JS = "(function(){try{var g=function(k){return window[k]?1:0};" +
        "return JSON.stringify({v:g('__mgSv'),b:g('__mgSb'),i:g('__mgSi'),k:g('__mgSk'),c:document.getElementById('__mgSc')?1:0," +
        "ua:/Mobile/i.test(navigator.userAgent)?'phone':'desktop',w:window.innerWidth,h:location.host});}catch(e){return '{}'}})()"

    /** Calls [done] once on the main thread with a plain-text report. */
    fun check(done: (String) -> Unit) {
        val targets = synchronized(recs) { recs.entries.map { it.key to it.value.view?.get() } }
        val out = LinkedHashMap<String, String>()
        for ((id, _) in targets) out[id] = "no answer (tab closed or not loaded)"
        var pending = 0
        var finished = false
        val main = Handler(Looper.getMainLooper())
        fun finish() {
            if (finished) return
            finished = true
            done(if (out.isEmpty()) "No social page was opened since the app started." else out.entries.joinToString("\n") { it.key + ": " + it.value })
        }
        for ((id, v) in targets) {
            if (v == null) continue
            pending++
            try {
                v.evaluateJavascript(PROBE_JS) { res ->
                    try {
                        val s = JSONTokener(res ?: "null").nextValue() as? String
                        val o = JSONObject(s ?: "{}")
                        fun f(k: String, n: String) = n + (if (o.optInt(k, 0) == 1) " yes" else " no")
                        out[id] = if (!o.has("w")) "page gave no data" else
                            f("b", "banner scan") + ", " + f("v", "viewport") + ", " + f("i", "Use-the-app skip") + ", " + f("k", "keyboard") + ", " + f("c", "css") +
                                "; " + o.optString("ua") + " page, width " + o.optInt("w") + " px, " + o.optString("h")
                    } catch (_: Throwable) { out[id] = "page gave no data" }
                    if (--pending <= 0) finish()
                }
            } catch (_: Throwable) { pending-- }
        }
        if (pending <= 0) finish() else main.postDelayed({ finish() }, 1500L)
    }
}
