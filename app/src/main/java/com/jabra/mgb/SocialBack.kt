package com.jabra.mgb

import android.app.Activity
import android.widget.Toast

/**
 * SOCIAL BACK LIKE AN APP (0.0.241, A3): Back already goes back inside the site first (MainActivity back rule). New: on the FIRST page of a social tab
 * (nothing left to go back to) the first Back only shows "Press Back again to close" and the second Back within 2 s closes the tab / leaves the app as before.
 * Switch "Ask before closing a social tab" (sm_back, default ON; only for Social sites ON + site switch ON, not Desktop-by-hand / ext / saved page).
 * Edge swipe: the phone's own back gesture already calls the same Back rule, so no extra gesture code.
 * Called from MainActivity.handleOnBackPressed after the "can go back" branch. true = Back was used up by the hint.
 */
object SocialBack {
    private var lastTab = -1
    private var lastAt = 0L

    fun hold(a: Activity, tab: TabManager.Tab): Boolean {
        if (!SocialMode.backOn(a) || !SocialMode.on(a)) return false
        if (tab.isExt || tab.savedPage || tab.desktop) return false
        val id = SocialProfiles.of(tab.url)?.id ?: return false
        if (!SocialProfiles.siteOn(a, id)) return false
        val now = System.currentTimeMillis()
        if (lastTab == tab.id && now - lastAt < 2000L) { lastTab = -1; return false }
        lastTab = tab.id; lastAt = now
        Toast.makeText(a, "Press Back again to close", Toast.LENGTH_SHORT).show()
        return true
    }
}
