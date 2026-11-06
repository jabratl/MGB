package com.jabra.mgb

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * SOCIAL LINKS FROM OTHER APPS (0.0.246, A6). Switch sm_links (default OFF) in mgb_ui.
 * How: the manifest has a small invisible link catcher (SocialLinkActivity, https links of the social sites) that is DISABLED
 * until the switch is ON; [sync] enables / disables it with PackageManager, so with the switch OFF Thazir is not a link handler at all
 * (old behaviour). The catcher only forwards the link to MainActivity (CLEAR_TOP + SINGLE_TOP, same pattern as the P6 shortcut),
 * so no second browser window starts. MainActivity calls [fromIntent] (cold start + onNewIntent).
 * Android decides who opens a link: the person must also allow the sites for Thazir in Android settings ([openAndroidSettings]).
 */
object SocialLinks {
    const val ACTION = "com.jabra.mgb.SOCIAL_LINK"
    private const val KEY = "sm_links"
    private const val CATCHER = "com.jabra.mgb.SocialLinkActivity"

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply(); sync(c) }

    /** Make the link catcher match the switch (cheap; only writes when the state differs). */
    fun sync(c: Context) {
        try {
            val cn = ComponentName(c.packageName, CATCHER)
            val pm = c.packageManager
            val isOn = pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            if (on(c) == isOn) return
            pm.setComponentEnabledSetting(cn,
                if (on(c)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            android.util.Log.w("Thazir.links", "sync failed", e)
        }
    }

    /** Called from MainActivity (cold start + onNewIntent). Returns true when the intent was ours. Handled once: action + data are cleared. */
    fun fromIntent(act: Activity, tabs: TabManager, intent: Intent?): Boolean {
        if (intent == null || intent.action != ACTION) return false
        val url = intent.dataString
        intent.action = null
        intent.data = null // a recreate (rotation, theme) must not open the link again
        if (url == null || !(url.startsWith("https://", true) || url.startsWith("http://", true))) return true
        val p = SocialProfiles.of(url)
        if (on(act) && p != null) {
            if (!SocialProfiles.siteOn(act, p.id)) SocialProfiles.setSiteOn(act, p.id, true)
            SocialMode.open(act, tabs, url) // Social mode ON + new tab
        } else {
            tabs.newTab(url) // switch OFF (late intent) or not a known site: plain tab, like any link
        }
        return true
    }

    /** Android page where the person allows the links (Open by default); older phones get the app details page. */
    fun openAndroidSettings(act: Activity) {
        val pkg = Uri.parse("package:" + act.packageName)
        try {
            if (Build.VERSION.SDK_INT >= 31) { act.startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)); return }
        } catch (e: Exception) { /* fall through */ }
        try {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        } catch (e: Exception) {
            Util.toast(act, "Open Android Settings > Apps > Thazir > Open by default")
        }
    }
}

/** Invisible link catcher (A6): forwards the https link to MainActivity and finishes at once. Enabled only by [SocialLinks.sync]. */
class SocialLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val u = intent?.data
            if (u != null) {
                startActivity(Intent(this, MainActivity::class.java).setAction(SocialLinks.ACTION).setData(u)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        } catch (e: Exception) {
            android.util.Log.w("Thazir.links", "forward failed", e)
        }
        finish()
    }
}
