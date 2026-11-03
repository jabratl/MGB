import { Zip } from './zip.js';
import { esc, escA } from './extract.js';

const XH = (lang, title, css, body) => `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${escA(lang)}" xml:lang="${escA(lang)}">
<head><meta charset="utf-8"/><title>${esc(title)}</title><link rel="stylesheet" type="text/css" href="${css}"/></head>
<body>${body}</body></html>`;
const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/gif': 'gif', 'image/svg+xml': 'svg', 'image/webp': 'webp' };

export function makeCss(o = {}) {
  return `body{font-size:${o.fontSize || 100}%;line-height:${o.lineHeight || 1.6};text-align:${o.align || 'justify'};margin:0 .4em}
p{margin:0 0 .8em;${o.indent ? 'text-indent:1.3em;' : ''}}
h1,h2,h3,h4{text-align:center;margin:1.2em 0 .8em;line-height:1.25;page-break-after:avoid}
img{max-width:100%;height:auto;display:block;margin:1em auto}
blockquote{margin:1em 1.5em;font-style:italic}
pre{white-space:pre-wrap;font-size:.9em}
table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #888;padding:.3em}
a{color:inherit}
.cover{text-align:center;margin:0;padding:0}.cover img{max-height:100%;margin:0 auto}
.tp{text-align:center;margin-top:30%}.tp h1{font-size:1.8em}.tp .au{margin-top:2em;font-size:1.2em}.tp .ds{margin-top:2em;font-size:.9em;opacity:.8}`;
}

export async function buildEpub({ meta, chapters, images, cover, css }) {
  const lang = meta.language || 'en', id = 'urn:uuid:' + crypto.randomUUID();
  const now = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
  const z = new Zip();
  z.add('mimetype', 'application/epub+zip', false);
  z.add('META-INF/container.xml', `<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`);
  z.add('OEBPS/style.css', css);

  const man = [], spine = [];
  man.push('<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>');
  man.push('<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>');
  man.push('<item id="css" href="style.css" media-type="text/css"/>');

  if (cover) {
    const ext = EXT[cover.mime] || 'jpg';
    z.add(`OEBPS/images/cover.${ext}`, cover.bytes, false);
    man.push(`<item id="cover-image" href="images/cover.${ext}" media-type="${cover.mime}" properties="cover-image"/>`);
    z.add('OEBPS/cover.xhtml', XH(lang, 'Cover', 'style.css', `<div class="cover" epub:type="cover"><img src="images/cover.${ext}" alt="Cover"/></div>`));
    man.push('<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>');
    spine.push('<itemref idref="cover" linear="yes"/>');
  }
  z.add('OEBPS/titlepage.xhtml', XH(lang, meta.title, 'style.css',
    `<section class="tp" epub:type="titlepage"><h1>${esc(meta.title)}</h1>${meta.author ? `<p class="au">${esc(meta.author)}</p>` : ''}${meta.description ? `<p class="ds">${esc(meta.description)}</p>` : ''}</section>`));
  man.push('<item id="titlepage" href="titlepage.xhtml" media-type="application/xhtml+xml"/>');
  spine.push('<itemref idref="titlepage"/>');

  for (const im of images) { z.add('OEBPS/images/' + im.name, im.bytes, false); man.push(`<item id="i-${esc(im.name.replace(/\W/g, '_'))}" href="images/${im.name}" media-type="${im.mime}"/>`); }

  const navItems = [], ncxPts = [];
  chapters.forEach((c, i) => {
    const f = `text/ch${String(i + 1).padStart(4, '0')}.xhtml`, cid = 'ch' + (i + 1);
    z.add('OEBPS/' + f, XH(lang, c.title, '../style.css', `<section epub:type="chapter">${c.body}</section>`));
    man.push(`<item id="${cid}" href="${f}" media-type="application/xhtml+xml"/>`);
    spine.push(`<itemref idref="${cid}"/>`);
    navItems.push(`<li><a href="${f}">${esc(c.title)}</a></li>`);
    ncxPts.push(`<navPoint id="n${i + 1}" playOrder="${i + 1}"><navLabel><text>${esc(c.title)}</text></navLabel><content src="${f}"/></navPoint>`);
  });

  z.add('OEBPS/nav.xhtml', XH(lang, 'Contents', 'style.css',
    `<nav epub:type="toc" id="toc"><h1>${esc(meta.title)}</h1><ol>${navItems.join('')}</ol></nav>
<nav epub:type="landmarks" hidden="hidden"><ol><li><a epub:type="bodymatter" href="${chapters.length ? 'text/ch0001.xhtml' : 'titlepage.xhtml'}">Start</a></li></ol></nav>`));
  z.add('OEBPS/toc.ncx', `<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="${id}"/><meta name="dtb:depth" content="1"/><meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head>
<docTitle><text>${esc(meta.title)}</text></docTitle><navMap>${ncxPts.join('')}</navMap></ncx>`);

  z.add('OEBPS/content.opf', `<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="${escA(lang)}">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">${id}</dc:identifier>
<dc:title>${esc(meta.title)}</dc:title>
<dc:creator>${esc(meta.author || 'Unknown')}</dc:creator>
<dc:language>${esc(lang)}</dc:language>
<dc:date>${now.slice(0, 10)}</dc:date>
${meta.description ? `<dc:description>${esc(meta.description)}</dc:description>` : ''}
${meta.source ? `<dc:source>${esc(meta.source)}</dc:source>` : ''}
<meta property="dcterms:modified">${now}</meta>
${cover ? '<meta name="cover" content="cover-image"/>' : ''}
${meta.series ? `<meta name="calibre:series" content="${escA(meta.series)}"/>` : ''}
</metadata>
<manifest>${man.join('\n')}</manifest>
<spine toc="ncx">${spine.join('\n')}</spine>
</package>`);
  return z.build();
}
