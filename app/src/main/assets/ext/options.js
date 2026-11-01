const $ = (id) => document.getElementById(id);
const IDS = Object.keys(DEFAULTS);
initTheme();
(async () => {
  const S = await getSettings();
  for (const k of IDS) {
    const e = $(k); if (!e) continue;
    if (e.type === 'checkbox') e.checked = !!S[k];
    else if (k === 'disabledSites') e.value = (S[k] || []).join('\n');
    else e.value = S[k];
    e.addEventListener('change', async () => {
      let v = e.type === 'checkbox' ? e.checked : e.value;
      if (e.type === 'number') v = Math.max(+e.min || 0, Math.min(+e.max || 1e9, parseInt(v, 10) || 0));
      if (k === 'disabledSites') v = v.split(/\s+/).map((x) => x.trim().toLowerCase()).filter(Boolean);
      await chrome.storage.sync.set({ [k]: v });
      $('saved').textContent = 'Saved.';
    });
  }
  const fill = (S2) => { for (const k of IDS) { const e = $(k); if (!e) continue; if (e.type === 'checkbox') e.checked = !!S2[k]; else if (k === 'disabledSites') e.value = (S2[k] || []).join('\n'); else e.value = S2[k]; } };
  $('exp').onclick = async () => {
    const all = await chrome.storage.sync.get(null);
    const blob = new Blob([JSON.stringify({ app: 'media-grabber', version: chrome.runtime.getManifest().version, build: chrome.runtime.getManifest().version_name, settings: all }, null, 2)], { type: 'application/json' });
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'media-grabber-settings.json';
    document.body.append(a); a.click(); setTimeout(() => { a.remove(); URL.revokeObjectURL(a.href); }, 3000);
    $('saved').textContent = 'Exported.';
  };
  $('imp').onclick = () => $('impf').click();
  $('impf').onchange = async () => {
    const f = $('impf').files[0]; if (!f) return;
    try {
      const j = JSON.parse(await f.text());
      const st = j && j.settings ? j.settings : j;
      const clean = {};
      for (const k of IDS) if (k in st) clean[k] = st[k]; // only known keys
      await chrome.storage.sync.set(clean);
      fill({ ...DEFAULTS, ...(await chrome.storage.sync.get(null)) });
      $('saved').textContent = 'Imported ' + Object.keys(clean).length + ' settings.';
    } catch (e) { $('saved').textContent = 'Import failed: ' + e.message; }
    $('impf').value = '';
  };
  $('rst').onclick = async () => {
    if (!confirm('Reset every setting to default?')) return;
    await chrome.storage.sync.clear(); fill(DEFAULTS); $('saved').textContent = 'Reset to default.';
  };
  const showLog = async () => { $('logbox').value = ((await chrome.storage.local.get('mgLog')).mgLog || []).join('\n'); };
  $('logcopy').onclick = async () => { await showLog(); $('logbox').select(); document.execCommand('copy'); $('saved').textContent = 'Log copied.'; };
  let healthText = '(not run)';
  $('rep').onclick = async () => {
    await showLog();
    try { await runHealth(); } catch (e) { /* optional */ }
    const st = await chrome.storage.sync.get(null);
    const txt = ['Media Grabber ' + chrome.runtime.getManifest().version + ' (' + (chrome.runtime.getManifest().version_name || '') + ')', 'Browser: ' + navigator.userAgent, 'Time: ' + new Date().toISOString(), '',
      'What works in this browser:', mgCapsText(mgCapabilities()), '', 'Health check:', healthText, '', 'Site memory:', JSON.stringify((await chrome.storage.local.get('mgSiteMem')).mgSiteMem || {}), '', 'Settings:', JSON.stringify(st, null, 1), '', 'Log:', $('logbox').value].join('\n');
    try { await navigator.clipboard.writeText(txt); $('saved').textContent = 'Report copied. Paste it when you report the problem (it holds your settings and recent events, no page contents).'; }
    catch (e) { $('logbox').value = txt; $('logbox').select(); document.execCommand('copy'); $('saved').textContent = 'Report copied.'; }
  };
  $('logclr').onclick = async () => { await chrome.storage.local.remove('mgLog'); showLog(); };
  showLog();
  try { // browser feature list (also saved so the on-page Diagnose can print it)
    const caps = mgCapabilities(); mgCapsSave();
    $('capl').innerHTML = caps.map((x) => '<li class="' + (x.ok ? 'y' : 'n') + '"><b>' + (x.ok ? 'yes' : 'no') + '</b><span>' + x.label + (x.ok ? '' : '<small>' + x.no + '</small>') + '</span></li>').join('');
  } catch (e) { /* the list is only informative */ }

  // ---------- V67: health check + what the extension remembers about sites (read-only views; Fix / Forget only touch what is shown) ----------
  const li = (ok, label, note, warn) => '<li class="' + (ok ? 'y' : 'n') + '"><b>' + (ok ? 'ok' : (warn ? 'check' : 'no')) + '</b><span>' + label + (note ? '<small>' + note + '</small>' : '') + '</span></li>';
  const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  async function runHealth() {
    const rows = [], plain = [];
    let problems = 0;
    const add = (ok, label, note, warn) => { rows.push(li(ok, label, ok ? '' : note, warn)); plain.push((ok ? 'ok     ' : 'PROBLEM') + ' ' + label + (ok ? '' : ' - ' + note)); if (!ok) problems++; };
    try { // browser features that the downloads depend on
      const caps = mgCapabilities(), hid = caps.find((x) => x.k === 'offscreen');
      add(!!(hid && hid.ok), 'Hidden background downloads', 'Not supported here - downloads use a small tab instead (works, just visible).', true);
    } catch (e) { add(false, 'Browser feature check', 'Could not run: ' + e.message, true); }
    try { // permissions
      const mf = chrome.runtime.getManifest();
      const ok = await chrome.permissions.contains({ permissions: (mf.permissions || []).filter((p) => p !== 'offscreen' || chrome.offscreen), origins: mf.host_permissions || [] });
      add(ok, 'Permissions', 'One or more permissions are off. Open the browser\'s extension page for Media Grabber and allow access to all sites.');
    } catch (e) { add(true, 'Permissions', ''); }
    let bad = [];
    try { // saved settings
      bad = mgHealthSettings(await chrome.storage.sync.get(null), DEFAULTS);
      add(!bad.length, 'Saved settings', bad.length + ' setting(s) hold a wrong value: ' + bad.map((b) => b.key + '=' + b.value).join(', ') + '. Press Fix to put them back to default.');
    } catch (e) { add(false, 'Saved settings', 'Could not be read: ' + e.message); }
    try { // storage room
      const u = await chrome.storage.local.getBytesInUse(null);
      add(u < 8 * 1024 * 1024, 'Local storage', 'Using ' + (u / 1048576).toFixed(1) + ' MB - clear History / old queue entries if downloads start to fail.', true);
    } catch (e) { /* not available in every browser */ }
    $('healthl').innerHTML = rows.join('');
    const tag = $('healthTag'); tag.textContent = problems ? problems + ' to check' : 'all good'; tag.className = 'tag ' + (problems ? 'bad' : 'ok');
    const old = $('healthFix'); if (old) old.remove();
    if (bad.length) {
      const b = document.createElement('button'); b.id = 'healthFix'; b.type = 'button'; b.textContent = 'Fix ' + bad.length + ' setting(s)';
      b.onclick = async () => { await chrome.storage.sync.remove(bad.map((x) => x.key)); fill({ ...DEFAULTS, ...(await chrome.storage.sync.get(null)) }); $('saved').textContent = 'Fixed.'; runHealth(); };
      $('healthAct').prepend(b);
    }
    healthText = plain.join('\n');
  }
  $('healthRun').onclick = () => runHealth();
  async function showMem() {
    const all = (await chrome.storage.local.get('mgSiteMem')).mgSiteMem || {}, hs = Object.keys(all).sort((a, b) => (all[b].t || 0) - (all[a].t || 0));
    $('meml').innerHTML = hs.length ? hs.map((h) => { const m = all[h]; return li(true, esc(h), 'page read worked ' + (m.html || 0) + 'x \u00b7 player tab worked ' + (m.sniff || 0) + 'x' + (m.front ? ' \u00b7 tab had to be shown ' + m.front + 'x' : '')).replace('<b>ok</b>', '<b>\u2022</b>'); }).join('') : '<li><span>Nothing yet. It fills while you download episode batches.</span></li>';
  }
  $('memClr').onclick = async () => { await chrome.storage.local.remove('mgSiteMem'); showMem(); $('saved').textContent = 'Forgotten.'; };
  runHealth().catch(() => {}); showMem().catch(() => {});
  let ok = false;
  try { ok = (await fetch(chrome.runtime.getURL('lib/mux.min.js'))).ok; } catch (e) { /* missing */ }
  $('mux').innerHTML = ok ? 'MP4 converter: <b>installed</b>.' :
    'MP4 converter: <b>not installed</b>. HLS .ts streams are saved as .ts (plays in VLC). For .mp4 output download <code>https://cdn.jsdelivr.net/npm/mux.js@7.0.3/dist/mux.min.js</code> and save it as <code>lib/mux.min.js</code> inside the extension folder, then reload the extension.';
})();
