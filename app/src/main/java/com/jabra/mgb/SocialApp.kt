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
    const val SOUND = "sm_sound"; const val NEXT = "sm_next"; const val ZOOM = "sm_zoom"
    private val DEF_OFF = setOf(NEXT)
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context, k: String): Boolean = sp(c).getBoolean(k, k !in DEF_OFF)
    fun set(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }

    private fun js(c: Context, chat: Boolean): String {
        val s = if (on(c, SHARE)) 1 else 0; val a = if (on(c, AWAKE)) 1 else 0; val h = if (on(c, HAPTIC)) 1 else 0
        val u = if (on(c, SOUND)) 1 else 0; val n = if (on(c, NEXT)) 1 else 0; val z = if (on(c, ZOOM) && !chat) 1 else 0
        return "(function(){try{if(!window.MGBSOC)return;window.__mgSoc={s:$s,a:$a,h:$h,u:$u,n:$n,z:$z};" +
            // pinch zoom: the page may not lock the viewport (zoom a photo like in the app); not on chat pages
            "if($z){try{var vm=document.querySelector('meta[name=viewport]');if(vm&&/maximum-scale|user-scalable/i.test(vm.content)){vm.content=vm.content.replace(/,?\\s*(maximum-scale|user-scalable|minimum-scale)\\s*=\\s*[^,]*/gi,'')+',user-scalable=yes,maximum-scale=5';}}catch(e){}}" +
            "if(window.__mgSocOn)return;window.__mgSocOn=1;var C=function(){return window.__mgSoc||{}};" +
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
            // reels sound on (like the app) + auto next reel
            "var um=0;document.addEventListener('click',function(e){try{var b=e.target&&e.target.closest?e.target.closest('[aria-label]'):null;if(!b)return;if(/audio|mute|sound|volume/i.test(b.getAttribute('aria-label')||'')){setTimeout(function(){var v=document.querySelector('video');if(v)um=v.muted?1:0},250)}}catch(x){}},true);" +
            "document.addEventListener('playing',function(e){try{var v=e.target;if(!C().u||um||!v||v.tagName!=='VIDEO'||!v.muted)return;if(!/^\\/(?:[^\\/]+\\/)?(reels?|stories|p|tv)\\//.test(location.pathname)&&location.pathname!=='/'&&location.pathname.indexOf('/reels')!==0)return;v.muted=false;if(v.volume===0)v.volume=1}catch(x){}},true);" +
            "document.addEventListener('timeupdate',function(e){try{var v=e.target;if(!C().n||!v||v.tagName!=='VIDEO'||!(v.duration>2)||v.currentTime<v.duration-0.35||!/\\/reels?\\//.test(location.pathname))return;" +
            "var n=Date.now();if(v.__mgN&&n-v.__mgN<4000)return;v.__mgN=n;var el=v.parentElement;while(el&&el!==document.body){var cs=getComputedStyle(el);if(/(auto|scroll)/.test(cs.overflowY)&&el.scrollHeight>el.clientHeight+10){el.scrollBy({top:el.clientHeight,behavior:'smooth'});return}el=el.parentElement}" +
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',code:'ArrowDown',keyCode:40,bubbles:true}))}catch(x){}},true);" +
            "var lt=0;function tick(){if(!C().h)return;var n=Date.now();if(n-lt<250)return;lt=n;try{MGBSOC.tick()}catch(e){}}" +
            "document.addEventListener('click',function(e){try{var b=e.target&&e.target.closest?e.target.closest('[aria-label],[role=button]'):null;if(!b)return;" +
            "var l=(b.getAttribute('aria-label')||'').toLowerCase();if(/^(like|unlike|save|remove|follow|following|upvote|repost|retweet|bookmark|heart|love)\\b/.test(l)||/^remove (like|save)/.test(l))tick();}catch(x){}},true);" +
            "var lastTap=0;document.addEventListener('touchend',function(e){try{var t=e.target;if(!t||!t.closest||!t.closest('article,video,[role=dialog]'))return;if(!/^(IMG|VIDEO|DIV|SPAN|CANVAS)$/.test(t.tagName))return;" +
            "var n=Date.now();if(n-lastTap<320){lastTap=0;tick()}else lastTap=n}catch(x){}},true);" +
            "}catch(e){}})();"
    }

    fun inject(ctx: Context, view: WebView, url: String? = null) { try { view.evaluateJavascript(js(ctx, SocialPull.isChat(url)), null) } catch (_: Exception) { } }

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
