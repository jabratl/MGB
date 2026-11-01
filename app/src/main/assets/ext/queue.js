// Media Grabber - queue page: live list of downloads with progress, speed, ETA, pause/resume, cancel
const $ = (s) => document.querySelector(s);
initTheme();
const snap = {}; // key -> {t, pct, rate}
const fmtEta = (s) => (!isFinite(s) || s <= 0 || s > 86400 ? '' : s >= 3600 ? Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm' : s >= 60 ? Math.floor(s / 60) + 'm ' + Math.round(s % 60) + 's' : Math.round(s) + 's');

function etaOf(j) {
  const now = Date.now(), p = j.pct || 0, s = snap[j.key] || (snap[j.key] = { t: now, pct: p, rate: 0 });
  if (now - s.t >= 1500) { const r = (p - s.pct) / ((now - s.t) / 1000); s.rate = s.rate ? s.rate * 0.6 + r * 0.4 : r; s.t = now; s.pct = p; }
  return j.state === 'run' && !j.paused && s.rate > 0 ? fmtEta((1 - p) / s.rate) : '';
}

const SV = {
  pause: '<svg viewBox="0 0 24 24"><rect x="6" y="5" width="4.2" height="14" rx="1.2"/><rect x="13.8" y="5" width="4.2" height="14" rx="1.2"/></svg>',
  play: '<svg viewBox="0 0 24 24"><path d="M8 5.5v13a1 1 0 001.5.86l10.5-6.5a1 1 0 000-1.72L9.5 4.64A1 1 0 008 5.5z"/></svg>',
  x: '<svg viewBox="0 0 24 24" style="fill:none;stroke:currentColor;stroke-width:2.4;stroke-linecap:round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  retry: '<svg viewBox="0 0 24 24" style="fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round"><path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20 4v5h-5"/></svg>',
};
function row(j) {
  const d = document.createElement('div');
  const run = j.state === 'run';
  d.className = 'job ' + (j.state || '') + (run && j.paused ? ' paused' : '');
  const top = document.createElement('div'); top.className = 'top';
  const nm = document.createElement('div'); nm.className = 'nm'; nm.textContent = j.title || j.key; nm.title = j.key;
  top.append(nm);
  if (j.kind) { const t = document.createElement('span'); t.className = 'tag'; t.textContent = String(j.kind).toUpperCase(); top.append(t); }
  const pc = document.createElement('span'); pc.className = 'pct'; pc.textContent = /Recording live/.test(j.text || '') && j.state === 'run' ? 'LIVE' : Math.round((j.pct || 0) * 100) + '%'; top.append(pc);
  const mk = (cls, svg, title, fn) => { const b = document.createElement('button'); b.className = 'ib ' + cls; b.innerHTML = svg; b.title = title; b.setAttribute('aria-label', title); b.onclick = fn; top.append(b); };
  const isLive = run && /Recording live/.test(j.text || '');
  if (isLive) mk('pz', '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>', 'Stop and save the recording', () => chrome.runtime.sendMessage({ type: 'job-stop', key: j.key }));
  if (run) {
    mk('pz', j.paused ? SV.play : SV.pause, j.paused ? 'Resume' : 'Pause', () => chrome.runtime.sendMessage({ type: 'job-pause', key: j.key, on: !j.paused }));
    mk('cx', SV.x, 'Cancel', () => chrome.runtime.sendMessage({ type: 'job-cancel', key: j.key }));
  }
  if (j.state === 'error' && j.params) mk('pz', SV.retry, 'Retry (starts again from the beginning)', () => chrome.runtime.sendMessage({ type: 'job-retry', key: j.key }));
  const bar = document.createElement('div'); bar.className = 'bar'; bar.setAttribute('role', 'progressbar'); bar.setAttribute('aria-valuemin', '0'); bar.setAttribute('aria-valuemax', '100'); bar.setAttribute('aria-valuenow', String(Math.round(Math.max(0, Math.min(1, j.pct || 0)) * 100)));
  const i = document.createElement('i'); i.style.width = Math.round(Math.max(0, Math.min(1, j.pct || 0)) * 100) + '%'; bar.append(i);
  const mt = document.createElement('div'); mt.className = 'mt';
  const l = document.createElement('span'); l.textContent = (run && j.paused ? 'Paused - ' : '') + (j.text || '').replace(/^(Paused|Resumed)\s*-?\s*/, '');
  const r = document.createElement('span'); r.className = 'st'; const eta = etaOf(j);
  r.textContent = run && j.paused ? 'Paused' : [eta ? 'ETA ' + eta : '', run ? 'Downloading' : j.state === 'done' ? 'Finished' : j.state === 'error' ? 'Failed' : ''].filter(Boolean).join(' \u00b7 ');
  mt.append(l, r);
  d.append(top, bar, mt);
  return d;
}

async function render() {
  const jobs = Object.values((await chrome.storage.session.get('mgJobs')).mgJobs || {}).sort((a, b) => (b.state === 'run') - (a.state === 'run') || b.updated - a.updated);
  const box = $('#list'); box.textContent = '';
  $('#cnt').textContent = `(${jobs.filter((j) => j.state === 'run').length} running, ${jobs.length} total)`;
  const sched = ((await chrome.storage.local.get('mgSched')).mgSched || []).sort((a, b) => a.when - b.when);
  sched.forEach((e) => {
    const d = document.createElement('div'); d.className = 'job';
    const top = document.createElement('div'); top.className = 'top';
    const nm = document.createElement('div'); nm.className = 'nm'; nm.textContent = (e.title || e.itemUrl); nm.title = e.itemUrl;
    const tag = document.createElement('span'); tag.className = 'tag'; tag.textContent = 'SCHEDULED';
    const pc = document.createElement('span'); pc.className = 'pct'; pc.textContent = new Date(e.when).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' });
    const cx = document.createElement('button'); cx.className = 'ib cx'; cx.innerHTML = SV.x; cx.title = 'Cancel the schedule'; cx.setAttribute('aria-label', 'Cancel the schedule'); cx.onclick = () => { chrome.runtime.sendMessage({ type: 'sched-cancel', id: e.id }); setTimeout(render, 200); };
    top.append(nm, tag, pc, cx); d.append(top); box.append(d);
  });
  if (!jobs.length && !sched.length) { const e = document.createElement('div'); e.className = 'empty'; e.textContent = 'No downloads yet. Start one from the Media Grabber button on a page.'; box.append(e); return; }
  jobs.forEach((j) => box.append(row(j)));
}
$('#clr').onclick = async () => { await chrome.runtime.sendMessage({ type: 'jobs-clear' }); setTimeout(render, 150); };
chrome.storage.onChanged.addListener((c, a) => { if (a === 'session' && c.mgJobs) render(); });
setInterval(render, 2000);
render();

// ---- episode batch card: live state written by the background (works even when the page panel gets no messages) ----
let askShown = '';
const EPB = { wait: ['Waiting', ''], find: ['Looking for video...', 'run'], sniff: ['Opening player...', 'run'], queued: ['Queued', 'ok'], skip: ['Already downloaded', ''], miss: ['No video found', 'err'], noq: ['Quality missing', 'err'] };
async function renderBatch() {
  const box = $('#batch');
  let b = null;
  try { b = (await chrome.storage.local.get('mgBatchLive')).mgBatchLive; } catch (e) { /* ignore */ }
  if (!b || (!b.running && !b.interrupted && Date.now() - (b.t || 0) > 6 * 3600e3) || b.dismissed) { box.textContent = ''; askShown = ''; return; }
  if (b.ask && askShown === b.ask.id && box.querySelector('.bask')) { // keep the open question untouched, only refresh the text above it
    const sub = box.querySelector('.sub'); if (sub) sub.textContent = b.text || '';
    return;
  }
  const done = Object.values(b.eps || {}).filter((e) => ['queued', 'skip', 'miss', 'noq'].includes(e.st)).length;
  box.textContent = '';
  const card = document.createElement('div'); card.className = 'bcard';
  const h = document.createElement('h2');
  const t = document.createElement('span'); t.textContent = (b.title ? b.title + ' - ' : '') + 'Episode batch';
  const tg = document.createElement('span'); tg.className = 'tag'; tg.style.flex = 'none';
  tg.textContent = b.running ? 'RUNNING ' + (b.idx || 0) + '/' + b.total : b.interrupted ? 'INTERRUPTED' : b.stopped ? 'STOPPED' : 'FINISHED';
  h.append(t, tg);
  const sub = document.createElement('div'); sub.className = 'sub'; sub.setAttribute('aria-live', 'polite'); sub.textContent = b.text || '';
  const bar = document.createElement('div'); bar.className = 'bar'; const bi = document.createElement('i'); bi.style.width = Math.round((done / Math.max(1, b.total)) * 100) + '%'; bar.append(bi);
  const cnt = document.createElement('div'); cnt.className = 'mt';
  cnt.innerHTML = ''; const c1 = document.createElement('span'); c1.textContent = `${b.ok || 0} queued of ${b.total}` + (b.cap ? ` \u00b7 quality ${b.cap}p` : ' \u00b7 best quality'); cnt.append(c1);
  card.append(h, sub, bar, cnt);
  // V66: why some episodes failed, grouped, with what to do (only after the batch ended)
  if (!b.running && Array.isArray(b.summary) && b.summary.length) {
    const sm = document.createElement('div'); sm.className = 'bsum';
    b.summary.forEach((x) => {
      const r = document.createElement('div'); r.className = 'bsr';
      const t1 = document.createElement('b'); t1.textContent = `${x.n} episode${x.n > 1 ? 's' : ''}: ${x.text}`;
      const t2 = document.createElement('div'); t2.className = 'act'; t2.textContent = x.act + (x.labels && x.labels.length ? ' \u00b7 ' + x.labels.join(', ') + (x.n > x.labels.length ? '...' : '') : '');
      r.append(t1, t2); sm.append(r);
    });
    card.append(sm);
    // V69: tell the user that the connection problems are handled by themselves
    if (!b.netRetried && b.summary.some((x) => x.cat === 'network')) { const nh = document.createElement('div'); nh.className = 'sub'; nh.textContent = 'Connection problem: these episodes are retried by themselves when the internet is back (or press Retry failed).'; card.append(nh); }
  }
  if (b.ask) {
    askShown = b.ask.id;
    const a = b.ask, q = document.createElement('div'); q.className = 'bask';
    const near = [...(a.avail || [])].sort((x, y) => Math.abs(x - a.want) - Math.abs(y - a.want) || x - y);
    const tt = document.createElement('b'); tt.textContent = `${a.label}: ${a.want}p is not available`;
    const ds = document.createElement('div'); ds.style.cssText = 'color:var(--mut);font-size:12.5px'; ds.textContent = 'Available: ' + near.map((x) => x + 'p').join(', ') + '. Which one should be downloaded?';
    const qb = document.createElement('div'); qb.className = 'qb';
    const all = document.createElement('label'); const cb = document.createElement('input'); cb.type = 'checkbox'; all.append(cb, ' Do the same for the rest of this batch');
    const answer = (hh) => { const mode = !cb.checked ? '' : hh === 0 ? 'skip' : hh < a.want ? 'low' : 'high'; chrome.runtime.sendMessage({ type: 'batch-ask-answer', id: a.id, h: hh, mode }); askShown = ''; setTimeout(renderBatch, 300); };
    near.forEach((hh, i) => { const bt = document.createElement('button'); bt.textContent = `Download ${hh}p` + (i === 0 ? ' (closest)' : hh > a.want ? ' (higher)' : ' (lower)'); bt.onclick = () => answer(hh); qb.append(bt); });
    const sk = document.createElement('button'); sk.textContent = 'Skip this episode'; sk.onclick = () => answer(0); qb.append(sk);
    q.append(tt, ds, qb, all); card.append(q);
  } else askShown = '';
  const acts = document.createElement('div'); acts.className = 'acts';
  if (b.running) { const st = document.createElement('button'); st.textContent = 'Stop after this episode'; st.onclick = () => { chrome.runtime.sendMessage({ type: 'batch-stop', tabId: b.tab }); }; acts.append(st); }
  else {
    if (b.interrupted || b.stopped) { const rs = document.createElement('button'); rs.textContent = 'Resume unfinished'; rs.onclick = () => { chrome.runtime.sendMessage({ type: 'batch-resume' }); setTimeout(renderBatch, 400); }; acts.append(rs); }
    const nbad = (b.miss || 0) + (b.noq || 0);
    if (!b.interrupted && !b.stopped && nbad > 0) { const rf = document.createElement('button'); rf.textContent = `Retry failed (${nbad})`; rf.onclick = () => { chrome.runtime.sendMessage({ type: 'batch-resume' }); setTimeout(renderBatch, 400); }; acts.append(rf); }
    const dm = document.createElement('button'); dm.textContent = 'Hide'; dm.onclick = async () => { b.dismissed = true; await chrome.storage.local.set({ mgBatchLive: b }); renderBatch(); }; acts.append(dm);
  }
  card.append(acts);
  const list = document.createElement('div'); list.className = 'beps';
  Object.entries(b.eps || {}).sort((x, y) => (x[1].n || 0) - (y[1].n || 0)).forEach(([u, e]) => {
    const r = document.createElement('div'); r.className = 'bep';
    const l = document.createElement('span'); l.className = 'l'; l.textContent = e.label || u; l.title = e.why || u;
    const bd = document.createElement('span'); const info = EPB[e.st] || [e.st, '']; bd.className = 'b ' + info[1];
    bd.textContent = info[0] + (e.q ? ' \u00b7 ' + e.q : ''); if (e.plain || e.why) bd.title = e.plain || e.why;
    r.append(l, bd); list.append(r);
  });
  card.append(list);
  box.append(card);
}
chrome.storage.onChanged.addListener((c, a) => { if (a === 'local' && c.mgBatchLive) renderBatch(); });
setInterval(renderBatch, 3000);
renderBatch();

// V69: when the connection comes back, retry ONCE the batch episodes that failed because of the connection. Same action as the "Retry failed" button.
// Only while this page is open, only after the batch ended, never when it was stopped / interrupted. Switch: Settings > Downloads > "Retry by itself when the connection comes back".
window.addEventListener('online', async () => {
  try {
    if ((await getSettings()).autoNetRetry === false) return;
    const b = (await chrome.storage.local.get('mgBatchLive')).mgBatchLive;
    if (!b || b.running || b.interrupted || b.stopped || b.dismissed || b.netRetried) return;
    if (Date.now() - (b.t || 0) > 6 * 3600e3) return;
    if (!Array.isArray(b.summary) || !b.summary.some((x) => x && x.cat === 'network')) return;
    b.netRetried = true;
    await chrome.storage.local.set({ mgBatchLive: b });
    chrome.runtime.sendMessage({ type: 'batch-resume' });
    setTimeout(renderBatch, 600);
  } catch (e) { /* ignore: the Retry failed button still works */ }
});
