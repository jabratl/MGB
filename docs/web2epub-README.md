# v1.8.0 - Chapter title fix

- New default title mode **Smart**: uses the catalog title; if it is only "Chapter N" (e.g. 第242章), the full name is completed from the chapter page itself (heading / title / og:title), ignoring the book name and site name.
- Catalog list now completes bare link text from the link's title attribute or its row text (trailing date / VIP / NEW stripped).
- Full title is written to both the EPUB nav (TOC) and the heading inside each chapter, also for cached chapters and API/JSON chapters.
- Fixed: Auto mode used to title every chapter with the book name.
- Cache signature bumped (old cache is re-fetched once).
- Modes: Smart (recommended) / Auto (page only) / Number / Catalog list titles.

# Web2EPUB Studio v1.1 (Chrome / Edge / Brave, Manifest V3)

Install: chrome://extensions → Developer mode ON → Load unpacked → select this folder.
Use: open any novel page or chapter → click the extension icon (or Alt+E) → Studio opens → **🔎 Find catalog** → Build EPUB.

## New in 1.1 — webnovel catalog features
- **Find catalog (auto)**: from a chapter page it locates the table-of-contents page (rel links, "Table of contents" links, breadcrumbs, JSON-LD, URL guess), then collects ALL chapters using every strategy it can:
  page links · catalog pagination (?page=N, /page/N, next/older) · "Load more / Show all" clicks + infinite scroll · chapter dropdowns (select) · JSON embedded in the page · Blogger label feeds · WordPress REST (category/tag) · Madara & NovelFull-style AJAX lists · detected API calls (with page loop) · sitemap.xml.
- **Catalog source picker**: if several sources are found, switch between them (shows count for each).
- **Auto-order** by chapter number (fixes newest-first lists) + **missing chapter number report**.
- **Parallel downloads** (1–4), **auto-retry** of failed chapters in a real browser tab, locked/paywall warning.
- **Split into volumes**: N chapters per EPUB (e.g. 200), numbered Vol 01, Vol 02…
- **Use titles from catalog** option, **Export list (.txt)**, **paste your own URL list**.
- URL-pattern guess for API/pattern mode when no list can be found.

Settings are remembered per website.

## Webnovel.com support (v1.2)
- Detects book id from any `webnovel.com` / `m.webnovel.com` book URL and reads `/book/{id}/catalog` (fast, then real-browser tab, then in-page JSON API).
- Auto-applies a safe preset on first use: real-browser loading, 2.5 s delay, 1 download at a time, 100 chapters per EPUB.
- Locked / paid chapters are detected and skipped (🔒 shown in the list when the page marks them); "Uncheck 🔒 locked" button; stops early after 5 locked chapters in a row.
- Only chapters your own logged-in browser can read are exported. For personal/offline reading.

## Smart chapter headings (v1.7, optional — off by default)
Advanced → tick **🌐 Smart chapter headings**, pick the novel language (or Auto), done.
- Understands 第12章 / 第十二章 · 第12話 · 제12화 / 12화 · Chapter 12 / XII · Capítulo 12 · Caput IV / Kapitel 5 / Chapitre 3 · الفصل 12 / الفصل الأول, plus prologue/epilogue words.
- Used for: chapter title detection, “Remove Chapter N from titles”, and (optional) **splitting one long page into chapters** at each heading.
- Custom: type your own prefix and/or suffix (comma separated). The title is whatever follows the heading (space, colon, dash or nothing).
- Safety: only lines that START with the heading and are short count; headings whose number does not increase are treated as normal text.
