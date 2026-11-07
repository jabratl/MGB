/* MGB yt-media.js (0.0.296): Settings > Media for youtube.com (document-start, www / m / music).
 * 1. Background play: the page keeps believing it is visible (document.hidden = false, no visibilitychange) while window.__mgbYm.bg === 1.
 * 2. Hide up-next / end cards (rec), sign-in + menu + comments + video info (dist), Shorts (sh) with plain CSS.
 * window.__mgbYm = {bg,rec,dist,sh} is set by YtAds.flag; __mgbYmApply() redraws the style. All in try/catch: a YouTube change must never break the page. */
(function () {
  'use strict';
  try {
    if (window.__mgbYmInit) return;
    window.__mgbYmInit = 1;
    var cfg = function () { return window.__mgbYm || {}; };
    var D = Document.prototype;
    var oh = Object.getOwnPropertyDescriptor(D, 'hidden'), ov = Object.getOwnPropertyDescriptor(D, 'visibilityState');
    function def(k, g) { try { Object.defineProperty(document, k, { configurable: true, get: g }); } catch (e) { } }
    def('hidden', function () { return cfg().bg ? false : (oh && oh.get ? oh.get.call(document) : false); });
    def('visibilityState', function () { return cfg().bg ? 'visible' : (ov && ov.get ? ov.get.call(document) : 'visible'); });
    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (n) {
      document.addEventListener(n, function (e) { if (cfg().bg) e.stopImmediatePropagation(); }, true);
    });

    var REC = '.ytp-endscreen-content,.ytp-ce-element,.ytp-ce-covering-overlay,.ytp-suggestion-set,.ytp-videowall-still,.ytp-autonav-endscreen-countdown-overlay,' +
      'ytd-watch-next-secondary-results-renderer,#related,ytm-watch-next-secondary-results-renderer,.related-chips-slot-wrapper,ytm-autonav-toggle-button-renderer,ytd-compact-autoplay-renderer{display:none!important}' +
      'ytm-item-section-renderer[section-identifier="related-items"]{display:none!important}';
    var DIST = 'ytm-comments-entry-point-header-renderer,ytm-comment-section-renderer,ytd-comments,#comments,ytm-slim-video-metadata-section-renderer,ytm-slim-video-information-renderer,' +
      'ytd-watch-metadata,#above-the-fold,ytm-topbar-menu-button-renderer,ytd-masthead #buttons ytd-button-renderer,ytd-masthead ytd-topbar-menu-button-renderer{display:none!important}';
    var SH1 = 'ytm-reel-shelf-renderer,ytd-reel-shelf-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,ytd-rich-shelf-renderer[is-shorts],ytd-mini-guide-entry-renderer[aria-label="Shorts"]{display:none!important}';
    var SH2 = 'ytm-rich-item-renderer:has(a[href^="/shorts/"]),ytd-rich-item-renderer:has(a[href^="/shorts/"]),ytd-video-renderer:has(a[href^="/shorts/"]),ytm-video-with-context-renderer:has(a[href^="/shorts/"]),' +
      'ytm-compact-video-renderer:has(a[href^="/shorts/"]),grid-shelf-view-model:has(a[href^="/shorts/"]),ytd-rich-section-renderer:has(ytd-rich-shelf-renderer[is-shorts]),ytd-guide-entry-renderer:has(a[title="Shorts"]),ytm-pivot-bar-item-renderer:has(a[href="/shorts"]){display:none!important}';
    var st = null;
    function apply() {
      try {
        var c = cfg(), css = '';
        if (c.rec) css += REC;
        if (c.dist) css += DIST;
        if (c.sh) css += SH1 + SH2; // two rules: an engine without :has() drops only the second
        var root = document.head || document.documentElement;
        if (!root) return;
        if (!st || !st.isConnected) { st = document.createElement('style'); st.id = 'mgb-ytm'; root.appendChild(st); }
        if (st.textContent !== css) st.textContent = css;
      } catch (e) { }
    }
    window.__mgbYmApply = apply;
    apply();
    document.addEventListener('readystatechange', apply);
    setInterval(apply, 3000);
  } catch (e) { }
})();
