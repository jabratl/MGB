// Readability-style content finder + strict XHTML serializer for EPUB
const SKIP = new Set('script style noscript iframe form button input select textarea nav aside footer svg canvas video audio object embed template link meta head title dialog menu'.split(' '));
const ALLOWED = new Set('p div span h1 h2 h3 h4 h5 h6 ul ol li blockquote pre code em i strong b u s sub sup a table thead tbody tfoot tr td th caption figure figcaption dl dt dd ruby rt rp small mark del ins q cite abbr'.split(' '));
const BAD_XML = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g;
const POS = /(content|article|chapter|entry|post-body|story|reader|novel|main|text|read)/i;
const NEG = /(comment|sidebar|footer|nav|menu|header|widget|share|related|meta|social|advert|sponsor|book_?info|bookinfo|intro|about|synops|blurb|summar)/i;
const SKIPKEY = /desc|synops|about|intro|summar|blurb|abstract|tagline|keyword|comment|review|related|recommend|seo|meta/i;   // JSON keys that hold the BOOK blurb, not chapter text
const JUNK = /(?:^|[\s_-])(?:comments?|disqus|share|sharing|social|sponsor\w*|advert\w*|ads?|adsbygoogle|banner|popup|modal|newsletter|related|recommend\w*|sidebar|breadcrumbs?|pagination|pager|toolbar|cookie|subscribe|widget|navigation|navbox|chapter-nav|nav-links|post-footer|post-share)(?:$|[\s_-])/i;
const LAZY = ['data-src', 'data-original', 'data-lazy-src', 'data-lazy', 'data-url', 'src'];

export const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
export const escA = s => esc(s).replace(/"/g, '&quot;');
const txt = el => el.textContent || '';
function linkDensity(el) {
  const t = txt(el).length || 1; let l = 0;
  el.querySelectorAll('a').forEach(a => l += txt(a).length);
  return l / t;
}
function ownText(el) { let s = ''; for (const n of el.childNodes) if (n.nodeType === 3) s += n.nodeValue; return s.trim(); }

export function findMain(doc) {
  const body = doc.body || doc.documentElement, sc = new Map();
  const init = el => {
    let s = 0; const cls = (el.getAttribute('class') || '') + ' ' + (el.id || ''), t = el.tagName;
    if (POS.test(cls)) s += 10; if (NEG.test(cls)) s -= 15;
    if (t === 'ARTICLE') s += 8; if (t === 'MAIN') s += 5;
    if (/^(UL|OL|NAV|ASIDE|FOOTER|HEADER|FORM)$/.test(t)) s -= 20;
    return s;
  };
  const add = (el, v) => { if (!el) return; if (!sc.has(el)) sc.set(el, init(el)); sc.set(el, sc.get(el) + v); };
  for (const b of body.querySelectorAll('p,pre,blockquote,td,div')) {
    const own = /^(P|PRE|BLOCKQUOTE)$/.test(b.tagName) ? txt(b).trim() : ownText(b);
    if (own.length < 25) continue;
    const pts = 1 + own.split(/[,，、،.。]/).length + Math.min(own.length / 100, 40);
    if (b.tagName === 'DIV' || b.tagName === 'TD') add(b, pts);
    add(b.parentElement, pts); add(b.parentElement && b.parentElement.parentElement, pts / 2);
  }
  let best = null, bs = 0;
  for (const [el, s] of [...sc].sort((a, b) => b[1] - a[1]).slice(0, 12)) {
    const v = s * (1 - Math.min(linkDensity(el), 0.9));
    if (v > bs) { bs = v; best = el; }
  }
  return best || body;
}

export function findNextInfo(doc, pageUrl, sel) {
  const abs = h => { try { if (!h || h.startsWith('#') || /^javascript:/i.test(h)) return null; const u = new URL(h, pageUrl); u.hash = ''; return u.href; } catch { return null; } };
  const T = a => (a.textContent || a.getAttribute('title') || a.getAttribute('aria-label') || '').trim();
  const R = a => ({ url: abs(a.getAttribute('href')), text: T(a) });
  if (sel) { try { const el = doc.querySelector(sel); const a = el && (el.matches('a[href]') ? el : el.closest('a')); if (a && abs(a.getAttribute('href'))) return R(a); } catch { } }
  const rel = doc.querySelector('a[rel~=next]') || doc.querySelector('link[rel~=next]');
  if (rel && abs(rel.getAttribute('href'))) return R(rel);
  const re = /^\s*(next|next\s*(chapter|page|part|ch\.?)|continue|›|»|→|>|>>|পরবর্তী|পরের|下一章|下一页|下一頁|下一话|下一話|다음|다음화|次へ|次の|siguiente|suivant|próximo|следующая|weiter)/i;
  const prev = /prev|previous|前|이전|আগের|পূর্ব|anterior|précédent|назад|zurück/i;
  const links = [...doc.querySelectorAll('a[href]')];
  for (const a of links) { const t = T(a); if (t.length < 40 && re.test(t) && !prev.test(t) && abs(a.getAttribute('href'))) return R(a); }
  for (const a of links) { const s = (a.getAttribute('class') || '') + ' ' + (a.id || ''); if (/(^|[-_ ])next([-_ ]|$)/i.test(s) && !prev.test(s) && abs(a.getAttribute('href'))) return R(a); }
  return null;
}

// ---- JSON / script helpers (chapter text hidden in data, not in the DOM) ----
export function longestString(o, min = 300) {
  let best = null;
  (function dfs(v, path, d) {
    if (d > 12 || v == null) return;
    if (typeof v === 'string') { if (v.length >= min && (!best || v.length > best.value.length)) best = { path, value: v }; return; }
    if (typeof v !== 'object') return;
    if (Array.isArray(v)) v.slice(0, 200).forEach((x, i) => dfs(x, path + '[' + i + ']', d + 1));
    else for (const k of Object.keys(v)) { if (SKIPKEY.test(k)) continue; dfs(v[k], path ? path + '.' + k : k, d + 1); }
  })(o, '', 0);
  return best;
}
export function jsonPick(o, path) {
  return path.replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean).reduce((a, k) => (a == null ? a : a[k]), o);
}
const looksProse = s => !/^data:|^https?:\/\/\S+$/.test(s) && /\s|[\u3000-\u9fff\u0980-\u09ff]/.test(s.slice(0, 600)) && !/function\s*\(|=>\s*\{/.test(s.slice(0, 600));
// balanced {...}/[...] starting at index i (string-aware)
function balanced(s, i) {
  let depth = 0, inStr = false, esc = false;
  for (let k = i; k < s.length; k++) {
    const c = s[k];
    if (inStr) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false; continue; }
    if (c === '"') inStr = true; else if (c === '{' || c === '[') depth++; else if (c === '}' || c === ']') { if (--depth === 0) return s.slice(i, k + 1); }
  }
  return '';
}
export function scriptFallback(doc) {
  let best = '';
  const consider = (s, min = 500) => { if (typeof s === 'string' && s.length >= min && s.length > best.length && looksProse(s)) best = s; };
  for (const sc of doc.querySelectorAll('script')) {
    const t = sc.textContent || ''; if (t.length < 500) continue;
    // var chapInfo = {...}  (webnovel.com style): chapter paragraphs live in contents[].content
    const ci = /(?:var|let|const)\s+(?:chapInfo|chapterInfo|chapterData|chapter_data)\s*=\s*[{[]/.exec(t);
    if (ci) {
      const raw = balanced(t, ci.index + ci[0].length - 1);
      for (const cand of [raw, raw.replace(/\\[bfnrtv]/g, '')]) { try { consider(bestText(JSON.parse(cand)), 120); break; } catch { } }
    }
    const type = (sc.getAttribute('type') || '').toLowerCase();
    if (type.includes('json') || sc.id === '__NEXT_DATA__') { try { consider(bestText(JSON.parse(t))); } catch { } continue; }
    if (/^\s*(?:window\.)?[\w$.\[\]'"]+\s*=\s*[{[]/.test(t)) {
      try { consider(bestText(JSON.parse(t.slice(t.search(/[{[]/)).replace(/;\s*$/, '')))); } catch { }
    }
    const re = /"((?:[^"\\]|\\.){800,})"/g; let m, c = 0;
    while ((m = re.exec(t)) && c++ < 20) { try { consider(JSON.parse('"' + m[1] + '"')); } catch { } }
  }
  return best || null;
}
// HTML string or plain text (from JSON / script) -> XHTML body
export function fromString(str, base, o, imgs) {
  const ctx = { base, imgs, removeSel: o.removeSel || '', repl: o.repl || [], images: o.images !== false };
  const s = String(str);
  if (/<(p|div|br|h[1-6])[\s>/]/i.test(s)) {
    const d = new DOMParser().parseFromString('<body>' + s, 'text/html'); let b = '';
    for (const ch of d.body.childNodes) b += walk(ch, ctx, false);
    return paragraphize(b.replace(/(?:<br\/>\s*){3,}/g, '<br/><br/>'));
  }
  let t = s.replace(BAD_XML, ''); for (const [a, b] of ctx.repl) t = t.split(a).join(b);
  return t.split(/\r?\n+/).map(x => x.trim()).filter(Boolean).map(x => `<p>${esc(x)}</p>`).join('\n');
}

function hidden(n) {
  if (n.hasAttribute('hidden') || n.getAttribute('aria-hidden') === 'true') return true;
  const st = n.getAttribute('style'); return !!st && /display\s*:\s*none|visibility\s*:\s*hidden/i.test(st);
}
function junk(n) {
  const s = (n.getAttribute('class') || '') + ' ' + (n.id || '');
  if (!s.trim() || !JUNK.test(s)) return false;
  return txt(n).length < 300 || linkDensity(n) > 0.3;
}
function absHref(h, base) {
  if (!h || h.startsWith('#') || /^javascript:/i.test(h)) return null;
  try { const u = new URL(h, base); return /^(https?|mailto):$/.test(u.protocol) ? u.href : null; } catch { return null; }
}
function img(n, c) {
  if (!c.images) return '';
  let s = '';
  for (const k of LAZY) { const v = n.getAttribute(k); if (v && !/^data:image\/(gif|png);base64,R0lGODlhAQAB/.test(v)) { s = v; break; } }
  if (!s) { const ss = n.getAttribute('srcset') || n.getAttribute('data-srcset'); if (ss) s = ss.split(',').pop().trim().split(/\s+/)[0]; }
  if (!s) return '';
  let u; try { u = new URL(s, c.base).href; } catch { return ''; }
  let i = c.imgs.map.get(u);
  if (i === undefined) { i = c.imgs.list.length; c.imgs.list.push(u); c.imgs.map.set(u, i); }
  return `<img src="@@IMG${i}@@" alt="${escA(n.getAttribute('alt') || '')}"/>`;
}
function walk(n, c, pre) {
  if (n.nodeType === 3) {
    let t = n.nodeValue.replace(BAD_XML, '');
    if (!pre) t = t.replace(/[ \t\r\n\f]+/g, ' ');
    for (const [a, b] of c.repl) t = t.split(a).join(b);
    return esc(t);
  }
  if (n.nodeType !== 1) return '';
  const tag = n.tagName.toLowerCase();
  if (SKIP.has(tag) || hidden(n)) return '';
  if (c.removeSel) { try { if (n.matches(c.removeSel)) return ''; } catch { } }
  if (junk(n)) return '';
  if (tag === 'img') return img(n, c);
  if (tag === 'br') return '<br/>';
  if (tag === 'hr') return '<hr/>';
  const p = pre || tag === 'pre';
  let inner = ''; for (const ch of n.childNodes) inner += walk(ch, c, p);
  if (!ALLOWED.has(tag)) return inner;
  if (tag !== 'td' && tag !== 'th' && !inner.replace(/<br\/>|\s/g, '') && !/<img/.test(inner)) return '';
  if (tag === 'a') { const u = absHref(n.getAttribute('href'), c.base); return u ? `<a href="${escA(u)}">${inner}</a>` : inner; }
  if (tag === 'td' || tag === 'th') {
    let a = ''; for (const k of ['colspan', 'rowspan']) { const v = parseInt(n.getAttribute(k), 10); if (v > 1 && v < 100) a += ` ${k}="${v}"`; }
    return `<${tag}${a}>${inner}</${tag}>`;
  }
  return `<${tag}>${inner}</${tag}>`;
}

// Text separated only by <br> (no <p>) -> real paragraphs
function paragraphize(html) {
  if (/<(p|table|ul|ol|pre|blockquote)[\s>]/.test(html)) return html;
  const flat = html.replace(/<\/?div>/g, '<br/>');
  return flat.split(/(?:<br\/>\s*)+/).map(s => s.trim()).filter(Boolean)
    .map(s => /^<h[1-6]>/.test(s) ? s : `<p>${s}</p>`).join('\n');
}

export function extractPage(doc, url, o, imgs) {
  let root = null;
  if (o.contentSelector) { try { root = doc.querySelector(o.contentSelector); } catch { } }
  const hadSel = !!root;
  root = root || findMain(doc);
  const ctx = { base: url, imgs, removeSel: o.removeSel || '', repl: o.repl || [], images: o.images !== false };
  let body = ''; for (const ch of root.childNodes) body += walk(ch, ctx, false);
  body = body.replace(/(?:<br\/>\s*){3,}/g, '<br/><br/>');
  body = paragraphize(body);
  let textLen = txt(root).trim().length, via = hadSel ? 'your selector' : 'auto-detected block';
  const rootPath = pathOf(root);
  if ((textLen < 150 || (o.contentSelector && !hadSel)) && !o.noScriptFallback) {            // text not in DOM -> look inside <script> data / JSON
    const fb = scriptFallback(doc);
    if (fb) { body = fromString(fb, url, o, imgs); textLen = fb.length; via = 'script/JSON data'; }
  }
  let title = '';
  const h = root.querySelector('h1,h2,h3') || doc.querySelector('h1');
  if (h) title = txt(h).trim().replace(/\s+/g, ' ');
  if (!title || title.length > 120) {
    const og = doc.querySelector('meta[property="og:title"]');
    title = ((og && og.content) || doc.title || '').split(/\s\|\s/)[0].trim().replace(/\s+/g, ' ');
  }
  if (!title) title = 'Untitled';
  const titles = [];   // every title candidate the page offers (best first); the studio picks the fullest
  const addT = t => { t = (t || '').replace(/[\s\u3000]+/g, ' ').trim(); if (t && !titles.includes(t)) titles.push(t); };
  if (!o.noHeading) {   // real chapter title is often just the first text line: "第12章 xxx" / "Chapter 12 ..."
    let m = null;
    if (o.headingMatch) {   // optional multilingual detector (Smart chapter headings)
      const re = /<p>([\s\S]*?)<\/p>/g, head = body.slice(0, 3000); let x, n = 0;
      while ((x = re.exec(head)) && n++ < 6) { const t = x[1].replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim(); if (o.headingMatch(t)) { m = [x[0], t]; break; } }
    } else m = /<p>\s*((?:第\s*[\d〇零一二三四五六七八九十百千两]+\s*[章节回话卷集篇]|Chapter\s+\d+)[^<]{0,120}?)\s*<\/p>/i.exec(body.slice(0, 3000));
    if (m) { addT(m[1]); title = m[1].trim(); body = body.replace(m[0], '').replace(/^\s*<h[1-6]>.*?<\/h[1-6]>\s*/s, ''); body = `<h2>${esc(title)}</h2>\n` + body; }
  }
  if (!o.noHeading && !/^\s*<h[1-6][\s>]/.test(body)) body = `<h2>${esc(title)}</h2>\n` + body;
  addT(title);
  [...root.querySelectorAll('h1,h2,h3')].slice(0, 3).forEach(h => addT(txt(h)));
  [...doc.querySelectorAll('h1')].slice(0, 2).forEach(h => addT(txt(h)));
  const og2 = doc.querySelector('meta[property="og:title"]'); if (og2) addT(og2.content);
  addT(doc.title);
  return { title, titles, body, textLen, hasImg: /<img/.test(body), via, rootPath };
}

// Chapter text hidden in a JSON API reply: a long string, or an array of paragraph strings/objects ({content|text|paragraph})
export function bestText(o, path) {
  const join = v => Array.isArray(v) ? v.map(x => typeof x === 'string' ? x : (x && typeof x === 'object' ? (x.content ?? x.text ?? x.paragraph ?? '') : '')).filter(s => typeof s === 'string' && s).join('\n') : (typeof v === 'string' ? v : '');
  if (path) return join(jsonPick(o, path));
  let best = '';
  (function dfs(v, d) {
    if (typeof v === 'string') { if (v.length > best.length) best = v; return; }
    if (d > 10 || v == null || typeof v !== 'object') return;
    if (Array.isArray(v) && v.length >= 3) {
      const parts = v.map(x => typeof x === 'string' ? x : (x && typeof x === 'object' ? (x.content ?? x.text ?? x.paragraph ?? '') : '')).filter(s => typeof s === 'string' && s);
      if (parts.length >= v.length * 0.8) { const j = parts.join('\n'); if (j.length > best.length) best = j; }
    }
    for (const k of Object.keys(v)) { if (SKIPKEY.test(k)) continue; dfs(v[k], d + 1); }
  })(o, 0);
  return best;
}

function pathOf(e) {
  const p = [];
  while (e && e.nodeType === 1 && p.length < 5) {
    let s = e.tagName.toLowerCase();
    if (e.id) s += '#' + e.id; else { const c = (e.getAttribute('class') || '').trim().split(/\s+/).filter(Boolean).slice(0, 2); if (c.length) s += '.' + c.join('.'); }
    p.unshift(s); e = e.parentElement;
  }
  return p.join(' > ');
}
