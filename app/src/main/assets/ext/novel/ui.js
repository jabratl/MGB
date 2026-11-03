// Thazir Novel Studio - UI glue only (tabs, live book card, build strip). All real work is in studio.js.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var body = document.body;
  try { if (window.matchMedia && matchMedia('(prefers-color-scheme: light)').matches) document.documentElement.setAttribute('data-theme', 'light'); } catch (e) { }

  function go(n) {
    body.setAttribute('data-tab', n);
    var bs = document.querySelectorAll('#tabs button');
    for (var i = 0; i < bs.length; i++) bs[i].classList.toggle('on', bs[i].getAttribute('data-go') === n);
    window.scrollTo(0, 0);
  }
  document.getElementById('tabs').addEventListener('click', function (e) {
    var b = e.target.closest('button[data-go]'); if (b) go(b.getAttribute('data-go'));
  });
  window.__w2eGo = go;

  // book card
  function hue(s) { var h = 0; for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360; return h; }
  var MODES = { single: 'Single page', next: 'Next links', toc: 'Chapter list', api: 'API pattern' };
  function card() {
    var t = ($('title').value || '').trim(), a = ($('author').value || '').trim();
    $('hTitle').textContent = t || 'Untitled book';
    $('hAuthor').textContent = a || 'Unknown author';
    $('coverLetter').textContent = (t || 'N').replace(/[^\p{L}\p{N}]/gu, '').charAt(0).toUpperCase() || 'N';
    $('cover').style.setProperty('--h', hue(t || 'novel'));
    var m = $('mode').value, n = 0;
    if (m === 'toc') { var c = ($('tocCount').textContent || '').match(/(\d[\d,]*)\s*(?:of|\/|selected)/i); var ch = document.querySelectorAll('#tocList input:checked'); n = ch.length || (c ? parseInt(c[1].replace(/,/g, ''), 10) : 0); }
    else if (m === 'next') n = parseInt($('maxCh').value, 10) || 0;
    else if (m === 'api') n = Math.max(0, (parseInt($('apiTo').value, 10) || 0) - (parseInt($('apiFrom').value, 10) || 0) + 1);
    else n = 1;
    $('hCh').textContent = n + (n === 1 ? ' chapter' : ' chapters');
    var f = $('outFmt').value; $('hFmt').textContent = f.toUpperCase();
    $('hMode').textContent = MODES[m] || m;
    $('btnBuild').textContent = 'Build ' + f.toUpperCase();
    $('fabBuild').textContent = 'Build ' + f.toUpperCase();
  }
  ['input', 'change', 'click'].forEach(function (ev) { document.addEventListener(ev, function () { setTimeout(card, 0); }, true); });
  setInterval(card, 1500);

  // empty chapters card / auto jump to the list when a catalog was found
  var toc = $('tocCard'), api = $('apiCard'), empty = $('chEmpty'), wantJump = false;
  function sync() { empty.hidden = !toc.hidden || !api.hidden; }
  var mo = new MutationObserver(function () {
    sync();
    if (wantJump && !toc.hidden && body.getAttribute('data-tab') === 'source') { wantJump = false; go('chapters'); }
  });
  mo.observe(toc, { attributes: true, attributeFilter: ['hidden'] }); mo.observe(api, { attributes: true, attributeFilter: ['hidden'] }); sync();
  ['btnFind', 'btnTabsGo'].forEach(function (id) { $(id).addEventListener('click', function () { wantJump = true; setTimeout(function () { wantJump = false; }, 90000); }); });

  // build strip mirrors the progress bar + last log line; the big button runs the real one
  var bar = $('bar'), log = $('log');
  function strip() {
    var v = bar.max ? bar.value / bar.max : 0;
    $('sFill').style.width = Math.round(v * 100) + '%';
    var last = log.lastElementChild; $('sTxt').textContent = $('btnBuild').disabled ? (last ? last.textContent : 'Working…') : (v >= 1 ? 'Done' : 'Ready');
    $('fabBuild').disabled = $('btnBuild').disabled;
  }
  setInterval(strip, 500);
  $('fabBuild').addEventListener('click', function () { go('build'); $('btnBuild').click(); });
  $('btnBuild').addEventListener('click', function () { setTimeout(function () { go('build'); }, 0); });
  $('btnTest').addEventListener('click', function () { go('build'); });
  card();
})();
