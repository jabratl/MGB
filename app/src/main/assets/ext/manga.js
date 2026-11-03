// Media Grabber - page images -> CBZ / ZIP / built-in reader (manga, webtoons, galleries)
const $ = (s) => document.querySelector(s);
initTheme();
const Q = new URLSearchParams(location.search);
let SET = { ...DEFAULTS }, DATA = null, pages = [], cancelled = false;

// ---------- zip (store only, no compression: images are already compressed) ----------
const CRC = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function crc32(u8) { let c = -1; for (let i = 0; i < u8.length; i++) c = CRC[(c ^ u8[i]) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; }
async function makeZip(files) {
  const enc = new TextEncoder(), parts = [], cds = [];
  let off = 0;
  for (const f of files) {
    const data = new Uint8Array(await f.blob.arrayBuffer()), crc = crc32(data), nm = enc.encode(f.name);
    const lh = new DataView(new ArrayBuffer(30));
    lh.setUint32(0, 0x04034b50, true); lh.setUint16(4, 20, true); lh.setUint16(6, 0x0800, true); lh.setUint16(10, 0, true); lh.setUint16(12, 0x21, true);
    lh.setUint32(14, crc, true); lh.setUint32(18, data.length, true); lh.setUint32(22, data.length, true); lh.setUint16(26, nm.length, true);
    parts.push(lh.buffer, nm, f.blob); // the Blob is referenced, not copied: only one image is in memory at a time
    const cd = new DataView(new ArrayBuffer(46));
    cd.setUint32(0, 0x02014b50, true); cd.setUint16(4, 20, true); cd.setUint16(6, 20, true); cd.setUint16(8, 0x0800, true); cd.setUint16(14, 0x21, true);
    cd.setUint32(16, crc, true); cd.setUint32(20, data.length, true); cd.setUint32(24, data.length, true); cd.setUint16(28, nm.length, true); cd.setUint32(42, off, true);
    cds.push(cd.buffer, nm);
    off += 30 + nm.length + data.length;
  }
  const cdSize = cds.reduce((a, b) => a + (b.byteLength || b.length), 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true); end.setUint32(12, cdSize, true); end.setUint32(16, off, true);
  return new Blob([...parts, ...cds, end.buffer], { type: 'application/zip' });
}

// ---------- PDF (JPEG pages embedded as-is, everything else re-encoded to JPEG) ----------
function jpegComps(u8) {
  let i = 2;
  while (i < u8.length - 9) {
    if (u8[i] !== 0xFF) { i++; continue; }
    const mk = u8[i + 1];
    if (mk >= 0xC0 && mk <= 0xCF && mk !== 0xC4 && mk !== 0xC8 && mk !== 0xCC) return u8[i + 9];
    i += 2 + ((u8[i + 2] << 8) | u8[i + 3]);
  }
  return 3;
}
async function toJpeg(p) {
  if (p.ext === 'jpg') {
    const data = new Uint8Array(await p.blob.arrayBuffer()), c = jpegComps(data);
    if (c === 3 || c === 1) return { data, w: p.w, h: p.h, cs: c === 1 ? 'DeviceGray' : 'DeviceRGB' };
  }
  const bmp = await createImageBitmap(p.blob);
  const cv = document.createElement('canvas'); cv.width = bmp.width; cv.height = bmp.height;
  const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height); ctx.drawImage(bmp, 0, 0); bmp.close();
  const b = await new Promise((r) => cv.toBlob(r, 'image/jpeg', 0.92));
  return { data: new Uint8Array(await b.arrayBuffer()), w: cv.width, h: cv.height, cs: 'DeviceRGB' };
}
async function makePdf(sel) {
  const enc = new TextEncoder(), parts = [], offs = [];
  let len = 0;
  const add = (x) => { const u = typeof x === 'string' ? enc.encode(x) : x; parts.push(u); len += u.length; };
  const n = sel.length;
  add('%PDF-1.4\n');
  offs[1] = len; add('1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n');
  offs[2] = len; add(`2 0 obj\n<< /Type /Pages /Count ${n} /Kids [${sel.map((_, i) => `${5 + 3 * i} 0 R`).join(' ')}] >>\nendobj\n`);
  for (let i = 0; i < n; i++) {
    const j = await toJpeg(sel[i]), im = 3 + 3 * i, ct = 4 + 3 * i, pg = 5 + 3 * i;
    offs[im] = len; add(`${im} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${j.w} /Height ${j.h} /ColorSpace /${j.cs} /BitsPerComponent 8 /Filter /DCTDecode /Length ${j.data.length} >>\nstream\n`); add(j.data); add('\nendstream\nendobj\n');
    const cs = `q ${j.w} 0 0 ${j.h} 0 0 cm /Im0 Do Q`;
    offs[ct] = len; add(`${ct} 0 obj\n<< /Length ${cs.length} >>\nstream\n${cs}\nendstream\nendobj\n`);
    offs[pg] = len; add(`${pg} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${j.w} ${j.h}] /Resources << /XObject << /Im0 ${im} 0 R >> >> /Contents ${ct} 0 R >>\nendobj\n`);
    $('#st').textContent = `Building PDF ${i + 1}/${n}...`;
  }
  const total = 3 + 3 * n, xref = len;
  let x = `xref\n0 ${total}\n0000000000 65535 f \n`;
  for (let k = 1; k < total; k++) x += String(offs[k]).padStart(10, '0') + ' 00000 n \n';
  add(x + `trailer\n<< /Size ${total} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`);
  return new Blob(parts, { type: 'application/pdf' });
}

// ---------- EPUB (fixed-layout, one image per page; keeps the reading direction) ----------
async function makeEpub(sel, title) {
  const esc = (s) => String(s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  const MT = { jpg: 'image/jpeg', png: 'image/png', gif: 'image/gif' };
  const rtl = $('#rtl').checked, pad = (i) => String(i + 1).padStart(4, '0');
  const files = [
    { name: 'mimetype', blob: new Blob(['application/epub+zip']) },
    { name: 'META-INF/container.xml', blob: new Blob(['<?xml version="1.0"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>']) }
  ];
  const manifest = [], spine = [];
  for (let i = 0; i < sel.length; i++) {
    const p = sel[i];
    let ext = p.ext, blob = p.blob, w = p.w, h = p.h;
    if (!MT[ext]) { const j = await toJpeg(p); ext = 'jpg'; blob = new Blob([j.data], { type: 'image/jpeg' }); w = j.w; h = j.h; }
    files.push({ name: `OEBPS/images/${pad(i)}.${ext}`, blob });
    files.push({ name: `OEBPS/p${pad(i)}.xhtml`, blob: new Blob([`<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="utf-8"/><meta name="viewport" content="width=${w}, height=${h}"/><title>${i + 1}</title><style>html,body{margin:0;padding:0;background:#000}img{display:block;width:100%;height:100%;object-fit:contain}</style></head><body><img src="images/${pad(i)}.${ext}" alt=""/></body></html>`]) });
    manifest.push(`<item id="img${pad(i)}" href="images/${pad(i)}.${ext}" media-type="${MT[ext]}"${i === 0 ? ' properties="cover-image"' : ''}/>`, `<item id="p${pad(i)}" href="p${pad(i)}.xhtml" media-type="application/xhtml+xml"/>`);
    spine.push(`<itemref idref="p${pad(i)}"/>`);
    $('#st').textContent = `Building EPUB ${i + 1}/${sel.length}...`;
  }
  files.push({ name: 'OEBPS/nav.xhtml', blob: new Blob([`<?xml version="1.0" encoding="utf-8"?>\n<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><meta charset="utf-8"/><title>${esc(title)}</title></head><body><nav epub:type="toc"><ol><li><a href="p0001.xhtml">${esc(title)}</a></li></ol></nav></body></html>`]) });
  const uid = 'urn:uuid:' + (crypto.randomUUID ? crypto.randomUUID() : Date.now());
  files.push({ name: 'OEBPS/content.opf', blob: new Blob([`<?xml version="1.0" encoding="utf-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" prefix="rendition: http://www.idpf.org/vocab/rendition/#"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">${uid}</dc:identifier><dc:title>${esc(title)}</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">${new Date().toISOString().replace(/\.\d+Z$/, 'Z')}</meta><meta property="rendition:layout">pre-paginated</meta><meta property="rendition:spread">none</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${manifest.join('')}</manifest><spine${rtl ? ' page-progression-direction="rtl"' : ''}>${spine.join('')}</spine></package>`]) });
  return makeZip(files);
}

// ---------- fetching ----------
function sniff(buf, type) {
  const b = new Uint8Array(buf.slice(0, 12));
  if (b[0] === 0xFF && b[1] === 0xD8) return 'jpg';
  if (b[0] === 0x89 && b[1] === 0x50) return 'png';
  if (b[0] === 0x47 && b[1] === 0x49) return 'gif';
  if (b[0] === 0x52 && b[1] === 0x49 && b[8] === 0x57) return 'webp';
  if (b[4] === 0x66 && b[5] === 0x74 && b[8] === 0x61 && b[9] === 0x76) return 'avif';
  return /image\//.test(type) ? type.split('/')[1].replace('jpeg', 'jpg').replace('svg+xml', 'svg') : '';
}
// S89.1: the page the grab started from is gone (closed / reloaded away): log code source-tab-closed (once per 10 s)
const tabGone = (e) => /source tab closed|no tab with id|receiving end does not exist|could not establish connection|extension context/i.test(String((e && e.message) || e));
const tabMsg = (msg) => (DATA.tab != null
  ? chrome.tabs.sendMessage(DATA.tab, msg).catch((e) => { if (tabGone(e)) dbgc('manga', 'source-tab-closed', `${hostOf(DATA.ref)}: ${msg && msg.type}`); throw e; })
  : (dbgc('manga', 'source-tab-closed', `${hostOf(DATA.ref)}: no source tab (${msg && msg.type})`), Promise.reject(new Error('source tab closed'))));
async function fromDataUrl(d) {
  const blob = await (await fetch(d)).blob();
  const buf = await blob.arrayBuffer();
  const ext = sniff(buf, blob.type);
  if (!ext) throw new Error('not an image');
  return { blob: new Blob([buf], { type: 'image/' + ext }), ext };
}
// Guess the full-size URL from a thumbnail URL (site rules + generic patterns)
function ruleCandidates(u) {
  const out = [];
  try {
    const x = new URL(u);
    const nh = /^t(\d*)\.nhentai\.net$/i.exec(x.hostname);
    const pm = /^(.*\/)(\d+)t\.(.+)$/.exec(x.pathname);
    if (nh && pm) {
      const exts = Array.from(new Set([pm[3], pm[3].split('.')[0], 'jpg', 'png', 'webp', 'gif']));
      for (const host of [`i${nh[1]}.nhentai.net`, 'i.nhentai.net']) for (const e of exts) out.push(`${x.protocol}//${host}${pm[1]}${pm[2]}.${e}`);
    }
    if (!nh && pm) for (const e of Array.from(new Set([pm[3], 'jpg', 'png', 'webp', 'gif']))) out.push(x.origin + pm[1] + pm[2] + '.' + e); // .../1t.jpg -> .../1.jpg
    const g = x.pathname.replace(/\/thumbs?\//i, '/').replace(/([_-])(thumb|thumbnail|small|tn|t)(?=\.\w{3,4}$)/i, '');
    if (g !== x.pathname) out.push(x.origin + g + x.search);
  } catch (e) { /* ignore */ }
  return out;
}

// Open the viewer page a thumbnail links to and pull the big image out of it
async function crawlViewer(p) {
  const r = await fetch(p.link, { credentials: 'include' });
  if (!r.ok) throw new Error('viewer HTTP ' + r.status);
  const html = await r.text();
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const sels = ['#gimg', '#fullimg', 'img.fit-horizontal', 'img[alt$=" full"]', '#image-container img', 'img#image', '.reader img', '#reader img', '.viewer img', '#content img', 'main img'];
  const attr = (im) => im.getAttribute('data-src') || im.getAttribute('data-original') || im.getAttribute('data-lazy-src') || im.getAttribute('src');
  for (const s of sels) {
    const im = doc.querySelector(s);
    const u = im && attr(im);
    if (u && !/^data:/.test(u)) return new URL(u, p.link).href;
  }
  // the image URL may only exist inside a script / data attribute: take the first full-size looking URL
  const urls = (html.replace(/\\\//g, '/').match(/https?:\/\/[^"'\s<>\\)]+?\.(?:jpe?g|png|webp|avif)/gi) || []).filter((u) => !/thumb|cover|logo|avatar|icon|banner|sprite|\/\d+t\.|\/ads?\//i.test(u));
  if (urls.length) return urls[0];
  const og = doc.querySelector('meta[property="og:image"]');
  if (og && og.content) return new URL(og.content, p.link).href;
  throw new Error('no image in viewer page');
}

// ---- the image server says "too many requests": wait, slow down, then speed up again (instead of hammering every host) ----
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const CF_PAGE = /just a moment|challenge-platform|cf-browser-verification|attention required! \| cloudflare/i; // S89.1: Cloudflare check page text
const RL = { until: 0, strikes: 0, cap: Infinity, ok: 0 };
let rlActive = 0;
const rlMax = () => Math.max(1, SET.concurrency || 6);
async function rlGate() { const w = RL.until - Date.now(); if (w > 0) await sleep(w + Math.random() * 250); }
function rlHit(retryAfterSec) {
  RL.strikes++; RL.ok = 0;
  const back = retryAfterSec > 0 ? Math.min(60, retryAfterSec) * 1000 : Math.min(15000, 700 * 2 ** Math.min(RL.strikes, 5));
  RL.until = Math.max(RL.until, Date.now() + back);
  RL.cap = Math.max(1, Math.floor((RL.cap === Infinity ? rlMax() : RL.cap) / 2));
  if (RL.strikes <= 6) dbgc('manga', 'rate-limited', `${hostOf(DATA.ref)}: server is rate-limiting us: waiting ${Math.round(back / 1000)}s, ${RL.cap} download(s) at a time from now`);
}
// S89.2: image load failure -> stable code (the rules read the code, never the English text). Prefix img- keeps them apart from the S89.1 page-list codes.
function imgCode(msg) {
  const m = String(msg || '').split(' | ')[0];
  const h = /HTTP (\d{3})/.exec(m);
  if (h) return 'img-' + mgHttpCode(h[1]);
  if (/not an image/i.test(m)) return 'not-an-image';
  if (/corrupt image/i.test(m)) return 'corrupt-image';
  if (/failed to fetch|network|timeout|timed out|aborted|load failed/i.test(m)) return 'img-network-timeout';
  return 'img-fetch-error';
}
const cleanErr = (m) => String(m || '?').replace(/https?:\/\/\S+/g, '<url>').slice(0, 100);
function logImgFails(stage, list) { // one line per code (not per page): count + the first page as the example
  const by = {};
  list.forEach((p) => { const c = imgCode(p.err); (by[c] = by[c] || []).push(p); });
  Object.entries(by).forEach(([c, ps]) => dbgc('manga', c, `${hostOf(DATA.ref)}: ${stage}, ${ps.length} page(s), e.g. page ${pages.indexOf(ps[0]) + 1}: ${cleanErr(ps[0].err)}`));
  const last = list.filter((p) => /page-fetch:|canvas:/.test(p.err || '')); // pages where even the in-page fetch and the canvas copy failed
  if (last.length) dbgc('manga', 'all-methods-failed', `${hostOf(DATA.ref)}: ${stage}, ${last.length} page(s) failed every method (direct, viewer, page-fetch, canvas)`);
}
async function bitmapOf(blob) { try { return await createImageBitmap(blob); } catch (e) { throw new Error('corrupt image: ' + String((e && e.message) || e).slice(0, 80)); } }
function rlOk() { if (RL.cap !== Infinity && ++RL.ok >= 8) { RL.ok = 0; RL.cap = RL.cap + 1 >= rlMax() ? Infinity : RL.cap + 1; } }

async function tryDirect(u) {
  let rateTries = 0;
  for (let t = 0; t < 2; t++) {
    if (cancelled) throw new Error('Cancelled');
    try {
      await rlGate();
      const r = await fetch(u, { credentials: 'include' });
      if (!r.ok) {
        if (r.status === 404) throw Object.assign(new Error('HTTP 404'), { final: true });
        if ((r.status === 429 || r.status === 503) && rateTries < 4) { rateTries++; rlHit(parseInt(r.headers.get('retry-after'), 10) || 0); t--; continue; }
        throw Object.assign(new Error('HTTP ' + r.status), { rate: r.status === 429 || r.status === 503 });
      }
      const buf = await r.arrayBuffer();
      const ext = sniff(buf, r.headers.get('content-type') || '');
      if (!ext || buf.byteLength < 500) throw new Error('not an image (' + (r.headers.get('content-type') || 'unknown type') + ')');
      rlOk();
      return { blob: new Blob([buf], { type: 'image/' + ext }), ext, url: u, how: 'direct' };
    } catch (e) { if (e.final || e.rate || t) throw e; await sleep(400); }
  }
}

// 1) rule/thumbnail upgrade  2) viewer page  3) found URL  4) fetch from inside the page  5) canvas copy
// remembers which server pattern worked (e.g. "w#.cdn.net|webp") so the next pages try it first
let goodTag = '';
const altTag = (u) => { try { const x = new URL(u); return x.hostname.replace(/\d+/g, '#') + '|' + x.pathname.split('.').pop(); } catch (e) { return ''; } };
async function fetchImg(p) {
  const errs = [];
  const run = async (list, tag) => {
    for (const u of Array.from(new Set(list))) {
      try { const r = await tryDirect(u); r.how = tag; return r; } catch (e) { errs[0] = errs[0] || e.message; if (e.rate) throw e; /* rate limit: other servers will not help, wait and retry later */ }
    }
    return null;
  };
  let r;
  if (p.alts && p.alts.length) { // links from a site adapter (hitomi, MangaDex, nhentai ...), best first
    const list = goodTag ? [...p.alts.filter((u) => altTag(u) === goodTag), ...p.alts.filter((u) => altTag(u) !== goodTag)] : p.alts;
    r = await run(list, 'site adapter');
    if (r) { goodTag = altTag(r.url); return r; }
  }
  r = await run(ruleCandidates(p.found), 'full-size rule');
  if (r) return r;
  if (p.link) {
    try {
      const full = await crawlViewer(p);
      r = await run([full], 'viewer page');
      if (r) return r;
    } catch (e) { errs.push('viewer: ' + e.message); }
  }
  r = await run([p.orig, p.found], 'direct');
  if (r) return r;
  for (const [type, how] of [['mg-fetch', 'page-fetch'], ['mg-canvas', 'canvas']]) {
    for (const u of Array.from(new Set([p.orig, p.found]))) {
      try {
        const a = await tabMsg({ type, url: u });
        if (!a || !a.ok) throw new Error((a && a.err) || 'no answer from page');
        return { ...(await fromDataUrl(a.data)), url: u, how };
      } catch (e) { errs.push(how + ': ' + e.message); }
    }
  }
  throw new Error(errs.join(' | '));
}

async function setupHeaders(ref) {
  if (!ref) return;
  try {
    const me = await chrome.tabs.getCurrent();
    const id = 2100000 + me.id;
    await chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id], addRules: [{ id, priority: 1,
      action: { type: 'modifyHeaders', requestHeaders: [{ header: 'referer', operation: 'set', value: ref }, { header: 'origin', operation: 'set', value: new URL(ref).origin }] },
      condition: { initiatorDomains: [chrome.runtime.id], resourceTypes: ['xmlhttprequest', 'image', 'other'], tabIds: [me.id] } }] });
  } catch (e) { /* optional */ }
}

// ---------- site adapters (sites.js): resolve the real page list, with fallbacks ----------
function siteCtx() {
  const ctx = {
    async text(url) {
      let r = null, body = '';
      try {
        r = await fetch(url, { credentials: 'include' });
        body = await r.text();
        if (!r.ok) throw new Error('HTTP ' + r.status);
        if (body.length < 60000 && CF_PAGE.test(body)) dbgc('manga', 'cloudflare-blocked', `${hostOf(url)}: a check page came back instead of the data (HTTP ${r.status}), rescued=no`);
        return body;
      } catch (e) { // blocked here (CORS / Cloudflare cookies): ask the real page to fetch it with its own cookies
        // S89.1 code: cloudflare-blocked (challenge) / 403-blocked, 404-gone, 429-slow-down ... (HTTP) / cors-blocked (fetch refused) / fetch-error
        const cf = r && !r.ok && ((r.headers.get('cf-mitigated') || '') || CF_PAGE.test(body));
        const code = cf ? 'cloudflare-blocked' : (r && !r.ok) ? mgHttpCode(r.status) : /failed to fetch|networkerror|load failed/i.test(String(e && e.message)) ? 'cors-blocked' : 'fetch-error';
        if (DATA.tab != null) {
          try { const a = await tabMsg({ type: 'fetch-text', url }); if (a && a.ok) { dbgc('manga', code, `${hostOf(url)}: ${String(e.message).slice(0, 80)}, rescued=tab`); return a.text; } } catch (e2) { /* keep first error */ }
        }
        dbgc('manga', code, `${hostOf(url)}: ${String(e.message).slice(0, 80)}, rescued=no`);
        throw e;
      }
    },
    async json(url) { return JSON.parse(await ctx.text(url)); },
    async tabEval(func) {
      if (DATA.tab == null) { dbgc('manga', 'source-tab-closed', `${hostOf(DATA.ref)}: no source tab (tabEval)`); throw new Error('source tab closed'); }
      let r;
      try { [r] = await chrome.scripting.executeScript({ target: { tabId: DATA.tab }, world: 'MAIN', func }); }
      catch (e) { if (tabGone(e)) dbgc('manga', 'source-tab-closed', `${hostOf(DATA.ref)}: tabEval`); throw e; }
      return r && r.result;
    },
    log: (m) => dbg('site', m)
  };
  return ctx;
}
// adapter -> generic scan of the page's scripts -> the real page (scrolled) -> what the page already found. First one that gives pages wins.
async function resolveTask() {
  const url = DATA.task.url || DATA.ref, ad = MG_SITES.byName(DATA.task.name), errs = [];
  const ctx = siteCtx();
  // S89.1: a fallback delivered the list after something before it failed -> code fallback-used (info, not an error)
  const win = (r) => { if (errs.length) dbgc('manga', 'fallback-used', `${hostOf(url)}: ${r.how} after ${errs.join(' | ').slice(0, 200)}`); return r; };
  if (ad) {
    for (let t = 0; t < 2; t++) { // 2nd try: the site's link keys may have rotated while we were starting
      try {
        const r = await ad.resolve(url, ctx);
        if (r.images.length) return { ...r, how: ad.name };
        dbgc('manga', 'adapter-failed', `${ad.name} try ${t + 1}: returned 0 pages`);
      } catch (e) { errs.push(ad.name + ': ' + e.message); dbgc('manga', 'adapter-failed', `${ad.name} try ${t + 1}: ${String(e.message).slice(0, 120)}`); if (cancelled) break; await new Promise((x) => setTimeout(x, 600)); }
    }
  }
  try {
    const imgs = MG_SITES.genericPages(await ctx.text(url), url);
    if (imgs.length) return win({ images: imgs, how: 'page scripts' });
    errs.push('page scripts: no page list');
  } catch (e) { errs.push('page scripts: ' + e.message); }
  if (DATA.tab != null) {
    try {
      $('#st').textContent = 'Scrolling the page to load every image...';
      const a = await tabMsg({ type: 'mg-collect' });
      if (a && a.ok && a.images.length) return win({ images: a.images, how: 'page images' });
      errs.push('page images: none');
    } catch (e) { errs.push('page images: ' + e.message); }
  }
  if (DATA.images && DATA.images.length) return win({ images: DATA.images, how: 'collected' });
  throw new Error(errs.join(' | ') || 'nothing found');
}
// links can expire (hitomi rotates its path, MangaDex servers change): ask the adapter again and swap in the fresh links for failed pages
async function refreshLinks() {
  if (!DATA.task) return false;
  if (DATA.refreshed >= 2) { dbgc('manga', 'refresh-failed', `${hostOf(DATA.ref)}: limit reached, links were already refreshed 2 times`); return false; }
  DATA.refreshed = (DATA.refreshed || 0) + 1;
  try {
    const ad = MG_SITES.byName(DATA.task.name); if (!ad) { dbgc('manga', 'refresh-failed', `${hostOf(DATA.ref)}: site rule ${String(DATA.task.name).slice(0, 40)} not found`); return false; }
    const r = await ad.resolve(DATA.task.url || DATA.ref, siteCtx());
    if (r.images.length !== pages.length) { dbgc('manga', 'refresh-failed', `${hostOf(DATA.ref)}: the list changed, now ${r.images.length} pages, had ${pages.length}`); return false; }
    goodTag = '';
    pages.forEach((p, i) => { if (p.bad) { p.alts = r.images[i].alts; p.found = r.images[i].found; p.orig = r.images[i].orig; } });
    dbg('site', `${ad.name}: refreshed links for ${pages.filter((p) => p.bad).length} pages`);
    return true;
  } catch (e) { dbg('site', 'refresh failed: ' + e.message); dbgc('manga', 'refresh-failed', `${hostOf(DATA.ref)}: ${cleanErr(e.message)}`); return false; }
}

// ---------- UI ----------
const selected = () => pages.filter((p) => p.sel && p.blob);
function updateButtons() {
  const n = selected().length;
  ['cbz', 'zip', 'pdf', 'epub', 'read'].forEach((id) => ($('#' + id).disabled = !n));
  $('#st').textContent = `${pages.filter((p) => p.blob).length}/${pages.length} loaded - ${n} selected - ${fmtSize(selected().reduce((a, p) => a + p.blob.size, 0))}`;
  $('#selc').textContent = `${n} selected`; $('#sbcbz').disabled = $('#sbread').disabled = !n;
}
function applyMinWidth() {
  const mw = parseInt($('#minw').value, 10) || 0;
  pages.forEach((p) => { if (p.w) p.sel = p.w >= mw && !p.dup; p.el.classList.toggle('sel', !!p.sel); });
  updateButtons();
}
function mount() {
  const g = $('#grid'); g.textContent = '';
  pages.forEach((p, i) => {
    p.el = document.createElement('div'); p.el.className = 'pg sel';
    p.el.innerHTML = `<span class="n">${i + 1}</span>`;
    p.el.onclick = () => { if (!p.blob) return; p.sel = !p.sel; p.el.classList.toggle('sel', p.sel); updateButtons(); };
    g.append(p.el);
  });
}
function renumber() { pages.forEach((p, i) => { p.el.querySelector('.n').textContent = i + 1; $('#grid').append(p.el); }); }

const seenHash = new Map();
let throttle = 0; // grows after HTTP 429/503
async function loadAll() {
  let done = 0, next = 0;
  const bar = $('#bar'); bar.max = pages.length;
  await Promise.all(Array.from({ length: Math.min(SET.concurrency || 6, pages.length) }, async () => {
    while (true) {
      while (RL.cap !== Infinity && rlActive >= RL.cap) await sleep(120); // the server asked us to slow down
      const i = next++;
      if (i >= pages.length) break;
      rlActive++;
      const p = pages[i];
      const gap = Math.max(SET.requestDelayMs || 0, throttle);
      if (gap) await new Promise((r) => setTimeout(r, gap));
      try {
        const r = await fetchImg(p);
        Object.assign(p, r);
        const bmp = await bitmapOf(r.blob); p.w = bmp.width; p.h = bmp.height; bmp.close();
        p.url2 = URL.createObjectURL(r.blob);
        const hsh = crc32(new Uint8Array(await r.blob.arrayBuffer())) + ':' + r.blob.size;
        if (seenHash.has(hsh)) { p.dup = true; p.sel = false; p.el.classList.remove('sel'); } else seenHash.set(hsh, i);
        p.el.insertAdjacentHTML('beforeend', `<img src="${p.url2}" loading="lazy"><span class="m">${p.w}x${p.h} - ${r.ext} - ${fmtSize(r.blob.size)}${p.dup ? ' - duplicate (unselected)' : ''}</span>`);
      } catch (e) { if (/429|503/.test(String(e.message))) throttle = Math.min(3000, (throttle || 500) * 2); p.bad = true; p.err = e.message; p.sel = false; p.el.classList.remove('sel'); p.el.classList.add('bad'); p.el.insertAdjacentHTML('beforeend', '<span class="m" title="' + String(e.message).replace(/"/g, "'") + '">failed</span>'); }
      rlActive--;
      bar.value = ++done; updateButtons();
    }
  }));
  const firstBad = pages.filter((p) => p.bad); // S89.2: what failed the first time (the screen status line is the only other place that shows it)
  if (firstBad.length) {
    const cnt = {}; firstBad.forEach((p) => { const c = imgCode(p.err); cnt[c] = (cnt[c] || 0) + 1; });
    dbgc('manga', 'first-pass-failed', `${hostOf(DATA.ref)}: ${firstBad.length} of ${pages.length} pages failed the first time (${Object.entries(cnt).map(([c, n]) => c + ' x' + n).join(', ').slice(0, 160)})`);
  }
  let rounds = 0;
  for (let pass = 0; pass < 2; pass++) { // pages that failed because of rate limits / network hiccups: try again, slowly
    const badNow = pages.filter((p) => p.bad);
    if (!badNow.length || !badNow.some((p) => /429|503|timeout|network|failed to fetch|HTTP 5|HTTP 403/i.test(p.err || ''))) break;
    rounds++;
    $('#st').textContent = `Retrying ${badNow.length} page(s) slowly (round ${pass + 1} of 2)...`;
    await sleep(2500 * (pass + 1));
    RL.until = 0; RL.cap = 2; RL.ok = 0;
    let k = 0;
    await Promise.all(Array.from({ length: 2 }, async () => { while (k < badNow.length) { const p = badNow[k++]; await sleep(250); await loadOne(p); } }));
    RL.cap = Infinity;
  }
  applyMinWidth();
  orderHint();
  { // debug log: which fallback delivered the pages, why the rest failed
    const how = {}; pages.forEach((p) => { if (p.how) how[p.how] = (how[p.how] || 0) + 1; });
    const badN = pages.filter((p) => p.bad);
    dbg('manga', `${hostOf(DATA.ref)}: ${pages.length} pages, ok=${JSON.stringify(how)}, failed=${badN.length}` + (badN.length ? ' e.g. ' + String(badN[0].err).slice(0, 160) : ''));
  }
  { // S89.2: what is left after the automatic retries, and the duplicates that were unselected on their own
    const left = pages.filter((p) => p.bad);
    if (firstBad.length) dbgc('manga', 'retry-result', `${hostOf(DATA.ref)}: auto retry rounds=${rounds} recovered=${firstBad.length - left.length} still-missing=${left.length}`);
    if (left.length) { dbgc('manga', 'still-missing', `${hostOf(DATA.ref)}: ${left.length} of ${pages.length} pages not loaded after the retries`); logImgFails('after retries', left); }
    const dups = pages.filter((p) => p.dup).length;
    if (dups) dbgc('manga', 'duplicates-unselected', `${hostOf(DATA.ref)}: ${dups} duplicate page(s) were unselected`);
  }
  if (DATA.expected && pages.length < DATA.expected) { dbgc('manga', 'pages-fewer-than-expected', `${hostOf(DATA.ref)}: found=${pages.length} expected=${DATA.expected}`); $('#st').textContent += ` - NOTE: the page says ${DATA.expected} pages but only ${pages.length} were found`; }
  const small = pages.filter((p) => p.w && p.w < 400).length;
  if (small > pages.length / 2) { dbgc('manga', 'thumbnails-only', `${hostOf(DATA.ref)}: ${small} of ${pages.length} pages are under 400px wide`); $('#st').textContent += ` - WARNING: ${small} pages are under 400px wide (thumbnails?)`; }
  const bad = pages.filter((p) => p.bad);
  if (bad.length) {
    const why = {};
    bad.forEach((p) => { const k = (p.err || '?').split(' | ')[0]; why[k] = (why[k] || 0) + 1; });
    $('#st').textContent += ` - ${bad.length} failed: ` + Object.entries(why).map(([k, v]) => `${v}x ${k}`).join(', ');
    const hint = diagnose(Object.keys(why).join(' ')); if (hint) $('#st').textContent += ' - ' + hint;
    $('#shot').hidden = false; $('#retry').hidden = false;
  }
}

async function loadOne(p) {
  try {
    const r = await fetchImg(p);
    p.bad = false; p.err = '';
    Object.assign(p, r);
    const bmp = await bitmapOf(r.blob); p.w = bmp.width; p.h = bmp.height; bmp.close();
    p.url2 = URL.createObjectURL(r.blob);
    p.el.querySelectorAll('.m, img').forEach((x) => x.remove());
    p.el.classList.remove('bad'); p.el.classList.add('sel'); p.sel = true;
    p.el.insertAdjacentHTML('beforeend', `<img src="${p.url2}"><span class="m">${p.w}x${p.h} - ${r.ext} - ${fmtSize(r.blob.size)}</span>`);
    return true;
  } catch (e) { p.err = e.message; return false; }
}
async function retryFailed() {
  const todo = pages.filter((p) => p.bad);
  if (!todo.length) return 0;
  if (DATA.task) { $('#st').textContent += ' - refreshing links...'; await refreshLinks(); }
  let ok = 0, i = 0;
  await Promise.all(Array.from({ length: 3 }, async () => { while (i < todo.length) { if (await loadOne(todo[i++])) ok++; } }));
  const left = pages.filter((p) => p.bad).length;
  $('#retry').hidden = !left; if (!left) $('#shot').hidden = true;
  updateButtons();
  $('#st').textContent += ` - retry: ${ok} recovered, ${left} still missing`;
  dbgc('manga', 'retry-result', `${hostOf(DATA.ref)}: manual retry recovered=${ok} still-missing=${left}`);
  if (left) { dbgc('manga', 'still-missing', `${hostOf(DATA.ref)}: ${left} of ${pages.length} pages not loaded after the manual retry`); logImgFails('after manual retry', pages.filter((p) => p.bad)); }
  return ok;
}
$('#retry').onclick = async () => { $('#retry').disabled = true; await retryFailed(); $('#retry').disabled = false; };

// ---------- saving ----------
function names(sel) {
  const w = String(sel.length).length < 3 ? 3 : String(sel.length).length;
  return sel.map((p, i) => ({ name: String(i + 1).padStart(w, '0') + '.' + p.ext, blob: p.blob }));
}
const LANG_ISO = { english: 'en', japanese: 'ja', chinese: 'zh', korean: 'ko', spanish: 'es', french: 'fr', german: 'de', russian: 'ru', italian: 'it', portuguese: 'pt', thai: 'th', indonesian: 'id', vietnamese: 'vi', turkish: 'tr', arabic: 'ar' };
function comicInfo(title, sel) {
  const esc = (x) => String(x == null ? '' : x).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  const m = DATA.meta || {}, list = (a) => (a && a.length ? esc(a.join(', ')) : '');
  const num = (DATA.chapter || '').match(/(\d+(?:\.\d+)?)(?!.*\d)/);
  const tag = (n, v) => (v ? `<${n}>${v}</${n}>` : '');
  const lang = (m.languages || []).map((l) => LANG_ISO[String(l).toLowerCase()]).find(Boolean);
  const people = Array.from(new Set([...(m.authors || []), ...(m.artists || [])]));
  return `<?xml version="1.0" encoding="utf-8"?>\n<ComicInfo>` +
    tag('Title', esc(title)) + tag('Series', esc(DATA.book || (m.parodies && m.parodies[0]) || '')) + (num ? `<Number>${num[1]}</Number>` : '') +
    tag('Summary', esc(m.summary || '')) + tag('Writer', list(people)) + tag('Penciller', list(m.artists)) + tag('Publisher', list(m.groups)) +
    tag('Genre', list((m.tags || []).slice(0, 12))) + tag('Tags', list(m.tags)) + tag('Characters', list(m.characters)) + tag('LanguageISO', lang || '') +
    `<PageCount>${sel.length}</PageCount><Web>${esc(DATA.ref || '')}</Web><Manga>${$('#rtl').checked ? 'YesAndRightToLeft' : 'Unknown'}</Manga>` +
    `<Pages><Page Image="0" Type="FrontCover"/></Pages></ComicInfo>`;
}

// ---------- post-processing: grayscale, auto-crop margins, split tall webtoon strips ----------
const toBlob = (cv, q = 0.92) => new Promise((r) => cv.toBlob(r, 'image/jpeg', q));
function cropBox(cv) {
  const w = cv.width, h = cv.height;
  if (h > 7000 || w < 100) return null; // too big to scan in memory
  const d = cv.getContext('2d').getImageData(0, 0, w, h).data;
  const px = (x, y) => { const i = (y * w + x) * 4; return [d[i], d[i + 1], d[i + 2]]; };
  const bg = px(0, 0), c2 = px(w - 1, h - 1), sum = (p) => p[0] + p[1] + p[2];
  if (!(sum(bg) > 690 || sum(bg) < 70) || Math.abs(sum(bg) - sum(c2)) > 60) return null; // only plain white / black margins
  const diff = (x, y) => { const p = px(x, y); return Math.abs(p[0] - bg[0]) + Math.abs(p[1] - bg[1]) + Math.abs(p[2] - bg[2]) > 60; };
  const sx = Math.max(1, w >> 8), sy = Math.max(1, h >> 8);
  const rowHas = (y) => { for (let x = 0; x < w; x += sx) if (diff(x, y)) return true; return false; };
  const colHas = (x) => { for (let y = 0; y < h; y += sy) if (diff(x, y)) return true; return false; };
  let t = 0, b = h - 1, l = 0, r = w - 1;
  while (t < b && !rowHas(t)) t++;
  while (b > t && !rowHas(b)) b--;
  while (l < r && !colHas(l)) l++;
  while (r > l && !colHas(r)) r--;
  const pad = 6; t = Math.max(0, t - pad); l = Math.max(0, l - pad); b = Math.min(h - 1, b + pad); r = Math.min(w - 1, r + pad);
  const area = (r - l + 1) * (b - t + 1) / (w * h);
  return area > 0.97 || area < 0.3 ? null : { x: l, y: t, w: r - l + 1, h: b - t + 1 };
}
function blankRow(ctx, y, w) { // true when the whole pixel row is (almost) one colour: a safe place to cut a strip
  const d = ctx.getImageData(0, y, w, 1).data;
  for (let i = 4; i < d.length; i += 4 * Math.max(1, w >> 9)) if (Math.abs(d[i] - d[0]) + Math.abs(d[i + 1] - d[1]) + Math.abs(d[i + 2] - d[2]) > 24) return false;
  return true;
}
async function postProcess(sel, mode) {
  const gray = mode === 'gray' || mode === 'cropgray', crop = mode === 'crop' || mode === 'cropgray', split = mode === 'split';
  const out = [];
  for (let i = 0; i < sel.length; i++) {
    const p = sel[i];
    $('#st').textContent = `Processing ${i + 1}/${sel.length}...`;
    if (p.ext === 'gif') { out.push(p); continue; }
    try {
      const bmp = await createImageBitmap(p.blob);
      let cv = document.createElement('canvas'); cv.width = bmp.width; cv.height = bmp.height;
      let ctx = cv.getContext('2d', { willReadFrequently: true });
      ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height);
      if (gray) ctx.filter = 'grayscale(1)';
      ctx.drawImage(bmp, 0, 0); bmp.close(); ctx.filter = 'none';
      if (crop) {
        const bx = cropBox(cv);
        if (bx) { const c2 = document.createElement('canvas'); c2.width = bx.w; c2.height = bx.h; c2.getContext('2d').drawImage(cv, bx.x, bx.y, bx.w, bx.h, 0, 0, bx.w, bx.h); cv = c2; ctx = cv.getContext('2d', { willReadFrequently: true }); }
      }
      const W = cv.width, H = cv.height;
      if (split && H > W * 2.5) {
        const chunk = Math.round(W * 1.5);
        let y = 0;
        while (y < H) {
          let end = Math.min(H, y + chunk);
          if (H - end < chunk * 0.3) end = H; // no tiny last piece
          else { for (let dlt = 0; dlt < chunk * 0.25; dlt += 2) { if (blankRow(ctx, Math.min(H - 1, end - dlt), W)) { end -= dlt; break; } if (end + dlt < H && blankRow(ctx, end + dlt, W)) { end += dlt; break; } } }
          const c3 = document.createElement('canvas'); c3.width = W; c3.height = end - y; c3.getContext('2d').drawImage(cv, 0, y, W, end - y, 0, 0, W, end - y);
          out.push({ ...p, blob: await toBlob(c3), ext: 'jpg', w: W, h: end - y });
          y = end;
        }
        continue;
      }
      if (!gray && !crop) { out.push(p); continue; }
      out.push({ ...p, blob: await toBlob(cv), ext: 'jpg', w: W, h: H });
    } catch (e) { out.push(p); }
  }
  return out;
}

async function save(kind) {
  let sel = selected();
  if (!sel.length) { $('#st').textContent += ' - nothing to save'; return false; }
  const title = $('#ttl').value.trim() || 'pages';
  if ($('#post').value !== 'none') sel = await postProcess(sel, $('#post').value);
  let files = names(sel);
  const cvt = $('#conv').value;
  if ((kind === 'cbz' || kind === 'zip') && cvt !== 'orig') files = await convertFiles(files, cvt);
  if (kind === 'cbz') files.push({ name: 'ComicInfo.xml', blob: new Blob([comicInfo(title, sel)], { type: 'text/xml' }) });
  $('#st').textContent = 'Building ' + kind.toUpperCase() + '...';
  const blob = kind === 'pdf' ? await makePdf(sel) : kind === 'epub' ? await makeEpub(sel, title) : await makeZip(files);
  const SETF = DATA.book ? { ...SET, subfolder: [SET.subfolder, DATA.book].filter(Boolean).join('/') } : SET;
  const filename = buildFileName(SETF, { title, site: hostOf(DATA.ref), name: title, kind: 'pages', res: '', lang: '', book: DATA.book || '', chapter: DATA.chapter || '', index: DATA.index || '' }, kind);
  const url = URL.createObjectURL(blob);
  const did = await chrome.downloads.download({ url, filename, conflictAction: 'uniquify' });
  addHistory({ id: did, title: filename.split('/').pop(), kind, site: hostOf(DATA.ref), src: DATA.ref, pages: sel.length, size: blob.size });
  if (DATA.autoClose) await waitDownload(did);
  $('#st').textContent = `Saved ${filename} (${fmtSize(blob.size)}, ${sel.length} pages)`;
  setTimeout(() => URL.revokeObjectURL(url), 60000);
  return true;
}

async function waitDownload(id) {
  for (let i = 0; i < 240; i++) {
    const [d] = await chrome.downloads.search({ id });
    if (!d || d.state !== 'in_progress') return;
    await new Promise((r) => setTimeout(r, 500));
  }
}

// ---------- re-encode (smaller CBZ/ZIP) + page order helpers ----------
async function convertFiles(files, fmt) {
  const type = fmt === 'webp' ? 'image/webp' : 'image/jpeg', ext = fmt === 'webp' ? 'webp' : 'jpg';
  const out = []; let saved = 0;
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    if (/\.gif$/i.test(f.name)) { out.push(f); continue; } // keep animations
    $('#st').textContent = `Converting ${i + 1}/${files.length}...`;
    try {
      const bmp = await createImageBitmap(f.blob);
      const cv = document.createElement('canvas'); cv.width = bmp.width; cv.height = bmp.height;
      const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height); ctx.drawImage(bmp, 0, 0); bmp.close();
      const b = await new Promise((r) => cv.toBlob(r, type, 0.85));
      if (b && b.size < f.blob.size) { out.push({ name: f.name.replace(/\.\w+$/, '.' + ext), blob: b }); saved += f.blob.size - b.size; } else out.push(f);
    } catch (e) { out.push(f); }
  }
  $('#st').textContent = `Converted - saved ${fmtSize(saved)}`;
  return out;
}
const fileNum = (p) => { const n = (String(p.orig || p.found || '').split(/[?#]/)[0].split('/').pop() || '').replace(/\.\w+$/, '').match(/(\d+)(?!.*\d)/); return n ? parseInt(n[1], 10) : null; };
function sortByName() {
  if (pages.some((p) => fileNum(p) === null)) { $('#st').textContent += ' - some file names have no number, cannot sort'; return; }
  pages.sort((a, b) => fileNum(a) - fileNum(b));
  renumber(); updateButtons();
}
function orderHint() {
  const ns = pages.map(fileNum);
  if (ns.length < 3 || ns.some((n) => n === null)) return;
  let up = 0, down = 0;
  for (let i = 1; i < ns.length; i++) { if (ns[i] > ns[i - 1]) up++; else if (ns[i] < ns[i - 1]) down++; }
  if (down > 0 && up > 0) $('#st').textContent += ' - NOTE: page order differs from file-name numbers, try "Sort by name"';
}
$('#sortn').onclick = sortByName;
$('#conv').onchange = () => { try { localStorage.setItem('mg-conv', $('#conv').value); } catch (e) { /* ignore */ } };

// ---------- reader (zoom, brightness, two-page spread, bookmark) ----------
let ri = 0;
const bmKey = () => 'mg-bm:' + (DATA && (DATA.ref || DATA.title) || '');
function bmSave() {
  try {
    const mode = $('#mode').value, rc = $('#rc');
    const v = mode === 'v' ? { sy: rc.scrollHeight > rc.clientHeight ? rc.scrollTop / (rc.scrollHeight - rc.clientHeight) : 0 } : { i: ri };
    localStorage.setItem(bmKey(), JSON.stringify({ ...v, mode, t: Date.now() }));
  } catch (e) { /* ignore */ }
}
function bmLoad() { try { return JSON.parse(localStorage.getItem(bmKey())); } catch (e) { return null; } }
function applyView() {
  const rc = $('#rc');
  rc.style.setProperty('--z', ($('#zoom').value / 100).toFixed(2));
  rc.style.setProperty('--br', ($('#bright').value / 100).toFixed(2));
  $('#zv').textContent = $('#zoom').value + '%';
  try { localStorage.setItem('mg-view', JSON.stringify({ z: $('#zoom').value, b: $('#bright').value })); } catch (e) { /* ignore */ }
}
function drawReader() {
  const rc = $('#rc'), sel = selected(), mode = $('#mode').value, rtl = $('#rtl').checked;
  if (!sel.length) return;
  rc.className = $('#fit').value + (mode === 'v' ? '' : ' paged') + (mode === 'd' ? ' dbl' : '');
  rc.textContent = '';
  if (mode === 'v') {
    sel.forEach((p) => { const i = new Image(); i.src = p.url2; rc.append(i); });
    $('#pn').textContent = sel.length + ' pages';
    rc.onclick = null;
  } else {
    const per = mode === 'd' ? 2 : 1;
    ri = Math.max(0, Math.min(sel.length - 1, ri));
    if (per === 2) ri -= ri % 2; // spreads start on odd pages (1-2, 3-4 ...)
    const group = sel.slice(ri, ri + per);
    if (per === 2 && rtl) group.reverse();
    group.forEach((p) => { const i = new Image(); i.src = p.url2; rc.append(i); });
    $('#pn').textContent = per === 2 && group.length > 1 ? `${ri + 1}-${ri + 2} / ${sel.length}` : `${ri + 1} / ${sel.length}`;
    rc.onclick = (e) => step(((e.clientX < innerWidth / 2) === rtl ? 1 : -1) * per);
    rc.scrollTop = 0;
    bmSave();
  }
  applyView();
  try { localStorage.setItem('mg-reader', JSON.stringify({ mode, rtl, fit: $('#fit').value })); } catch (e) { /* ignore */ }
}
function step(d) { ri += d; drawReader(); }
function openReader() {
  $('#rt').textContent = $('#ttl').value; $('#rd').classList.add('on'); ri = 0;
  const bm = bmLoad();
  if (bm && ($('#mode').value === bm.mode || bm.mode === 'v' || $('#mode').value === 'v')) {
    if ($('#mode').value !== 'v' && bm.i != null) ri = bm.i;
  }
  drawReader();
  if (bm && $('#mode').value === 'v' && bm.sy) { const rc = $('#rc'); setTimeout(() => { rc.scrollTop = bm.sy * (rc.scrollHeight - rc.clientHeight); }, 400); }
  if (bm) { $('#pn').textContent += ' - resumed'; }
}
function closeReader() { if ($('#mode').value === 'v') bmSave(); $('#rd').classList.remove('on'); }
['mode', 'fit', 'rtl'].forEach((id) => ($('#' + id).onchange = drawReader));
['zoom', 'bright'].forEach((id) => ($('#' + id).oninput = applyView));
$('#bm').onclick = () => { bmSave(); $('#pn').textContent += ' - bookmarked'; };
$('#rx').onclick = closeReader;
document.addEventListener('keydown', (e) => {
  if (!$('#rd').classList.contains('on')) return;
  if (e.key === 'Escape') closeReader();
  const rtl = $('#rtl').checked, mode = $('#mode').value, per = mode === 'd' ? 2 : 1;
  if (mode !== 'v') {
    if (e.key === 'ArrowRight' || e.key === ' ') step((rtl ? -1 : 1) * per);
    if (e.key === 'ArrowLeft') step((rtl ? 1 : -1) * per);
  }
  if (e.key === '+' || e.key === '=') { $('#zoom').value = Math.min(300, +$('#zoom').value + 10); applyView(); }
  if (e.key === '-') { $('#zoom').value = Math.max(30, +$('#zoom').value - 10); applyView(); }
});
(function restoreView() { try { const v = JSON.parse(localStorage.getItem('mg-view')); if (v) { $('#zoom').value = v.z; $('#bright').value = v.b; } } catch (e) { /* ignore */ } })();

// ---------- last resort: screenshot the images from the real page ----------
async function shootPage(p) {
  const tab = await chrome.tabs.get(DATA.tab);
  let cv = null, ctx = null, offset = 0, H = 0, W = 0, dpr = 1;
  for (let guard = 0; guard < 60; guard++) {
    const r = await tabMsg({ type: 'mg-pos', url: p.found, offset });
    if (!r || !r.ok) throw new Error((r && r.err) || 'page did not answer');
    if (!cv) { dpr = r.dpr; W = r.width; H = r.height; cv = document.createElement('canvas'); cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); ctx = cv.getContext('2d'); }
    const vTop = Math.max(r.top, 0), vBot = Math.min(r.top + r.height, r.vh);
    if (vBot - vTop < 2) throw new Error('image not visible');
    await new Promise((res) => setTimeout(res, 550)); // captureVisibleTab is limited to 2 calls/second
    const png = await chrome.tabs.captureVisibleTab(tab.windowId, { format: 'png' });
    const bmp = await createImageBitmap(await (await fetch(png)).blob());
    const sx = Math.max(0, r.left) * dpr, sy = vTop * dpr, sw = Math.min(r.width, r.vw || r.width) * dpr, sh = (vBot - vTop) * dpr;
    ctx.drawImage(bmp, sx, sy, Math.min(sw, bmp.width - sx), Math.min(sh, bmp.height - sy), (r.left < 0 ? -r.left * dpr : 0), (vTop - r.top) * dpr, Math.min(sw, bmp.width - sx), Math.min(sh, bmp.height - sy));
    bmp.close();
    offset = vBot - r.top;
    if (offset >= H - 1) break;
  }
  const blob = await new Promise((res) => cv.toBlob(res, 'image/png'));
  return { blob, ext: 'png', how: 'screenshot' };
}
async function shootAll() {
  const todo = pages.filter((p) => p.bad);
  if (!todo.length) return;
  $('#shot').disabled = true;
  await chrome.tabs.update(DATA.tab, { active: true });
  const me = await chrome.tabs.getCurrent();
  let ok = 0, shotErr = '';
  for (const p of todo) {
    $('#st').textContent = `Screenshot ${ok + 1}/${todo.length} - keep the manga tab visible, do not scroll it...`;
    try {
      Object.assign(p, await shootPage(p));
      const bmp = await createImageBitmap(p.blob); p.w = bmp.width; p.h = bmp.height; bmp.close();
      p.url2 = URL.createObjectURL(p.blob); p.bad = false; p.sel = true; p.el.classList.remove('bad'); p.el.classList.add('sel');
      p.el.querySelector('.m').remove();
      p.el.insertAdjacentHTML('beforeend', `<img src="${p.url2}"><span class="m">${p.w}x${p.h} - screenshot</span>`);
      ok++;
    } catch (e) { p.err = e.message; shotErr = shotErr || `page ${pages.indexOf(p) + 1}: ${cleanErr(e.message)}`; }
  }
  if (ok < todo.length) dbgc('manga', 'screenshot-failed', `${hostOf(DATA.ref)}: saved ${ok} of ${todo.length}, e.g. ${shotErr}`);
  tabMsg({ type: 'mg-restore' }).catch(() => {});
  await chrome.tabs.update(me.id, { active: true });
  $('#shot').disabled = false;
  updateButtons();
  $('#st').textContent += ` - screenshot saved ${ok}/${todo.length} (screen quality, not original)`;
}
$('#shot').onclick = shootAll;

$('#minw').onchange = applyMinWidth;
$('#all').onclick = () => { pages.forEach((p) => { if (p.blob) { p.sel = true; p.el.classList.add('sel'); } }); updateButtons(); };
$('#none').onclick = () => { pages.forEach((p) => { p.sel = false; p.el.classList.remove('sel'); }); updateButtons(); };
$('#rev').onclick = () => { pages.reverse(); renumber(); updateButtons(); };
$('#cbz').onclick = () => save('cbz');
$('#zip').onclick = () => save('zip');
$('#pdf').onclick = () => save('pdf');
$('#epub').onclick = () => save('epub');
$('#read').onclick = openReader;

(async function main() {
  SET = await getSettings();
  try { const cv = localStorage.getItem('mg-conv'); if (cv) $('#conv').value = cv; } catch (e) { /* ignore */ }
  try { const s = JSON.parse(localStorage.getItem('mg-reader')); if (s) { $('#mode').value = s.mode; $('#fit').value = s.fit; $('#rtl').checked = s.rtl; } } catch (e) { /* ignore */ }
  const key = 'manga_' + Q.get('id');
  DATA = (await chrome.storage.session.get(key))[key];
  if (!DATA) { $('#st').textContent = 'Nothing to show. Open this from the page panel.'; return; }
  $('#ttl').value = sanitize(DATA.chapter || DATA.title || 'pages', 120);
  document.title = DATA.title || 'Pages';
  await setupHeaders(DATA.ref);
  let list = DATA.images || [];
  if (DATA.task) {
    $('#st').textContent = 'Finding the page links...';
    try {
      const r = await resolveTask();
      list = r.images;
      if (r.ref) { DATA.ref = r.ref; await setupHeaders(r.ref); } // some CDNs only answer with their own site as Referer
      if (r.title && !DATA.chapter) { $('#ttl').value = sanitize(r.title, 120); document.title = r.title; }
      if (r.meta && !((DATA.meta && DATA.meta.tags) || []).length) DATA.meta = r.meta;
      if (r.book && !DATA.book) DATA.book = r.book;
      dbg('manga', `${hostOf(DATA.ref)}: page list from ${r.how} (${list.length} pages)`);
    } catch (e) {
      dbg('manga', `${hostOf(DATA.ref)}: no page list - ${e.message}`);
      dbgc('manga', 'no-page-list', `${hostOf(DATA.ref)}: ${String(e.message).slice(0, 200)}${list.length ? ' (using ' + list.length + ' pages the panel already had)' : ''}`);
      if (!list.length) { $('#st').textContent = 'No pages found (' + e.message.slice(0, 200) + '). Open the reader / chapter page itself, wait until the first page shows, then try again. Settings > Copy full report has details.'; return; }
    }
  }
  if (!list.length) { dbgc('manga', 'no-page-list', `${hostOf(DATA.ref)}: the list is empty`); $('#st').textContent = 'No page images found.'; return; }
  pages = list.map((im) => ({ ...im, sel: true }));
  mount();
  await loadAll();
  const auto = DATA.auto;
  if (auto === 'cbz' || auto === 'zip' || auto === 'pdf' || auto === 'epub') {
    if (pages.some((p) => p.bad)) await retryFailed(); // one automatic repair pass before saving
    let okSave = false;
    try { okSave = !!(await save(auto)); } catch (e) { $('#st').textContent = 'Save failed: ' + e.message; }
    if (DATA.autoClose) { chrome.runtime.sendMessage({ type: 'manga-done', id: Q.get('id'), ok: okSave, missing: pages.filter((p) => p.bad).length }); setTimeout(() => window.close(), 800); }
  }
  else if (auto === 'read') openReader();
})();
