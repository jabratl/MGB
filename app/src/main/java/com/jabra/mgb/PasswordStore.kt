package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * S30: built-in password manager. Logins are saved per host (user + password); the password is AES-GCM encrypted with
 * a Keystore key (Vault) and stored in filesDir/mgb_pw.json. NOT part of Export data.
 * Page side: HOOK_JS (injected on page finish, normal tabs only) reports a submitted login form to the native
 * Bridge. The page can only call onSubmit / onField; the host always comes from the tab url (not from the page),
 * saving always asks the user, and filling is done by native code after Vault.unlock (menu: Fill password).
 */
object PasswordStore {
    private const val TAG = "MGB.pw"
    private const val FILE = "mgb_pw.json"
    private val main = Handler(Looper.getMainLooper())

    class Entry(val host: String, val user: String, val enc: String)

    private val list = ArrayList<Entry>()
    private var loaded = false
    private var lastKey = ""
    private var lastAt = 0L
    private var lastHint = ""

    // ---- storage

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                list.add(Entry(o.optString("h"), o.optString("u"), o.optString("p")))
            }
        } catch (e: Exception) { Log.e(TAG, "load failed", e) }
    }

    private fun save(ctx: Context) {
        try {
            val a = JSONArray()
            for (e in list) a.put(JSONObject().put("h", e.host).put("u", e.user).put("p", e.enc))
            File(ctx.applicationContext.filesDir, FILE).writeText(a.toString())
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    fun all(ctx: Context): List<Entry> { load(ctx); return list.sortedBy { it.host } }
    fun forHost(ctx: Context, host: String): List<Entry> { load(ctx); return list.filter { it.host == host } }

    private fun put(ctx: Context, host: String, user: String, pass: String) {
        load(ctx)
        list.removeAll { it.host == host && it.user == user }
        list.add(Entry(host, user, Vault.encrypt(pass)))
        save(ctx)
    }

    private fun remove(ctx: Context, e: Entry) { load(ctx); list.remove(e); save(ctx) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mgb_pw", Context.MODE_PRIVATE)
    private fun never(ctx: Context, host: String) = prefs(ctx).getStringSet("never", emptySet())?.contains(host) == true
    private fun setNever(ctx: Context, host: String) {
        val s = HashSet(prefs(ctx).getStringSet("never", emptySet()) ?: emptySet())
        s.add(host)
        prefs(ctx).edit().putStringSet("never", s).apply()
    }

    // ---- page side

    const val HOOK_JS = """(function(){
if(window.__mgbpw)return;window.__mgbpw=1;
function grab(root){
var p=root.querySelector('input[type=password]');
if(!p||!p.value)return;
var u='',ins=root.querySelectorAll('input');
for(var i=0;i<ins.length;i++){
if(ins[i]===p)break;
var t=(ins[i].type||'text').toLowerCase();
if((t==='text'||t==='email'||t==='tel')&&ins[i].value)u=ins[i].value;
}
try{MGBPW.onSubmit(u,p.value);}catch(e){}
}
document.addEventListener('submit',function(e){grab(e.target);},true);
document.addEventListener('click',function(e){
var b=e.target&&e.target.closest?e.target.closest('button,input[type=submit],input[type=button],[role=button]'):null;
if(!b)return;
grab(b.form||b.closest('form')||document);
},true);
if(document.querySelector('input[type=password]')){try{MGBPW.onField();}catch(e){}}
})();"""

    private const val FILL_JS = """function(u,p){
function set(el,v){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);
el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
var all=[].slice.call(document.querySelectorAll('input'));
var pw=null;
for(var i=0;i<all.length;i++){if(all[i].type==='password'&&all[i].offsetParent!==null){pw=all[i];break;}}
if(!pw)return 'nofield';
var us=null;
for(var j=all.indexOf(pw)-1;j>=0;j--){
var t=(all[j].type||'text').toLowerCase();
if((t==='text'||t==='email'||t==='tel')&&all[j].offsetParent!==null){us=all[j];break;}
}
if(us&&u)set(us,u);
set(pw,p);
return 'ok';
}"""

    /** Object exposed to normal tab pages as window.MGBPW (added in TabManager.createWebView, not for private tabs). */
    class Bridge(private val tm: TabManager) {
        @JavascriptInterface
        fun onSubmit(user: String, pass: String) {
            main.post {
                val t = tm.active ?: return@post
                if (t.priv || t.isExt || !t.url.startsWith("http")) return@post
                val act = tm.host() as? FragmentActivity ?: return@post
                offerSave(act, Util.host(t.url), user.take(200), pass.take(500))
            }
        }

        @JavascriptInterface
        fun onField() {
            main.post {
                val t = tm.active ?: return@post
                if (t.priv || !t.url.startsWith("http")) return@post
                val host = Util.host(t.url)
                if (lastHint == host + t.id) return@post
                if (forHost(tm.host(), host).isNotEmpty()) {
                    lastHint = host + t.id
                    Util.toast(tm.host(), "Saved login for this site: Menu > Fill password")
                }
            }
        }
    }

    // ---- save prompt

    private fun offerSave(act: FragmentActivity, host: String, user: String, pass: String) {
        if (pass.isEmpty() || never(act, host)) return
        val key = "$host|$user|${pass.hashCode()}"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 5000) return // submit + click both fire
        lastKey = key
        lastAt = now
        val old = forHost(act, host).firstOrNull { it.user == user }
        if (old != null && Vault.decrypt(old.enc) == pass) return // nothing new
        val who = if (user.isBlank()) "(no user name)" else user
        MaterialAlertDialogBuilder(act)
            .setTitle(if (old != null) "Update password for $host?" else "Save password for $host?")
            .setMessage(who)
            .setPositiveButton(if (old != null) "Update" else "Save") { _, _ ->
                put(act, host, user, pass)
                Util.toast(act, "Password saved")
            }
            .setNegativeButton("Not now", null)
            .setNeutralButton("Never here") { _, _ -> setNever(act, host) }
            .show()
    }

    // ---- fill

    /** Menu: Fill password for the page in the active tab. */
    fun fill(act: FragmentActivity, tabs: TabManager) {
        val t = tabs.active ?: return
        if (t.priv) { Util.toast(act, "Not available in a private tab"); return }
        val host = Util.host(t.url)
        val es = forHost(act, host)
        if (es.isEmpty()) { Util.toast(act, "No saved login for $host"); return }
        val run = { e: Entry ->
            Vault.unlock(act, "Fill password for $host") {
                val pass = Vault.decrypt(e.enc)
                if (pass == null) Util.toast(act, "Cannot read this password")
                else {
                    val js = "(" + FILL_JS + ")(" + JSONObject.quote(e.user) + "," + JSONObject.quote(pass) + ")"
                    t.web.evaluateJavascript(js) { r ->
                        if (r != null && r.contains("nofield")) Util.toast(act, "No password field on this page")
                    }
                }
            }
        }
        if (es.size == 1) run(es[0])
        else MaterialAlertDialogBuilder(act)
            .setTitle("Fill password")
            .setItems(es.map { it.user.ifBlank { "(no user name)" } }.toTypedArray()) { _, i -> run(es[i]) }
            .show()
    }

    // ---- saved list

    fun screen(act: FragmentActivity) {
        val es = all(act)
        if (es.isEmpty()) { Util.toast(act, "No saved passwords yet"); return }
        MaterialAlertDialogBuilder(act)
            .setTitle("Saved passwords")
            .setItems(es.map { it.host + "  ·  " + it.user.ifBlank { "(no user name)" } }.toTypedArray()) { _, i -> entryMenu(act, es[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copy(act: Activity, label: String, text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Util.toast(act, "$label copied")
    }

    private fun entryMenu(act: FragmentActivity, e: Entry) {
        MaterialAlertDialogBuilder(act)
            .setTitle(e.host)
            .setItems(arrayOf("Copy user name", "Copy password", "Show password", "Delete")) { _, i ->
                when (i) {
                    0 -> copy(act, "User name", e.user)
                    1 -> Vault.unlock(act, "Copy password") {
                        val p = Vault.decrypt(e.enc)
                        if (p == null) Util.toast(act, "Cannot read this password") else copy(act, "Password", p)
                    }
                    2 -> Vault.unlock(act, "Show password") {
                        val p = Vault.decrypt(e.enc)
                        MaterialAlertDialogBuilder(act).setTitle(e.host).setMessage(e.user + "\n" + (p ?: "(cannot read)"))
                            .setPositiveButton("Close", null).show()
                    }
                    else -> MaterialAlertDialogBuilder(act)
                        .setMessage("Delete the saved login for ${e.host}?")
                        .setPositiveButton("Delete") { _, _ -> remove(act, e); Util.toast(act, "Deleted") }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .show()
    }
}
