package com.jabra.mgb

import android.content.Context
import java.io.File

/**
 * APPEARANCE A2: ALL ap_* prefs of the Appearance plan (file mgb_ui, so Backup.kt already saves them).
 * Later steps (A3-A8) only call these getters / setters. Defaults = today's behaviour.
 * Existing keys reused elsewhere: night (UiMode), dark_pages (UiMode), hide_bar (MainActivity scroll).
 */
object AppearancePrefs {
    const val K_BAR_POS = "ap_bar_pos"
    const val K_HOME_ACTION = "ap_home_action"
    const val K_FULL_URL = "ap_full_url"
    const val K_SWIPE_FS = "ap_swipe_fs"
    const val K_SWIPE_TABS = "ap_swipe_tabs"
    const val K_WP_DIM = "ap_wp_dim"
    const val K_UNDO_TOAST = "ap_undo_toast"
    const val K_START_PAGE = "ap_start_page"

    val BAR_POS = listOf("bottom", "top")
    val HOME_ACTIONS = listOf("home", "back", "newtab", "refresh", "bookmarks", "extensions")

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    /** "bottom" (default) or "top". */
    fun barPos(c: Context): String = sp(c).getString(K_BAR_POS, "bottom").let { if (it in BAR_POS) it!! else "bottom" }
    fun setBarPos(c: Context, v: String) { if (v in BAR_POS) sp(c).edit().putString(K_BAR_POS, v).apply() }
    fun barTop(c: Context): Boolean = barPos(c) == "top"

    /** home | back | newtab | refresh | bookmarks | extensions (default home). */
    fun homeAction(c: Context): String = sp(c).getString(K_HOME_ACTION, "home").let { if (it in HOME_ACTIONS) it!! else "home" }
    fun setHomeAction(c: Context, v: String) { if (v in HOME_ACTIONS) sp(c).edit().putString(K_HOME_ACTION, v).apply() }

    /** A3: icon + name of a home-button action (bar button, preview and chips use the same table). */
    fun homeIconAndLabel(action: String): Pair<Int, String> = when (action) {
        "back" -> Pair(R.drawable.ic_back, "Back")
        "newtab" -> Pair(R.drawable.ic_add, "New tab")
        "refresh" -> Pair(R.drawable.ic_reload, "Refresh")
        "bookmarks" -> Pair(R.drawable.ic_bookmark, "Bookmarks")
        "extensions" -> Pair(R.drawable.ic_ext, "Extensions")
        else -> Pair(R.drawable.ic_home, "Home")
    }

    fun fullUrl(c: Context): Boolean = sp(c).getBoolean(K_FULL_URL, false)
    fun setFullUrl(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_FULL_URL, on).apply() }

    fun swipeFs(c: Context): Boolean = sp(c).getBoolean(K_SWIPE_FS, false)
    fun setSwipeFs(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_SWIPE_FS, on).apply() }

    fun swipeTabs(c: Context): Boolean = sp(c).getBoolean(K_SWIPE_TABS, false)
    fun setSwipeTabs(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_SWIPE_TABS, on).apply() }

    /** Wallpaper dim 0..80 (default 35). */
    fun wpDim(c: Context): Int = sp(c).getInt(K_WP_DIM, 35).coerceIn(0, 80)
    fun setWpDim(c: Context, v: Int) { sp(c).edit().putInt(K_WP_DIM, v.coerceIn(0, 80)).apply() }

    /** Wallpaper file (exists = wallpaper on). Not part of the backup. */
    fun wallpaperFile(c: Context): File = File(c.filesDir, "wallpaper.jpg")
    fun hasWallpaper(c: Context): Boolean = wallpaperFile(c).exists()

    fun undoToast(c: Context): Boolean = sp(c).getBoolean(K_UNDO_TOAST, true)
    fun setUndoToast(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_UNDO_TOAST, on).apply() }

    /** ON = a fresh home tab at every launch (restored tabs stay). OFF = today's behaviour. */
    /** A5: snackbars anchor above the bar only when it is at the bottom; in top mode they sit at the screen bottom. */
    fun snackAnchor(a: android.app.Activity): android.view.View? =
        if (barTop(a)) null else a.findViewById<android.view.View>(R.id.bottom_bar)

    fun startPage(c: Context): Boolean = sp(c).getBoolean(K_START_PAGE, false)
    fun setStartPage(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_START_PAGE, on).apply() }
}
