package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * S29 / S35: screen orientation policy.
 *  - Phones: the browser stays PORTRAIT (setting "Rotate browser with phone" turns this off). Tablets are never locked.
 *  - Built-in player: rotates its own picture (see PlayerScreen), the Activity never rotates for it.
 *  - Web fullscreen video: follows the system auto-rotate setting while it is open (forVideo), then back to the policy (restore).
 */
object Orient {
    private const val PREFS = "mgb_ui"

    fun isPortrait(act: Activity): Boolean =
        act.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun isPhone(act: Activity): Boolean = act.resources.configuration.smallestScreenWidthDp < 600

    fun rotateBrowser(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("rotate_browser", true) // 0.0.280: default ON (was OFF = portrait lock; a locked app never gets the system rotate arrow)

    fun setRotateBrowser(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("rotate_browser", on).apply()
    }

    /** Browser policy: portrait on phones unless the user allows rotation. Call in onCreate and after the setting changes. */
    fun apply(act: Activity) {
        try {
            act.requestedOrientation =
                if (isPhone(act) && !rotateBrowser(act)) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (e: Exception) { /* activity gone */ }
    }

    /** Fullscreen web video: let the phone rotate (the system auto-rotate switch decides). */
    fun forVideo(act: Activity) {
        try { act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER } catch (e: Exception) { /* activity gone */ }
    }

    /** Back to the browser policy (wasPortrait is kept only for the old call sites). */
    @Suppress("UNUSED_PARAMETER")
    fun restore(act: Activity, wasPortrait: Boolean) = apply(act)
}
