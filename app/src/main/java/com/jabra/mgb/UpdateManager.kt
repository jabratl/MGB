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

    private class Info(
        val tag: String, val build: Int, val notes: String, val url: String, val size: Long, val name: String,
        val extName: String = "", val extUrl: String = "", val extSize: Long = -1L
    )

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
                    is Info -> {
                        // S16: same native code + newer ext zip = ext-only update (no APK install)
                        val eb = ExtUpdater.matchBuild(res.extName)
                        if (eb > ExtUpdater.currentBuild()) {
                            ExtUpdater.showDialog(a, res.tag, res.notes, res.extUrl, res.extName, res.extSize, eb)
                        } else if (res.build > BuildConfig.VERSION_CODE && eb < 0) {
                            showDialog(a, res)
                        } else {
                            cleanCache(a, null)
                            if (!silent) {
                                Util.toast(a, "You have the latest version (${BuildConfig.VERSION_NAME})")
                                ExtUpdater.offerReset(a)
                            }
                        }
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
            var apk: JSONObject? = null
            var ext: JSONObject? = null
            for (i in 0 until assets.length()) {
                val x = assets.getJSONObject(i)
                val name = x.optString("name")
                if (apk == null && name.endsWith(".apk")) apk = x
                else if (ext == null && name.endsWith(".zip") && name.contains("-ext-b")) ext = x
            }
            val ax = apk ?: throw Exception("release has no APK")
            return Info(
                tag, build, j.optString("body").trim(), ax.optString("browser_download_url"), ax.optLong("size", -1L), ax.optString("name"),
                ext?.optString("name") ?: "", ext?.optString("browser_download_url") ?: "", ext?.optLong("size", -1L) ?: -1L
            )
        } finally {
            c.disconnect()
        }
    }

    private fun cleanCache(a: Activity, keep: String?) {
        if (UpdState.state == UpdState.RUN) return // 0.0.314: never touch files of a running download
        File(a.cacheDir, "updates").listFiles()?.forEach { if (it.name != keep && it.name != "$keep.part") it.delete() }
    }

    private fun showDialog(a: Activity, i: Info) {
        val v = LayoutInflater.from(a).inflate(R.layout.dialog_update, null)
        val progress = v.findViewById<LinearProgressIndicator>(R.id.upd_progress)
        val status = v.findViewById<TextView>(R.id.upd_status)
        v.findViewById<TextView>(R.id.upd_version).text =
            "${i.tag}  (now ${BuildConfig.VERSION_NAME}, build ${BuildConfig.VERSION_CODE})" +
                if (i.size > 0) "  •  ${Util.fmtBytes(i.size)}" else ""
        v.findViewById<TextView>(R.id.upd_notes).text = i.notes.ifBlank { "New version available." }

        var armed = false
        var installed = false
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("Update available")
            .setView(v)
            .setPositiveButton("Update", null)
            .setNegativeButton("Later", null)
            .setNeutralButton("Cancel download", null)
            .create()
        // 0.0.314: closing the dialog no longer stops the download; UpdateService keeps going in the background
        dlg.setOnDismissListener { UpdState.listener = null }
        dlg.show()

        val pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        val neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
        val neu = dlg.getButton(AlertDialog.BUTTON_NEUTRAL)
        neu.visibility = View.GONE
        fun bind() {
            if (UpdState.name != i.name || UpdState.state == UpdState.IDLE) return
            progress.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            if (UpdState.total > 0) progress.progress = (UpdState.done * 100 / UpdState.total).toInt()
            when (UpdState.state) {
                UpdState.RUN -> {
                    armed = true
                    pos.isEnabled = false
                    neg.text = "Hide"
                    neu.visibility = View.VISIBLE
                    status.text = UpdState.msg
                }
                UpdState.DONE -> {
                    progress.progress = 100
                    neu.visibility = View.GONE
                    neg.text = "Later"
                    pos.isEnabled = true
                    pos.text = "Install"
                    status.text = "Downloaded. Installing…"
                    val f = UpdState.file
                    if (armed && !installed && f != null) { installed = true; UpdateService.clearDone(a); install(a, f) }
                }
                UpdState.FAIL -> {
                    neu.visibility = View.GONE
                    neg.text = "Later"
                    pos.isEnabled = true
                    pos.text = "Retry"
                    status.text = "Failed: ${UpdState.msg}"
                }
            }
        }
        UpdState.listener = { bind() }
        neu.setOnClickListener { UpdateService.cancel(a); status.text = "Cancelling…" }
        pos.setOnClickListener {
            val f = UpdState.file
            if (UpdState.state == UpdState.DONE && UpdState.name == i.name && f != null && f.exists()) { install(a, f); return@setOnClickListener }
            armed = true
            installed = false
            cleanCache(a, i.name)
            UpdateService.start(a, i.url, i.name, i.size)
            bind()
        }
        bind() // reopened while a download is already running: show its progress
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
