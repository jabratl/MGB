package com.jabra.mgb

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper

/**
 * S29: screen orientation helper for the player and fullscreen web video.
 * Problem fixed: after a video forced landscape, the whole browser stayed landscape.
 * restore() goes back to the orientation the app had before the video, then hands control back to the system.
 */
object Orient {
    fun isPortrait(act: Activity): Boolean =
        act.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    fun restore(act: Activity, wasPortrait: Boolean) {
        try {
            if (wasPortrait) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Handler(Looper.getMainLooper()).postDelayed({
                    try { act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } catch (e: Exception) { /* activity gone */ }
                }, 700)
            } else {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } catch (e: Exception) { /* activity gone */ }
    }
}
