package com.jabra.mgb

import android.app.Activity
import org.json.JSONObject

/**
 * D6: control and recovery for the engine jobs listed in Downloads.
 *  - Pause all / Resume all / Stop all = the same messages as the per-job buttons (job-pause, job-cancel), one per running job,
 *    plus batch-stop per page so a batch does not queue new episodes after Stop all. The engine also pauses / stops the
 *    video + audio joining step now (downloader.js gate) and drops queued jobs that have not started (background.js job-cancel).
 *  - "New link": a failed job (for example an expired link) is started again from its source: an episode of a batch goes
 *    through batch-episodes again (the engine looks for a fresh video link in the episode page), any other job opens its source
 *    page in a new tab so the Files detected panel finds the video again. Parts already downloaded are not reused.
 */
object DlControl {
    fun pauseAll(running: List<JSONObject>) {
        for (j in running) if (!j.optBoolean("paused")) EngineApi.send(JSONObject().put("type", "job-pause").put("key", j.optString("key")).put("on", true))
    }

    fun resumeAll(running: List<JSONObject>) {
        for (j in running) if (j.optBoolean("paused")) EngineApi.send(JSONObject().put("type", "job-pause").put("key", j.optString("key")).put("on", false))
    }

    fun stopAll(running: List<JSONObject>) {
        for (j in running) EngineApi.send(JSONObject().put("type", "job-cancel").put("key", j.optString("key")))
        for (t in running.map { it.optInt("tab", -1) }.filter { it >= 0 }.distinct()) EngineApi.send(JSONObject().put("type", "batch-stop").put("tabId", t))
    }

    private fun params(j: JSONObject): JSONObject = j.optJSONObject("params") ?: JSONObject()

    private fun source(j: JSONObject): String = params(j).optString("ref").takeIf { it.startsWith("http") } ?: ""

    fun canRefresh(j: JSONObject): Boolean = j.optString("epUrl").startsWith("http") || source(j).isNotEmpty()

    /** done() = close the Downloads screen (the page opens behind it). */
    fun refresh(act: Activity, j: JSONObject, done: () -> Unit) {
        val ep = j.optString("epUrl")
        val p = params(j)
        if (ep.startsWith("http")) {
            val title = j.optString("title").ifBlank { ep }
            val arr = org.json.JSONArray().put(JSONObject().put("url", ep).put("label", p.optString("chapter").ifBlank { title }).put("book", p.optString("book")).put("title", title))
            EngineApi.send(JSONObject().put("type", "batch-episodes").put("tabId", j.optInt("tab", 0)).put("cap", p.optString("cap")).put("miss", "near").put("episodes", arr))
            Util.toast(act, "Looking for a new link in the episode page...")
        } else {
            val s = source(j)
            if (s.isEmpty()) return
            TabsApi.openTab(s)
            Util.toast(act, "Page opened. Download the video again from Files detected.")
            done()
        }
    }
}
