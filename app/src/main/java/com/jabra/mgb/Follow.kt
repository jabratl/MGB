package com.jabra.mgb

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * S61: Follow manga as a native screen (panel TOOLS > Followed manga).
 * Uses the same V77 storage as follow.html: storage.local mgFollow {url: {url,title,host,seen,unread,checked,added,error}},
 * V77 setting followHours (0 = never), V77 message follow-check (check now). V77 does the real checking.
 */
object Follow {
    private fun noFrag(u: String) = u.substringBefore('#')
    private fun all(): JSONObject = try { ChromeBridge.local.get("mgFollow").optJSONObject("mgFollow") ?: JSONObject() } catch (e: Exception) { JSONObject() }
    private fun save(o: JSONObject) { try { ChromeBridge.storageSet("local", JSONObject().put("mgFollow", o)) } catch (e: Exception) { /* engine not ready */ } }
    private fun hours(): Int = try { ChromeBridge.sync.get("followHours").optInt("followHours", 6) } catch (e: Exception) { 6 }

    fun isFollowing(ref: String): Boolean = all().has(noFrag(ref))

    fun count(): Int = all().length()

    fun label(ref: String): String {
        val n = all().length()
        return "Followed manga" + (if (n > 0) " ($n)" else "")
    }

    /** Follow / unfollow the chapter list of this page (the chapters seen now count as read). */
    fun toggle(act: Activity, ref: String, f: NativeBatch.Found?) {
        val r = all()
        val k = noFrag(ref)
        if (r.has(k)) {
            r.remove(k)
            save(r)
            Util.toast(act, "Unfollowed")
            return
        }
        if (f == null) { Util.toast(act, "No chapter list on this page"); return }
        val seen = org.json.JSONArray()
        for (e in f.eps) seen.put(e.url)
        r.put(k, JSONObject().put("url", k).put("title", f.book).put("host", Util.host(ref))
            .put("seen", seen).put("unread", org.json.JSONArray()).put("checked", System.currentTimeMillis()).put("added", System.currentTimeMillis()))
        save(r)
        val h = hours()
        Util.toast(act, if (h > 0) "Following: checked every $h h" else "Following, but checking is off (set the interval)")
    }

    private fun intervalDialog(act: Activity, back: () -> Unit) {
        val vals = intArrayOf(0, 1, 3, 6, 12, 24, 48)
        val names = arrayOf("Never", "Every hour", "Every 3 hours", "Every 6 hours", "Every 12 hours", "Every day", "Every 2 days")
        val cur = hours()
        val i0 = vals.indexOf(cur).let { if (it < 0) 3 else it }
        MaterialAlertDialogBuilder(act).setTitle("Check followed manga")
            .setSingleChoiceItems(names, i0) { d, i ->
                try { ChromeBridge.storageSet("sync", JSONObject().put("followHours", vals[i])) } catch (e: Exception) { /* not ready */ }
                d.dismiss(); back()
            }
            .setNegativeButton("Back") { _, _ -> back() }.show()
    }

    /** The list screen. openUrl opens a page in the browser. */
    fun open(act: Activity, ref: String, found: NativeBatch.Found?, openUrl: (String) -> Unit) {
        val r = all()
        val keys = r.keys().asSequence().toList().sortedByDescending { r.optJSONObject(it)?.optJSONArray("unread")?.length() ?: 0 }
        val names = ArrayList<String>()
        val runs = ArrayList<() -> Unit>()
        val again = { open(act, ref, found, openUrl) }
        val here = isFollowing(ref)
        if (ref.isNotEmpty()) {
            names.add(if (here) "Unfollow this page" else "Follow this page" + (if (found != null) " (${found.eps.size} chapters)" else ""))
            runs.add { toggle(act, ref, found); again() }
        }
        names.add("Check now"); runs.add {
            Util.toast(act, "Checking...")
            EngineApi.ask(JSONObject().put("type", "follow-check")) { res ->
                Util.toast(act, if (res != null) "${res.optInt("n", 0)} new chapter(s) found" else "Check failed (engine not ready)")
                again()
            }
        }
        val h = hours()
        names.add("Check every: " + (if (h <= 0) "never" else "$h h")); runs.add { intervalDialog(act, again) }
        for (k in keys) {
            val f = r.optJSONObject(k) ?: continue
            val un = f.optJSONArray("unread")?.length() ?: 0
            val err = f.optString("error")
            names.add((if (un > 0) "● $un new  " else "") + f.optString("title").ifBlank { k } + (if (err.isNotEmpty()) "  (check failed)" else ""))
            runs.add { item(act, k, f, openUrl, again) }
        }
        MaterialAlertDialogBuilder(act).setTitle("Followed manga")
            .setItems(names.toTypedArray()) { _, i -> runs[i]() }
            .setNegativeButton("Close", null).show()
    }

    private fun item(act: Activity, k: String, f: JSONObject, openUrl: (String) -> Unit, back: () -> Unit) {
        val un = f.optJSONArray("unread")
        val sb = StringBuilder(Util.host(k))
        if (f.optLong("checked") > 0) sb.append("\nchecked ").append(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(f.optLong("checked"))))
        if (f.optString("error").isNotEmpty()) sb.append("\nLast check failed: ").append(f.optString("error"))
        if (un != null && un.length() > 0) {
            sb.append("\n\nNew:")
            val from = maxOf(0, un.length() - 10)
            for (i in un.length() - 1 downTo from) sb.append("\n- ").append(un.optJSONObject(i)?.optString("label") ?: "")
        }
        val b = MaterialAlertDialogBuilder(act).setTitle(f.optString("title").ifBlank { k }).setMessage(sb.toString())
            .setPositiveButton("Open") { _, _ -> openUrl(f.optString("url").ifBlank { k }) }
            .setNegativeButton("Back") { _, _ -> back() }
        b.setNeutralButton("Unfollow") { _, _ -> val c = all(); c.remove(k); save(c); back() }
        if (un != null && un.length() > 0) {
            b.setNeutralButton("Mark read") { _, _ -> val c = all(); c.optJSONObject(k)?.put("unread", org.json.JSONArray()); save(c); back() }
        }
        b.show()
    }
}
