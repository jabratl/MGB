// Media Grabber - Telegram Web module (world: MAIN). Telegram streams videos through its own service worker, so the extension
// cannot fetch them. This adds a "Download video" button that fetches the video from inside the page, in 512 KB range requests,
// and writes it straight to disk (Save dialog) or, where that is not available, to a file after the last chunk.
(() => {
  if (window.__mgTg) return;
  window.__mgTg = 1;
  const CHUNK = 512 * 1024;
  let btn = null, busy = false, resetT = 0;
  const off = () => { try { return document.documentElement.dataset.mgSite === 'off'; } catch (e) { return false; } };
  const srcOf = (v) => { const s = v.currentSrc || v.src || ''; return /\/(stream|progressive)\//.test(s) ? s : ''; };
  const shown = (v) => { const r = v.getBoundingClientRect(); return r.width > 80 && r.height > 80 && r.bottom > 0 && r.top < innerHeight; };
  function target() {
    const vs = [...document.querySelectorAll('video')].filter((v) => srcOf(v) && shown(v));
    vs.sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight);
    return vs[0] || null;
  }
  function meta(url) {
    try { const t = decodeURIComponent(url.split(/\/(?:stream|progressive)\//)[1] || ''); const j = JSON.parse(t.replace(/^[^{]*/, '')); return { name: j.fileName || '', size: +j.size || 0 }; }
    catch (e) { return { name: '', size: 0 }; }
  }
  const label = (t) => { if (btn) btn.textContent = t; };
  async function run(v) {
    const url = srcOf(v), m = meta(url);
    let name = (m.name || '').replace(/[\\/:*?"<>|]/g, '_') || 'telegram-video-' + Date.now() + '.mp4';
    if (!/\.[a-z0-9]{2,4}$/i.test(name)) name += '.mp4';
    let writer = null; const chunks = [];
    if (window.showSaveFilePicker) {
      try { writer = await (await window.showSaveFilePicker({ suggestedName: name })).createWritable(); }
      catch (e) { if (e && e.name === 'AbortError') return; writer = null; }
    }
    let start = 0, total = m.size, got = 0;
    while (!total || start < total) {
      const r = await fetch(url, { headers: { Range: 'bytes=' + start + '-' + (start + CHUNK - 1) } });
      if (r.status !== 200 && r.status !== 206) throw new Error('HTTP ' + r.status);
      const cr = /\/(\d+)\s*$/.exec(r.headers.get('content-range') || '');
      if (cr) total = +cr[1];
      const buf = new Uint8Array(await r.arrayBuffer());
      if (!buf.length) break;
      if (writer) await writer.write(buf); else chunks.push(buf);
      got += buf.length; start += buf.length;
      label(total ? '\u2B07 ' + Math.round((got / total) * 100) + '%' : '\u2B07 ' + (got / 1048576).toFixed(1) + ' MB');
      if (r.status === 200 && !cr) break; // server ignored Range: the whole file arrived in one go
      if (!total && buf.length < CHUNK) break;
    }
    if (writer) await writer.close();
    else {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob(chunks, { type: 'video/mp4' })); a.download = name;
      document.body.append(a); a.click();
      setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 8000);
    }
  }
  function ensure() {
    if (btn) return btn;
    btn = document.createElement('button');
    btn.style.cssText = 'position:fixed;top:72px;right:16px;z-index:2147483647;padding:9px 14px;border:0;border-radius:99px;background:#5b8dff;color:#fff;font:600 13px system-ui,sans-serif;cursor:pointer;box-shadow:0 6px 20px rgba(0,0,0,.35);display:none';
    btn.onclick = async () => {
      if (busy) return;
      const v = target(); if (!v) return;
      busy = true; label('\u2B07 0%');
      try { await run(v); label('\u2714 Saved'); } catch (e) { label('Failed - retry'); console.warn('[Media Grabber] Telegram download failed', e); }
      busy = false; clearTimeout(resetT); resetT = setTimeout(() => label('\u2B07 Download video'), 3500);
    };
    document.body.append(btn);
    return btn;
  }
  setInterval(() => {
    if (!document.body) return;
    const show = !off() && (busy || !!target());
    if (!show && !btn) return;
    const b = ensure();
    if (!busy && !b.textContent) label('\u2B07 Download video');
    b.style.display = show ? '' : 'none';
  }, 900);
})();
