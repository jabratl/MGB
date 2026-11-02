package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * S30: email aliases through the user's own addy.io account (https://app.addy.io/docs, checked 2026-11):
 * GET /api/v1/domain-options, POST /api/v1/aliases {domain, description}, GET /api/v1/aliases,
 * POST /api/v1/active-aliases {id}, DELETE /api/v1/active-aliases/{id}, DELETE /api/v1/aliases/{id}.
 * Headers: Authorization Bearer <key>, X-Requested-With XMLHttpRequest. The API key is kept Keystore-encrypted
 * (Vault) in prefs mgb_alias. Mail to the alias is forwarded by addy.io to the user's own inbox; MGB has no server.
 */
object EmailAlias {
    private const val BASE = "https://app.addy.io"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    class Alias(val id: String, val email: String, val active: Boolean, val desc: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mgb_alias", Context.MODE_PRIVATE)

    fun hasKey(ctx: Context) = prefs(ctx).getString("k", null) != null
    private fun key(ctx: Context): String? = prefs(ctx).getString("k", null)?.let { Vault.decrypt(it) }
    private fun setKey(ctx: Context, k: String?) {
        if (k.isNullOrBlank()) prefs(ctx).edit().remove("k").apply()
        else prefs(ctx).edit().putString("k", Vault.encrypt(k.trim())).apply()
    }

    // ---- API (blocking: call from a thread)

    private class Res(val code: Int, val body: String)

    private fun call(key: String, method: String, path: String, json: JSONObject? = null, query: Map<String, String> = emptyMap()): Res {
        val ub = (BASE + path).toHttpUrl().newBuilder()
        for ((k, v) in query) ub.addQueryParameter(k, v)
        val rb = Request.Builder().url(ub.build())
            .header("Authorization", "Bearer $key")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json")
        val body = if (json != null) json.toString().toRequestBody(JSON) else if (method == "POST") "{}".toRequestBody(JSON) else null
        rb.method(method, body)
        http.newCall(rb.build()).execute().use { r -> return Res(r.code, r.body?.string() ?: "") }
    }

    private fun err(r: Res): String = when (r.code) {
        401 -> "Wrong or expired API key"
        403 -> try { JSONObject(r.body).optString("message", "Not allowed") } catch (e: Exception) { "Not allowed" }
        422 -> try { JSONObject(r.body).optString("message", "Rejected") } catch (e: Exception) { "Rejected" }
        429 -> "Too many requests, try again in a minute"
        else -> "Error " + r.code
    }

    /** Runs [work] on a thread, then [done] on the UI thread. */
    private fun <T> bg(act: Activity, work: () -> T, done: (T?, String?) -> Unit) {
        Thread {
            var v: T? = null
            var e: String? = null
            try { v = work() } catch (x: Exception) { e = "No connection" }
            act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) done(v, e) }
        }.start()
    }

    // ---- UI

    private fun copy(act: Activity, text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Email alias", text))
    }

    private fun input(act: Activity, hint: String, text: String = ""): EditText = EditText(act).apply {
        setText(text)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        val p = Util.dp(act, 20)
        setPadding(p, p / 2, p, p / 2)
    }

    /** Settings: set / replace / remove the API key. */
    fun keyDialog(act: Activity, then: (() -> Unit)? = null) {
        val et = input(act, "addy.io API key")
        val b = MaterialAlertDialogBuilder(act)
            .setTitle("addy.io API key")
            .setMessage("Create a free addy.io account, then Settings > API > Create New API Key and paste it here. Mail sent to your aliases is forwarded to your own inbox.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val k = et.text.toString().trim()
                if (k.length < 20) { Util.toast(act, "That does not look like an API key"); return@setPositiveButton }
                setKey(act, k)
                Util.toast(act, "API key saved")
                then?.invoke()
            }
            .setNegativeButton("Cancel", null)
        if (hasKey(act)) b.setNeutralButton("Remove key") { _, _ -> setKey(act, null); Util.toast(act, "API key removed") }
        b.show()
    }

    /** Create an alias for [site] (used by menu and Settings). The new address is copied. */
    fun create(act: Activity, site: String) {
        if (!hasKey(act)) { keyDialog(act) { create(act, site) }; return }
        val et = input(act, "What is it for?", site)
        MaterialAlertDialogBuilder(act)
            .setTitle("New email alias")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val k = key(act)
                if (k == null) { Util.toast(act, "Key unreadable: set it again"); keyDialog(act); return@setPositiveButton }
                val desc = et.text.toString().trim().take(200)
                Util.toast(act, "Creating...")
                bg(act, {
                    val d = call(k, "GET", "/api/v1/domain-options")
                    if (d.code != 200) return@bg Pair<String?, String?>(null, err(d))
                    val o = JSONObject(d.body)
                    var domain = o.optString("defaultAliasDomain", "")
                    if (domain.isBlank()) domain = o.optJSONArray("data")?.optString(0, "") ?: ""
                    if (domain.isBlank()) return@bg Pair<String?, String?>(null, "No alias domain on this account")
                    val r = call(k, "POST", "/api/v1/aliases", JSONObject().put("domain", domain).put("description", desc))
                    if (r.code != 201 && r.code != 200) Pair<String?, String?>(null, err(r))
                    else Pair<String?, String?>(JSONObject(r.body).getJSONObject("data").getString("email"), null)
                }) { res, e ->
                    val email = res?.first
                    if (email == null) Util.toast(act, res?.second ?: e ?: "Failed")
                    else {
                        copy(act, email)
                        MaterialAlertDialogBuilder(act).setTitle("Alias created (copied)").setMessage(email)
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** List the newest 100 aliases; tap = Copy / Disable|Enable / Delete. */
    fun manage(act: Activity) {
        if (!hasKey(act)) { keyDialog(act) { manage(act) }; return }
        val k = key(act)
        if (k == null) { keyDialog(act); return }
        Util.toast(act, "Loading...")
        bg(act, {
            val r = call(k, "GET", "/api/v1/aliases", query = mapOf("page[size]" to "100", "sort" to "-created_at"))
            if (r.code != 200) return@bg Pair<List<Alias>?, String?>(null, err(r))
            val a = JSONObject(r.body).getJSONArray("data")
            val out = ArrayList<Alias>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Alias(o.getString("id"), o.getString("email"), o.optBoolean("active", true), o.optString("description", "")))
            }
            Pair<List<Alias>?, String?>(out, null)
        }) { res, e ->
            val list = res?.first
            if (list == null) Util.toast(act, res?.second ?: e ?: "Failed")
            else if (list.isEmpty()) Util.toast(act, "No aliases yet")
            else MaterialAlertDialogBuilder(act)
                .setTitle("Email aliases")
                .setItems(list.map { (if (it.active) "" else "[off] ") + it.email + (if (it.desc.isBlank() || it.desc == "null") "" else "\n" + it.desc) }.toTypedArray()) { _, i -> one(act, k, list[i]) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun one(act: Activity, k: String, a: Alias) {
        MaterialAlertDialogBuilder(act)
            .setTitle(a.email)
            .setItems(arrayOf("Copy address", if (a.active) "Disable (stop forwarding)" else "Enable", "Delete")) { _, i ->
                when (i) {
                    0 -> { copy(act, a.email); Util.toast(act, "Copied") }
                    1 -> bg(act, {
                        if (a.active) call(k, "DELETE", "/api/v1/active-aliases/" + a.id)
                        else call(k, "POST", "/api/v1/active-aliases", JSONObject().put("id", a.id))
                    }) { r, e ->
                        Util.toast(act, if (r != null && r.code in 200..204) (if (a.active) "Disabled" else "Enabled") else (r?.let { err(it) } ?: e ?: "Failed"))
                    }
                    else -> MaterialAlertDialogBuilder(act)
                        .setMessage("Delete ${a.email}? Mail sent to it will bounce.")
                        .setPositiveButton("Delete") { _, _ ->
                            bg(act, { call(k, "DELETE", "/api/v1/aliases/" + a.id) }) { r, e ->
                                Util.toast(act, if (r != null && r.code in 200..204) "Deleted" else (r?.let { err(it) } ?: e ?: "Failed"))
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .show()
    }
}
