const $ = (s) => document.querySelector(s);
initTheme();
const ago = (t) => new Date(t).toLocaleString();
async function render() {
  const q = $('#q').value.toLowerCase();
  const all = (await chrome.storage.local.get('mgHist')).mgHist || [];
  const list = all.filter((e) => !q || ((e.title || '') + ' ' + (e.site || '') + ' ' + (e.kind || '')).toLowerCase().includes(q));
  $('#cnt').textContent = `(${list.length}/${all.length})`;
  const box = $('#list'); box.textContent = '';
  if (!list.length) { const d = document.createElement('div'); d.className = 'empty'; d.textContent = 'Nothing here yet.'; box.append(d); return; }
  for (const e of list.slice(0, 200)) {
    let gone = false;
    try { const [d] = await chrome.downloads.search({ id: e.id }); gone = !d || d.exists === false; } catch (x) { gone = true; }
    const row = document.createElement('div'); row.className = 'row' + (gone ? ' gone' : '');
    const mn = document.createElement('div'); mn.className = 'mn';
    const nm = document.createElement('div'); nm.className = 'nm'; nm.textContent = e.title || '(unnamed)'; nm.title = e.src || '';
    const mt = document.createElement('div'); mt.className = 'mt';
    mt.textContent = [String(e.kind || '').toUpperCase(), e.pages ? e.pages + ' pages' : '', e.size ? fmtSize(e.size) : '', e.site || '', ago(e.time), gone ? 'file removed' : ''].filter(Boolean).join(' | ');
    mn.append(nm, mt); row.append(mn);
    const mk = (t, fn) => { const b = document.createElement('button'); b.textContent = t; b.onclick = fn; row.append(b); };
    if (!gone) mk('Show file', () => chrome.downloads.show(e.id));
    if (!gone) mk('Delete file', async () => { if (!confirm('Delete this file from your disk?')) return; try { await chrome.downloads.removeFile(e.id); } catch (x) { /* already gone */ } render(); });
    mk('Remove', async () => { const h = (await chrome.storage.local.get('mgHist')).mgHist || []; await chrome.storage.local.set({ mgHist: h.filter((x) => !(x.id === e.id && x.time === e.time)) }); render(); });
    if (e.src && /^https?:/i.test(e.src)) mk('Source', () => chrome.tabs.create({ url: e.src }));
    box.append(row);
  }
}

// ---- unsaved live recordings: segments a crashed / closed recording left on disk (kept for 3 days) ----
async function recordings() {
  const out = [];
  try {
    if (!(navigator.storage && navigator.storage.getDirectory)) return out;
    const base = await (await navigator.storage.getDirectory()).getDirectoryHandle('mg-seg');
    for await (const [id, dir] of base.entries()) {
      if (dir.kind !== 'directory') continue;
      try {
        const names = []; let size = 0;
        for await (const [n, f] of dir.entries()) if (f.kind === 'file') names.push(n);
        if (!names.includes('meta.json')) continue;
        const meta = JSON.parse(await (await (await dir.getFileHandle('meta.json')).getFile()).text());
        if (Date.now() - (meta.upd || 0) < 90000) continue; // still being recorded by an open downloader tab
        const plan = liveRecoverPlan(meta, names);
        if (plan) out.push({ id, dir, meta, plan, size: meta.bytes || size });
      } catch (e) { /* unreadable folder: skip */ }
    }
  } catch (e) { /* no store yet */ }
  return out;
}
async function renderRec() {
  const box = $('#rec'); box.textContent = '';
  const list = await recordings();
  for (const r of list) {
    const row = document.createElement('div'); row.className = 'row rec';
    const mn = document.createElement('div'); mn.className = 'mn';
    const nm = document.createElement('div'); nm.className = 'nm'; nm.textContent = 'Unsaved recording: ' + (r.meta.title || 'live'); nm.title = r.meta.src || '';
    const mt = document.createElement('div'); mt.className = 'mt';
    mt.textContent = [r.plan.segs.length + ' segments', r.size ? fmtSize(r.size) : '', r.plan.ext.toUpperCase(), ago(r.meta.upd || r.meta.t0), 'plays in VLC / most players'].filter(Boolean).join(' | ');
    mn.append(nm, mt); row.append(mn);
    const mk = (t, fn) => { const b = document.createElement('button'); b.textContent = t; b.onclick = async () => { b.disabled = true; try { await fn(b); } catch (e) { alert('Failed: ' + (e.message || e)); } b.disabled = false; }; row.append(b); };
    mk('Recover', async (b) => {
      const files = [];
      if (r.plan.init) files.push(await (await r.dir.getFileHandle('init')).getFile());
      for (const n of r.plan.segs) files.push(await (await r.dir.getFileHandle(n)).getFile());
      const url = URL.createObjectURL(new Blob(files, { type: r.plan.mime }));
      const id = await chrome.downloads.download({ url, filename: r.plan.name, conflictAction: 'uniquify' });
      const onCh = (d) => { if (d.id === id && d.state && d.state.current !== 'in_progress') { chrome.downloads.onChanged.removeListener(onCh); setTimeout(() => URL.revokeObjectURL(url), 5000); } };
      chrome.downloads.onChanged.addListener(onCh);
      b.textContent = 'Saved - keep the data?';
    });
    mk('Discard', async () => { if (!confirm('Delete this unsaved recording from the browser storage?')) return; await (await (await navigator.storage.getDirectory()).getDirectoryHandle('mg-seg')).removeEntry(r.id, { recursive: true }); renderRec(); });
    box.append(row);
  }
}
$('#q').oninput = render;
$('#clr').onclick = async () => { if (confirm('Clear the whole history list? (files are not deleted)')) { await chrome.storage.local.remove('mgHist'); render(); } };
render();
renderRec();
