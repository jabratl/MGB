package com.jabra.mgb

import android.content.Context

/**
 * ACCOUNT SLOTS (0.0.276, C5). Pref sm_slot_<id> in mgb_ui: absent / 1 = Account 1 (profile social_<id>, as before), 2 = Account 2 (profile social_<id>_2).
 * The slot only decides which login profile a NEW tab on that site gets (SocialMode.pname). Open tabs keep the profile they were opened with,
 * so both accounts can be open side by side. Needs "Keep social logins separate" ON and a WebView with multi-profile; otherwise it does nothing.
 * Messenger with the Facebook share ON follows the Facebook slot.
 */
object SocialSlot {
    /** Keys for the SettingsScreen RESET list. */
    val keys: List<String> = SocialProfiles.all.map { "sm_slot_" + it.id }

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun slot(c: Context, id: String): Int = sp(c).getInt("sm_slot_" + id, 1).coerceIn(1, 2)
    fun setSlot(c: Context, id: String, v: Int) {
        if (v <= 1) sp(c).edit().remove("sm_slot_" + id).apply() else sp(c).edit().putInt("sm_slot_" + id, 2).apply()
    }
    fun cycle(c: Context, id: String): Int { val n = if (slot(c, id) >= 2) 1 else 2; setSlot(c, id, n); return n }
    fun label(v: Int): String = if (v >= 2) "Account 2" else "Account 1"
    /** "" for slot 1 (old profile names stay valid), "_2" for slot 2. */
    fun suffix(c: Context, id: String): String = if (slot(c, id) >= 2) "_2" else ""
    fun overrides(c: Context): Int = SocialProfiles.all.count { slot(c, it.id) >= 2 }
}
