package com.jabra.mgb

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * 0.0.305: small "like the official app" extras on social pages (Facebook, Instagram, X, Reddit, Pinterest ... = any Social profile).
 *  sm_share   Share buttons open the Android share sheet (the page calls navigator.share; WebView has none, so the site fell back to "copy link").
 *  sm_awake   The screen stays on while a video / reel plays on the page (back to normal when it pauses, ends or you leave).
 *  sm_haptic  A light tick when you Like / Unlike / Save / Follow (button aria-label) or double-tap a picture / video.
 * All default ON. Page script is started by SocialMode.inject (social pages only, each part guards itself). Bridge = window.MGBSOC (TabManager).
 */
object SocialApp {
    const val SHARE = "sm_share"; const val AWAKE = "sm_awake"; const val HAPTIC = "sm_haptic"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context, k: String): Boolean = sp(c).getBoolean(k, true)
    fun set(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }

    private fun js(c: Context): String {
        val s = if (on(c, SHARE)) 1 else 0; val a = if (on(c, AWAKE)) 1 else 0; val h = if (on(c, HAPTIC)) 1 else 0
        return "(function(){try{if(!window.MGBSOC)return;window.__mgSoc={s:$s,a:$a,h:$h};if(window.__mgSocOn)return;window.__mgSocOn=1;var C=function(){return window.__mgSoc||{}};" +
            // share sheet
            "try{var sh=function(d){d=d||{};if(!C().s)return Promise.reject(new DOMException('off','NotAllowedError'));" +
            "try{MGBSOC.share(String(d.title||''),String(d.text||''),String(d.url||location.href))}catch(e){return Promise.reject(e)}return Promise.resolve()};" +
            "Object.defineProperty(navigator,'share',{value:sh,configurable:true,writable:true});" +
            "Object.defineProperty(navigator,'canShare',{value:function(){return !!C().s},configurable:true,writable:true});}catch(e){}" +
            // keep screen on while a video plays
            "var pl=0;function chk(){var any=false;try{var v=document.querySelectorAll('video');for(var i=0;i<v.length;i++){if(!v[i].paused&&!v[i].ended&&v[i].readyState>2){any=true;break}}}catch(e){}" +
            "var w=any&&C().a?1:0;if(w!==pl){pl=w;try{MGBSOC.awake(w===1)}catch(e){}}}" +
            "['playing','pause','ended','emptied','abort'].forEach(function(n){document.addEventListener(n,function(){setTimeout(chk,50)},true)});" +
            "setInterval(chk,4000);document.addEventListener('visibilitychange',chk);" +
            // haptic tick
            "var lt=0;function tick(){if(!C().h)return;var n=Date.now();if(n-lt<250)return;lt=n;try{MGBSOC.tick()}catch(e){}}" +
            "document.addEventListener('click',function(e){try{var b=e.target&&e.target.closest?e.target.closest('[aria-label],[role=button]'):null;if(!b)return;" +
            "var l=(b.getAttribute('aria-label')||'').toLowerCase();if(/^(like|unlike|save|remove|follow|following|upvote|repost|retweet|bookmark|heart|love)\\b/.test(l)||/^remove (like|save)/.test(l))tick();}catch(x){}},true);" +
            "var lastTap=0;document.addEventListener('touchend',function(e){try{var t=e.target;if(!t||!t.closest||!t.closest('article,video,[role=dialog]'))return;if(!/^(IMG|VIDEO|DIV|SPAN|CANVAS)$/.test(t.tagName))return;" +
            "var n=Date.now();if(n-lastTap<320){lastTap=0;tick()}else lastTap=n}catch(x){}},true);" +
            "}catch(e){}})();"
    }

    fun inject(ctx: Context, view: WebView) { try { view.evaluateJavascript(js(ctx), null) } catch (_: Exception) { } }

    class Bridge(private val tm: TabManager, private val web: WebView) {
        private val ui = Handler(Looper.getMainLooper())
        private var lastShare = 0L

        @JavascriptInterface
        fun share(title: String, text: String, url: String) {
            ui.post {
                val c = tm.host()
                if (!on(c, SHARE)) return@post
                val now = System.currentTimeMillis(); if (now - lastShare < 800) return@post; lastShare = now
                try {
                    val link = SocialClean.maybe(c, url.take(2000))
                    val body = listOf(text.take(1000), link).filter { it.isNotBlank() && !(it == link && text.contains(link)) }.joinToString("\n")
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                    if (title.isNotBlank()) i.putExtra(Intent.EXTRA_SUBJECT, title.take(200))
                    val ch = Intent.createChooser(i, null)
                    if (c !is android.app.Activity) ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(ch)
                } catch (_: Exception) { }
            }
        }

        @JavascriptInterface
        fun awake(on: Boolean) { ui.post { try { web.keepScreenOn = on && SocialApp.on(tm.host(), AWAKE) } catch (_: Exception) { } } }

        @JavascriptInterface
        fun tick() { ui.post { try { web.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } catch (_: Exception) { } } }
    }
}
