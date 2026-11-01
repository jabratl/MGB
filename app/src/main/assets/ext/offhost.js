// Media Grabber - hidden host document. Chrome allows only ONE offscreen document, so every stream
// download runs in its own iframe inside this one (offscreen.html = the downloader page + API shim).
const frames = new Map(); // job key -> iframe
chrome.runtime.onMessage.addListener((m, s, respond) => {
  if (!m) return;
  if (m.type === 'off-start') {
    if (frames.has(m.key)) { frames.get(m.key).remove(); frames.delete(m.key); }
    const f = document.createElement('iframe');
    f.src = 'offscreen.html?' + m.qs;
    document.body.append(f);
    frames.set(m.key, f);
    respond({ ok: true });
    return;
  }
  if (m.type === 'off-end') {
    const f = frames.get(m.key);
    if (f) { f.remove(); frames.delete(m.key); }
    respond({ ok: true, left: frames.size });
  }
});
