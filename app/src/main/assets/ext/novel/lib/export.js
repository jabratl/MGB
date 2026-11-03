// Non-EPUB outputs: plain text, DOCX (hand-written minimal OOXML), ZIP of one .txt per chapter
import { Zip } from './zip.js';
const xml = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
const noHead = b => b.replace(/^\s*<h[1-6]>.*?<\/h[1-6]>\s*/s, '');
export function htmlToText(body) {
  const s = body.replace(/<img[^>]*>/g, '').replace(/<br\s*\/?>/gi, '\n').replace(/<\/(p|div|h[1-6]|li|tr|blockquote|pre|table|ul|ol)>/gi, '\n\n').replace(/<[^>]+>/g, '');
  const t = new DOMParser().parseFromString('<!doctype html><body>' + s, 'text/html').body.textContent;   // decodes &amp; &lt; …
  return t.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}
export function buildTxt({ title, author, chapters }) {
  const out = [title, author ? 'by ' + author : '', ''];
  for (const c of chapters) out.push('\n\n=== ' + c.title + ' ===\n', htmlToText(noHead(c.body)));
  return new Blob([out.join('\n').trim() + '\n'], { type: 'text/plain;charset=utf-8' });
}
const safe = s => s.replace(/[\\/:*?"<>|]+/g, '_').replace(/\s+/g, ' ').trim().slice(0, 80) || 'chapter';
export async function buildTxtZip({ chapters }) {
  const z = new Zip();
  chapters.forEach((c, i) => z.add(`${String(i + 1).padStart(4, '0')} - ${safe(c.title)}.txt`, c.title + '\n\n' + htmlToText(noHead(c.body)) + '\n'));
  return z.build();
}
export async function buildDocx({ title, author, chapters }) {
  const W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';
  const P = (t, style, extra = '') => `<w:p><w:pPr><w:pStyle w:val="${style}"/>${extra}</w:pPr><w:r><w:t xml:space="preserve">${xml(t)}</w:t></w:r></w:p>`;
  let body = P(title, 'Title') + (author ? P('by ' + author, 'Subtitle') : '');
  for (const c of chapters) {
    body += P(c.title, 'Heading1', '<w:pageBreakBefore/>');
    for (const para of htmlToText(noHead(c.body)).split(/\n{2,}/)) {
      if (!para.trim()) continue;
      body += '<w:p><w:r>' + para.split('\n').map((l, k) => (k ? '<w:br/>' : '') + `<w:t xml:space="preserve">${xml(l)}</w:t>`).join('') + '</w:r></w:p>';
    }
  }
  const z = new Zip();
  z.add('[Content_Types].xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>');
  z.add('_rels/.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
  z.add('word/_rels/document.xml.rels', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>');
  z.add('word/styles.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles ${W}><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="140" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/><w:spacing w:before="2400" w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="56"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/></w:pPr><w:rPr><w:sz w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="240"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style></w:styles>`);
  z.add('word/document.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document ${W}><w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>`);
  const b = await z.build();
  return new Blob([b], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' });
}
