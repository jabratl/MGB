/* MGB yt-adblock.js (0.0.105): YouTube ads, document-start script for www / m / music.youtube.com (also embeds).
 * 1. ad data is cut out of the player response (ytInitialPlayerResponse, JSON.parse, Response.json) so no ad is scheduled
 * 2. if an ad still plays (.ad-showing): mute, jump to the end, press Skip
 * 3. CSS hides ad slots / promoted items / banners in the page
 * Off when window.__mgbYtOff === 1 (set by TabManager when the blocker is off or ads are allowed on the site).
 * Everything is in try/catch: a YouTube change must never break the page. */
(function () {
  'use strict';
  try {
    if (window.__mgbYt) return;
    window.__mgbYt = 1;
    var off = function () { return window.__mgbYtOff === 1; };
    var KEYS = ['adPlacements', 'adSlots', 'playerAds'];

    function prune(o, d) {
      if (!o || typeof o !== 'object' || off()) return o;
      d = d || 0;
      if (d > 3) return o;
      try {
        if (Array.isArray(o)) { for (var i = 0; i < o.length && i < 8; i++) prune(o[i], d + 1); return o; }
        for (var k = 0; k < KEYS.length; k++) { if (KEYS[k] in o) { try { delete o[KEYS[k]]; } catch (e) { try { o[KEYS[k]] = undefined; } catch (e2) { } } } }
        if (o.playerResponse) prune(o.playerResponse, d + 1);
        if (o.response) prune(o.response, d + 1);
      } catch (e) { }
      return o;
    }
    window.__mgbYtPrune = prune; // for tests

    // 1a. JSON.parse (XHR / fetch text that YouTube parses itself)
    var P = JSON.parse;
    JSON.parse = function (t) {
      var r = P.apply(this, arguments);
      try { if (typeof t === 'string' && (t.indexOf('adPlacements') > -1 || t.indexOf('playerAds') > -1 || t.indexOf('adSlots') > -1)) prune(r); } catch (e) { }
      return r;
    };
    // 1b. Response.json() (fetch)
    try {
      var RJ = Response.prototype.json;
      Response.prototype.json = function () {
        return RJ.apply(this, arguments).then(function (r) { return prune(r); });
      };
    } catch (e) { }
    // 1c. inline <script>: ytInitialPlayerResponse = {...}
    try {
      var held;
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true,
        get: function () { return held; },
        set: function (v) { held = prune(v); }
      });
    } catch (e) { }

    // 2. an ad is playing anyway (server side inserted / missed)
    var inAd = false, prevMuted = false, prevRate = 1;
    function tick() {
      if (off() || document.hidden) return;
      var p = document.querySelector('.html5-video-player');
      if (!p) return;
      var showing = p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting');
      var v = p.querySelector('video') || document.querySelector('video');
      if (showing && v) {
        if (!inAd) { inAd = true; prevMuted = v.muted; prevRate = v.playbackRate || 1; }
        try { v.muted = true; v.playbackRate = 16; if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration; } catch (e) { }
        var b = document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot button,.videoAdUiSkipButton,.ytp-ad-overlay-close-button');
        if (b) { try { b.click(); } catch (e) { } }
      } else if (inAd) {
        inAd = false;
        if (v) { try { v.muted = prevMuted; v.playbackRate = prevRate; } catch (e) { } }
      }
    }

    // 3. CSS (two rules: an engine without :has() drops only the second)
    var CSS1 = '#masthead-ad,#player-ads,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,' +
      'ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,ytd-display-ad-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,' +
      'ytd-player-legacy-desktop-watch-ads-renderer,ytd-enforcement-message-view-model,.ytp-ad-overlay-container,.ytp-ad-overlay-slot,.ytp-ad-image-overlay,' +
      'ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-companion-slot,ytm-display-ad-renderer,' +
      'ytm-statement-banner-renderer,ytm-banner-promo-renderer,.ytm-ad-slot,#related ytd-ad-slot-renderer{display:none!important}';
    var CSS2 = 'ytd-rich-item-renderer:has(ytd-ad-slot-renderer),ytd-rich-section-renderer:has(ytd-statement-banner-renderer),ytm-rich-item-renderer:has(ad-slot-renderer){display:none!important}';
    function css() {
      if (off() || document.getElementById('mgb-yt-css')) return;
      var h = document.head || document.documentElement;
      if (!h) return;
      var a = document.createElement('style'); a.id = 'mgb-yt-css'; a.textContent = CSS1; h.appendChild(a);
      var b = document.createElement('style'); b.id = 'mgb-yt-css2'; b.textContent = CSS2; h.appendChild(b);
    }

    setInterval(function () { try { css(); tick(); } catch (e) { } }, 250);
  } catch (e) { }
})();
