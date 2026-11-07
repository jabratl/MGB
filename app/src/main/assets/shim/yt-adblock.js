/* MGB yt-adblock.js (0.0.300 = v2): YouTube ads, document-start script for www / m / music.youtube.com and embeds (also youtube-nocookie).
 * Brave / uBlock style "scriptlets", no request is blocked by this script (so YouTube sees no failed ad request):
 * 1. ad data is cut out of EVERY JSON YouTube reads: first page data (ytInitialPlayerResponse, ytInitialData), fetch() and XHR answers of /youtubei/v1/*
 *    (player, next, browse, search, reel...), JSON.parse. Removed: adPlacements / adSlots / playerAds keys, and ad items in feed / search / Shorts /
 *    watch-next lists (adSlotRenderer, promoted video, banner promo, masthead...), the ads engagement panel.
 * 2. if an ad still plays (server side inserted, .ad-showing): mute, 16x, jump to the end, press Skip (checked on every video event + every 200 ms)
 * 3. "ad blockers are not allowed" popup: removed, page scroll and the paused video are given back
 * 4. CSS hides ad slots / promoted items / banners / upsell bars in the page
 * Off when window.__mgbYtOff === 1 (set by YtAds.flag when the blocker is off or ads are allowed on the site).
 * Everything is in try/catch: a YouTube change must never break the page. */
(function () {
  'use strict';
  try {
    if (window.__mgbYt >= 2) return;
    window.__mgbYt = 2;
    var off = function () { return window.__mgbYtOff === 1; };
    var STAT = window.__mgbYtStat = { pruned: 0, skipped: 0, popups: 0 };
    var DEL = { adPlacements: 1, adSlots: 1, playerAds: 1 };
    var AD = {
      adSlotRenderer: 1, promotedSparklesWebRenderer: 1, promotedSparklesTextSearchRenderer: 1, searchPyvRenderer: 1,
      compactPromotedVideoRenderer: 1, promotedVideoRenderer: 1, displayAdRenderer: 1, inFeedAdLayoutRenderer: 1,
      bannerPromoRenderer: 1, statementBannerRenderer: 1, brandVideoSingletonRenderer: 1, brandVideoShelfRenderer: 1,
      videoMastheadAdV3Renderer: 1, primetimePromoRenderer: 1, actionCompanionAdRenderer: 1, playerLegacyDesktopWatchAdsRenderer: 1,
      adBreakServiceRenderer: 0
    };
    var WRAP = { richItemRenderer: 1, richSectionRenderer: 1, content: 1 };
    var MARK = /adPlacements|adSlots|playerAds|adSlotRenderer|promotedSparkles|searchPyvRenderer|[pP]romotedVideoRenderer|displayAdRenderer|inFeedAdLayoutRenderer|bannerPromoRenderer|statementBannerRenderer|brandVideo|videoMastheadAd|primetimePromo|actionCompanionAd|playerLegacyDesktopWatchAds|engagement-panel-ads/;
    var YTU = /\/youtubei\/v1\//;
    var P = JSON.parse;

    // is this array item an ad? (the ad renderer may sit in a wrapper: richItemRenderer.content.adSlotRenderer)
    function isAd(e, d) {
      if (!e || typeof e !== 'object') return false;
      for (var k in e) {
        if (AD[k] === 1) return true;
        if (d < 3 && WRAP[k] === 1 && isAd(e[k], d + 1)) return true;
      }
      var ep = e.engagementPanelSectionListRenderer;
      return !!(ep && typeof ep.targetId === 'string' && ep.targetId.indexOf('engagement-panel-ads') > -1);
    }

    var budget = 0;
    function walk(o, d) {
      if (!o || typeof o !== 'object' || d > 14 || budget-- < 0) return;
      var i;
      if (Array.isArray(o)) {
        for (i = o.length - 1; i >= 0; i--) {
          if (isAd(o[i], 0)) { o.splice(i, 1); STAT.pruned++; } else walk(o[i], d + 1);
        }
        return;
      }
      for (var dk in DEL) if (dk in o) { try { delete o[dk]; } catch (e) { try { o[dk] = undefined; } catch (e2) { } } STAT.pruned++; }
      for (var k in o) { var v = o[k]; if (v && typeof v === 'object') walk(v, d + 1); }
    }
    function prune(o) {
      if (!o || typeof o !== 'object' || off()) return o;
      try { budget = 60000; walk(o, 0); } catch (e) { }
      return o;
    }
    window.__mgbYtPrune = prune; // for tests

    // 1a. first page data: var ytInitialPlayerResponse = {...} / ytInitialData = {...}
    function trap(name) {
      try {
        var held;
        Object.defineProperty(window, name, { configurable: true, get: function () { return held; }, set: function (v) { held = prune(v); } });
      } catch (e) { }
    }
    trap('ytInitialPlayerResponse'); trap('ytInitialData');

    // 1b. JSON.parse (anything YouTube parses itself)
    JSON.parse = function (t) {
      var r = P.apply(this, arguments);
      try { if (typeof t === 'string' && t.length > 200 && MARK.test(t)) prune(r); } catch (e) { }
      return r;
    };

    // 1c. fetch(): the answer of /youtubei/v1/* is rewritten without ads before YouTube reads it (text or json)
    try {
      var F = window.fetch;
      if (F) window.fetch = function (inp) {
        var p = F.apply(this, arguments);
        try {
          var u = typeof inp === 'string' ? inp : ((inp && inp.url) || '');
          if (off() || !YTU.test(u)) return p;
          return p.then(function (r) {
            try {
              if (!r || !r.ok) return r;
              return r.clone().text().then(function (t) {
                if (t.length < 200 || !MARK.test(t)) return r;
                var o = prune(P(t));
                return new Response(JSON.stringify(o), { status: r.status, statusText: r.statusText, headers: r.headers });
              }).catch(function () { return r; });
            } catch (e) { return r; }
          });
        } catch (e) { return p; }
      };
    } catch (e) { }

    // 1d. XMLHttpRequest: response / responseText of /youtubei/v1/* (older and some mobile paths)
    try {
      var XP = XMLHttpRequest.prototype, XO = XP.open;
      XP.open = function (m, u) { try { this.__mgbY = YTU.test(String(u)); } catch (e) { } return XO.apply(this, arguments); };
      ['response', 'responseText'].forEach(function (k) {
        var d = Object.getOwnPropertyDescriptor(XP, k);
        if (!d || !d.get) return;
        Object.defineProperty(XP, k, {
          configurable: true, enumerable: d.enumerable,
          get: function () {
            var v = d.get.call(this);
            try {
              if (!this.__mgbY || this.readyState !== 4 || !v || off()) return v;
              if (typeof v === 'string') {
                if (v.length < 200 || !MARK.test(v)) return v;
                if (this.__mgbS === v) return this.__mgbC;
                var s = JSON.stringify(prune(P(v)));
                this.__mgbS = v; this.__mgbC = s;
                return s;
              }
              if (typeof v === 'object' && this.__mgbD !== v) { prune(v); this.__mgbD = v; }
            } catch (e) { }
            return v;
          }
        });
      });
    } catch (e) { }

    // 2. an ad plays anyway (server side inserted / missed): mute, 16x, jump to the end, Skip
    var inAd = false, prevMuted = false, prevRate = 1;
    var SKIP = '.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot button,.videoAdUiSkipButton,.ytp-ad-overlay-close-button';
    function ads() {
      var p = document.querySelector('.html5-video-player');
      if (!p) return;
      var showing = p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting');
      var v = p.querySelector('video.html5-main-video') || p.querySelector('video') || document.querySelector('video');
      if (showing && v) {
        if (!inAd) { inAd = true; STAT.skipped++; prevMuted = v.muted; prevRate = v.playbackRate || 1; }
        try { v.muted = true; v.playbackRate = 16; if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.1) v.currentTime = v.duration; } catch (e) { }
        var b = document.querySelector(SKIP);
        if (b) { try { b.click(); } catch (e) { } }
      } else if (inAd) {
        inAd = false;
        if (v) { try { v.muted = prevMuted; v.playbackRate = prevRate; } catch (e) { } }
      }
    }

    // 3. "ad blockers violate the terms" popup: remove it, give back scroll and the paused video
    function popup() {
      var e = document.querySelector('ytd-enforcement-message-view-model');
      if (!e) return;
      STAT.popups++;
      var dlg = (e.closest && e.closest('tp-yt-paper-dialog')) || e;
      try { dlg.remove(); } catch (x) { dlg.style.display = 'none'; }
      document.querySelectorAll('tp-yt-iron-overlay-backdrop').forEach(function (b) { b.style.display = 'none'; });
      try { document.body.style.overflow = ''; document.documentElement.style.overflow = ''; } catch (x) { }
      var v = document.querySelector('video');
      if (v && v.paused) { try { v.play(); } catch (x) { } }
    }

    // 4. CSS (two rules: an engine without :has() drops only the second)
    var CSS1 = '#masthead-ad,#player-ads,.video-ads,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,' +
      'ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,ytd-display-ad-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,' +
      'ytd-player-legacy-desktop-watch-ads-renderer,ytd-enforcement-message-view-model,ytd-brand-video-singleton-renderer,ytd-brand-video-shelf-renderer,' +
      'ytd-video-masthead-ad-v3-renderer,ytd-primetime-promo-renderer,ytd-search-pyv-renderer,ytd-mealbar-promo-renderer,#mealbar-promo-renderer,' +
      '.ytp-ad-overlay-container,.ytp-ad-overlay-slot,.ytp-ad-image-overlay,.ytp-ad-player-overlay,.ytp-ad-player-overlay-layout,.ytp-paid-content-overlay,' +
      'ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-companion-slot,ytm-display-ad-renderer,' +
      'ytm-statement-banner-renderer,ytm-banner-promo-renderer,ytm-mealbar-promo-renderer,.ytm-ad-slot,#related ytd-ad-slot-renderer,' +
      'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]{display:none!important}';
    var CSS2 = 'ytd-rich-item-renderer:has(ytd-ad-slot-renderer),ytd-rich-section-renderer:has(ytd-statement-banner-renderer),ytm-rich-item-renderer:has(ad-slot-renderer),' +
      'ytd-reel-video-renderer:has(ytd-ad-slot-renderer),ytd-reel-video-renderer:has(.ytReelAdsRenderer),ytm-reel-item-renderer:has(ad-slot-renderer){display:none!important}';
    function css() {
      if (off() || document.getElementById('mgb-yt-css')) return;
      var h = document.head || document.documentElement;
      if (!h) return;
      var a = document.createElement('style'); a.id = 'mgb-yt-css'; a.textContent = CSS1; h.appendChild(a);
      var b = document.createElement('style'); b.id = 'mgb-yt-css2'; b.textContent = CSS2; h.appendChild(b);
    }

    function run() { try { if (off()) return; css(); ads(); popup(); } catch (e) { } }
    setInterval(run, 200);
    ['timeupdate', 'loadeddata', 'playing', 'play'].forEach(function (ev) { try { document.addEventListener(ev, run, true); } catch (e) { } });
  } catch (e) { }
})();
