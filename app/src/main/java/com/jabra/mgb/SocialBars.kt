package com.jabra.mgb

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Looper
import android.webkit.WebView
import androidx.core.view.WindowCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * SOCIAL SYSTEM BAR COLOUR (0.0.240, A2): on a social page the status bar + navigation bar take the colour of the site
 * (meta theme-color, read after the page loads; fallback = the app background mg_bg). Switch "Match phone bars to the site"
 * (sm_bars, default ON; only does anything while Social sites + the site switch are ON). A non-social page, another tab,
 * Desktop-by-hand or switch OFF puts the original bar colours back. The page script only READS one meta tag.
 * Hooks: SocialMode.inject (read colour, page loads + in-page routes) and MainActivity onPageInfo / onActiveChanged (apply).
 */
object SocialBars {
    private var act: WeakReference<Activity>? = null
    private val colors = WeakHashMap<WebView, Int>() // colour read from the page (per WebView)
    private val asked = WeakHashMap<WebView, Long>()
    private var saved = false
    private var oStatus = 0
    private var oNav = 0
    private var oLightS = false
    private var oLightN = false
    private var shown = 0
    private var anim: ValueAnimator? = null

    /** Picks the theme-color meta whose media query matches (dark / light), turns any CSS colour into rgb(...) with the browser itself. */
    private const val JS = "(function(){try{var m=document.querySelectorAll('meta[name=theme-color]'),v='';" +
        "for(var i=0;i<m.length;i++){var md=m[i].getAttribute('media');if(!md||(window.matchMedia&&matchMedia(md).matches)){v=m[i].getAttribute('content')||'';break;}}" +
        "if(!v)return '';var e=document.createElement('i');e.style.color=v;(document.body||document.documentElement).appendChild(e);" +
        "var c=getComputedStyle(e).color;e.remove();return c||'';}catch(x){return '';}})()"

    /** Called from SocialMode.inject (social pages only). Reads the site colour (throttled 1 s per page) and applies it when this is the active tab. */
    fun read(ctx: android.content.Context, view: WebView?) {
        if (view == null || !SocialMode.barsOn(ctx)) return
        val now = System.currentTimeMillis()
        if (now - (asked[view] ?: 0L) < 1000L) return
        asked[view] = now
        view.evaluateJavascript(JS) { raw ->
            val c = parse(raw)
            if (c != null) colors[view] = c else colors.remove(view)
            val a = act?.get() ?: return@evaluateJavascript
            val t = BookmarksScreen.tabsRef?.active
            if (t != null && t.web === view) sync(a, t)
        }
    }

    /** "\"rgb(12, 34, 56)\"" or "\"rgba(...)\"" -> opaque colour; transparent / empty -> null. */
    private fun parse(raw: String?): Int? {
        val s = (raw ?: "").trim().trim('"')
        val m = Regex("""rgba?\(\s*(\d+)[ ,]+(\d+)[ ,]+(\d+)(?:[ ,/]+([\d.]+))?\s*\)""").find(s) ?: return null
        val al = m.groupValues[4].toDoubleOrNull() ?: 1.0
        if (al < 0.5) return null
        return Color.rgb(m.groupValues[1].toInt().coerceIn(0, 255), m.groupValues[2].toInt().coerceIn(0, 255), m.groupValues[3].toInt().coerceIn(0, 255))
    }

    /** Active tab changed / page info changed. Safe to call often. */
    fun sync(a: Activity, tab: TabManager.Tab?) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        act = WeakReference(a)
        val id = if (tab == null || tab.isExt || tab.savedPage || tab.desktop || !SocialMode.on(a) || !SocialMode.barsOn(a)) null else SocialProfiles.of(tab.url)?.id
        if (id == null || tab == null || !SocialProfiles.siteOn(a, id)) { restore(a); return }
        val c = colors[tab.web] ?: a.getColor(R.color.mg_bg)
        apply(a, c)
    }

    private fun apply(a: Activity, c: Int) {
        try {
            val w = a.window
            if (!saved) {
                saved = true
                oStatus = w.statusBarColor; oNav = w.navigationBarColor
                val ic = WindowCompat.getInsetsController(w, w.decorView)
                oLightS = ic.isAppearanceLightStatusBars; oLightN = ic.isAppearanceLightNavigationBars
                shown = oStatus
            }
            if (c == shown && anim == null) return
            val light = luminance(c) > 0.5
            val ic = WindowCompat.getInsetsController(w, w.decorView)
            ic.isAppearanceLightStatusBars = light; ic.isAppearanceLightNavigationBars = light
            paint(a, shown, c, !TabPrefs.reduce(a))
        } catch (_: Exception) { }
    }

    private fun restore(a: Activity) {
        if (!saved) return
        try {
            val w = a.window
            val ic = WindowCompat.getInsetsController(w, w.decorView)
            ic.isAppearanceLightStatusBars = oLightS; ic.isAppearanceLightNavigationBars = oLightN
            paint(a, shown, oStatus, !TabPrefs.reduce(a), oNav)
        } catch (_: Exception) { }
        saved = false
    }

    /** 200 ms colour fade (instant with Reduce motion). nav = explicit navigation colour (restore), else same as the status colour. */
    private fun paint(a: Activity, from: Int, to: Int, animate: Boolean, nav: Int = to) {
        anim?.cancel(); anim = null
        val w = a.window
        shown = to
        if (!animate) { w.statusBarColor = to; w.navigationBarColor = nav; return }
        anim = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 200
            addUpdateListener { v -> val x = v.animatedValue as Int; w.statusBarColor = x; w.navigationBarColor = if (nav == to) x else nav }
            start()
        }
    }

    private fun luminance(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
}
