package com.jabra.mgb

import android.app.Activity
import android.net.Uri
import android.util.Log

/**
 * S36: the old Dialog player (S28-S35) is gone. open() starts PlayerActivity, a real Activity that rotates like any
 * player app (Media3 ExoPlayer, subtitles, audio tracks). The two host hooks stay as empty functions because
 * MainActivity still calls them (an Activity player survives the browser being recreated by itself).
 */
object PlayerScreen {
    fun open(act: Activity, uri: Uri, title: String, land: Boolean = false) {
        if (act.isFinishing || act.isDestroyed) return
        try {
            PlayerActivity.start(act, uri, title)
        } catch (e: Exception) {
            Log.e("MGB.player", "open failed", e)
            Util.toast(act, "Cannot open the player")
        }
    }

    fun onHostDestroy(act: Activity) { }

    fun reopenPending(act: Activity) { }
}
