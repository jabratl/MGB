// Media Grabber - site adapters for page images (galleries / chapters whose images are not plain <img> tags).
// Loaded by content.js (detect only), background.js (probe) and manga.js (resolve).
// Every adapter has:  name, test(URL) -> bool,  probe(url, ctx) -> quick info,  resolve(url, ctx) -> { images, title, book, meta, ref }
// An image is { found, orig, link, alts: [more URLs to try, best first] }.
// ctx = { text(url, opt), json(url, opt), tabEval(fn, args), log(msg), refresh }  (supplied by the caller, all optional except text/json)
const MG_SITES = (() => {
  const hostIs = (h, ...d) => d.some((x) => h === x || h.endsWith('.' + x));
  const uniq = (a) => Array.from(new Set(a.filter(Boolean)));
  const EXT_RE = /\.(?:jpe?g|png|webp|avif|gif)(?:[?#][^"'\s]*)?$/i;

  // ---------- hitomi.la ----------
  // Image links are not in the page: the site's own scripts build them from a hash, a rotating path (gg.js) and a server number.
  // Order of attempts: 1) ask the page's own functions (always matches the live site)  2) rebuild the link ourselves from gg.js + galleries/ID.js.
  const HITOMI_LTN = ['https://ltn.gold-usergeneratedcontent.net', 'https://ltn.hitomi.la'];
  const hitomiId = (url) => { const m = /(?:-|\/reader\/|\/galleries\/|\/)(\d{3,9})\.(?:html|js)(?:[?#].*)?$/i.exec(url) || /[?&]id=(\d{3,9})/.exec(url); return m ? m[1] : ''; };
  async function hitomiInfo(id, ctx) {
    let lastErr = null;
    for (const base of HITOMI_LTN) {
      try {
        const js = await ctx.text(`${base}/galleries/${id}.js`);
        const m = /galleryinfo\s*=\s*(\{[\s\S]*\})\s*;?\s*$/.exec(js.trim()) || /(\{[\s\S]*\})/.exec(js);
        if (!m) throw new Error('galleryinfo not found');
        return { info: JSON.parse(m[1]), base };
      } catch (e) { lastErr = e; }
    }
    throw lastErr || new Error('galleries js unreachable');
  }
  function parseGG(txt) {
    const b = (/\bb\s*[:=]\s*['"]([^'"]+)['"]/.exec(txt) || [])[1] || '';
    const m = new Map(); let def = 0;
    const dv = /var\s+o\s*=\s*(\d+)/.exec(txt); if (dv) def = +dv[1];
    let pending = [], inDefault = false;
    const re = /case\s+(\d+)\s*:|default\s*:|\bo\s*=\s*(\d+)\s*;|\bbreak\s*;/g;
    let t;
    while ((t = re.exec(txt))) {
      if (t[1] != null) pending.push(+t[1]);
      else if (/^default/.test(t[0])) { inDefault = true; pending = []; }
      else if (t[2] != null) { if (inDefault) def = +t[2]; else pending.forEach((c) => m.set(c, +t[2])); }
      else { if (!inDefault) pending = []; }
    }
    return { b, m, def };
  }
  function hitomiAlts(file, gg) {
    const h = String(file.hash || '');
    if (h.length < 3) return [];
    const g = parseInt(h.slice(-1) + h.slice(-3, -1), 16);
    if (isNaN(g)) return [];
    const mv = gg.m.has(g) ? gg.m.get(g) : gg.def;
    const path = `${gg.b}${g}/${h}`;
    const out = [];
    const sub = (p) => [p + (1 + mv), p + (2 - mv), p + 1, p + 2]; // best guess first, then the other server numbers
    const orig = (file.name || '').split('.').pop().toLowerCase();
    const add = (sd, ext, dir) => sd.forEach((s) => out.push(`https://${s}.gold-usergeneratedcontent.net/${dir}${path}.${ext}`));
    if (file.haswebp) add(sub('w'), 'webp', '');
    if (file.hasavif) add(sub('a'), 'avif', '');
    add([String.fromCharCode(97 + mv), 'a', 'b'], orig && orig.length <= 4 ? orig : 'jpg', '');
    return uniq(out);
  }
  async function hitomiResolve(url, ctx) {
    const id = hitomiId(url);
    if (!id) throw new Error('no gallery number in the address');
    const readerRef = `https://hitomi.la/reader/${id}.html`;
    let info = null, own = [], fromPage = null, ggTxt = '';
    // 1) the live page's own code (works when the page has loaded its scripts: gallery page and reader both do)
    if (ctx.tabEval) {
      try {
        fromPage = await ctx.tabEval(() => {
          const out = { urls: [], info: null };
          try {
            const gi = window.galleryinfo; if (!gi || !gi.files) return out;
            out.info = { title: gi.title, japanese_title: gi.japanese_title, files: gi.files.length };
            const fn = window.url_from_url_from_hash;
            if (typeof fn !== 'function') return out;
            gi.files.forEach((f) => {
              const c = [];
              const tryIt = (...a) => { try { const u = fn(gi.id, f, ...a); if (u && /^https?:/.test(u)) c.push(u); } catch (e) { /* variant not supported */ } };
              if (f.haswebp) tryIt('webp', undefined, undefined);
              if (f.hasavif) tryIt('avif', undefined, undefined);
              tryIt('webp', 'webp'); tryIt(undefined, undefined, undefined);
              out.urls.push(Array.from(new Set(c)));
            });
          } catch (e) { /* ignore */ }
          return out;
        });
      } catch (e) { ctx.log && ctx.log('hitomi: page code not reachable (' + e.message + ')'); }
    }
    // 2) our own rebuild (also gives the file list when the page code was not available)
    try {
      const r = await hitomiInfo(id, ctx); info = r.info;
      for (const base of [r.base, ...HITOMI_LTN.filter((x) => x !== r.base)]) {
        try { ggTxt = await ctx.text(`${base}/gg.js`); if (/\bb\s*[:=]/.test(ggTxt)) break; } catch (e) { /* next host */ }
      }
      if (ggTxt) { const gg = parseGG(ggTxt); own = info.files.map((f) => hitomiAlts(f, gg)); }
    } catch (e) { ctx.log && ctx.log('hitomi: own rebuild failed (' + e.message + ')'); if (!fromPage || !fromPage.urls.length) throw e; }
    const n = info ? info.files.length : fromPage.urls.length;
    const images = [];
    for (let i = 0; i < n; i++) {
      const list = uniq([...((fromPage && fromPage.urls[i]) || []), ...(own[i] || [])]);
      if (!list.length) continue;
      images.push({ found: list[0], orig: list[0], link: '', alts: list });
    }
    if (!images.length) throw new Error('could not build image links');
    const names = (a, k) => (a || []).map((x) => x[k]).filter(Boolean);
    const meta = info ? {
      tags: (info.tags || []).map((t) => t.tag).filter(Boolean), artists: names(info.artists, 'artist'), groups: names(info.groups, 'group'),
      parodies: names(info.parodys || info.parodies, 'parody'), characters: names(info.characters, 'character'), languages: [info.language].filter(Boolean), authors: [], summary: ''
    } : null;
    return { images, title: info ? info.title : '', meta, ref: readerRef };
  }

  // ---------- MangaDex ----------
  const mdId = (url) => (/\/chapter\/([0-9a-f-]{36})/i.exec(url) || [])[1] || '';
  async function mangadexResolve(url, ctx) {
    const id = mdId(url); if (!id) throw new Error('not a chapter address');
    const srv = await ctx.json(`https://api.mangadex.org/at-home/server/${id}`);
    if (!srv || !srv.chapter) throw new Error('server answer has no chapter');
    const { hash, data = [], dataSaver = [] } = srv.chapter, base = srv.baseUrl;
    const images = data.map((f, i) => {
      const main = `${base}/data/${hash}/${f}`;
      const alts = [main, dataSaver[i] ? `${base}/data-saver/${hash}/${dataSaver[i]}` : ''];
      return { found: main, orig: main, link: '', alts: uniq(alts) };
    });
    let title = '', book = '';
    try {
      const c = await ctx.json(`https://api.mangadex.org/chapter/${id}?includes[]=manga`);
      const a = c.data.attributes, mg = (c.data.relationships || []).find((r) => r.type === 'manga');
      book = mg && mg.attributes && mg.attributes.title ? (mg.attributes.title.en || Object.values(mg.attributes.title)[0] || '') : '';
      title = [book, a.volume ? 'Vol.' + a.volume : '', a.chapter ? 'Ch.' + a.chapter : '', a.title || ''].filter(Boolean).join(' ');
    } catch (e) { /* title is optional */ }
    return { images, title, book, meta: null, ref: 'https://mangadex.org/' };
  }
  async function mangadexProbe(url, ctx) {
    const t = /\/title\/([0-9a-f-]{36})/i.exec(url);
    if (!t) return { pages: 0 };
    const lang = (navigator.language || 'en').slice(0, 2), out = [];
    for (let off = 0; off < 3000; off += 500) {
      const r = await ctx.json(`https://api.mangadex.org/manga/${t[1]}/feed?limit=500&offset=${off}&translatedLanguage[]=${lang}&order[chapter]=asc&includeExternalUrl=0&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic`);
      (r.data || []).forEach((c) => { const a = c.attributes || {}; if (a.pages > 0) out.push({ url: 'https://mangadex.org/chapter/' + c.id, label: ('Chapter ' + (a.chapter || '?') + (a.title ? ' - ' + a.title : '')) }); });
      if (!r.data || r.data.length < 500) break;
    }
    return { pages: 0, chapters: out };
  }

  // ---------- nhentai ----------
  const nhId = (url) => (/\/g\/(\d+)/.exec(url) || [])[1] || '';
  // last resort when the API and the page's JSON are both unavailable: the gallery page itself lists a thumbnail per page
  function nhFromThumbs(html, id) {
    const re = /galleries\/(\d+)\/(\d+)t\.(jpg|png|webp|gif)/gi, ext = new Map(); let mid = '', mm;
    while ((mm = re.exec(html))) { if (!mid) mid = mm[1]; if (mm[1] === mid) ext.set(+mm[2], mm[3].toLowerCase()); }
    if (!mid || ext.size < 2) return null;
    let total = Math.max(...ext.keys());
    const i0 = html.indexOf('id="info"');
    if (i0 >= 0 && typeof expectedFromText === 'function') total = Math.max(total, expectedFromText(html.slice(i0, i0 + 4000).replace(/<[^>]*>/g, ' ')));
    const hosts = ['i', 'i1', 'i2', 'i3', 'i4', 'i5', 'i7'], images = [];
    for (let n = 1; n <= total; n++) {
      const e = ext.get(n) || ext.get(1) || 'jpg', list = [];
      hosts.forEach((h) => { list.push(`https://${h}.nhentai.net/galleries/${mid}/${n}.${e}`); });
      ['jpg', 'png', 'webp'].filter((x) => x !== e).forEach((x) => { list.push(`https://i.nhentai.net/galleries/${mid}/${n}.${x}`); list.push(`https://i3.nhentai.net/galleries/${mid}/${n}.${x}`); });
      images.push({ found: list[0], orig: list[0], link: `https://nhentai.net/g/${id}/${n}/`, alts: uniq(list) });
    }
    return { images, title: '', meta: { tags: [], artists: [], groups: [], parodies: [], characters: [], languages: [], authors: [], summary: '' }, ref: `https://nhentai.net/g/${id}/` };
  }
  async function nhentaiResolve(url, ctx) {
    const id = nhId(url); if (!id) throw new Error('not a gallery address');
    let g;
    try { g = await ctx.json(`https://nhentai.net/api/gallery/${id}`); }
    catch (e) { // API blocked: read the gallery page instead
      const html = await ctx.text(`https://nhentai.net/g/${id}/`);
      const m = /JSON\.parse\(\s*"((?:[^"\\]|\\.)*)"\s*\)/.exec(html);
      if (!m) { const fb = nhFromThumbs(html, id); if (fb) return fb; throw e; }
      g = JSON.parse(JSON.parse('"' + m[1] + '"'));
    }
    const mid = g.media_id, ex = { j: 'jpg', p: 'png', g: 'gif', w: 'webp' };
    const hosts = ['i', 'i1', 'i2', 'i3', 'i4', 'i5', 'i7'];
    const images = (g.images.pages || []).map((p, i) => {
      const e = ex[p.t] || 'jpg', list = [];
      hosts.forEach((h) => { list.push(`https://${h}.nhentai.net/galleries/${mid}/${i + 1}.${e}`); });
      hosts.slice(0, 3).forEach((h) => { list.push(`https://${h}.nhentai.net/galleries/${mid}/${i + 1}.${e}.webp`); });
      return { found: list[0], orig: list[0], link: '', alts: uniq(list) };
    });
    const tags = (g.tags || []);
    const pick = (t) => tags.filter((x) => x.type === t).map((x) => x.name);
    return {
      images, title: (g.title && (g.title.pretty || g.title.english || g.title.japanese)) || '',
      meta: { tags: pick('tag'), artists: pick('artist'), groups: pick('group'), parodies: pick('parody'), characters: pick('character'), languages: pick('language'), authors: [], summary: '' },
      ref: `https://nhentai.net/g/${id}/`
    };
  }

  // ---------- generic: pull the page list out of scripts / JSON in the HTML (any site) ----------
  const BAD = /logo|avatar|icon|emoji|sprite|banner|loading|placeholder|favicon|thumb|cover|\/ads?\/|gravatar|button|badge/i;
  function genericPages(html, base) {
    const txt = html.replace(/\\u002F/gi, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
    const found = [];
    (txt.match(/(?:https?:)?\/\/[^"'\s<>\\)(,]+?\.(?:jpe?g|png|webp|avif)(?:\?[^"'\s<>\\)(,]*)?/gi) || []).forEach((u) => {
      try { const x = new URL(u.startsWith('//') ? 'https:' + u : u, base).href; if (!BAD.test(x)) found.push(x); } catch (e) { /* skip */ }
    });
    // group by folder, keep the biggest group that looks like numbered pages
    const groups = new Map();
    found.forEach((u) => { const k = u.replace(/[^/]*$/, ''); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(u); });
    let best = [];
    groups.forEach((arr) => { const a = uniq(arr); if (a.length > best.length) best = a; });
    if (best.length < 3) return [];
    const num = (u) => { const m = (u.split(/[?#]/)[0].split('/').pop() || '').match(/(\d+)(?!.*\d)/); return m ? +m[1] : NaN; };
    if (best.every((u) => !isNaN(num(u)))) best.sort((a, b) => num(a) - num(b));
    return best.map((found2) => ({ found: found2, orig: found2, link: '', alts: [] }));
  }

  const ADAPTERS = [
    { name: 'hitomi', label: 'hitomi.la', test: (u) => hostIs(u.hostname, 'hitomi.la') && !!hitomiId(u.href), resolve: hitomiResolve,
      probe: async (url, ctx) => { const r = await hitomiInfo(hitomiId(url), ctx); return { pages: r.info.files.length, title: r.info.title }; } },
    { name: 'mangadex', label: 'MangaDex', test: (u) => hostIs(u.hostname, 'mangadex.org') && (!!mdId(u.href) || /\/title\//.test(u.pathname)), resolve: mangadexResolve,
      probe: async (url, ctx) => (mdId(url) ? { pages: 0 } : mangadexProbe(url, ctx)) },
    { name: 'nhentai', label: 'nhentai', test: (u) => hostIs(u.hostname, 'nhentai.net') && !!nhId(u.href), resolve: nhentaiResolve,
      probe: async (url, ctx) => { const g = await ctx.json(`https://nhentai.net/api/gallery/${nhId(url)}`); return { pages: (g.images.pages || []).length, title: (g.title && g.title.pretty) || '' }; } }
  ];

  return {
    ADAPTERS, genericPages, hitomiAlts, parseGG, hitomiId,
    // which adapter handles this address (null = none). Chapter-list-only pages (MangaDex /title/) are not "resolvable".
    match(url, forResolve) {
      try { const u = new URL(url); const a = ADAPTERS.find((x) => x.test(u)); return a && (!forResolve || a.name !== 'mangadex' || mdId(url)) ? a : null; } catch (e) { return null; }
    },
    byName(n) { return ADAPTERS.find((x) => x.name === n) || null; }
  };
})();
