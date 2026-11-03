// Dependency-free ZIP writer (deflate-raw via CompressionStream, CRC32, UTF-8 names)
const T = new Uint32Array(256);
for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; T[n] = c >>> 0; }
export function crc32(b) { let c = -1; for (let i = 0; i < b.length; i++) c = T[(c ^ b[i]) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; }
async function deflate(data) {
  const cs = new CompressionStream('deflate-raw');
  const w = cs.writable.getWriter(); w.write(data); w.close();
  return new Uint8Array(await new Response(cs.readable).arrayBuffer());
}
const enc = new TextEncoder();
export class Zip {
  constructor() { this.items = []; }
  add(name, data, compress = true) {
    if (typeof data === 'string') data = enc.encode(data);
    this.items.push({ name, data, compress });
  }
  async build() {
    const parts = [], central = []; let offset = 0;
    const d = new Date();
    const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
    const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
    for (const it of this.items) {
      const nb = enc.encode(it.name), crc = crc32(it.data);
      let method = 0, body = it.data;
      if (it.compress && it.data.length > 64) {
        const z = await deflate(it.data);
        if (z.length < it.data.length) { method = 8; body = z; }
      }
      const lh = new DataView(new ArrayBuffer(30));
      lh.setUint32(0, 0x04034b50, true); lh.setUint16(4, 20, true); lh.setUint16(6, 0x0800, true);
      lh.setUint16(8, method, true); lh.setUint16(10, time, true); lh.setUint16(12, date, true);
      lh.setUint32(14, crc, true); lh.setUint32(18, body.length, true); lh.setUint32(22, it.data.length, true);
      lh.setUint16(26, nb.length, true); lh.setUint16(28, 0, true);
      parts.push(new Uint8Array(lh.buffer), nb, body);
      const ch = new DataView(new ArrayBuffer(46));
      ch.setUint32(0, 0x02014b50, true); ch.setUint16(4, 20, true); ch.setUint16(6, 20, true); ch.setUint16(8, 0x0800, true);
      ch.setUint16(10, method, true); ch.setUint16(12, time, true); ch.setUint16(14, date, true);
      ch.setUint32(16, crc, true); ch.setUint32(20, body.length, true); ch.setUint32(24, it.data.length, true);
      ch.setUint16(28, nb.length, true); ch.setUint32(42, offset, true);
      central.push(new Uint8Array(ch.buffer), nb);
      offset += 30 + nb.length + body.length;
    }
    let cdSize = 0; for (const c of central) cdSize += c.length;
    const end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true); end.setUint16(8, this.items.length, true); end.setUint16(10, this.items.length, true);
    end.setUint32(12, cdSize, true); end.setUint32(16, offset, true);
    return new Blob([...parts, ...central, new Uint8Array(end.buffer)], { type: 'application/epub+zip' });
  }
}
