// Catalog / chapter-list discovery helpers for webnovel sites.
// Strategies: page links, <select> dropdowns, embedded JSON, catalog-link finder, pagination,
// Blogger feeds, WordPress REST, Madara/Novel AJAX, sitemap, API scan, URL pattern.
const BN = '০১২৩৪৫৬৭৮৯';
const ascii = s => (s || '').replace(/[০-৯]/g, d => BN.indexOf(d));
export const clean = s => (s || '').replace(/\s+/g, ' ').trim();
const sleep = ms => new Promise(r => setTimeout(r, ms));
export const norm = u => { try { const x = new URL(u); x.hash = ''; return x.href; } catch { return u; } };
const abs = (h, base) => {
  try {
    if (!h || /^(javascript:|#|mailto:|tel:)/i.test(h.trim())) return null;
    const u = new URL(h, base); if (!/^https?:$/.test(u.protocol)) return null; u.hash = ''; return u.href;
  } catch { return null; }
};
const strip = s => clean(new DOMParser().parseFromString('<b>' + (s || ''), 'text/html').body.textContent);
const safeDec = s => { try { return decodeURIComponent(s); } catch { return s; } };

// ---------- chapter numbers, ordering, gaps ----------
export function chapterNum(text, href = '', loose = false) {
  const t = ascii(clean(text));
  let m = /(?:chapter|chap\.?|ch\.?|episode|ep\.?|part|অধ্যায়|পর্ব)\s*[-:#.]?\s*(\d+(?:\.\d+)?)/i.exec(t)
    || /第\s*(\d+)\s*[章节節回话話集]/.exec(t)
    || /^\s*#?(\d+(?:\.\d+)?)\s*[-.:)\s]/.exec(t);
  if (m) return parseFloat(m[1]);
  let p = ''; try { p = safeDec(new URL(href).pathname); } catch { }
  m = /(?:chapter|chap|ch|episode|ep|part)[-_/]?(\d+)/i.exec(p);
  if (m) return parseFloat(m[1]);
  if (loose) { m = /(\d+)(?!.*\d)/.exec(p); if (m) return parseFloat(m[1]); }
  return null;
}
export function orderList(list) {
  if (list.length < 2) return { list, mode: 'kept' };
  const nums = list.map(x => chapterNum(x.text, x.href, true));
  const have = nums.filter(n => n != null);
  const f = have[0], l = have[have.length - 1];
  if (have.length === list.length && new Set(have).size >= have.length * 0.95) {
    const idx = list.map((_, i) => i).sort((a, b) => nums[a] - nums[b] || a - b);
    const sorted = idx.some((v, i) => v !== i);
    return { list: idx.map(i => list[i]), mode: sorted ? 'sorted' : 'kept' };
  }
  if (have.length >= list.length * 0.7 && f > l) return { list: [...list].reverse(), mode: 'reversed' };
  return { list, mode: 'kept' };
}
export function gaps(list) {
  const ns = list.map(x => chapterNum(x.text, x.href, false)).filter(n => n != null && Number.isInteger(n));
  if (ns.length < Math.max(5, list.length * 0.8)) return [];
  const set = new Set(ns), lo = Math.min(...ns), hi = Math.max(...ns), miss = [];
  if (hi - lo > 20000) return [];
  for (let n = lo; n <= hi; n++) if (!set.has(n)) miss.push(n);
  return miss;
}

// ---------- links on a page ----------
// Link label that keeps the REAL chapter name: if the anchor only says "第242章" but its title/aria-label or its own list row
// carries the rest ("第242章 堂姐，我好像要突破了"), use that. Trailing dates / VIP / NEW tags are dropped.
const TAIL = /\s*(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?|\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|\bVIP\b|\bNEW\b|\bHOT\b|最新|更新)\s*$/i;
export function linkLabel(a) {
  let t = clean(a.textContent || '');
  for (const k of ['title', 'aria-label', 'data-title']) { const x = clean(a.getAttribute(k) || ''); if (x && x.length > t.length && x.length <= 140 && (!t || x.includes(t))) t = x; }
  if (t.length <= 16) {
    const row = a.closest('li,tr,dd,dt,.chapter,.chapter-item,.item');
    if (row && row.querySelectorAll('a[href]').length === 1) {
      let rt = clean(row.textContent || ''); for (let i = 0; i < 3; i++) rt = rt.replace(TAIL, '').trim();
      if (rt.length > t.length && rt.length <= 140 && rt.startsWith(t)) t = rt;
    }
  }
  return t;
}
export function docLinks(doc, base) {
  const seen = new Set(), out = [];
  for (const a of doc.querySelectorAll('a[href]')) {
    const href = abs(a.getAttribute('href'), base), text = linkLabel(a);
    if (!href || !text || seen.has(href)) continue; seen.add(href); out.push({ href, text: text.slice(0, 120) });
  }
  return out;
}
const CHAPISH = /(chapter|chap\.?\s*\d|\bch\.?\s*\d|episode|\bep\.?\s*\d|prologue|epilogue|第\s*[\d〇零一二三四五六七八九十百千两]+\s*[章节節回话話集卷]|অধ্যায়|পর্ব|^\s*#?\d+\s*[-.:)]|^\s*#?\d+\s)/i;
export const shape = h => {
  try { const u = new URL(h), last = u.pathname.split('/').pop(); return u.origin + u.pathname.replace(/[^/]*$/, '') + (/\d/.test(last) ? '#' : ''); } catch { return h; }
};
function groupBy(list) {
  const g = new Map(); for (const t of list) { const k = shape(t.href); (g.get(k) || g.set(k, []).get(k)).push(t); }
  let best = []; for (const v of g.values()) if (v.length > best.length) best = v; return best;
}
export function bestGroup(list) {
  const path = groupBy(list), txt = groupBy(list.filter(t => CHAPISH.test(t.text)));
  const best = (txt.length >= 5 && txt.length >= path.length * 0.6) ? txt : path;
  return best.length >= 3 ? best : [];
}
export function selectLinks(doc, base) {
  let best = [];
  for (const s of doc.querySelectorAll('select')) {
    const items = [];
    for (const o of s.querySelectorAll('option')) {
      const v = o.getAttribute('value') || '';
      const href = (/^(https?:|\/|\.)/.test(v) || /\.(html?|php)\b/.test(v)) ? abs(v, base) : null;
      if (href) items.push({ href, text: clean(o.textContent) });
    }
    if (items.length > best.length) best = items;
  }
  return best.length >= 5 ? best : [];
}
export function fragmentLinks(html, base, strict = false) {
  const d = new DOMParser().parseFromString(html, 'text/html'); let l = docLinks(d, base);
  if (strict) l = bestGroup(l);
  const s = selectLinks(d, base); return s.length > l.length ? s : l;
}

// ---------- chapter arrays inside JSON ----------
const TK = ['title', 'name', 'chapter_title', 'chapterTitle', 'chapter_name', 'chapterName', 'label', 'text'];
const UK = ['url', 'href', 'link', 'permalink', 'chapter_url', 'chapterUrl', 'uri', 'path'];
export function findChapterArrays(o, base) {
  let best = [];
  (function dfs(v, d) {
    if (d > 10 || v == null || typeof v !== 'object') return;
    if (Array.isArray(v)) {
      if (v.length >= 5 && v.every(x => x && typeof x === 'object' && !Array.isArray(x))) {
        const s = v.slice(0, 300);
        const tk = TK.find(k => s.filter(x => typeof x[k] === 'string').length >= s.length * 0.8);
        const uk = UK.find(k => s.filter(x => typeof x[k] === 'string' && x[k]).length >= s.length * 0.8);
        if (tk && uk) {
          const items = v.map(x => ({ href: abs(x[uk], base), text: strip(String(x[tk])) })).filter(x => x.href && x.text);
          if (items.length > best.length) best = items;
        }
      }
      v.slice(0, 5000).forEach(x => dfs(x, d + 1));
    } else for (const k of Object.keys(v)) dfs(v[k], d + 1);
  })(o, 0);
  return best;
}
export function embedded(doc, base) {
  let best = [];
  for (const sc of doc.querySelectorAll('script')) {
    const t = sc.textContent || ''; if (t.length < 200) continue;
    const type = (sc.getAttribute('type') || '').toLowerCase(); let obj = null;
    if (type.includes('json') || sc.id === '__NEXT_DATA__') { try { obj = JSON.parse(t); } catch { } }
    else if (/^\s*(?:window\.)?[\w$.\[\]'"]+\s*=\s*[{[]/.test(t)) { try { obj = JSON.parse(t.slice(t.search(/[{[]/)).replace(/;\s*$/, '')); } catch { } }
    if (!obj) continue;
    const r = findChapterArrays(obj, base); if (r.length > best.length) best = r;
  }
  return best;
}

// ---------- from a chapter page: where is the catalog? ----------
export function findCatalogLinks(doc, pageUrl) {
  const out = new Map(), cur = norm(pageUrl); let origin = ''; try { origin = new URL(pageUrl).origin; } catch { }
  const add = (href, score, why) => {
    const u = abs(href, pageUrl); if (!u || u === cur) return;
    try { const x = new URL(u); if (x.origin !== origin) return; if (x.pathname === '/' && score < 90) return; } catch { return; }
    const o = out.get(u); if (!o || o.score < score) out.set(u, { url: u, score, why });
  };
  const re = /(table\s*of\s*contents?|\btoc\b|chapter\s*list|list\s*of\s*chapters|all\s*chapters?|chapters?\s*index|\bindex\b|catalog(ue)?|\bcontents\b|目录|目錄|章节列表|章節列表|全部章节|সূচিপত্র|সব\s*অধ্যায়|অধ্যায়\s*তালিকা|novel\s*(info|page|details?)|back\s*to\s*(novel|book|series|index)|series\s*page|book\s*page)/i;
  doc.querySelectorAll('link[rel~=index],link[rel~=contents],link[rel~=up],a[rel~=index],a[rel~=contents],a[rel~=up]').forEach(e => add(e.getAttribute('href'), 90, 'rel link'));
  for (const a of doc.querySelectorAll('a[href]')) {
    const t = clean(a.textContent || a.getAttribute('title') || a.getAttribute('aria-label') || ''), cls = (a.getAttribute('class') || '') + ' ' + (a.id || '');
    if (t.length < 60 && re.test(t)) add(a.getAttribute('href'), 70, 'link “' + t.slice(0, 30) + '”');
    else if (/(^|[-_ ])(toc|chapter-list|chapters|novel-link|back-to-novel|index-link)([-_ ]|$)/i.test(cls)) add(a.getAttribute('href'), 55, 'link class');
  }
  const bc = [...doc.querySelectorAll('[class*=breadcrumb] a[href],nav[aria-label*=readcrumb] a[href],[itemtype*=BreadcrumbList] a[href]')];
  if (bc.length) { add(bc[bc.length - 1].getAttribute('href'), 80, 'breadcrumb'); if (bc.length > 1) add(bc[bc.length - 2].getAttribute('href'), 40, 'breadcrumb'); }
  for (const sc of doc.querySelectorAll('script[type="application/ld+json"]')) {
    try {
      const j = JSON.parse(sc.textContent); const arr = [].concat(j, j['@graph'] || []);
      for (const n of arr) if (n && n['@type'] === 'BreadcrumbList' && Array.isArray(n.itemListElement)) {
        const urls = n.itemListElement.map(e => typeof e.item === 'string' ? e.item : (e.item && (e.item['@id'] || e.item.url))).filter(Boolean);
        if (urls.length > 1) add(urls[urls.length - 2], 75, 'JSON-LD breadcrumb'); if (urls.length > 2) add(urls[urls.length - 3], 45, 'JSON-LD breadcrumb');
      }
    } catch { }
  }
  try {
    const u = new URL(pageUrl), p = u.pathname.replace(/\/+$/, '');
    const g1 = p.replace(/[-_/]?(chapter|chap|ch|episode|ep)[-_/]?\d+.*$/i, ''); if (g1 && g1 !== p) add(u.origin + g1 + '/', 38, 'URL guess');
    const g2 = p.replace(/\/[^/]+$/, ''); if (g2 && g2 !== p) add(u.origin + g2 + '/', 30, 'parent folder');
  } catch { }
  return [...out.values()].sort((a, b) => b.score - a.score).slice(0, 6);
}

// ---------- catalog pagination ----------
export function findPager(doc, url) {
  let cur; try { cur = new URL(url); } catch { return null; }
  const shp = x => x.pathname.replace(/\d+/g, '#');
  const ok = h => {
    const u = abs(h, url); if (!u || u === norm(url)) return null; let v; try { v = new URL(u); } catch { return null; }
    if (v.origin !== cur.origin) return null;
    const pg = /[?&](page|paged|p|pg)=\d+/i.test(v.search) || /\/page\/\d+/i.test(v.pathname) || /[-_/]p(age)?[-_]?\d+\/?$/i.test(v.pathname) || /updated-max|start-index|offset=/i.test(v.search);
    return (pg || shp(v) === shp(cur)) ? u : null;
  };
  for (const e of doc.querySelectorAll('a[rel~=next],link[rel~=next],.next.page-numbers,a.next,.blog-pager-older-link,#Blog1_blog-pager-older-link,li.next a,.pagination .next a')) { const r = ok(e.getAttribute('href')); if (r) return r; }
  const re = /^\s*(next|next\s*page|older(\s*posts?)?|›|»|>|→|পরবর্তী|下一页|下一頁)\s*$/i;
  for (const a of doc.querySelectorAll('a[href]')) { if (re.test(a.textContent || '') || re.test(a.getAttribute('aria-label') || '')) { const r = ok(a.getAttribute('href')); if (r) return r; } }
  for (const box of doc.querySelectorAll('.pagination,.page-numbers,.pager,.wp-pagenavi,nav[aria-label*=agination],[class*=pagination]')) {
    const cu = box.querySelector('[aria-current],.current,.active,strong'), c = cu ? parseInt(ascii(cu.textContent), 10) : 1;
    let best = null, bn = 1e9;
    for (const a of box.querySelectorAll('a[href]')) { const n = parseInt(ascii(clean(a.textContent)), 10); if (n > c && n < bn) { const r = ok(a.getAttribute('href')); if (r) { best = r; bn = n; } } }
    if (best) return best;
  }
  return null;
}

// ---------- platform-specific sources ----------
async function pageLoop(u, parse, max = 100) {
  const m = /([?&](?:page|pg|p|pageNum|pageNo|page_no)=)(\d+)/i.exec(u); if (!m) return null;
  const all = [], seen = new Set(); let n = +m[2] > 1 ? 1 : +m[2];
  for (let i = 0; i < max; i++, n++) {
    const r = await fetch(u.replace(m[0], m[1] + n), { credentials: 'include' }); if (!r.ok) break;
    const fresh = parse(await r.text()).filter(x => !seen.has(x.href)); if (!fresh.length) break;
    fresh.forEach(x => seen.add(x.href)); all.push(...fresh); await sleep(250);
  }
  return all;
}
export async function apiScan(urls, base) {
  const out = [];
  for (const u of urls.slice(0, 40)) {
    try {
      const r = await fetch(u, { credentials: 'include' }); if (!r.ok) continue;
      const text = await r.text();
      const parse = t => { try { return findChapterArrays(JSON.parse(t), u); } catch { return fragmentLinks(t, u, true); } };
      let items = parse(text); if (items.length < 3) continue;
      try { const more = await pageLoop(u, parse); if (more && more.length > items.length) items = more; } catch { }
      out.push({ name: 'API · ' + u.replace(/^https?:\/\//, '').slice(0, 70), items });
    } catch { }
  }
  return out;
}
export async function blogger(doc, url) {
  const u = new URL(url), gen = (doc.querySelector('meta[name=generator]') || {}).content || '';
  if (!(/blogspot\.|blogger\.com$/.test(u.hostname) || /blogger/i.test(gen) || doc.querySelector('link[rel="service.post"]'))) return [];
  const labels = new Set(), m0 = /\/search\/label\/([^?/#]+)/.exec(u.pathname); if (m0) labels.add(safeDec(m0[1]));
  if (!labels.size) {
    for (const a of doc.querySelectorAll('.post-labels a,.post-footer a[rel=tag],a[rel=tag],.labels a')) {
      const m = /\/search\/label\/([^?/#]+)/.exec(a.getAttribute('href') || ''); if (m) labels.add(safeDec(m[1]));
    }
  }
  const feed = async label => {
    const items = []; let start = 1;
    for (let i = 0; i < 80; i++) {
      const f = `${u.origin}/feeds/posts/default${label ? '/-/' + encodeURIComponent(label) : ''}?alt=json&max-results=150&start-index=${start}`;
      const r = await fetch(f); if (!r.ok) break; const j = await r.json(), es = (j.feed && j.feed.entry) || [];
      for (const e of es) { const l = (e.link || []).find(x => x.rel === 'alternate'); if (l) items.push({ href: abs(l.href, url), text: clean(e.title && e.title.$t), pub: (e.published && e.published.$t) || '' }); }
      if (es.length < 150) break; start += 150; await sleep(200);
    }
    return items.sort((a, b) => a.pub < b.pub ? -1 : a.pub > b.pub ? 1 : 0);
  };
  const out = [];
  for (const l of [...labels].slice(0, 6)) { try { out.push({ name: `Blogger label “${l}”`, items: await feed(l) }); } catch { } }
  if (!out.length) { try { out.push({ name: 'Blogger · all posts', items: await feed('') }); } catch { } }
  return out;
}
export async function wordpress(doc, url) {
  const l = doc.querySelector('link[rel="https://api.w.org/"]'), gen = (doc.querySelector('meta[name=generator]') || {}).content || '';
  if (!l && !/wordpress/i.test(gen) && !/wp-content/.test(doc.documentElement.innerHTML.slice(0, 20000))) return [];
  const u = new URL(url); let root = l ? abs(l.getAttribute('href'), url) : u.origin + '/wp-json/';
  if (!root || root.includes('?')) root = u.origin + '/wp-json/'; if (!root.endsWith('/')) root += '/';
  const terms = [];
  for (const el of doc.querySelectorAll('article,body,.post,[class*=category-],[class*=tag-]')) {
    for (const c of (el.getAttribute('class') || '').split(/\s+/)) { let m = /^category-(.+)$/.exec(c); if (m) terms.push(['categories', m[1]]); m = /^tag-(.+)$/.exec(c); if (m) terms.push(['tags', m[1]]); }
  }
  const segs = u.pathname.split('/').filter(s => s && !/^(category|tag|series|page|novel|novels|\d+)$/i.test(s)).slice(-3);
  for (const s of segs) { terms.push(['categories', s]); terms.push(['tags', s]); }
  const seen = new Set(), out = [];
  for (const [tax, slug] of terms.slice(0, 12)) {
    const k = tax + slug; if (seen.has(k)) continue; seen.add(k);
    try {
      const r = await fetch(`${root}wp/v2/${tax}?slug=${encodeURIComponent(slug)}&_fields=id,name`); if (!r.ok) continue;
      const t = (await r.json())[0]; if (!t) continue;
      const items = []; let total = 1;
      for (let p = 1; p <= total && p <= 100; p++) {
        const rr = await fetch(`${root}wp/v2/posts?${tax}=${t.id}&per_page=100&page=${p}&orderby=date&order=asc&_fields=link,title`); if (!rr.ok) break;
        total = +rr.headers.get('X-WP-TotalPages') || 1;
        for (const x of await rr.json()) items.push({ href: x.link, text: strip(x.title && x.title.rendered) });
        await sleep(200);
      }
      if (items.length >= 3) out.push({ name: `WordPress ${tax === 'tags' ? 'tag' : 'category'} “${t.name || slug}”`, items });
    } catch { }
  }
  return out;
}
export async function madara(doc, url) {
  if (!doc.querySelector('.wp-manga-chapter,#manga-chapters-holder,ul.version-chap,.listing-chapters_wrap,body.wp-manga,[class*=wp-manga]')) return [];
  const u = new URL(url); let novel = u.pathname.replace(/\/+$/, '');
  if (!doc.querySelector('#manga-chapters-holder,.listing-chapters_wrap')) novel = novel.replace(/\/[^/]+$/, '');
  const base = u.origin + novel + '/'; let items = [];
  try { const r = await fetch(base + 'ajax/chapters/', { method: 'POST', credentials: 'include', headers: { 'X-Requested-With': 'XMLHttpRequest' } }); if (r.ok) items = fragmentLinks(await r.text(), base); } catch { }
  if (items.length < 3) {
    const h = doc.querySelector('#manga-chapters-holder'), id = (h && h.getAttribute('data-id')) || (doc.querySelector('input.rating-post-id') || {}).value;
    if (id) try { const r = await fetch(u.origin + '/wp-admin/admin-ajax.php', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'XMLHttpRequest' }, body: 'action=manga_get_chapters&manga=' + encodeURIComponent(id) }); if (r.ok) items = fragmentLinks(await r.text(), base); } catch { }
  }
  return items.length >= 3 ? [{ name: 'Madara AJAX chapter list', items }] : [];
}
export async function novelAjax(doc, url) {
  const el = doc.querySelector('[data-novel-id]'), id = (el && el.getAttribute('data-novel-id')) || (doc.querySelector('#novel-id') || {}).value;
  if (!id) return [];
  const o = new URL(url).origin, out = [];
  for (const p of ['/ajax/chapter-archive?novelId=', '/ajax/chapter-option?novelId=']) {
    try { const r = await fetch(o + p + encodeURIComponent(id), { credentials: 'include', headers: { 'X-Requested-With': 'XMLHttpRequest' } }); if (!r.ok) continue; const items = fragmentLinks(await r.text(), url); if (items.length >= 3) { out.push({ name: 'Novel AJAX ' + p.split('/').pop().split('?')[0], items }); break; } } catch { }
  }
  return out;
}
export async function sitemap(url) {
  const u = new URL(url); let queue = [];
  try { const r = await fetch(u.origin + '/robots.txt'); if (r.ok) for (const m of (await r.text()).matchAll(/^\s*sitemap:\s*(\S+)/gim)) queue.push(m[1]); } catch { }
  if (!queue.length) queue = ['/sitemap.xml', '/sitemap_index.xml', '/wp-sitemap.xml', '/sitemap-index.xml'].map(p => u.origin + p);
  const done = new Set(), found = []; let n = 0;
  while (queue.length && n < 40 && found.length < 60000) {
    const sm = queue.shift(); if (done.has(sm) || /\.gz$/i.test(sm)) continue; done.add(sm); n++;
    let t; try { const r = await fetch(sm); if (!r.ok) continue; t = await r.text(); } catch { continue; }
    const locs = [...t.matchAll(/<loc>\s*(?:<!\[CDATA\[)?\s*([^<\]\s]+)/gi)].map(m => m[1].replace(/&amp;/g, '&'));
    if (/<sitemapindex/i.test(t)) queue.push(...locs); else found.push(...locs);
  }
  const segs = u.pathname.split('/').filter(Boolean).slice(0, 2).map(s => safeDec(s).toLowerCase().replace(/[-_]?(chapter|chap|ch|episode|ep)[-_]?\d+.*$/, ''));
  let key = segs.sort((a, b) => b.length - a.length)[0] || ''; if (key.length < 4) key = '';
  const chap = /chap|ch[-_]?\d|episode|ep[-_]?\d|章|话|話|\/\d+\/?$/i;
  return found.filter(h => (!key || safeDec(h).toLowerCase().includes(key)) && chap.test(safeDec(h))).slice(0, 8000)
    .map(h => ({ href: h, text: safeDec(h.replace(/\/+$/, '').split('/').pop()).replace(/[-_]+/g, ' ') }));
}

// ---------- URL pattern ({n}) from a chapter URL ----------
export function patternFromUrl(url) {
  try {
    const u = new URL(url), p = u.pathname;
    let m = /((?:chapter|chap|ch|episode|ep)[-_/]?)(\d+)/i.exec(p) || /()(\d+)(?!.*\d)/.exec(p);
    if (!m) return null;
    const i = m.index + m[1].length, n = m[2];
    return { template: u.origin + p.slice(0, i) + '{n' + (n.length > 1 && n[0] === '0' ? ':' + n.length : '') + '}' + p.slice(i + n.length) + u.search, n: +n };
  } catch { return null; }
}

// ---------- Webnovel.com (Qidian International) ----------
export const webnovelId = url => {
  try { const u = new URL(url); if (!/(^|\.)webnovel\.com$/.test(u.hostname)) return null; const m = /\/book\/(?:[^/]*_)?(\d{10,})/.exec(u.pathname); return m ? m[1] : null; } catch { return null; }
};
export function webnovelFromJson(obj, origin, id) {
  const items = [], seen = new Set();
  (function dfs(v, d) {
    if (d > 12 || v == null || typeof v !== 'object') return;
    if (Array.isArray(v)) {
      const ok = v.filter(x => x && typeof x === 'object' && /^\d{10,}$/.test(String(x.chapterId ?? x.cid ?? x.id ?? '')) && typeof (x.chapterName ?? x.name ?? x.title) === 'string');
      if (ok.length >= 3 && ok.length >= v.length * 0.6) for (const x of ok) {
        const cid = String(x.chapterId ?? x.cid ?? x.id), h = `${origin}/book/${id}/${cid}`;
        if (!seen.has(h)) { seen.add(h); items.push({ href: h, text: clean(String(x.chapterName ?? x.name ?? x.title)) }); }
      }
      v.slice(0, 3000).forEach(x => dfs(x, d + 1));
    } else for (const k of Object.keys(v)) dfs(v[k], d + 1);
  })(obj, 0);
  return items;
}
export function webnovelFromDoc(doc, url, id) {
  const origin = new URL(url).origin, re = new RegExp('^/book/[^/]*' + id + '/[^/]*\\d{10,}$'), items = [], seen = new Set();
  for (const a of doc.querySelectorAll('a[href]')) {
    let p = '', h = ''; try { const u = new URL(a.getAttribute('href'), origin); p = u.pathname; u.hash = ''; u.search = ''; h = u.href; } catch { continue; }
    if (!re.test(p) || seen.has(h)) continue; seen.add(h);
    const li = a.closest('li') || a, locked = !!(a.querySelector('[class*=lock i],[class*=vip i],[class*=privilege i]') || /lock|vip|privilege|paid/i.test(li.getAttribute('class') || ''));
    items.push({ href: h, text: clean(a.getAttribute('title') || a.textContent) || p.split('/').pop(), locked });
  }
  if (items.length >= 3) return items;
  for (const sc of doc.querySelectorAll('script')) {
    const t = sc.textContent || ''; if (t.length < 200 || !/chapterId|chapterName/.test(t)) continue;
    let o = null; try { o = JSON.parse(t); } catch { try { o = JSON.parse(t.slice(t.search(/[{[]/)).replace(/;\s*$/, '')); } catch { } }
    if (!o) continue; const r = webnovelFromJson(o, origin, id); if (r.length > items.length) return r;
  }
  return items;
}
