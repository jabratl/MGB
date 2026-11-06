package com.jabra.mgb

import android.content.Context
import org.json.JSONObject

/**
 * SOCIAL BLUR (0.0.229, P5): blur pictures / videos on social pages until the person taps them.
 *
 * Mode (key sm_blur in mgb_ui, default 0 = OFF = nothing is added to any page):
 *  - 1 FLAGGED: only media the site marks itself (per-site selector in [FLAG_SEL]) or whose post text says NSFW / sensitive / 18+.
 *  - 2 ALL: every picture, video, canvas and inline background-image until tap.
 * Strength: key sm_blur_px, 4..24 px, default 12.
 * Runs only where SocialMode.inject runs (master switch ON + this site's switch ON), so the per-site switch of P4 also switches the blur.
 *
 * Page script (one per page, guards itself with window.__mgSz): adds class __mgbl (CSS filter: blur) to media, a capture-phase click
 * reveals the item under the finger (also through overlays), a small "Reveal all" pill shows the count. Media added later (infinite scroll)
 * is caught by a MutationObserver + load / scroll listeners. Items under 64 px (avatars, icons) are never blurred.
 * Deviation from the 40 s rule of the other page scripts: the observer must live as long as the page (infinite scroll); it is batched (250 ms).
 * It never touches password fields and sends nothing out. Selectors of live sites are assumptions and may need a one-line fix.
 */
object SocialBlur {
    const val OFF = 0
    const val FLAGGED = 1
    const val ALL = 2

    private const val K_MODE = "sm_blur"
    private const val K_PX = "sm_blur_px"
    /** Keys for the SettingsScreen RESET list. */
    val keys: List<String> = listOf(K_MODE, K_PX)

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun mode(c: Context): Int = sp(c).getInt(K_MODE, OFF).coerceIn(OFF, ALL)
    fun setMode(c: Context, v: Int) { sp(c).edit().putInt(K_MODE, v.coerceIn(OFF, ALL)).apply() }
    fun px(c: Context): Int = sp(c).getInt(K_PX, 12).coerceIn(4, 24)
    fun setPx(c: Context, v: Int) { sp(c).edit().putInt(K_PX, v.coerceIn(4, 24)).apply() }

    /**
     * The site's own "sensitive" marker, by profile id (CSS selector; the media inside a match is blurred).
     * ASSUMPTIONS from how the live sites look (not tested). Sites not listed use only the text check in the script.
     */
    private val FLAG_SEL = mapOf(
        "reddit" to "shreddit-post[nsfw],shreddit-post[is-nsfw],shreddit-post[spoiler],[data-nsfw=\"true\"],.thing.over18,.over18",
        "x" to "[data-testid=\"sensitive_media\"],[data-testid=\"tweet\"] [aria-label*=\"ensitive\"]",
        "tumblr" to "[data-nsfw],.nsfw",
        "bluesky" to "[data-testid=\"contentHider-post\"],[data-testid=\"contentHider-embed\"]"
    )

    // Page script. Raw string: no dollar sign inside. set(mode, px, sel) can be called again any time (settings changed).
    private const val JS = """(function(){try{if(window.__mgSz)return;
var G=0,mode=0,px=12,sel='',gen=0,pend=null,mo=null,pill=null,all=false;
var RX=/(nsfw|\b18\+|sensitive (content|media)|mature content|adult content)/i;
var CT='article,[role=article],shreddit-post,[data-testid=tweet],.thing,li';
var st=document.createElement('style');st.id='__mgSzs';
st.textContent='.__mgbl{filter:blur(var(--mgbl,12px))!important}html.__mgall .__mgbl,.__mgbl.__mgrv{filter:none!important}';
(document.head||document.documentElement).appendChild(st);
function big(e){var r=e.getBoundingClientRect();if(r.width<1&&r.height<1)return -1;return Math.max(r.width,r.height)>=64?1:0;}
function flagged(e){try{if(sel&&e.closest(sel))return true;var c=e.closest(CT);if(!c){c=e.parentElement;for(var i=0;i<2&&c&&c.parentElement;i++)c=c.parentElement;}
if(c&&c.textContent&&c.textContent.length<4000&&RX.test(c.textContent))return true;}catch(x){}return false;}
function one(e){if(e.__mgg===gen)return;
if(e.tagName==='IMG'&&!e.complete){if(!e.__mgl){e.__mgl=1;e.addEventListener('load',sched,{once:true});}return;}
var b=big(e);if(b<0)return;e.__mgg=gen;
if(b===0)return;
if(e.tagName!=='IMG'&&e.tagName!=='VIDEO'&&e.tagName!=='CANVAS'&&(e.textContent||'').trim().length>1)return;
if(mode===2||(mode===1&&flagged(e)))e.classList.add('__mgbl');}
function scan(){pend=null;if(!mode)return;var l=document.querySelectorAll('img,video,canvas,[style*="background-image"]');
for(var i=0;i<l.length;i++)one(l[i]);upd();}
function sched(){if(!mode||pend)return;pend=setTimeout(scan,250);}
function upd(){var n=document.querySelectorAll('.__mgbl:not(.__mgrv)').length;
if(!pill){pill=document.createElement('div');pill.id='__mgSzp';
pill.setAttribute('style','position:fixed;left:50%;bottom:76px;transform:translateX(-50%);z-index:2147483647;padding:8px 14px;border-radius:999px;background:rgba(25,26,36,.92);color:#fff;border:1px solid rgba(255,255,255,.25);font:600 13px sans-serif;display:none;cursor:pointer');
pill.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();all=!all;document.documentElement.classList.toggle('__mgall',all);upd();},true);
(document.body||document.documentElement).appendChild(pill);}
pill.textContent=all?'Blur again':'Reveal all ('+n+')';
pill.style.display=(mode&&(n>0||all))?'block':'none';}
function clean(){var l=document.querySelectorAll('.__mgbl,.__mgrv');for(var i=0;i<l.length;i++){l[i].classList.remove('__mgbl','__mgrv');}
all=false;document.documentElement.classList.remove('__mgall');}
document.addEventListener('click',function(e){if(!mode||all)return;if(pill&&pill.contains(e.target))return;
var s=document.elementsFromPoint(e.clientX,e.clientY);
for(var i=0;i<s.length;i++){var t=s[i];if(t.classList&&t.classList.contains('__mgbl')&&!t.classList.contains('__mgrv')){
e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();t.classList.add('__mgrv');upd();return;}}},true);
document.addEventListener('load',function(e){if(e.target&&e.target.tagName==='IMG')sched();},true);
window.addEventListener('scroll',sched,{passive:true});window.addEventListener('resize',sched,{passive:true});
mo=new MutationObserver(sched);mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','srcset','style']});
window.__mgSz={set:function(m,p,s){m=m|0;p=p|0;s=s||'';if(m===mode&&p===px&&s===sel){sched();return;}mode=m;px=p;sel=s;gen++;
document.documentElement.style.setProperty('--mgbl',px+'px');clean();if(mode)scan();else upd();}};
}catch(e){}})();"""

    /** Script for this page: installs the blur (once) and passes the current settings. Call on every inject; it is cheap. */
    fun script(ctx: Context, profileId: String): String {
        val sel = JSONObject.quote(FLAG_SEL[profileId] ?: "")
        return JS + "try{window.__mgSz&&window.__mgSz.set(" + mode(ctx) + "," + px(ctx) + "," + sel + ");}catch(e){}"
    }

    /** Called by SocialMode.inject for a social page whose switches are ON. Mode OFF still sends the short call, so a blur from earlier is removed. */
    fun inject(ctx: Context, view: android.webkit.WebView?, profileId: String?) {
        if (view == null || profileId == null) return
        if (mode(ctx) == OFF) {
            view.evaluateJavascript("try{window.__mgSz&&window.__mgSz.set(0,12,'');}catch(e){}", null)
            return
        }
        view.evaluateJavascript(script(ctx, profileId), null)
    }
}
