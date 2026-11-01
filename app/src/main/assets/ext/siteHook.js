// Media Grabber - site modules (runs inside the page, world: MAIN, document_start).
// Reads the data some sites keep in their own API answers / embedded JSON, and reports the media it finds to content.js
// through the same postMessage channel as hook.js. Supported: TikTok, Douyin, Vimeo, SoundCloud, Bandcamp.
(() => {
  if (window.__mgSite) return;
  window.__mgSite = 1;
  const host = location.hostname.toLowerCase();
  const is = (d) => host === d || host.endsWith('.' + d);
  const off = () => { try { return document.documentElement.dataset.mgSite === 'off'; } catch (e) { return false; } };
  const seen = new Set();
  let emitted = 0;
  const norm = (u) => {
    u = String(u || '').trim();
    if (!u) return '';
    if (u.startsWith('//')) u = 'https:' + u;
    if (!/^https?:/i.test(u)) { try { u = new URL(u, location.href).href; } catch (e) { return ''; } }
    return u;
  };
  const kindOf = (u) => {
    const p = String(u).split('#')[0];
    if (/\.m3u8(\?|$)/i.test(p)) return 'hls';
    if (/\.mpd(\?|$)/i.test(p)) return 'dash';
    if (/\.(mp3|m4a|aac|ogg|wav|flac)(\?|$)/i.test(p)) return 'audio';
    if (/\.(vtt|srt)(\?|$)/i.test(p)) return 'subtitle';
    return 'video';
  };
  const emit = (url, kind) => {
    const u = norm(url);
    if (!u || off() || emitted >= 60) return;
    kind = kind || kindOf(u);
    const k = kind + '|' + u;
    if (seen.has(k)) return;
    seen.add(k); emitted++;
    window.postMessage({ __mg: 1, site: 1, url: u, kind }, '*');
  };

  // find a JSON object that follows a marker in a script text, e.g. "playerConfig = {...}"
  function jsonAfter(text, marker) {
    const i = text.indexOf(marker);
    if (i < 0) return null;
    const s = text.indexOf('{', i + marker.length);
    if (s < 0) return null;
    let depth = 0, str = false, esc = false;
    for (let p = s; p < text.length; p++) {
      const c = text[p];
      if (str) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') str = false; }
      else if (c === '"') str = true;
      else if (c === '{') depth++;
      else if (c === '}' && --depth === 0) { try { return JSON.parse(text.slice(s, p + 1)); } catch (e) { return null; } }
    }
    return null;
  }
  function walk(o, fn, depth = 0, budget = { n: 4000 }) {
    if (!o || typeof o !== 'object' || depth > 9 || budget.n-- <= 0) return;
    if (Array.isArray(o)) { for (const x of o) walk(x, fn, depth + 1, budget); return; }
    fn(o);
    for (const k in o) { const v = o[k]; if (v && typeof v === 'object') walk(v, fn, depth + 1, budget); }
  }

  // ---- Vimeo: player config ----
  function parseVimeo(j) {
    const req = j && j.request;
    if (!req) return;
    const f = req.files || {};
    if (f.hls && f.hls.cdns) {
      const c = f.hls.cdns[f.hls.default_cdn] || Object.values(f.hls.cdns)[0];
      if (c && c.url) emit(c.url, 'hls'); // one CDN is enough
    }
    (f.progressive || []).forEach((p) => p && p.url && emit(p.url, 'video'));
    (req.text_tracks || []).forEach((t) => { try { if (t && t.url) emit(new URL(t.url, 'https://player.vimeo.com').href, 'subtitle'); } catch (e) { /* skip */ } });
  }

  // ---- TikTok / Douyin: video objects in API answers and page state ----
  function harvestVideo(v) {
    const urls = [];
    const add = (x) => { if (typeof x === 'string' && /^(https?:)?\/\//.test(x) && !urls.includes(x)) urls.push(x); };
    const addr = (a) => { if (!a) return; if (typeof a === 'string') return add(a); add(a.url); (a.UrlList || a.url_list || []).slice(0, 2).forEach(add); };
    addr(v.downloadAddr); addr(v.download_addr); addr(v.playAddr); addr(v.play_addr); addr(v.PlayAddr);
    (v.bitrateInfo || v.bit_rate || []).slice(0, 3).forEach((b) => b && addr(b.PlayAddr || b.play_addr));
    urls.slice(0, 4).forEach((u) => emit(u, kindOf(u) === 'hls' ? 'hls' : 'video'));
  }
  function harvestVideos(j) { walk(j, (o) => { if (o.video && typeof o.video === 'object') harvestVideo(o.video); }); }

  // ---- SoundCloud: the media endpoint answers {url: ...} ----
  function parseSoundCloud(j) {
    if (!j || !j.url) return;
    const k = kindOf(j.url);
    emit(j.url, k === 'video' ? 'audio' : k);
  }

  const HANDLERS = [];
  if (is('vimeo.com')) HANDLERS.push({ test: (u) => /\/config(\?|$)/.test(u), parse: parseVimeo });
  if (is('tiktok.com') || is('douyin.com')) HANDLERS.push({ test: (u) => /\/(api|aweme)\//.test(u), parse: harvestVideos });
  if (is('soundcloud.com')) HANDLERS.push({ test: (u) => /\/media\/soundcloud:tracks:/.test(u), parse: parseSoundCloud });

  if (HANDLERS.length) {
    const _fetch = window.fetch;
    if (_fetch) {
      window.fetch = function (input) {
        const p = _fetch.apply(this, arguments);
        try {
          const url = typeof input === 'string' ? input : (input && input.url) || String(input);
          const hs = HANDLERS.filter((h) => h.test(url));
          if (hs.length) p.then((r) => {
            if (!r || !r.ok) return;
            r.clone().json().then((j) => hs.forEach((h) => { try { h.parse(j, url); } catch (e) { /* ignore */ } })).catch(() => {});
          }).catch(() => {});
        } catch (e) { /* never break the page */ }
        return p;
      };
    }
    const _open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, url) {
      try {
        const u = String(url), hs = HANDLERS.filter((h) => h.test(u));
        if (hs.length) this.addEventListener('load', () => {
          try {
            let j = this.response;
            if (this.responseType === '' || this.responseType === 'text') j = JSON.parse(this.responseText);
            else if (this.responseType !== 'json') return;
            hs.forEach((h) => h.parse(j, u));
          } catch (e) { /* ignore */ }
        });
      } catch (e) { /* never break the page */ }
      return _open.apply(this, arguments);
    };
  }

  // ---- data embedded in the page itself ----
  function scanPage() {
    try {
      if (is('tiktok.com') || is('douyin.com')) {
        for (const id of ['__UNIVERSAL_DATA_FOR_REHYDRATION__', 'SIGI_STATE', 'RENDER_DATA']) {
          const el = document.getElementById(id);
          if (!el) continue;
          const t = el.textContent || '';
          let j = null;
          try { j = JSON.parse(t); } catch (e) { try { j = JSON.parse(decodeURIComponent(t)); } catch (e2) { /* not JSON */ } }
          if (j) harvestVideos(j);
        }
      }
      if (is('vimeo.com')) {
        document.querySelectorAll('script:not([src])').forEach((s) => {
          const t = s.textContent || '';
          if (t.length < 200 || !/"files"\s*:/.test(t)) return;
          const j = jsonAfter(t, 'playerConfig') || jsonAfter(t, 'var config') || jsonAfter(t, 'config =');
          if (j) parseVimeo(j);
        });
      }
      if (is('bandcamp.com')) {
        document.querySelectorAll('[data-tralbum]').forEach((el) => {
          try { const t = JSON.parse(el.getAttribute('data-tralbum')); (t.trackinfo || []).forEach((ti) => Object.values(ti.file || {}).forEach((u) => emit(u, 'audio'))); } catch (e) { /* skip */ }
        });
      }
    } catch (e) { /* ignore */ }
  }
  document.addEventListener('DOMContentLoaded', scanPage);
  window.addEventListener('load', () => setTimeout(scanPage, 800));
  if (document.readyState !== 'loading') scanPage();
})();
