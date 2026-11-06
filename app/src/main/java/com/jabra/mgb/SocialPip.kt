package com.jabra.mgb

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational

/**
 * PICTURE-IN-PICTURE FOR SOCIAL VIDEO (0.0.278, D8). Switch sm_pip (default OFF) in mgb_ui.
 * When a video is full screen (TabManager.inFullscreen) on a social tab (Social mode ON, site switch ON) and the person presses Home
 * (MainActivity.onUserLeaveHint), the activity goes into a small floating window instead of simply leaving.
 * Needs Android 8+ (API 26) and the Manifest flag supportsPictureInPicture on MainActivity. Any error = nothing happens (old behaviour).
 * NOT built: play / pause buttons in the small window (default window controls only), audio with the screen off (needs a service, not planned).
 */
object SocialPip {
    private const val KEY = "sm_pip"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    /** True when the phone can show a picture-in-picture window. */
    fun supported(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && c.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Called from MainActivity.onUserLeaveHint. Returns true when the small window was started. */
    fun enter(act: Activity, tabs: TabManager): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            if (!on(act) || !supported(act) || !tabs.inFullscreen) return false
            val t = tabs.active ?: return false
            if (t.isExt || !SocialMode.on(act)) return false
            val p = SocialProfiles.of(t.url) ?: return false
            if (!SocialProfiles.siteOn(act, p.id)) return false
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            act.enterPictureInPictureMode(params)
        } catch (e: Throwable) {
            false
        }
    }
}
