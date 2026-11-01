const $ = (s) => document.querySelector(s);
initTheme();
let tab = null, rec = null;
const SECTIONS = [['hls', 'Streams (HLS)'], ['dash', 'DASH streams'], ['video', 'Videos'], ['audio', 'Audio'], ['subtitle', 'Subtitles']];

function el(tag, props = {}, ...kids) { const e = document.createElement(tag); Object.assign(e, props); kids.flat().forEach((k) => e.append(k)); return e; }
function btn(text, fn, cls) { const b = el('button', { textContent: text, className: cls || '' }); b.onclick = () => fn(b); return b; }
function ensure(id, after) { // containers that popup.html has not got (also used by the side panel page)
  let e = document.getElementById(id);
  if (!e) { e = document.createElement(id === 'toast' ? 'div' : 'section'); e.id = id; if (id === 'toast') { e.className = 'toast'; e.setAttribute('role', 'status'); document.body.append(e); } else { const a = $(after); if (a) a.after(e); else document.body.append(e); } }
  return e;
}
let toastT = 0;
function toast(msg) { const t = ensure('toast'); t.textContent = msg; t.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2600); }
// V71: "Download again?" - only when the same item was saved before AND its file is still on the disk (resolves true = started, false = cancelled)
function askAgain(d) {
  return new Promise((res) => {
    const ov = document.createElement('div'); ov.className = 'dupov';
    const c = document.createElement('div'); c.className = 'dupcard'; c.setAttribute('role', 'alertdialog');
    const h = document.createElement('b'); h.textContent = 'Download again?';
    const p = document.createElement('p'); p.textContent = 'You already downloaded "' + (d.title || 'this file') + '"' + (d.time ? ' on ' + new Date(d.time).toLocaleString() : '') + ' and it is still on your device.';
    const r = document.createElement('div'); r.className = 'dupbtn';
    const mk = (t, ok, cls) => { const b = document.createElement('button'); b.textContent = t; if (cls) b.className = cls; b.onclick = () => { ov.remove(); res(ok); }; r.append(b); return b; };
    mk('Cancel', false); mk('Download again', true, 'primary');
    c.append(h, p, r); ov.append(c); document.body.append(ov);
  });
}
const job = async (it, extra = {}) => {
  if (!extra.manual && !extra.clip && it.kind !== 'subtitle') {
    let d = null;
    try { const r = await chrome.runtime.sendMessage({ type: 'dup-check', tabId: tab.id, key: it.key }); d = r && r.dup; } catch (e) { d = null; }
    if (d && !(await askAgain(d))) return false;
  }
  if (!extra.manual) toast(it.kind === 'subtitle' ? 'Subtitle download started' : 'Download started \u2013 progress is shown above');
  await chrome.runtime.sendMessage({ type: 'start-job', tabId: tab.id, key: it.key, ...extra });
  return true;
};

async function copy(text, b) {
  await navigator.clipboard.writeText(text);
  const old = b.textContent; b.textContent = 'Copied'; setTimeout(() => (b.textContent = old), 900);
}

const SVG = {
  dl: '<svg viewBox="0 0 24 24"><path d="M12 3v12m0 0l-5-5m5 5l5-5M4 20h16"/></svg>',
  more: '<svg viewBox="0 0 24 24" class="fill"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>',
  play: '<svg viewBox="0 0 24 24" class="fill"><path d="M8 5.5v13a1 1 0 001.5.86l10.5-6.5a1 1 0 000-1.72L9.5 4.64A1 1 0 008 5.5z"/></svg>',
  pause: '<svg viewBox="0 0 24 24" class="fill"><rect x="6" y="5" width="4.2" height="14" rx="1.2"/><rect x="13.8" y="5" width="4.2" height="14" rx="1.2"/></svg>',
  x: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  folder: '<svg viewBox="0 0 24 24"><path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z"/></svg>',
};
function html(h) { const t = document.createElement('template'); t.innerHTML = h.trim(); return t.content.firstChild; }

function card(it) {
  const info = it.info || {};
  const isSub = it.kind === 'subtitle', isAud = it.kind === 'audio', isVid = !isSub && !isAud;
  const si = isSub ? subInfo(it.url) : null;
  const name = isSub ? subTitle(it) : isAud ? baseName(it.url) : (rec.title || baseName(it.url));
  const dur = info.dur || (rec.hint && rec.hint.dur && !isSub ? rec.hint.dur : 0);
  const res = info.variants && info.variants[0] && info.variants[0].h ? info.variants[0].h + 'p' : '';
  const chips = [isAud ? 'AUDIO' : '', isSub ? 'SUBTITLE' : '', isSub && (it.lang || si.lang) ? normLang(it.lang || si.lang).toUpperCase() : '', si && si.forced ? 'FORCED' : '', si && si.cc ? 'CC' : '', res, isAud && dur ? fmtDur(dur) : '', (it.kind === 'hls' || it.kind === 'dash') ? '' : fmtSize(it.size), info.live ? 'LIVE' : '', info.protected ? 'DRM' : '']
    .filter(Boolean).map((t) => el('span', { className: 'chip' + (t === 'DRM' || t === 'LIVE' ? ' warn' : ''), textContent: t }));
  const th = el('div', { className: 'th' });
  if (!isSub) {
    const poster = isVid && rec.hint && rec.hint.poster;
    if (poster) th.style.backgroundImage = `url("${poster.replace(/"/g, '%22')}")`; else th.innerHTML = isAud ? '&#9835;' : SVG.play;
    if (isVid) th.append(el('span', { className: 'ty', textContent: it.kind === 'hls' ? 'HLS' : it.kind.toUpperCase() }));
    if (isVid && dur) th.append(el('span', { className: 'dur', textContent: fmtDur(dur) }));
  }
  const more = el('div', { className: 'more' });
  const grp = (label, ...kids) => more.append(el('span', { className: 'mh', textContent: label }), el('div', { className: 'grp' }, ...kids));
  const c = buildCommands(it, { referer: it.ref || rec.pageUrl, title: rec.title });
  const tm = el('input', { type: 'time', title: 'Start time' }); tm.style.cssText = 'background:var(--panel2);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:4px 6px;font:inherit';
  const sch = btn('Schedule', (b) => { if (!tm.value) { tm.focus(); return; } chrome.runtime.sendMessage({ type: 'schedule-job', hhmm: tm.value, job: { itemUrl: it.url, kind: it.kind, ref: it.ref || rec.pageUrl || '', title: rec.title || '', variant: 'best' } }); b.textContent = 'Scheduled'; setTimeout(() => { b.textContent = 'Schedule'; }, 1800); });
  grp('Copy', btn('Copy URL', (b) => copy(it.url, b)), btn('yt-dlp', (b) => copy(c.ytdlp, b)), btn('ffmpeg', (b) => copy(c.ffmpeg, b)), btn('cURL', (b) => copy(c.curl, b)));
  if (it.kind === 'hls') grp('Quality or clip', btn('Quality / clip...', () => job(it, { manual: true })));
  if (!isSub) grp('Start later', tm, sch);
  const moreBtn = el('button', { className: 'ic2', title: 'More options', innerHTML: SVG.more }); moreBtn.onclick = () => more.classList.toggle('on');
  const row = el('div', { className: 'dlr' });
  const vars = (it.kind === 'hls' || it.kind === 'dash') && info.variants && info.variants.length ? info.variants.slice(0, 8) : null;
  if (info.protected) row.append(el('span', { className: 'note', textContent: 'DRM protected - cannot be downloaded' }));
  else if (isSub) {
    const b1 = btn('.srt', () => job(it, { fmt: 'srt' }), 'primary'), b3 = btn('.vtt', () => job(it, { fmt: 'vtt' })), b2 = btn('Original', () => job(it, { fmt: 'orig' }));
    more.prepend(el('span', { className: 'mh', textContent: 'Save as' }), el('div', { className: 'grp' }, btn('.txt (text only)', () => job(it, { fmt: 'txt' }))));
    row.append(b1, b3, b2, moreBtn);
  } else if (vars) {
    const sel = el('select', { title: 'Choose the quality' });
    sel.append(el('option', { value: 'best', textContent: 'Best (auto)' }));
    vars.forEach((v, i) => {
      const [n, d] = qLabel(v.h, v.bw), sz = qSize(v, dur);
      sel.append(el('option', { value: v.url || v.id, textContent: [n + (d ? ' ' + d : ''), sz ? '~' + sz : '', i === vars.length - 1 && vars.length > 1 ? 'smallest' : ''].filter(Boolean).join(' \u00b7 ') }));
    });
    const go = el('button', { className: 'primary dlb', innerHTML: SVG.dl + '<span>Download</span>' });
    go.onclick = () => { job(it, { variant: sel.value }).then((ok) => { if (!ok) return; go.innerHTML = SVG.dl + '<span>Started</span>'; setTimeout(() => (go.innerHTML = SVG.dl + '<span>Download</span>'), 1400); }); };
    row.append(el('label', { className: 'sel' }, sel), go, moreBtn);
  } else {
    const go = el('button', { className: 'primary dlb grow', innerHTML: SVG.dl + '<span>Download' + (it.size ? ' \u00b7 ' + fmtSize(it.size) : '') + '</span>' });
    go.onclick = () => job(it, { variant: 'best' });
    row.append(go, moreBtn);
  }
  return el('div', { className: 'item' + (isSub ? ' sub' : ''), title: it.url }, th, el('div', { className: 'mn' }, el('div', { className: 'name', textContent: name }), el('div', { className: 'meta' }, chips), row, more));
}

// the item most people want: video/stream that is not DRM, highest resolution, then longest, then biggest
function pickBest(items) {
  const score = (i) => { const inf = i.info || {}; return [inf.live ? 0 : 1, inf.variants && inf.variants[0] ? inf.variants[0].h || 0 : 0, inf.dur || 0, i.size || 0]; };
  const cmp = (a, b) => { const o = cmpStreams(a, b); if (o) return o; const x = score(a), y = score(b); for (let k = 0; k < 4; k++) if (x[k] !== y[k]) return y[k] - x[k]; return (b.time || 0) - (a.time || 0); };
  const c = items.filter((i) => ['hls', 'dash', 'video'].includes(i.kind) && !(i.info && i.info.protected)).sort(cmp);
  return c[0] || null;
}
function hero(it, items) {
  const info = it.info || {};
  const dur = info.dur || (rec.hint && rec.hint.dur) || 0;
  const res = info.variants && info.variants[0] && info.variants[0].h ? info.variants[0].h + 'p' : '';
  const vids = items.filter((i) => ['hls', 'dash', 'video'].includes(i.kind) && !(i.info && i.info.protected));
  const same = vids.filter((i) => i !== it && Math.abs(((i.info && i.info.dur) || 0) - dur) <= 2).length;
  const chips = [res, it.kind === 'hls' ? '' : fmtSize(it.size), info.live ? 'LIVE' : ''].filter(Boolean).map((t) => el('span', { className: 'chip', textContent: t }));
  const th = el('div', { className: 'th' }), poster = rec.hint && rec.hint.poster;
  if (poster) th.style.backgroundImage = `url("${poster.replace(/"/g, '%22')}")`; else th.innerHTML = SVG.play;
  if (dur) th.append(el('span', { className: 'dur', textContent: fmtDur(dur) }));
  const go = el('button', { className: 'primary big', innerHTML: SVG.dl + '<span>Download best</span>' });
  go.onclick = () => { job(it, { variant: 'best' }).then((ok) => { if (!ok) return; go.innerHTML = SVG.dl + '<span>Started</span>'; setTimeout(() => (go.innerHTML = SVG.dl + '<span>Download best</span>'), 1600); }); };
  return el('div', { className: 'hero' }, el('div', { className: 'hrow' }, th,
    el('div', { className: 'hl' }, el('span', { className: 'ht', textContent: 'Best match' }), el('div', { className: 'name', textContent: rec.title || baseName(it.url) }), el('div', { className: 'meta' }, chips),
      same ? el('div', { className: 'hn', textContent: same + ' more link' + (same > 1 ? 's for the same video are' : ' for the same video is') + ' listed below' }) : '')), go);
}

async function render() {
  const r = await chrome.runtime.sendMessage({ type: 'get-state', tabId: tab.id });
  rec = r.rec;
  const items = rec.items.filter((i) => !i.hidden);
  const list = $('#list'); list.textContent = '';
  $('#page').textContent = rec.title || tab.title || tab.url || '';
  $('#count').textContent = items.length ? `(${items.length})` : '';
  if (!items.length) {
    const e = el('div', { className: 'empty' });
    e.innerHTML = '<div class="eico"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></svg></div><b class="et">No video found yet</b><p class="ed">Most players only load their video after it starts playing.</p><ol class="steps"><li>Play the video for a second</li><li>Press <b>Play &amp; scan</b></li><li>Still nothing? Press <b>Diagnose</b> and send the report</li></ol>';
    const row = el('div', { className: 'emptyrow' }, btn('Play & scan', () => $('#playscan').click(), 'primary'), btn('Diagnose', () => $('#diag').click()));
    e.append(row); list.append(e); return;
  }
  const hasVid = items.some((i) => ['hls', 'dash', 'video'].includes(i.kind));
  const best = pickBest(items);
  if (best) { list.append(hero(best, items)); list.append(el('h3', { textContent: 'All found' })); }
  if (!hasVid && items.some((i) => i.kind === 'subtitle')) list.append(el('div', { className: 'empty slim', textContent: 'Subtitles found but no video stream yet. Press "Play & scan" (or let the video play for a few seconds), then check again.' }));
  if (hasVid && !items.some((i) => i.kind === 'subtitle')) {
    const bar = el('div', { className: 'subbar' });
    bar.append(btn('Save subtitles from player', async () => {
      toast('Reading subtitles from the player...');
      const r = await chrome.runtime.sendMessage({ type: 'subs-capture', tabId: tab.id, fmt: 'srt' }).catch(() => null);
      toast(r && r.n ? 'Saved ' + r.n + ' subtitle file' + (r.n > 1 ? 's' : '') + ' from the player' : 'No subtitle in the player yet. Turn subtitles ON there, play a few seconds, try again.');
    }));
    list.append(bar);
  }
  for (const [kind, title] of SECTIONS) {
    const xs = items.filter((i) => i.kind === kind);
    if (!xs.length) continue;
    list.append(el('h3', { textContent: title + ' \u00b7 ' + xs.length }));
    if (kind === 'subtitle') {
      const bar = el('div', { className: 'subbar' });
      const all = (f) => async () => { const r = await chrome.runtime.sendMessage({ type: 'subs-all', tabId: tab.id, fmt: f }).catch(() => null); toast(r && r.n ? 'Saving ' + r.n + ' subtitle file' + (r.n > 1 ? 's' : '') + '...' : 'No subtitle found'); };
      bar.append(btn('All .srt', all('srt'), 'primary'), btn('All .vtt', all('vtt')), btn('All .txt', all('txt')), btn('From player', async () => {
        toast('Reading subtitles from the player...');
        const r = await chrome.runtime.sendMessage({ type: 'subs-capture', tabId: tab.id, fmt: 'srt' }).catch(() => null);
        toast(r && r.n ? 'Saved ' + r.n + ' subtitle file' + (r.n > 1 ? 's' : '') + ' from the player' : 'No readable subtitle in the player. Turn subtitles ON there, play a few seconds, try again.');
      }));
      list.append(bar);
    }
    xs.sort((a, b) => (!!a.old - !!b.old) || (b.size || 0) - (a.size || 0) || (b.time || 0) - (a.time || 0)).forEach((it) => list.append(card(it)));
  }
}

async function renderJobs() {
  const all = Object.values((await chrome.storage.session.get('mgJobs')).mgJobs || {});
  const run = all.filter((j) => j.state === 'run').sort((a, b) => b.updated - a.updated);
  const box = $('#jobs'); box.textContent = '';
  if (!run.length) return;
  box.append(el('h3', { textContent: `Downloading (${run.length})` }));
  run.slice(0, 4).forEach((j) => {
    const pct = Math.round(Math.max(0, Math.min(1, j.pct || 0)) * 100);
    const bar = el('div', { className: 'bar' }, el('i'));
    bar.firstChild.style.width = pct + '%';
    const pz = el('button', { className: 'ib', title: j.paused ? 'Resume' : 'Pause', innerHTML: j.paused ? SVG.play : SVG.pause });
    pz.onclick = () => chrome.runtime.sendMessage({ type: 'job-pause', key: j.key, on: !j.paused });
    const cx = el('button', { className: 'ib', title: 'Cancel', innerHTML: SVG.x });
    cx.onclick = () => chrome.runtime.sendMessage({ type: 'job-cancel', key: j.key });
    const parts = [el('span', { textContent: j.title || j.key }), el('span', { className: 'pc', textContent: /Recording live/.test(j.text || '') ? 'LIVE' : pct + '%' })];
    if (/Recording live/.test(j.text || '')) {
      const st = el('button', { className: 'ib', title: 'Stop and save the recording', innerHTML: '<svg viewBox="0 0 24 24" class="fill"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>' });
      st.onclick = () => chrome.runtime.sendMessage({ type: 'job-stop', key: j.key });
      parts.push(st);
    }
    box.append(el('div', { className: 'job' + (j.paused ? ' paused' : '') }, el('div', { className: 'jn' }, ...parts, pz, cx), bar));
  });
}


// files saved in the last half hour, with a button that shows them in the Downloads folder (where the browser supports it)
async function renderRecent() {
  const box = ensure('recent', '#jobs'); box.textContent = '';
  let h = [];
  try { h = (await chrome.storage.local.get('mgHist')).mgHist || []; } catch (e) { return; }
  const rows = h.filter((e) => Date.now() - e.time < 30 * 60000).slice(0, 2);
  const canShow = !!(chrome.downloads && typeof chrome.downloads.show === 'function');
  const ok = [];
  for (const e of rows) {
    if (e.id > 0 && chrome.downloads && chrome.downloads.search) { try { const [d] = await chrome.downloads.search({ id: e.id }); if (!d || d.exists === false) continue; } catch (x) { /* keep */ } }
    ok.push(e);
  }
  if (!ok.length) return;
  box.append(el('h3', { textContent: 'Just saved' }));
  ok.forEach((e) => {
    const parts = [el('span', { textContent: e.title || 'file' }), el('span', { className: 'pc ok', textContent: e.size ? fmtSize(e.size) : '' })];
    if (canShow && e.id > 0) { const b = el('button', { className: 'ib', title: 'Show in folder', innerHTML: SVG.folder }); b.setAttribute('aria-label', 'Show in folder'); b.onclick = () => { try { chrome.downloads.show(e.id); } catch (x) { toast('Cannot open the folder here'); } }; parts.push(b); }
    box.append(el('div', { className: 'job rec' }, el('div', { className: 'jn' }, ...parts)));
  });
}

// Android Kiwi / Quetta open this popup as a tab of its own, so "the active tab" is the popup itself.
// Look for the page you came from: opener tab first, then recent web tabs; prefer the one that has media.
async function pickTab() {
  const isWeb = (t) => t && /^https?:/i.test(t.url || '');
  const cands = [];
  const add = (t) => { if (isWeb(t) && !cands.some((c) => c.id === t.id)) cands.push(t); };
  let me = null;
  try { me = await chrome.tabs.getCurrent(); } catch (e) { /* real popup: no tab */ }
  const [act] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!me || !me.id) add(act);
  if (me && me.openerTabId) add(await chrome.tabs.get(me.openerTabId).catch(() => null));
  (await chrome.tabs.query({})).filter(isWeb).sort((a, b) => (b.lastAccessed || 0) - (a.lastAccessed || 0)).forEach(add);
  const store = await chrome.storage.session.get(cands.slice(0, 8).map((t) => 'tab_' + t.id));
  const withMedia = cands.slice(0, 8).filter((t) => store['tab_' + t.id] && (store['tab_' + t.id].items || []).some((i) => !i.hidden));
  return withMedia[0] || cands[0] || act || null;
}

(async () => {
  tab = await pickTab();
  if (!tab) { $('#list').append(el('div', { className: 'empty', textContent: 'No web page found. Open the video page, then open Media Grabber again.' })); return; }
  document.querySelectorAll('button[title]:not([aria-label])').forEach((b) => b.setAttribute('aria-label', b.title));
  await render();
  renderJobs(); renderRecent();
  chrome.storage.onChanged.addListener((c, area) => { if (area === 'session' && c.mgJobs) renderJobs(); if (area === 'local' && c.mgHist) renderRecent(); });
  chrome.storage.onChanged.addListener((c, area) => { if (area === 'session' && c['tab_' + tab.id]) render(); });
  $('#playscan').onclick = (ev) => {
    const b = ev.currentTarget, old = b.innerHTML;
    b.disabled = true; b.textContent = 'Scanning...';
    chrome.runtime.sendMessage({ type: 'play-scan', tabId: tab.id });
    const before = rec ? rec.items.length : 0;
    $('#page').textContent = 'Pressing play on the page...';
    [1500, 3000, 4500].forEach((ms) => setTimeout(render, ms));
    setTimeout(async () => {
      await render(); b.disabled = false; b.innerHTML = old;
      const added = rec.items.length - before;
      if (!added) $('#page').textContent = 'Nothing new. Tap the video yourself so it starts playing, then press Rescan.';
    }, 7000);
  };
  $('#diag').onclick = async () => {
    try { await chrome.tabs.sendMessage(tab.id, { type: 'diagnose' }, { frameId: 0 }); window.close(); }
    catch (e) { $('#page').textContent = 'This page cannot be checked (a browser page, or reload the tab once and try again).'; }
  };
  $('#rescan').onclick = () => { chrome.runtime.sendMessage({ type: 'rescan', tabId: tab.id }); setTimeout(render, 700); };
  $('#clear').onclick = async () => { await chrome.runtime.sendMessage({ type: 'clear', tabId: tab.id }); render(); };
  $('#panel').onclick = () => { chrome.runtime.sendMessage({ type: 'open-panel', tabId: tab.id }); window.close(); };
  $('#follow').onclick = () => chrome.tabs.create({ url: chrome.runtime.getURL('hub.html') + '#follow' });
  $('#queue').onclick = () => chrome.tabs.create({ url: chrome.runtime.getURL('hub.html') + '#queue' });
  $('#hist').onclick = () => chrome.tabs.create({ url: chrome.runtime.getURL('hub.html') + '#history' });
  $('#opts').onclick = () => chrome.runtime.openOptionsPage();
  $('#player').onclick = () => chrome.tabs.create({ url: chrome.runtime.getURL('player.html') });
  $('#side').onclick = async () => { try { await chrome.sidePanel.open({ tabId: tab.id }); window.close(); } catch (e) { /* side panel not supported here */ } };
})();
