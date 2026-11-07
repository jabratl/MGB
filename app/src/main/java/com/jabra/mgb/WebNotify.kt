package com.jabra.mgb

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.284: website notifications. Android WebView has no Notification API, so a small script gives pages window.Notification:
 * permission = the saved site answer (SitePerms "notifications"), requestPermission() asks once (Allow / Block, remember),
 * new Notification(title, {body}) posts a normal Android notification. Works only while Thazir is running (no background push).
 * Private tabs get no bridge, so the script does nothing there. Default "Blocked" (Site permissions) refuses without a question.
 */
object WebNotify {
    private const val CH = "mgb_web_notify"
    private const val WHAT = "notifications"
    private val lastAt = HashMap<String, Long>()
    private var seq = 0

    private const val JS = "(function(){if(window.__mgNot||!window.MGBNOT)return;var h=location.hostname;if(!h)return;window.__mgNot=1;" +
        "var perm=function(){try{return MGBNOT.state(h)}catch(e){return 'default'}};" +
        "function N(t,o){o=o||{};this.title=String(t);this.body=String(o.body||'');this.onclick=null;this.onclose=null;this.onerror=null;this.onshow=null;" +
        "if(perm()==='granted'){try{MGBNOT.show(h,String(t).slice(0,100),String(o.body||'').slice(0,300),String(o.tag||''))}catch(e){}var s=this;setTimeout(function(){if(s.onshow){try{s.onshow()}catch(e){}}},0)}}" +
        "N.prototype.close=function(){};N.prototype.addEventListener=function(){};N.prototype.removeEventListener=function(){};N.prototype.dispatchEvent=function(){return true};" +
        "Object.defineProperty(N,'permission',{get:perm});N.maxActions=0;" +
        "N.requestPermission=function(cb){return new Promise(function(res){window.__mgNotCb=function(v){if(cb){try{cb(v)}catch(e){}}res(v)};try{MGBNOT.request(h)}catch(e){window.__mgNotCb('denied')}})};" +
        "try{Object.defineProperty(window,'Notification',{value:N,configurable:true,writable:true})}catch(e){window.Notification=N}})();"

    /** Called from TabManager.onPageStarted / onPageFinished (normal tabs only). */
    fun inject(view: WebView) { try { view.evaluateJavascript(JS, null) } catch (_: Exception) { } }

    private fun androidOk(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    class Bridge(private val tm: TabManager, private val web: WebView) {
        private val ui = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun state(host: String): String {
            val c = tm.host()
            return when (SitePerms.get(c, host, WHAT)) { true -> "granted"; false -> "denied"; null -> if (SitePerms.blockedByDefault(c, WHAT)) "denied" else "default" }
        }

        @JavascriptInterface
        fun request(host: String) {
            ui.post {
                val c = tm.host()
                fun reply(v: String) { try { web.evaluateJavascript("window.__mgNotCb&&window.__mgNotCb('$v')", null) } catch (_: Exception) { } }
                val real = try { Util.host(web.url ?: "") } catch (_: Exception) { "" }
                if (real.isEmpty() || !real.equals(host, ignoreCase = true)) { reply("denied"); return@post } // the page can only ask for itself
                SitePerms.get(c, real, WHAT)?.let { reply(if (it) "granted" else "denied"); return@post }
                if (SitePerms.blockedByDefault(c, WHAT)) { reply("denied"); return@post }
                val a = c as? Activity
                if (a == null || a.isFinishing || a.isDestroyed) { reply("default"); return@post }
                var remember = true
                var done = false
                fun finish(ok: Boolean) {
                    if (done) return
                    done = true
                    if (remember) SitePerms.set(c, real, WHAT, ok)
                    if (ok && !androidOk(c)) { // Android 13+: the app itself must be allowed to show notifications
                        try { ActivityCompat.requestPermissions(a, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7101) } catch (_: Exception) { }
                    }
                    reply(if (ok) "granted" else "denied")
                }
                try {
                    MaterialAlertDialogBuilder(a)
                        .setTitle("Allow $real to show notifications?")
                        .setMultiChoiceItems(arrayOf("Remember for this site"), booleanArrayOf(true)) { _, _, on -> remember = on }
                        .setPositiveButton("Allow") { _, _ -> finish(true) }
                        .setNegativeButton("Block") { _, _ -> finish(false) }
                        .setOnCancelListener { done = true; reply("default") }
                        .show()
                } catch (_: Exception) { reply("default") }
            }
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun show(host: String, title: String, body: String, tag: String) {
            try {
                val c = tm.host()
                if (SitePerms.get(c, host, WHAT) != true || !androidOk(c)) return
                val now = System.currentTimeMillis()
                synchronized(lastAt) { if (now - (lastAt[host] ?: 0L) < 1000) return; lastAt[host] = now } // one per second per site
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Website notifications", NotificationManager.IMPORTANCE_DEFAULT))
                val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
                val b = NotificationCompat.Builder(c, CH)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title.ifBlank { host })
                    .setContentText(body)
                    .setSubText(host)
                    .setAutoCancel(true)
                if (launch != null) b.setContentIntent(android.app.PendingIntent.getActivity(c, 0, launch, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                val id = if (tag.isNotEmpty()) 20000 + (host + tag).hashCode().and(0x3fff) else 24000 + (seq++ % 1000)
                NotificationManagerCompat.from(c).notify(id, b.build())
            } catch (_: Exception) { /* optional */ }
        }
    }
}
