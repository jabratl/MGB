// Settings page UI layer: tabs, search, quick presets (with undo), "reset this option", live file-name preview.
// It only reads / writes the same inputs and chrome.storage keys options.js already handles - no setting logic lives here.
(() => {
  const $ = (id) => document.getElementById(id);
  const rows = [...document.querySelectorAll('.row[data-k]')];
  const tabsEl = [...document.querySelectorAll('.tab')], btns = [...document.querySelectorAll('.tabbtn')];
  let stored = { ...DEFAULTS };

  // ---------- tabs ----------
  const TAB_KEY = 'mg-settings-tab';
  function showTab(name) {
    if (!tabsEl.some((t) => t.dataset.tab === name)) name = 'quick';
    tabsEl.forEach((t) => t.classList.toggle('on', t.dataset.tab === name));
    btns.forEach((b) => { b.classList.toggle('on', b.dataset.tab === name); b.setAttribute('aria-selected', b.dataset.tab === name); });
    try { localStorage.setItem(TAB_KEY, name); } catch (e) { /* ignore */ }
    if (location.hash !== '#' + name) history.replaceState(null, '', '#' + name);
  }
  btns.forEach((b) => b.addEventListener('click', () => { $('q').value = ''; setSearch(''); showTab(b.dataset.tab); window.scrollTo({ top: 0 }); }));
  let first = location.hash.slice(1);
  try { first = first || localStorage.getItem(TAB_KEY) || 'quick'; } catch (e) { first = first || 'quick'; }
  showTab(first);
  window.addEventListener('scroll', () => $('top').classList.toggle('sc', window.scrollY > 4), { passive: true });

  // ---------- search ----------
  function setSearch(q) {
    q = q.trim().toLowerCase();
    document.body.classList.toggle('searching', !!q);
    let n = 0;
    rows.forEach((r) => {
      const hit = !q || (r.textContent + ' ' + r.dataset.k).toLowerCase().includes(q);
      r.hidden = !hit; if (hit) n++;
    });
    document.body.classList.toggle('nores', !!q && !n);
    // V74g: "Advanced options" blocks open by themselves while a search has a hit inside; the old state comes back when the search is cleared
    document.querySelectorAll('details.adv').forEach((d) => {
      if (q) { if (d.dataset.was === undefined) d.dataset.was = d.open ? '1' : '0'; d.open = [...d.querySelectorAll('.row')].some((r) => !r.hidden); }
      else if (d.dataset.was !== undefined) { d.open = d.dataset.was === '1'; delete d.dataset.was; }
    });
    if (!q) showTab(location.hash.slice(1) || 'quick');
  }
  $('q').addEventListener('input', () => setSearch($('q').value));

  // ---------- helpers ----------
  const inputOf = (k) => $(k);
  const defVal = (k) => (Array.isArray(DEFAULTS[k]) ? DEFAULTS[k].join('\n') : DEFAULTS[k]);
  function curVal(k, e) {
    if (e.type === 'checkbox') return e.checked;
    if (k === 'disabledSites') return e.value.split(/\s+/).filter(Boolean).join('\n');
    if (e.type === 'number') return parseInt(e.value, 10) || 0;
    return e.value;
  }
  function same(k, e) {
    const c = curVal(k, e), d = defVal(k);
    return e.type === 'checkbox' ? c === !!d : String(c) === String(d);
  }
  function setDom(k, v) {
    const e = inputOf(k); if (!e) return;
    if (e.type === 'checkbox') e.checked = !!v;
    else if (Array.isArray(v)) e.value = v.join('\n');
    else e.value = v;
  }

  // ---------- "reset this option" buttons ----------
  rows.forEach((r) => {
    const k = r.dataset.k, e = inputOf(k);
    if (!e || !(k in DEFAULTS) || !['checkbox', 'text', 'number', 'select-one', 'textarea'].includes(e.type)) return;
    const b = document.createElement('button');
    b.className = 'rs'; b.type = 'button'; b.hidden = true; b.textContent = '\u21BA';
    b.title = 'Reset to default'; b.setAttribute('aria-label', 'Reset ' + k + ' to default');
    b.addEventListener('click', (ev) => { ev.preventDefault(); setDom(k, DEFAULTS[k]); e.dispatchEvent(new Event('change', { bubbles: true })); refresh(); });
    r.append(b);
  });
  function refreshBadges() {
    rows.forEach((r) => {
      const b = r.querySelector('.rs'), e = inputOf(r.dataset.k);
      if (b && e) b.hidden = same(r.dataset.k, e);
    });
  }

  // ---------- live file-name preview (uses the real naming code) ----------
  const EX = [
    ['Episode of a show (batch)', { title: 'Naruto Shippuden Episode 5', book: 'Naruto Shippuden Season 2', chapter: 'Episode 5', site: 'animixplay.cz', name: 'master', kind: 'video', res: '1080p', lang: '', index: '5' }, 'mp4'],
    ['Its subtitle', { title: 'Naruto Shippuden Episode 5', book: 'Naruto Shippuden Season 2', chapter: 'Episode 5', site: 'animixplay.cz', name: 'master', kind: 'subtitle', res: '', lang: 'en', index: '5', suffix: 'en' }, 'srt'],
    ['Single video', { title: 'Digimon Beatbreak Episode 49 English Subbed', site: 'animixplay.cz', name: 'index', kind: 'video', res: '720p', lang: '' }, 'mp4'],
    ['Manga chapter', { title: 'One Piece Chapter 12', book: 'One Piece', chapter: 'Chapter 12', site: 'mangadex.org', name: '', kind: 'pages', res: '', lang: '' }, 'cbz']
  ];
  function refreshPreview() {
    const box = $('pvList'); if (!box) return;
    const S = { ...DEFAULTS, ...stored };
    for (const k of ['nameTemplate', 'subfolder', 'libraryMode', 'padChapters']) { const e = inputOf(k); if (e) S[k] = curVal(k, e); }
    box.textContent = '';
    for (const [label, v, ext] of EX) {
      let name;
      try { name = 'Downloads/' + buildFileName(S, v, ext); } catch (e) { name = '(could not build a name)'; }
      const d = document.createElement('div'); d.className = 'pv';
      const b = document.createElement('b'); b.textContent = label;
      const c = document.createElement('code'); c.textContent = name;
      d.append(b, c); box.append(d);
    }
  }
  function refresh() { refreshBadges(); refreshPreview(); }
  document.addEventListener('input', refresh);
  document.addEventListener('change', () => { refresh(); const s = $('saved'); s.classList.add('ok'); setTimeout(() => s.classList.remove('ok'), 1500); });

  // ---------- presets (apply together, show what changed, undo) ----------
  const PRESET_KEYS = ['libraryMode', 'subfolder', 'nameTemplate', 'autoSubs', 'subLangs', 'mergeAV', 'convertMp4', 'maxHeight', 'padChapters', 'skipDone', 'followHours',
    'blockAds', 'siteAdapters', 'speedLimitKBps', 'concurrency', 'maxJobs', 'batchParallel', 'requestDelayMs'];
  const PRESETS = {
    anime: { label: 'Anime (Plex)', patch: { libraryMode: 'plex', subfolder: 'Anime', autoSubs: true, subLangs: 'en', mergeAV: true, convertMp4: true, maxHeight: 1080 } },
    manga: { label: 'Manga', patch: { subfolder: 'Manga', libraryMode: 'off', nameTemplate: '{title}', padChapters: true, skipDone: true, followHours: 6, blockAds: true, siteAdapters: true } },
    lowdata: { label: 'Low data', patch: { maxHeight: 480, speedLimitKBps: 800, concurrency: 3, maxJobs: 1, batchParallel: 1 } },
    fast: { label: 'Fast', patch: { maxHeight: 0, speedLimitKBps: 0, concurrency: 12, maxJobs: 3, batchParallel: 3, requestDelayMs: 0 } },
    default: { label: 'Recommended', patch: Object.fromEntries(PRESET_KEYS.map((k) => [k, DEFAULTS[k]])) }
  };
  const NICE = { libraryMode: 'Library naming', subfolder: 'Folder', nameTemplate: 'File name', autoSubs: 'Subtitles', subLangs: 'Subtitle languages', mergeAV: 'Merge audio+video', convertMp4: 'Convert to MP4', maxHeight: 'Max quality', padChapters: 'Pad chapter numbers', skipDone: 'Skip downloaded', followHours: 'Follow check (hours)', blockAds: 'Skip ads', siteAdapters: 'Site adapters', speedLimitKBps: 'Speed limit (KB/s)', concurrency: 'Parallel segments', maxJobs: 'Jobs at once', batchParallel: 'Episodes searched at once', requestDelayMs: 'Delay between requests (ms)' };
  const show = (v, k) => (k === 'libraryMode' ? (v === 'plex' ? 'Plex' : 'off') : k === 'maxHeight' ? (+v ? v + 'p' : 'highest') : v === true ? 'on' : v === false ? 'off' : v === '' ? '(empty)' : String(v));
  async function applyPatch(key) {
    const P = PRESETS[key]; if (!P) return;
    const keys = Object.keys(P.patch).filter((k) => k in DEFAULTS);
    const before = await chrome.storage.sync.get(keys);
    const prev = { ...Object.fromEntries(keys.map((k) => [k, DEFAULTS[k]])), ...before };
    const changed = keys.filter((k) => String(prev[k]) !== String(P.patch[k]));
    await chrome.storage.sync.set(Object.fromEntries(keys.map((k) => [k, P.patch[k]])));
    keys.forEach((k) => setDom(k, P.patch[k]));
    stored = { ...stored, ...P.patch };
    refresh();
    const m = $('presetMsg'); m.hidden = false; m.textContent = '';
    const t = document.createElement('div');
    const strong = document.createElement('b'); strong.textContent = P.label + ' applied. ';
    t.append(strong, document.createTextNode(changed.length ? changed.map((k) => (NICE[k] || k) + ': ' + show(prev[k], k) + ' \u2192 ' + show(P.patch[k], k)).join(' \u00b7 ') : 'Nothing needed to change.'));
    m.append(t);
    if (changed.length) {
      const u = document.createElement('button'); u.type = 'button'; u.textContent = 'Undo';
      u.addEventListener('click', async () => {
        await chrome.storage.sync.remove(keys.filter((k) => !(k in before)));
        await chrome.storage.sync.set(before);
        keys.forEach((k) => setDom(k, k in before ? before[k] : DEFAULTS[k]));
        stored = { ...stored, ...prev };
        refresh(); m.textContent = 'Undone.'; setTimeout(() => { m.hidden = true; }, 2000);
      });
      m.append(u);
    }
    $('saved').textContent = 'Saved.';
  }
  document.querySelectorAll('[data-p]').forEach((b) => b.addEventListener('click', () => applyPatch(b.dataset.p)));

  // ---------- start ----------
  (async () => {
    try { stored = await getSettings(); } catch (e) { /* defaults */ }
    setTimeout(refresh, 60); setTimeout(refresh, 400); // options.js fills the inputs in the meantime
  })();

  // V74f: arrow keys move between the setting tabs (Up / Left = previous, Down / Right = next, Home / End)
  const tabNav = document.querySelector('.tabs');
  if (tabNav) tabNav.addEventListener('keydown', (e) => {
    const k = e.key; if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(k)) return;
    const i = btns.indexOf(document.activeElement); if (i < 0) return;
    e.preventDefault();
    const n = k === 'Home' ? 0 : k === 'End' ? btns.length - 1 : (i + (k === 'ArrowDown' || k === 'ArrowRight' ? 1 : -1) + btns.length) % btns.length;
    btns[n].focus(); btns[n].click();
  });
})();
