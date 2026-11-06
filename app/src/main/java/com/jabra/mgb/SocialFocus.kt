package com.jabra.mgb

import android.content.Context

/**
 * FOCUS MODE (0.0.264, C2). Switch sm_focus (default OFF) in mgb_ui.
 * When ON, the page CSS of a social site (SocialMode.inject, same style tag as the other site CSS) also hides Reels / Shorts-like
 * blocks, promoted posts and suggestion boxes. Selectors are ASSUMPTIONS about live sites and WILL change: a fix = edit one line in [CSS]
 * (or the person types a CSS line in Site fix, which is applied after this and wins). Only CSS, no script, nothing is sent.
 * Rules: no single quote and no backslash in a rule (the CSS is placed inside a JS string); double quotes are fine.
 * Not built from the C2 plan: X "For you" -> Following switch (needs a click script).
 */
object SocialFocus {
    private const val KEY = "sm_focus"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    private const val HIDE = "{display:none!important}"
    private val CSS: Map<String, String> = mapOf(
        "facebook" to "a[href*=\"/reel/\"],a[href*=\"/reels/\"],[aria-label=\"Reels\"],[data-pagelet*=Reels]$HIDE",
        "instagram" to "a[href*=\"/reels/\"],a[href=\"/explore/\"]$HIDE",
        "reddit" to "shreddit-ad-post,shreddit-post[promoted],[data-testid=ad]$HIDE",
        "x" to "[data-testid=placementTracking],[aria-label*=\"Who to follow\"],[aria-label*=\"Trending\"]$HIDE",
        "pinterest" to "[data-test-id*=promoted i],[data-test-id*=sponsored i]$HIDE",
        "linkedin" to ".ad-banner-container,[data-ad-banner]$HIDE"
    )

    /** CSS for this profile id, or an empty string (site without rules). */
    fun css(id: String): String = CSS[id] ?: ""
}
