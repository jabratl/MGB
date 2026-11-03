package com.jabra.mgb

import android.app.Activity
import android.app.TimePickerDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * S61: scheduled downloads as a native screen (panel TOOLS > Scheduled downloads).
 * V77 keeps the list in storage.local mgSched [{id, when, itemUrl, kind, ref, title, variant}]; V77 messages
 * schedule-job {hhmm, job} and sched-cancel {id}. V77 starts the job at the time (alarm), this only asks and shows.
 * Live HLS streams scheduled this way are recorded until the "Stop recording after" limit (Download engine screen).
 */
object Sched {
    private fun list(): List<JSONObject> {
        val a: JSONArray = try { ChromeBridge.local.get("mgSched").optJSONArray("mgSched") } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out.sortedBy { it.optLong("when") }
    }

    fun label(): String = "Scheduled downloads" + list().size.let { if (it > 0) " ($it)" else "" }

    private fun fmt(t: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(t))

    /** jobs = one object per file {itemUrl, kind, ref, title}. */
    fun open(act: Activity, jobs: () -> List<JSONObject>) {
        val l = list()
        val names = ArrayList<String>()
        val runs = ArrayList<() -> Unit>()
        val again = { open(act, jobs) }
        val n = jobs().size
        if (n > 0) {
            names.add("Schedule the $n file(s) shown now...")
            runs.add { pickTime(act, jobs(), again) }
        }
        for (e in l) {
            names.add(fmt(e.optLong("when")) + "   " + e.optString("title").ifBlank { e.optString("itemUrl").substringBefore('?').substringAfterLast('/') })
            runs.add {
                MaterialAlertDialogBuilder(act).setTitle("Scheduled download").setMessage(fmt(e.optLong("when")) + "\n" + e.optString("title"))
                    .setPositiveButton("Cancel the schedule") { _, _ ->
                        EngineApi.send(JSONObject().put("type", "sched-cancel").put("id", e.optString("id")))
                        Util.toast(act, "Schedule cancelled")
                        // storage updates a moment later
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ again() }, 400)
                    }
                    .setNegativeButton("Back") { _, _ -> again() }.show()
            }
        }
        if (names.isEmpty()) { names.add("Nothing scheduled. Open a page with videos, then TOOLS > Scheduled downloads."); runs.add { } }
        MaterialAlertDialogBuilder(act).setTitle("Scheduled downloads")
            .setItems(names.toTypedArray()) { _, i -> runs[i]() }
            .setNegativeButton("Close", null).show()
    }

    private fun pickTime(act: Activity, jobs: List<JSONObject>, back: () -> Unit) {
        val now = java.util.Calendar.getInstance()
        TimePickerDialog(act, { _, h, m ->
            val hhmm = String.format("%02d:%02d", h, m)
            for (j in jobs) EngineApi.send(JSONObject().put("type", "schedule-job").put("hhmm", hhmm).put("job", j))
            Util.toast(act, "${jobs.size} download(s) scheduled for $hhmm (tomorrow if the time has passed)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ back() }, 500)
        }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
    }
}
