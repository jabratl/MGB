// Library hub: Downloads | History | Following | Settings in one page. Each tab is the existing page, loaded on first use
// and kept alive afterwards, so nothing about those pages changes. Links between them switch tabs instead of navigating.
const PAGES = { queue: 'queue.html', history: 'history.html', follow: 'follow.html', settings: 'options.html' };
const FILE_TO_TAB = { 'queue.html': 'queue', 'history.html': 'history', 'follow.html': 'follow', 'options.html': 'settings' };
const frames = {};
const $ = (s) => document.querySelector(s);
initTheme();

function frameFor(t) {
  if (frames[t]) return frames[t];
  const f = document.createElement('iframe');
  f.title = t; f.src = PAGES[t] + '?embed=1';
  f.addEventListener('load', () => {
    try { // same-origin: turn links to the sibling pages into tab switches
      f.contentDocument.addEventListener('click', (e) => {
        const a = e.target && e.target.closest && e.target.closest('a[href]');
        if (!a) return;
        const m = /^(queue|history|follow|options)\.html/.exec(a.getAttribute('href') || '');
        if (m) { e.preventDefault(); select(FILE_TO_TAB[m[1] + '.html']); }
      });
    } catch (err) { /* cross-origin: leave links alone */ }
  });
  $('#fr').append(f);
  return (frames[t] = f);
}
function select(t) {
  if (!PAGES[t]) t = 'queue';
  document.querySelectorAll('.tb').forEach((b) => { const on = b.dataset.t === t; b.classList.toggle('on', on); b.setAttribute('aria-selected', on); b.tabIndex = on ? 0 : -1; });
  Object.values(frames).forEach((f) => f.classList.remove('on'));
  frameFor(t).classList.add('on');
  try { localStorage.setItem('mg-hub-tab', t); } catch (e) { /* ignore */ }
  if (location.hash !== '#' + t) history.replaceState(null, '', '#' + t);
}
document.querySelectorAll('.tb').forEach((b) => b.addEventListener('click', () => select(b.dataset.t)));
// V70: keyboard - Left / Right / Home / End move between the tabs
try {
  $('#tabs').addEventListener('keydown', (e) => {
    const all = [...document.querySelectorAll('.tb')], i = all.indexOf(document.activeElement);
    if (i < 0 || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
    e.preventDefault();
    const n = e.key === 'Home' ? 0 : e.key === 'End' ? all.length - 1 : (i + (e.key === 'ArrowRight' ? 1 : -1) + all.length) % all.length;
    all[n].focus(); select(all[n].dataset.t);
  });
} catch (e) { /* ignore */ }
window.addEventListener('hashchange', () => select(location.hash.slice(1)));
let first = location.hash.slice(1);
try { first = first || localStorage.getItem('mg-hub-tab') || 'queue'; } catch (e) { first = first || 'queue'; }
select(first);

// number of running downloads on the Downloads tab
async function badge() {
  try {
    const jobs = (await chrome.storage.session.get('mgJobs')).mgJobs || {};
    const n = Object.values(jobs).filter((j) => j && j.state === 'run').length;
    const b = $('#bq'); b.hidden = !n; b.textContent = n;
  } catch (e) { /* storage.session not available */ }
}
badge(); setInterval(badge, 2500);
try { chrome.storage.onChanged.addListener((c, area) => { if (area === 'session' && c.mgJobs) badge(); }); } catch (e) { /* ignore */ }
