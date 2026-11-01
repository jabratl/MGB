// Media Grabber - built-in player: local files, direct links, playlist, PiP, keyboard shortcuts.
// HLS (.m3u8) plays when lib/hls.min.js (hls.js) is present, the same way lib/mux.min.js is optional for MP4 conversion.
const $ = (s) => document.querySelector(s);
initTheme();
const v = $('#v');
let list = [], cur = -1, hls = null;
const LS = { get(k, d) { try { const x = localStorage.getItem('mgp_' + k); return x == null ? d : JSON.parse(x); } catch (e) { return d; } }, set(k, x) { try { localStorage.setItem('mgp_' + k, JSON.stringify(x)); } catch (e) { /* ignore */ } } };
v.volume = LS.get('vol', 1); v.muted = LS.get('mute', false);
let speed = LS.get('speed', 1); v.playbackRate = speed;
const showSpeed = () => { $('#spd').textContent = speed + 'x'; };
showSpeed();
v.addEventListener('volumechange', () => { LS.set('vol', v.volume); LS.set('mute', v.muted); });

const nameOf = (u) => { try { return decodeURIComponent(new URL(u).pathname.split('/').filter(Boolean).pop() || u); } catch (e) { return u; } };
function render() {
  const box = $('#list'); box.textContent = '';
  if (!list.length) { const e = document.createElement('div'); e.id = 'empty'; e.textContent = 'Playlist is empty. Open files, drop them here, or paste a link.'; box.append(e); return; }
  list.forEach((it, i) => {
    const d = document.createElement('div'); d.className = 'it' + (i === cur ? ' on' : '');
    const n = document.createElement('span'); n.className = 'n'; n.textContent = it.name; n.title = it.name;
    const x = document.createElement('button'); x.className = 'x'; x.textContent = '\u00d7'; x.title = 'Remove';
    x.onclick = (e) => { e.stopPropagation(); remove(i); };
    d.onclick = () => play(i); d.append(n, x); box.append(d);
  });
}
function remove(i) {
  const [it] = list.splice(i, 1); if (it && it.blob) URL.revokeObjectURL(it.src);
  if (i < cur) cur--; else if (i === cur) { cur = -1; v.removeAttribute('src'); v.load(); $('#now').textContent = 'Nothing playing'; if (list.length) return play(Math.min(i, list.length - 1)); }
  render();
}
async function loadHls() {
  if (window.Hls) return true;
  try { if (!(await fetch(chrome.runtime.getURL('lib/hls.min.js'))).ok) return false; } catch (e) { return false; }
  return new Promise((res) => { const s = document.createElement('script'); s.src = 'lib/hls.min.js'; s.onload = () => res(!!window.Hls); s.onerror = () => res(false); document.head.append(s); });
}
async function play(i) {
  if (i < 0 || i >= list.length) return;
  cur = i; const it = list[i];
  if (hls) { try { hls.destroy(); } catch (e) { /* ignore */ } hls = null; }
  $('#now').textContent = it.name; document.title = it.name + ' - Media Grabber';
  if (/\.m3u8(\?|$)/i.test(it.src)) {
    if (await loadHls()) { hls = new Hls(); hls.loadSource(it.src); hls.attachMedia(v); }
    else if (v.canPlayType('application/vnd.apple.mpegurl')) v.src = it.src; // phone browsers play HLS natively, no hls.js needed
    else { $('#now').textContent = 'This browser cannot play HLS by itself: save hls.js as lib/hls.min.js in the extension folder and reload the extension.'; render(); return; }
  } else v.src = it.src;
  v.playbackRate = speed;
  v.play().catch(() => {});
  render();
}
function addFiles(files, playNow) {
  const first = list.length;
  [...files].forEach((f) => list.push({ name: f.name, src: URL.createObjectURL(f), blob: true }));
  render();
  if (playNow && list.length > first) play(first);
}
function addUrl(u, title) {
  u = String(u || '').trim(); if (!/^https?:/i.test(u)) return;
  list.push({ name: title || nameOf(u), src: u }); render(); if (cur < 0) play(list.length - 1);
}
v.addEventListener('ended', () => { if (cur + 1 < list.length) play(cur + 1); });
v.addEventListener('error', () => { $('#now').textContent = (list[cur] ? list[cur].name + ' - ' : '') + 'cannot be played here (format or access problem)'; });

$('#open').onclick = () => $('#files').click();
$('#files').onchange = (e) => { addFiles(e.target.files, cur < 0); e.target.value = ''; };
$('#go').onclick = () => { addUrl($('#url').value); $('#url').value = ''; };
$('#url').onkeydown = (e) => { if (e.key === 'Enter') $('#go').click(); e.stopPropagation(); };
$('#clr').onclick = () => { list.forEach((it) => it.blob && URL.revokeObjectURL(it.src)); list = []; cur = -1; v.removeAttribute('src'); v.load(); $('#now').textContent = 'Nothing playing'; render(); };
$('#next').onclick = () => play(cur + 1);
$('#prev').onclick = () => play(cur - 1);
async function pip() { try { if (document.pictureInPictureElement) await document.exitPictureInPicture(); else await v.requestPictureInPicture(); } catch (e) { $('#now').textContent = 'Picture in picture is not available for this video.'; } }
$('#pip').onclick = pip;

// drag and drop
let dragN = 0;
addEventListener('dragenter', (e) => { e.preventDefault(); dragN++; $('#drop').style.display = 'flex'; });
addEventListener('dragleave', () => { if (--dragN <= 0) { dragN = 0; $('#drop').style.display = 'none'; } });
addEventListener('dragover', (e) => e.preventDefault());
addEventListener('drop', (e) => { e.preventDefault(); dragN = 0; $('#drop').style.display = 'none'; if (e.dataTransfer.files.length) addFiles(e.dataTransfer.files, cur < 0); });

// keyboard
const seek = (d) => { v.currentTime = Math.max(0, Math.min(v.duration || 1e9, v.currentTime + d)); };
const setSpeed = (x) => { speed = Math.max(0.25, Math.min(4, Math.round(x * 100) / 100)); v.playbackRate = speed; LS.set('speed', speed); showSpeed(); };
const fs = () => { if (document.fullscreenElement) document.exitFullscreen(); else $('#stage').requestFullscreen().catch(() => {}); };
addEventListener('keydown', (e) => {
  if (e.ctrlKey || e.metaKey || e.altKey || /^(INPUT|TEXTAREA)$/.test(e.target.tagName)) return;
  const k = e.key; let used = true;
  if (k === ' ' || k === 'k') (v.paused ? v.play() : v.pause());
  else if (k === 'ArrowRight') seek(e.shiftKey ? 30 : 10);
  else if (k === 'ArrowLeft') seek(e.shiftKey ? -30 : -10);
  else if (k === 'ArrowUp') v.volume = Math.min(1, v.volume + 0.05);
  else if (k === 'ArrowDown') v.volume = Math.max(0, v.volume - 0.05);
  else if (k === 'm' || k === 'M') v.muted = !v.muted;
  else if (k === 'f' || k === 'F') fs();
  else if (k === 'p' || k === 'P') pip();
  else if (k === 'n' || k === 'N' || k === 'PageDown') play(cur + 1);
  else if (k === 'b' || k === 'B' || k === 'PageUp') play(cur - 1);
  else if (k === ']') setSpeed(speed + 0.25);
  else if (k === '[') setSpeed(speed - 0.25);
  else if (k === 'Home') v.currentTime = 0;
  else if (k === 'End') v.currentTime = Math.max(0, (v.duration || 0) - 0.5);
  else if (/^[0-9]$/.test(k) && v.duration) v.currentTime = v.duration * (+k / 10);
  else used = false;
  if (used) e.preventDefault();
});
// double click: left / right side seek, centre fullscreen
v.addEventListener('dblclick', (e) => {
  const r = v.getBoundingClientRect(), x = (e.clientX - r.left) / r.width;
  if (x < 0.33) seek(-10); else if (x > 0.67) seek(10); else fs();
});

// opened with ?url=... (for example from another page)
const qp = new URLSearchParams(location.search);
// the site often wants its own Referer for the video files: add it for requests of this tab only
async function setupRef(ref) {
  if (!/^https?:/i.test(ref || '')) return;
  try {
    const me = await chrome.tabs.getCurrent(), id = 3000000 + (me ? me.id : Math.floor(Math.random() * 1e5));
    await chrome.declarativeNetRequest.updateSessionRules({
      removeRuleIds: [id],
      addRules: [{ id, priority: 2, action: { type: 'modifyHeaders', requestHeaders: [{ header: 'Referer', operation: 'set', value: ref }] },
        condition: { initiatorDomains: [chrome.runtime.id], resourceTypes: ['xmlhttprequest', 'media', 'other'], ...(me ? { tabIds: [me.id] } : {}) } }]
    });
    addEventListener('pagehide', () => { try { chrome.declarativeNetRequest.updateSessionRules({ removeRuleIds: [id] }); } catch (e) { /* ignore */ } });
  } catch (e) { console.warn('referer rule failed', e); }
}
render();
(async () => { if (qp.get('url')) { await setupRef(qp.get('ref')); addUrl(qp.get('url'), qp.get('title')); } })();
