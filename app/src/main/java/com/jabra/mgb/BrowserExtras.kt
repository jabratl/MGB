package com.jabra.mgb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S14: things a normal browser needs besides pages.
 *  - file chooser: MainActivity owns the ActivityResult launchers (init in onCreate); tabs and extension
 *    windows (ExtScreen is a Dialog and cannot get activity results) both call chooseFile()
 *  - web permissions (camera, microphone, location): ask the user first (Allow / Block), then the Android permission.
 *    A permission that is not declared in the manifest is refused with a toast (see HANDOFF: extra manifest)
 *  - desktop user agent
 * Extension pages (appassets.androidplatform.net) never get camera / microphone / location.
 */
object BrowserExtras {
    private var act: ComponentActivity? = null
    private var fileLauncher: ActivityResultLauncher<Intent>? = null
    private var permLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingFile: ValueCallback<Array<Uri>>? = null
    private var pendingPerm: ((Map<String, Boolean>) -> Unit)? = null
    private var dUa: String? = null
    private var exportLauncher: ActivityResultLauncher<String>? = null // S18
    private var importLauncher: ActivityResultLauncher<Array<String>>? = null // S18
    private var pendingImport: ((Int) -> Unit)? = null // S18

    /** Remembered Allow / Block answers for this run: "origin|kinds" -> answer. */
    private val decided = HashMap<String, Boolean>()

    /** Viewport script used by desktop mode (runs when a page has finished loading). */
    const val DESKTOP_JS = "(function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
        "m.setAttribute('content','width=1024');})();"

    /** Call in Activity.onCreate (launchers must be registered before the Activity is STARTED). */
    fun init(a: ComponentActivity) {
        pendingFile?.onReceiveValue(null)
        pendingFile = null
        pendingPerm = null
        act = a
        fileLauncher = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = pendingFile
            pendingFile = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        }
        permLauncher = a.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { m ->
            val f = pendingPerm
            pendingPerm = null
            f?.invoke(m)
        }
        // S18: bookmark export / import (system file picker). Registered after the two above (their keys stay the same).
        exportLauncher = a.registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) writeBookmarks(a, uri)
        }
        importLauncher = a.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = pendingImport
            pendingImport = null
            if (uri != null) readBookmarks(a, uri, cb)
        }
    }

    // ---------- S18: bookmarks import / export ----------

    fun exportBookmarks() {
        val l = exportLauncher
        val a = act
        if (l == null || a == null) return
        try { l.launch("mgb-bookmarks.html") } catch (e: Exception) { Util.toast(a, "No file picker found") }
    }

    /** onDone(added) runs on the main thread after the file was read. */
    fun importBookmarks(onDone: ((Int) -> Unit)?) {
        val l = importLauncher
        val a = act
        if (l == null || a == null) return
        pendingImport = onDone
        try { l.launch(arrayOf("*/*")) } catch (e: Exception) { pendingImport = null; Util.toast(a, "No file picker found") }
    }

    private fun writeBookmarks(a: ComponentActivity, uri: Uri) {
        val text = Bookmarks.exportHtml()
        val n = Bookmarks.bookmarks().size
        Thread {
            val ok = try {
                a.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
            } catch (e: Exception) { false }
            a.runOnUiThread { Util.toast(a, if (ok) "Exported $n bookmarks" else "Export failed") }
        }.start()
    }

    private fun readBookmarks(a: ComponentActivity, uri: Uri, cb: ((Int) -> Unit)?) {
        Thread {
            var added = -1
            try {
                val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.size <= 5 * 1024 * 1024) added = Bookmarks.importText(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) { added = -1 }
            a.runOnUiThread {
                Util.toast(a, when {
                    added < 0 -> "Could not read this file"
                    added == 0 -> "No new bookmarks found in this file"
                    else -> "Imported $added bookmarks"
                })
                if (added >= 0) cb?.invoke(added)
            }
        }.start()
    }

    // ---------- file chooser ----------

    /** WebChromeClient.onShowFileChooser for tabs and ExtScreen windows. Returns true = we answer the callback. */
    fun chooseFile(cb: ValueCallback<Array<Uri>>?, p: WebChromeClient.FileChooserParams?): Boolean {
        if (cb == null) return false
        val l = fileLauncher
        if (p == null || l == null) {
            cb.onReceiveValue(null)
            return true
        }
        pendingFile?.onReceiveValue(null) // a second chooser replaces the first
        pendingFile = cb
        try {
            l.launch(p.createIntent())
        } catch (e: Exception) {
            pendingFile = null
            cb.onReceiveValue(null)
            act?.let { Util.toast(it, "No file picker found") }
        }
        return true
    }

    // ---------- web permissions ----------

    fun webPermission(req: PermissionRequest) {
        val a = act
        val origin = req.origin.toString()
        if (a == null || origin.startsWith(ContentInjector.EXT_ORIGIN)) { req.deny(); return }
        val needed = ArrayList<String>()
        val names = ArrayList<String>()
        val web = ArrayList<String>()
        for (r in req.resources) {
            when (r) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { needed.add(Manifest.permission.CAMERA); names.add("camera"); web.add(r) }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { needed.add(Manifest.permission.RECORD_AUDIO); names.add("microphone"); web.add(r) }
            }
        }
        if (web.isEmpty()) { req.deny(); return }
        val what = names.joinToString(" and ")
        ask(a, origin, what) { ok ->
            if (!ok) req.deny()
            else withAndroidPermission(a, needed) { granted ->
                if (granted) req.grant(web.toTypedArray()) else req.deny()
            }
        }
    }

    fun geoPermission(origin: String?, cb: GeolocationPermissions.Callback?) {
        if (cb == null) return
        val a = act
        if (origin == null || a == null || origin.startsWith(ContentInjector.EXT_ORIGIN)) {
            if (origin != null) cb.invoke(origin, false, false)
            return
        }
        ask(a, origin, "location") { ok ->
            if (!ok) cb.invoke(origin, false, false)
            else withAndroidPermission(a, listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted ->
                cb.invoke(origin, granted, false)
            }
        }
    }

    private fun ask(a: ComponentActivity, origin: String, what: String, answer: (Boolean) -> Unit) {
        val key = "$origin|$what"
        decided[key]?.let { answer(it); return }
        if (a.isFinishing || a.isDestroyed) { answer(false); return }
        var done = false
        fun finish(ok: Boolean) {
            if (done) return
            done = true
            decided[key] = ok
            answer(ok)
        }
        MaterialAlertDialogBuilder(a)
            .setMessage("Allow ${Util.host(origin)} to use your $what?")
            .setPositiveButton("Allow") { _, _ -> finish(true) }
            .setNegativeButton("Block") { _, _ -> finish(false) }
            .setOnCancelListener { done = true; answer(false) } // dismissed: refuse this time, ask again next time
            .show()
    }

    @Suppress("DEPRECATION")
    private fun declared(a: Context): Set<String> = try {
        a.packageManager.getPackageInfo(a.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    private fun withAndroidPermission(a: ComponentActivity, perms: List<String>, result: (Boolean) -> Unit) {
        val dec = declared(a)
        if (perms.any { it !in dec }) {
            Util.toast(a, "This build does not include that permission yet")
            result(false)
            return
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(a, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { result(true); return }
        val l = permLauncher
        if (l == null) { result(false); return }
        pendingPerm?.invoke(emptyMap()) // an older question that never got an answer
        pendingPerm = { m -> result(missing.all { m[it] == true }) }
        try {
            l.launch(missing.toTypedArray())
        } catch (e: Exception) {
            pendingPerm = null
            result(false)
        }
    }

    // ---------- desktop mode ----------

    fun desktopUa(ctx: Context): String {
        dUa?.let { return it }
        val base = try { WebSettings.getDefaultUserAgent(ctx) }
        catch (e: Exception) { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
        val ua = base
            .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace("Version/4.0 ", "")
            .replace(" Mobile", "")
        dUa = ua
        return ua
    }
}
