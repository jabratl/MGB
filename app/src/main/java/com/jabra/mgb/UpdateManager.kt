package com.jabra.mgb

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S15: in-app update from GitHub Releases.
 * The CI publishes every build as release "v<version>-b<run>" with the APK attached; <run> is also the
 * app's versionCode. So: latest release's "-bN" > BuildConfig.VERSION_CODE  =>  update available.
 * BuildConfig.GH_REPO ("owner/repo") is filled from GITHUB_REPOSITORY at build time. Repo must be public.
 */
object UpdateManager {
    private const val PREFS = "mgb_update"
    private const val AUTO_EVERY_MS = 12L * 3600 * 1000
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Info(val tag: String, val build: Int, val notes: String, val url: String, val size: Long, val name: String)

    /** On app start: silent check, at most every 12 hours. */
    fun autoCheck(a: Activity) {
        val sp = a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("last", 0L) < AUTO_EVERY_MS) return
        sp.edit().putLong("last", now).apply()
        check(a, true)
    }

    fun check(a: Activity, silent: Boolean) {
        if (BuildConfig.GH_REPO.isEmpty()) {
            if (!silent) Util.toast(a, "Update source is not set in this build")
            return
        }
        if (!silent) Util.toast(a, "Checking for updates…")
        io.execute {
            val res: Any = try { fetch() } catch (e: Exception) { e }
            main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                when (res) {
                    is Info -> if (res.build > BuildConfig.VERSION_CODE) showDialog(a, res) else {
                        cleanCache(a, null)
                        if (!silent) Util.toast(a, "You have the latest version (${BuildConfig.VERSION_NAME})")
                    }
                    is Exception -> if (!silent) Util.toast(a, "Update check failed: ${res.message}")
                }
            }
        }
    }

    private fun http(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MGB-updater")
        c.setRequestProperty("Accept", accept)
        return c
    }

    private fun fetch(): Info {
        val c = http("https://api.github.com/repos/${BuildConfig.GH_REPO}/releases/latest", "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw Exception("no release found (repo must be public)")
                403 -> throw Exception("GitHub rate limit, try again later")
                else -> throw Exception("GitHub HTTP $code")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val build = Regex("-b(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw Exception("unknown release tag \"$tag\"")
            val assets = j.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val x = assets.getJSONObject(i)
                val name = x.optString("name")
                if (name.endsWith(".apk")) {
                    return Info(tag, build, j.optString("body").trim(), x.optString("browser_download_url"), x.optLong("size", -1L), name)
                }
            }
            throw Exception("release has no APK")
        } finally {
            c.disconnect()
        }
    }

    private fun cleanCache(a: Activity, keep: String?) {
        File(a.cacheDir, "updates").listFiles()?.forEach { if (it.name != keep) it.delete() }
    }

    private fun showDialog(a: Activity, i: Info) {
        val v = LayoutInflater.from(a).inflate(R.layout.dialog_update, null)
        val progress = v.findViewById<LinearProgressIndicator>(R.id.upd_progress)
        val status = v.findViewById<TextView>(R.id.upd_status)
        v.findViewById<TextView>(R.id.upd_version).text =
            "${i.tag}  (now ${BuildConfig.VERSION_NAME}, build ${BuildConfig.VERSION_CODE})" +
                if (i.size > 0) "  •  ${Util.fmtBytes(i.size)}" else ""
        v.findViewById<TextView>(R.id.upd_notes).text = i.notes.ifBlank { "New version available." }

        val cancelled = AtomicBoolean(false)
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("Update available")
            .setView(v)
            .setPositiveButton("Update", null)
            .setNegativeButton("Later", null)
            .create()
        dlg.setOnDismissListener { cancelled.set(true) }
        dlg.show()

        val pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        pos.setOnClickListener {
            pos.isEnabled = false
            progress.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = "Starting…"
            cleanCache(a, i.name)
            val dest = File(File(a.cacheDir, "updates").apply { mkdirs() }, i.name)
            io.execute {
                try {
                    val cached = dest.exists() && (i.size <= 0 || dest.length() == i.size)
                    if (!cached) {
                        download(i, dest, cancelled) { done, total ->
                            main.post {
                                if (total > 0) progress.progress = (done * 100 / total).toInt()
                                status.text = "Downloading ${Util.fmtBytes(done)}" +
                                    if (total > 0) " / ${Util.fmtBytes(total)}" else ""
                            }
                        }
                    }
                    main.post {
                        if (cancelled.get()) return@post
                        progress.progress = 100
                        status.text = "Downloaded. Installing…"
                        pos.isEnabled = true
                        pos.text = "Install"
                        install(a, dest)
                    }
                } catch (e: Exception) {
                    main.post {
                        if (cancelled.get()) return@post
                        status.text = "Failed: ${e.message}"
                        pos.isEnabled = true
                        pos.text = "Retry"
                    }
                }
            }
        }
    }

    private fun download(i: Info, dest: File, cancelled: AtomicBoolean, onProgress: (Long, Long) -> Unit) {
        val part = File(dest.parentFile, dest.name + ".part")
        val c = http(i.url, "application/octet-stream")
        try {
            if (c.responseCode != 200) throw Exception("download HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else i.size
            c.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        if (cancelled.get()) throw Exception("cancelled")
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 150) { last = now; onProgress(done, total) }
                    }
                }
            }
            if (i.size > 0 && part.length() != i.size) throw Exception("incomplete download")
            if (!part.renameTo(dest)) throw Exception("could not save the file")
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun install(a: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Util.toast(a, "Allow \"install unknown apps\" for MGB, go back, then tap Install")
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", f)
        a.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
