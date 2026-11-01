// V74a: header "more" menu (popup + side panel) and the side panel's bottom tabs. Pure UI: every action still runs through the
// same button ids popup.js already handles, so no download / scan logic is touched here.
(() => {
  const $ = (id) => document.getElementById(id);
  const btn = $('moreBtn'), menu = $('menu');
  if (btn && menu) {
    const set = (on) => { menu.hidden = !on; btn.setAttribute('aria-expanded', on ? 'true' : 'false'); document.body.classList.toggle('menu-open', on); };
    btn.addEventListener('click', (e) => { e.stopPropagation(); set(menu.hidden); if (!menu.hidden) { const f = menu.querySelector('.mi'); if (f) f.focus(); } });
    menu.addEventListener('click', () => set(false));
    document.addEventListener('click', (e) => { if (!menu.hidden && !menu.contains(e.target)) set(false); });
    document.addEventListener('keydown', (e) => {
      if (menu.hidden) return;
      if (e.key === 'Escape') { set(false); btn.focus(); return; }
      const items = [...menu.querySelectorAll('.mi')].filter((x) => x.offsetParent), i = items.indexOf(document.activeElement);
      if (e.key === 'ArrowDown') { e.preventDefault(); (items[i + 1] || items[0]).focus(); }
      if (e.key === 'ArrowUp') { e.preventDefault(); (items[i - 1] || items[items.length - 1]).focus(); }
    });
  }
  document.querySelectorAll('.bnav [data-go]').forEach((b) => b.addEventListener('click', () => { const t = $(b.dataset.go); if (t) t.click(); }));
})();
