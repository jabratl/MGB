package com.jabra.mgb

import android.app.Activity

/**
 * SOCIAL FULL SCREEN (0.0.238, A1): while the active tab is on a social site (Social sites ON, site switch ON, not Desktop-by-hand) and the switch
 * "Full screen on social sites" (sm_full, default OFF) is ON, the page goes fullscreen with the existing BarGestures.enter (address bar + system bars
 * hidden, small restore handle). Tap the handle or press Back = bars come back and stay until you leave that site (then it is armed again).
 * A page that is not social, a tab change or switch OFF leaves fullscreen only if this file entered it (a fullscreen the person started by swipe is never touched).
 * Called from MainActivity.onPageInfo + onActiveChanged (active tab only). Switch OFF = returns at once.
 */
object SocialFull {
    private var lastTab = -1
    private var byUs = false
    private var declined: String? = null

    fun sync(a: Activity, tab: TabManager.Tab?) {
        if (!SocialMode.fullOn(a) && !SocialReels.fullOn(a)) {
            if (byUs) { byUs = false; BarGestures.exit(a) }
            return
        }
        if (tab == null) return
        if (tab.id != lastTab) { lastTab = tab.id; byUs = false; declined = null } // new tab: MainActivity already left fullscreen
        val id = if (tab.isExt || tab.savedPage || tab.desktop || !SocialMode.on(a)) null else SocialProfiles.of(tab.url)?.id
        val site = id != null && SocialProfiles.siteOn(a, id)
        val want = site && (SocialMode.fullOn(a) || SocialReels.fullOn(a) && SocialReels.isReelPage(tab.url)) // 0.0.309: reel pages go full screen on their own
        if (want) {
            if (BarGestures.active) return
            if (byUs) { byUs = false; declined = id; return } // the person showed the bars: leave them until this site is left
            if (declined != id) { BarGestures.enter(a); byUs = true }
        } else {
            declined = null
            if (byUs) { byUs = false; BarGestures.exit(a) }
        }
    }
}
