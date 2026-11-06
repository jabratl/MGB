package com.jabra.mgb

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * D3: preferred video format (Any / MP4 / WebM) for downloads. Stored in the engine setting preferContainer
 * (same storage as maxHeight). It only decides which stream is picked when a DASH stream offers both; nothing is converted.
 * Default "any" = the behaviour before D3. Quality caps (up to 8K) live in DetectedPanel.maxQDialog.
 */
object QualityPrefs {
    private val VALS = arrayOf("any", "mp4", "webm")
    private val NAMES = arrayOf("Any (as before)", "MP4", "WebM")

    fun container(): String = try { ChromeBridge.sync.get("preferContainer").optString("preferContainer", "any") } catch (e: Exception) { "any" }

    fun containerLabel(): String = NAMES[VALS.indexOf(container()).let { if (it < 0) 0 else it }]

    fun audioOnlyOn(): Boolean = try { ChromeBridge.sync.get("audioOnly").optBoolean("audioOnly", false) } catch (e: Exception) { false }

    /** D4: engine setting audioOnly (default OFF). Per file: the "Audio only" row in the file menu. */
    fun toggleAudioOnly(act: Activity, done: () -> Unit) {
        val next = !audioOnlyOn()
        try { ChromeBridge.storageSet("sync", JSONObject().put("audioOnly", next)) } catch (e: Exception) { /* engine not ready */ }
        Util.toast(act, "Audio only downloads: " + (if (next) "ON" else "OFF"))
        done()
    }

    fun containerDialog(act: Activity, done: () -> Unit) {
        val cur = VALS.indexOf(container()).let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(act)
            .setTitle("Preferred video format")
            .setSingleChoiceItems(NAMES, cur) { d, i ->
                try { ChromeBridge.storageSet("sync", JSONObject().put("preferContainer", VALS[i])) } catch (e: Exception) { /* engine not ready */ }
                Util.toast(act, "Format: " + NAMES[i])
                d.dismiss()
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
