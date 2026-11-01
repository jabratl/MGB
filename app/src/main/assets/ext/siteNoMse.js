// Media Grabber - "no MediaSource" module (world: MAIN, document_start; registered only for the sites listed in Settings).
// Some players fall back to a plain MP4 / HLS URL when the browser has no MediaSource. That URL can be detected and downloaded,
// while MSE streams (blob:) cannot. If a site stops playing after you add it, remove it from the list again.
(() => {
  try { delete window.MediaSource; } catch (e) { /* ignore */ }
  try { delete window.WebKitMediaSource; } catch (e) { /* ignore */ }
  try { if (window.MediaSource) Object.defineProperty(window, 'MediaSource', { value: undefined, configurable: true }); } catch (e) { /* ignore */ }
})();
