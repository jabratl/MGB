// Media Grabber - followed manga: new chapters, open, mark read, unfollow
const $ = (s) => document.querySelector(s);
initTheme();
const load = async () => (await chrome.storage.local.get('mgFollow')).mgFollow || {};
const save = (r) => chrome.storage.local.set({ mgFollow: r });

async function render() {
  const r = await load(), keys = Object.keys(r).sort((a, b) => (r[b].unread || []).length - (r[a].unread || []).length);
  const box = $('#list'); box.textContent = '';
  if (!keys.length) { const e = document.createElement('div'); e.className = 'empty'; e.textContent = 'Nothing followed yet.'; box.append(e); return; }
  for (const k of keys) {
    const f = r[k], un = f.unread || [];
    const d = document.createElement('div'); d.className = 'f';
    const top = document.createElement('div'); top.className = 'top';
    const nm = document.createElement('div'); nm.className = 'nm'; nm.textContent = f.title || k; nm.title = k;
    top.append(nm);
    if (un.length) { const n = document.createElement('span'); n.className = 'new'; n.textContent = un.length + ' new'; top.append(n); }
    const mk = (t, fn) => { const b = document.createElement('button'); b.textContent = t; b.onclick = fn; top.append(b); };
    mk('Open', () => chrome.tabs.create({ url: f.url }));
    if (un.length) mk('Mark read', async () => { const c = await load(); if (c[k]) c[k].unread = []; await save(c); render(); });
    mk('Unfollow', async () => { const c = await load(); delete c[k]; await save(c); render(); });
    const mt = document.createElement('div'); mt.className = 'mt';
    mt.textContent = [f.host, f.checked ? 'checked ' + new Date(f.checked).toLocaleString() : 'not checked yet'].filter(Boolean).join(' | ');
    d.append(top, mt);
    if (f.error) { const e = document.createElement('div'); e.className = 'mt err'; e.textContent = 'Last check failed: ' + f.error + (diagnose(f.error) ? ' - ' + diagnose(f.error) : ''); d.append(e); }
    if (un.length) {
      const ul = document.createElement('div'); ul.className = 'ul';
      un.slice(-15).reverse().forEach((c) => { const a = document.createElement('a'); a.href = c.url; a.target = '_blank'; a.textContent = c.label; ul.append(a); });
      d.append(ul);
    }
    box.append(d);
  }
}
$('#chk').onclick = async () => {
  $('#chk').disabled = true; $('#msg').textContent = 'Checking...';
  const res = await chrome.runtime.sendMessage({ type: 'follow-check' }).catch(() => null);
  $('#msg').textContent = res ? `${res.n} new chapter(s) found` : 'Check failed';
  $('#chk').disabled = false; render();
};
chrome.storage.onChanged.addListener((c, a) => { if (a === 'local' && c.mgFollow) render(); });
render();
