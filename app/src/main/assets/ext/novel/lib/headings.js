// Smart chapter-heading detection (optional). Works for Chinese, Japanese, Korean, English, Spanish,
// Latin-script languages (Latin/FR/DE/IT/PT/ID/TR…) and Arabic, or with a custom prefix/suffix.
// A heading = [prefix] NUMBER [suffix] [separator] [title]    e.g.  第12章 标题 · 제12화 · Chapter 12: Title · الفصل 12
const D = '0-9０-９٠-٩۰-۹';                         // ASCII, full-width, Arabic-Indic, Persian digits
const CJKN = '〇零一二三四五六七八九十百千万两兩壹贰叁肆伍陆柒捌玖拾佰仟';
const NUM = `(?:[${D}]+|[${CJKN}]+|[IVXLCDM]{1,8})`;
const SEP = '[\\s:：.、,，\\-–—·|]';
const esc = s => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const EN_W = 'one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred';
const ES_W = 'uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|veinte|treinta|cuarenta|cincuenta|cien|primero|segundo|tercero|cuarto|quinto|único|unico';
const LA_W = 'unus|primus|secundus|tertius|quartus|quintus|sextus|septimus|octavus|nonus|decimus';
const AR_W = 'الأول|الاول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر|(?:الحادي|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع)\\s+عشر|العشرون';

const LANGS = {
  zh: { pre: ['第'], suf: ['章', '节', '節', '回', '话', '話', '卷', '集', '篇', '部'], special: ['序章', '序言', '楔子', '引子', '前言', '尾声', '尾聲', '后记', '後記', '番外', '终章', '終章'] },
  ja: { pre: ['第', ''], suf: ['話', '章', '節', '回', '巻', '幕', '部'], special: ['プロローグ', 'エピローグ', '序章', '終章', '番外編', '外伝', 'あとがき', '幕間', '閑話'] },
  ko: { pre: ['제', ''], suf: ['화', '장', '회', '편', '권', '부'], special: ['프롤로그', '에필로그', '외전', '후기', '서장', '종장', '번외'] },
  en: { pre: ['Chapter', 'Chap.', 'Chap', 'Ch.', 'Ch', 'Episode', 'Ep.', 'Ep', 'Part', 'Book', 'Volume', 'Vol.', 'Act'], words: EN_W, special: ['Prologue', 'Epilogue', 'Interlude', 'Afterword', 'Foreword', 'Preface', 'Side Story', 'Extra', 'Bonus Chapter'] },
  es: { pre: ['Capítulo', 'Capitulo', 'Cap.', 'Cap', 'Episodio', 'Parte', 'Libro', 'Acto'], words: ES_W, special: ['Prólogo', 'Prologo', 'Epílogo', 'Epilogo', 'Interludio'] },
  la: { pre: ['Caput', 'Capitulum', 'Chapitre', 'Chap.', 'Kapitel', 'Kap.', 'Capitolo', 'Capítulo', 'Capitulo', 'Cap.', 'Bab', 'Bölüm', 'Rozdział', 'Hoofdstuk', 'Kapittel', 'Luku', 'Fejezet', 'Liber', 'Pars', 'Épisode', 'Folge', 'Teil', 'Partie', 'Parte', 'Livre', 'Buch'], words: LA_W, special: ['Prologus', 'Epilogus', 'Prologue', 'Prolog', 'Prologo', 'Prólogo', 'Épilogue', 'Epilog', 'Epilogo'] },
  ar: { pre: ['الفصل رقم', 'الحلقة رقم', 'الجزء رقم', 'الفصل', 'فصل', 'الجزء', 'جزء', 'الحلقة', 'حلقة', 'الباب', 'باب'], words: AR_W, special: ['المقدمة', 'مقدمة', 'الخاتمة', 'خاتمة', 'تمهيد', 'إهداء', 'الملحق'] }
};

// one regex fragment (no capture groups) for a spec {pre[], suf[], words?, special[]}
function fragment(spec) {
  const pres = [...spec.pre].sort((a, b) => b.length - a.length).map(esc), sufs = [...(spec.suf || [])].sort((a, b) => b.length - a.length).map(esc);
  const num = spec.words ? `(?:${NUM}|${spec.words})` : NUM, out = [];
  if (sufs.length) out.push(`(?:${pres.join('|')})\\s*${num}\\s*(?:${sufs.join('|')})`);
  else if (pres.some(Boolean)) out.push(`(?:${pres.filter(Boolean).join('|')})\\s*${num}(?=$|${SEP})`);
  const sp = (spec.special || []).sort((a, b) => b.length - a.length).map(esc);
  if (sp.length) out.push(`(?:${sp.join('|')})(?=$|${SEP})`);
  return out.join('|');
}
const list = s => String(s || '').split(/[,，\n]+/).map(x => x.trim()).filter(Boolean);

// makeMatcher({lang:'auto'|zh|ja|ko|en|es|la|ar|custom, pre, suf}) -> fn(text) => {head,title,full,num} | null
export function makeMatcher(cfg, maxLen = 100) {
  let frag = '', pres = [];
  if (cfg.lang === 'custom') {
    const pre = list(cfg.pre), suf = list(cfg.suf); if (!pre.length && !suf.length) return null;
    frag = fragment({ pre: pre.length ? pre : [''], suf, special: [] }); pres = pre;
  } else if (LANGS[cfg.lang]) { frag = fragment(LANGS[cfg.lang]); pres = LANGS[cfg.lang].pre; }
  else { frag = Object.values(LANGS).map(fragment).join('|'); pres = Object.values(LANGS).flatMap(l => l.pre); }
  const re = new RegExp(`^\\s*(${frag})(?:${SEP}*)(.*)$`, 'iu');
  const preRe = new RegExp('^(?:' + [...new Set(pres)].filter(Boolean).sort((a, b) => b.length - a.length).map(esc).join('|') + ')\\s*', 'iu');
  const hasPre = pres.some(Boolean);
  const fn = text => {
    const t = String(text || '').replace(/\s+/g, ' ').trim(); if (!t || t.length > maxLen) return null;
    const m = re.exec(t); if (!m) return null;
    return { head: m[1], title: (m[2] || '').trim(), full: t, num: numFrom(hasPre ? m[1].replace(preRe, '') : m[1]) };
  };
  return fn;
}

// the number at the start of the text left after the prefix ("12章" -> 12, "XII" -> 12, "Prologue" -> null)
function numFrom(r) {
  let m = new RegExp('^[' + D + ']+').exec(r) || new RegExp('^[' + CJKN + ']+').exec(r) || /^[IVXLCDM]{1,8}(?![\p{L}\p{N}])/u.exec(r);
  return m ? numVal(m[0]) : null;
}
const CJK_D = { '〇': 0, '零': 0, '一': 1, '壹': 1, '二': 2, '两': 2, '兩': 2, '贰': 2, '三': 3, '叁': 3, '四': 4, '肆': 4, '五': 5, '伍': 5, '六': 6, '陆': 6, '七': 7, '柒': 7, '八': 8, '捌': 8, '九': 9, '玖': 9 };
const CJK_U = { '十': 10, '拾': 10, '百': 100, '佰': 100, '千': 1000, '仟': 1000 };
const ROMAN = { I: 1, V: 5, X: 10, L: 50, C: 100, D: 500, M: 1000 };
export function numVal(s) {
  s = String(s).trim(); if (!s) return null;
  const ascii = s.replace(/[０-９]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xFEE0)).replace(/[٠-٩]/g, c => c.charCodeAt(0) - 0x660).replace(/[۰-۹]/g, c => c.charCodeAt(0) - 0x6F0);
  if (/^\d+$/.test(ascii)) return parseInt(ascii, 10);
  if (/^[〇零一二三四五六七八九十百千万两兩壹贰叁肆伍陆柒捌玖拾佰仟]+$/.test(s)) {
    if (!/[十百千拾佰仟]/.test(s)) { let v = 0; for (const c of s) v = v * 10 + (CJK_D[c] ?? 0); return v; }
    let total = 0, cur = 0;
    for (const c of s) { if (c in CJK_D) cur = CJK_D[c]; else if (c in CJK_U) { total += (cur || 1) * CJK_U[c]; cur = 0; } }
    return total + cur;
  }
  if (/^[IVXLCDM]+$/.test(s.toUpperCase()) && /^[IVXLCDM]+$/i.test(s)) {
    let v = 0; const u = s.toUpperCase(); for (let i = 0; i < u.length; i++) { const a = ROMAN[u[i]], b = ROMAN[u[i + 1]] || 0; v += a < b ? -a : a; } return v;
  }
  return null;
}


// ---------- chapter-title helpers (v1.8) ----------
// A "bare" heading is only the chapter marker: "第242章", "Chapter 12", "12", "제12화" ... (no real title text yet)
let _auto = null;
const autoM = () => _auto || (_auto = makeMatcher({ lang: 'auto' }, 200));
const sp = s => String(s || '').replace(/[\s　]+/g, ' ').trim();
export function isBare(text) {
  const t = sp(text); if (!t) return true;
  if (/^[0-9０-９٠-٩۰-۹]+[.、:：]?$/.test(t)) return true;
  const m = autoM()(t); return !!m && !m.title;
}
// "<title>" strings look like "第242章 标题 - 小说名 - 站名": keep the chapter part, drop the book / site name.
function trimSite(c, novel) {
  let parts = sp(c).split(/\s*[|｜_]\s*|\s+[-–—]\s+/).map(sp).filter(Boolean);
  if (!parts.length) return '';
  if (novel) { const k = parts.findIndex(p => p.includes(novel) || (p.length >= 4 && novel.includes(p))); if (k === 0) return parts.length === 1 ? sp(c) : parts[0]; if (k > 0) parts = parts.slice(0, k); }
  if (parts.length === 1) return parts[0];
  const m0 = autoM()(parts[0]);
  if (m0 && !m0.title) return parts[0] + ' ' + parts[1];       // "Chapter 5 - The Return"
  return parts[0];                                             // "第1章 标题 | 站名"  /  generic "Title - Site"
}
// tocText: link text from the catalog (may be bare). cands: page title candidates, best first. novel: the book title.
// Returns the fullest sensible chapter title, or '' when nothing better than the catalog text exists.
export function pickTitle(tocText, cands, novel = '') {
  const toc = sp(tocText), nv = sp(novel), M = autoM();
  if (toc && !isBare(toc)) return toc;                         // the catalog already has a real title
  const list = [];
  (cands || []).forEach((c, idx) => {
    const t = trimSite(c, nv); if (!t || t.length > 160 || list.some(x => x.t === t)) return;
    if (nv && (t === nv || (t.includes(nv) && t.length <= nv.length + 3))) return;      // that's the book name, not a chapter
    list.push({ t, idx });
  });
  const mt = toc ? M(toc) : null; let best = '';
  for (const { t } of list) {
    const mc = M(t); let ok = false;
    if (mt) ok = (mc && mc.title && (mt.num == null || mc.num === mt.num)) || (t.length > toc.length && t.replace(/\s/g, '').startsWith(toc.replace(/\s/g, '')));
    else ok = !!(mc && mc.title);
    if (ok && t.length > best.length) best = t;
  }
  if (best) return best;
  if (mt) {                                                    // chapter number is in the catalog, the name lives in a separate page heading
    for (const { t, idx } of list) if (idx < 3 && !M(t) && t.length >= 2 && t.length <= 90) return toc + ' ' + t;
    return toc;
  }
  if (!toc) { for (const { t } of list) if (M(t)) return t; return list[0] ? list[0].t : ''; }
  return toc;
}

// ---------- split one body (HTML string made of <p>/<h*> blocks) at every heading ----------
const VOID = new Set(['img', 'br', 'hr', 'input', 'meta', 'link']);
function topLevel(html) {
  const els = [], re = /<(\/?)([a-zA-Z][\w:-]*)\b[^>]*?(\/?)>/g; let depth = 0, start = 0, top = '', m;
  while ((m = re.exec(html))) {
    const close = m[1] === '/', name = m[2].toLowerCase(), self = m[3] === '/' || VOID.has(name);
    if (!close) { if (depth === 0) { start = m.index; top = name; } if (self) { if (depth === 0) els.push({ s: start, e: re.lastIndex, tag: name }); } else depth++; }
    else { depth--; if (depth <= 0) { if (depth === 0) els.push({ s: start, e: re.lastIndex, tag: top }); depth = 0; } }
  }
  return els;
}
const plain = h => h.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n)).replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim();
const xesc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
export const plainText = plain;

export function splitBody(html, match, strip = false) {
  let els = topLevel(html);
  for (let k = 0; k < 3 && els.length <= 2; k++) {                 // unwrap a single big <div>/<section>
    const w = els.find(x => (x.tag === 'div' || x.tag === 'section') && (x.e - x.s) > html.length * 0.8); if (!w) break;
    html = html.slice(html.indexOf('>', w.s) + 1, html.lastIndexOf('</')); els = topLevel(html);
  }
  const heads = []; let prev = null;
  els.forEach((el, i) => {
    if (!/^(p|h[1-6]|div)$/.test(el.tag)) return;
    const m = match(plain(html.slice(el.s, el.e))); if (!m) return;
    if (m.num != null && prev != null && m.num <= prev) return;       // repeated / lower number = just text mentioning a chapter
    if (m.num != null) prev = m.num; heads.push({ i, m });
  });
  if (heads.length < 2) return null;
  const parts = [], first = html.slice(0, els[heads[0].i].s).trim();
  if (plain(first.replace(/<h[1-6][\s\S]*?<\/h[1-6]>/g, '')).length > 40 || /<img/.test(first)) parts.push({ title: null, body: first });
  heads.forEach((h, k) => {
    const end = k + 1 < heads.length ? els[heads[k + 1].i].s : html.length, rest = html.slice(els[h.i].e, end).trim();
    if (plain(rest).length < 3 && !/<img/.test(rest)) return;      // empty stub (e.g. a table of contents line)
    const title = strip ? (h.m.title || h.m.full) : h.m.full;
    parts.push({ title, body: `<h2>${xesc(title)}</h2>\n${rest}` });
  });
  return parts.length >= 2 ? parts : null;
}
export function splitChapters(chapters, match, { strip = false } = {}) {
  const out = [];
  for (const c of chapters) {
    const parts = splitBody(c.body, match, strip);
    if (!parts) { out.push(c); continue; }
    for (const p of parts) out.push(p.title == null ? { ...c, body: p.body } : { ...c, title: p.title, body: p.body });
  }
  return out;
}
