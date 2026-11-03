package com.jabra.mgb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * S45: in-page video button. JS (injected on page finish, normal tabs only, main frame) puts one small
 * "Thazir" pill on the top-right of the biggest visible <video> for a few seconds (shows again on touch / scroll).
 * Tap = the video's address (currentSrc) goes to the built-in player (WebPlay). blob: / MSE video has no address,
 * so the grabber sheet opens instead (it lists the detected streams, each with "Play in player" and download).
 * The page can reach window.MGBVID.play too, so calls are rate limited (one per 2 s) and only open the player.
 */
object WebVideo {
    fun on(ctx: Context) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean("video_btn", true)
    fun setOn(ctx: Context, v: Boolean) = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putBoolean("video_btn", v).apply()

    private var lastAt = 0L

    class Bridge(private val tm: TabManager) {
        @JavascriptInterface
        fun smart(): Boolean = DlMode.smartOn // S54

        @JavascriptInterface
        fun play(src: String, title: String) {
            Handler(Looper.getMainLooper()).post {
                val now = System.currentTimeMillis()
                if (now - lastAt < 2000) return@post
                lastAt = now
                tm.onWebVideo?.invoke(src.take(4000), title.take(200))
            }
        }
    }

    const val JS = """(function(){
if(window.__mgbvid)return;window.__mgbvid=1;
var btn=document.createElement('div');
btn.textContent='\u25B6 Thazir';
btn.style.cssText='position:fixed;z-index:2147483647;display:none;padding:7px 13px;border-radius:17px;background:rgba(20,16,32,.88);color:#fff;font:600 13px/1.2 sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.45);border:1px solid rgba(255,255,255,.28);-webkit-tap-highlight-color:transparent;user-select:none;-webkit-user-select:none;';
var cur=null,shown=0;
function best(){
var vs=document.getElementsByTagName('video'),b=null,ba=0,vh=window.innerHeight,vw=window.innerWidth;
for(var i=0;i<vs.length;i++){
var r=vs[i].getBoundingClientRect();
var w=Math.min(r.right,vw)-Math.max(r.left,0),h=Math.min(r.bottom,vh)-Math.max(r.top,0);
if(w<160||h<90)continue;
var cs=getComputedStyle(vs[i]);
if(cs.visibility==='hidden'||cs.display==='none')continue;
var a=w*h;if(a>ba){ba=a;b=vs[i];}
}
return b;
}
function place(){
if(!btn.isConnected&&document.documentElement)document.documentElement.appendChild(btn);
if(document.fullscreenElement||document.webkitFullscreenElement){btn.style.display='none';return;}
try{if(MGBVID.smart()){var ph=document.querySelector('[data-mgb-pill]');if(ph&&ph.isConnected&&ph.style.display!=='none'){btn.style.display='none';return;}}}catch(x){}
var v=best();
if(!v){btn.style.display='none';cur=null;return;}
if(v!==cur){cur=v;shown=Date.now();}
if(Date.now()-shown>4500){btn.style.display='none';return;}
var r=v.getBoundingClientRect();
btn.style.display='block';
var w=btn.offsetWidth||92;
btn.style.left=Math.max(4,Math.min(r.right,window.innerWidth)-w-8)+'px';
btn.style.top=Math.max(4,Math.max(r.top,0)+8)+'px';
}
function poke(){shown=Date.now();place();}
btn.addEventListener('click',function(e){
e.preventDefault();e.stopPropagation();
var v=cur;if(!v)return;
var s=v.currentSrc||v.src||'';
try{v.pause();}catch(x){}
try{MGBVID.play(s,document.title||'');}catch(x){}
},true);
document.addEventListener('touchstart',poke,{capture:true,passive:true});
window.addEventListener('scroll',place,{passive:true});
window.addEventListener('resize',place);
setInterval(place,500);
place();
})();"""
}
