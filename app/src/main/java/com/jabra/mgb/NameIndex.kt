package com.jabra.mgb

import android.app.Activity
import org.json.JSONObject

/**
 * D5: switch for the engine setting batchOrganize (default OFF = names as before).
 * ON: a batch (episodes / playlist items, they carry an index and a show or page name) is saved in a sub-folder named
 * after the show or page, with an index prefix "01 - title". The name template wins when it already has {index}, a
 * sub-folder template that already has {series} or {book} wins, and Plex / Jellyfin naming (libraryMode) wins over both.
 * The naming itself is done in shared.js buildFileName.
 */
object NameIndex {
    fun on(): Boolean = try { ChromeBridge.sync.get("batchOrganize").optBoolean("batchOrganize", false) } catch (e: Exception) { false }

    fun toggle(act: Activity, done: () -> Unit) {
        val next = !on()
        try { ChromeBridge.storageSet("sync", JSONObject().put("batchOrganize", next)) } catch (e: Exception) { /* engine not ready */ }
        Util.toast(act, "Batch folder and numbers: " + (if (next) "ON" else "OFF"))
        done()
    }
}
