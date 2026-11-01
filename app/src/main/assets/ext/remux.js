// Fragmented MP4 (init + moof/mdat fragments) -> normal progressive MP4 (moov up front, full sample index).
// Fixes: no seeking / wrong or missing duration / video stopping early in players.
// Timestamps are rebuilt from sample durations, so segment joins and timestamp jumps cannot break playback.
(function (root) {
  const u32 = (v, o) => v.getUint32(o);
  const type4 = (b, o) => String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]);

  function boxes(b, start, end) {
    const out = [];
    const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
    let p = start;
    while (p + 8 <= end) {
      let size = u32(v, p), hdr = 8;
      const t = type4(b, p + 4);
      if (size === 1) { size = u32(v, p + 8) * 4294967296 + u32(v, p + 12); hdr = 16; }
      else if (size === 0) size = end - p;
      if (size < hdr || p + size > end) break;
      out.push({ t, s: p, e: p + size, d: p + hdr });
      p += size;
    }
    return out;
  }
  const find = (b, bx, t) => bx.find((x) => x.t === t);
  const kidsOf = (b, bx) => boxes(b, bx.d, bx.e);

  // ----- writers -----
  const enc = (s) => [s.charCodeAt(0), s.charCodeAt(1), s.charCodeAt(2), s.charCodeAt(3)];
  function mk(t, ...payload) {
    let len = 8;
    for (const p of payload) len += p.length;
    const o = new Uint8Array(len);
    new DataView(o.buffer).setUint32(0, len);
    o.set(enc(t), 4);
    let q = 8;
    for (const p of payload) { o.set(p, q); q += p.length; }
    return o;
  }
  const fullbox = (t, ver, flags, body) => mk(t, new Uint8Array([ver, (flags >> 16) & 255, (flags >> 8) & 255, flags & 255]), body);
  function words(arr) { const o = new Uint8Array(arr.length * 4); const v = new DataView(o.buffer); arr.forEach((x, i) => v.setUint32(i * 4, x >>> 0)); return o; }
  const copy = (b, bx) => b.slice(bx.s, bx.e);

  // ----- init segment -----
  function parseInit(b) {
    const top = boxes(b, 0, b.length);
    const ftyp = find(b, top, 'ftyp'), moov = find(b, top, 'moov');
    if (!moov) throw new Error('no moov in init segment');
    const mk0 = kidsOf(b, moov);
    const mvex = find(b, mk0, 'mvex');
    const trex = {};
    if (mvex) for (const x of kidsOf(b, mvex)) if (x.t === 'trex') { const v = new DataView(b.buffer, b.byteOffset + x.d); trex[u32(v, 4)] = { dur: u32(v, 12), size: u32(v, 16), flags: u32(v, 20) }; }
    const tracks = {};
    for (const trak of mk0.filter((x) => x.t === 'trak')) {
      const tk = kidsOf(b, trak);
      const tkhd = find(b, tk, 'tkhd'), mdia = find(b, tk, 'mdia');
      const tv = new DataView(b.buffer, b.byteOffset + tkhd.d);
      const id = tv.getUint8(0) === 1 ? u32(tv, 20) : u32(tv, 12);
      const md = kidsOf(b, mdia);
      const mdhd = find(b, md, 'mdhd'), hdlr = find(b, md, 'hdlr'), minf = find(b, md, 'minf');
      const mv = new DataView(b.buffer, b.byteOffset + mdhd.d);
      const timescale = mv.getUint8(0) === 1 ? u32(mv, 20) : u32(mv, 12);
      const mi = kidsOf(b, minf);
      const stbl = find(b, mi, 'stbl');
      const stsd = find(b, kidsOf(b, stbl), 'stsd');
      const handler = type4(b, hdlr.d + 8);
      tracks[id] = {
        id, timescale, handler, tkhd: copy(b, tkhd), mdhd: copy(b, mdhd), hdlr: copy(b, hdlr), stsd: copy(b, stsd),
        mediaHdr: mi.filter((x) => x.t === 'vmhd' || x.t === 'smhd' || x.t === 'nmhd' || x.t === 'sthd').map((x) => copy(b, x)),
        dinf: mi.filter((x) => x.t === 'dinf').map((x) => copy(b, x)),
        trex: trex[id] || { dur: 0, size: 0, flags: 0 },
        // sample table (filled while reading fragments)
        durs: [], sizes: [], syncs: [], ctss: [], chunks: [], firstTfdt: null, minCts: 0, hasCts: false, negCts: false, count: 0, total: 0,
      };
    }
    return { ftyp: ftyp ? copy(b, ftyp) : null, tracks };
  }

  // ----- fragment -----
  // returns array of {track, runs:[{off,len}]} where off is an offset inside this buffer
  function parseFragments(b, tracks, sink) {
    const top = boxes(b, 0, b.length);
    for (let i = 0; i < top.length; i++) {
      if (top[i].t !== 'moof') continue;
      const moof = top[i];
      for (const traf of kidsOf(b, moof).filter((x) => x.t === 'traf')) {
        const tk = kidsOf(b, traf);
        const tfhd = find(b, tk, 'tfhd');
        const hv = new DataView(b.buffer, b.byteOffset + tfhd.d);
        const hf = u32(hv, 0) & 0xffffff, rawId = u32(hv, 4);
        const id = sink.idMap ? sink.idMap[rawId] : rawId; // several sources (video + audio file) can reuse the same track id
        const T = id == null ? null : tracks[id];
        if (!T) continue;
        let p = 8, base = moof.s, dDur = T.trex.dur, dSize = T.trex.size, dFlags = T.trex.flags;
        if (hf & 1) { base = u32(hv, p) * 4294967296 + u32(hv, p + 4) - (sink.baseShift || 0); p += 8; }
        if (hf & 2) p += 4;
        if (hf & 8) { dDur = u32(hv, p); p += 4; }
        if (hf & 0x10) { dSize = u32(hv, p); p += 4; }
        if (hf & 0x20) { dFlags = u32(hv, p); p += 4; }
        const tfdt = find(b, tk, 'tfdt');
        if (tfdt && T.firstTfdt === null) {
          const dv = new DataView(b.buffer, b.byteOffset + tfdt.d);
          T.firstTfdt = dv.getUint8(0) === 1 ? u32(dv, 4) * 4294967296 + u32(dv, 8) : u32(dv, 4);
        }
        let cursor = base;
        for (const trun of tk.filter((x) => x.t === 'trun')) {
          const rv = new DataView(b.buffer, b.byteOffset + trun.d);
          const ver = rv.getUint8(0), rf = u32(rv, 0) & 0xffffff, n = u32(rv, 4);
          let q = 8, off = cursor;
          if (rf & 1) { off = base + rv.getInt32(q); q += 4; }
          let first = null;
          if (rf & 4) { first = u32(rv, q); q += 4; }
          const start = off;
          let len = 0;
          for (let s = 0; s < n; s++) {
            let d = dDur, z = dSize, f = dFlags, c = 0;
            if (rf & 0x100) { d = u32(rv, q); q += 4; }
            if (rf & 0x200) { z = u32(rv, q); q += 4; }
            if (rf & 0x400) { f = u32(rv, q); q += 4; }
            if (rf & 0x800) { c = ver ? rv.getInt32(q) : u32(rv, q); q += 4; if (c) T.hasCts = true; if (c < 0) T.negCts = true; }
            if (s === 0 && first !== null) f = first;
            T.durs.push(d); T.sizes.push(z); T.ctss.push(c);
            if (!(f & 0x10000)) T.syncs.push(T.count + 1);
            if (T.count === 0 || c < T.minCts) T.minCts = c;
            T.count++; T.total += d; len += z;
          }
          sink.runs.push({ id, off: start, len, n });
          cursor = start + len;
        }
      }
    }
  }

  // ----- build moov -----
  function buildMoov(tracks, mdatStart, co64) {
    const list = Object.values(tracks).filter((t) => t.count);
    const MS = 1000;
    // presentation start of each track, in seconds
    const startOf = (t) => ((t.firstTfdt || 0) + (t.hasCts ? t.minCts : 0)) / t.timescale;
    const t0 = Math.min(...list.map(startOf));
    let maxDur = 0;
    const traks = list.map((t) => {
      const nS = t.count;
      // stts (run-length)
      const stts = []; let cur = null;
      for (const d of t.durs) { if (cur && cur[1] === d) cur[0]++; else { cur = [1, d]; stts.push(cur); } }
      const sttsB = fullbox('stts', 0, 0, words([stts.length, ...stts.flat()]));
      let ctts = null;
      if (t.hasCts) {
        const e = []; let c2 = null;
        for (const c of t.ctss) { if (c2 && c2[1] === c) c2[0]++; else { c2 = [1, c]; e.push(c2); } }
        ctts = fullbox('ctts', t.negCts ? 1 : 0, 0, words([e.length, ...e.flat()]));
      }
      const stss = (t.syncs.length && t.syncs.length < nS) ? fullbox('stss', 0, 0, words([t.syncs.length, ...t.syncs])) : null;
      // one chunk per run
      const stsc = []; let lastN = -1;
      t.chunks.forEach((c, i) => { if (c.n !== lastN) { stsc.push(i + 1, c.n, 1); lastN = c.n; } });
      const stscB = fullbox('stsc', 0, 0, words([stsc.length / 3, ...stsc]));
      const stszB = fullbox('stsz', 0, 0, words([0, nS, ...t.sizes]));
      let stco;
      if (co64) {
        const o = new Uint8Array(4 + t.chunks.length * 8); const v = new DataView(o.buffer);
        v.setUint32(0, t.chunks.length);
        t.chunks.forEach((c, i) => { const a = mdatStart + c.pos; v.setUint32(4 + i * 8, Math.floor(a / 4294967296)); v.setUint32(8 + i * 8, a >>> 0); });
        stco = fullbox('co64', 0, 0, o);
      } else {
        stco = fullbox('stco', 0, 0, words([t.chunks.length, ...t.chunks.map((c) => mdatStart + c.pos)]));
      }
      const stbl = mk('stbl', t.stsd, sttsB, ...(ctts ? [ctts] : []), ...(stss ? [stss] : []), stscB, stszB, stco);
      const minf = mk('minf', ...t.mediaHdr, ...t.dinf, stbl);
      // mdhd with real duration (version 0 or 1)
      const mdhd = t.mdhd.slice(); const mdv = new DataView(mdhd.buffer);
      if (mdhd[8] === 1) { mdv.setUint32(32, Math.floor(t.total / 4294967296)); mdv.setUint32(36, t.total >>> 0); }
      else mdv.setUint32(24, t.total >>> 0);
      const mdia = mk('mdia', mdhd, t.hdlr, minf);
      // tkhd: duration in movie timescale (ms)
      const delay = Math.round((startOf(t) - t0) * MS);
      const mediaStart = t.hasCts ? t.minCts : 0;
      const mediaDur = Math.max(0, Math.round(((t.total - mediaStart) / t.timescale) * MS));
      const durMs = mediaDur + delay;
      maxDur = Math.max(maxDur, durMs);
      const tkhd = t.tkhd.slice(); const tv = new DataView(tkhd.buffer);
      tkhd[8 + 3] |= 0x07; // enabled | in movie | in preview
      if (tkhd[8] === 1) { tv.setUint32(8 + 4 + 24, Math.floor(durMs / 4294967296)); tv.setUint32(8 + 4 + 28, durMs >>> 0); }
      else tv.setUint32(8 + 4 + 16, durMs);
      // edit list: optional empty edit, then the media from its first presented sample
      const eds = [];
      // entry = segment_duration(u32), media_time(i32), media_rate(16.16) -> 3 words
      if (delay > 0) eds.push(delay, 0xffffffff, 0x00010000);
      eds.push(mediaDur, mediaStart, 0x00010000);
      const elst = fullbox('elst', 0, 0, words([eds.length / 3, ...eds]));
      return mk('trak', tkhd, mk('edts', elst), mdia);
    });
    const ids = list.map((t) => t.id);
    const mvhd = fullbox('mvhd', 0, 0, words([0, 0, MS, maxDur, 0x00010000, 0x01000000, 0, 0, 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000, 0, 0, 0, 0, 0, 0, Math.max(...ids) + 1]));
    return mk('moov', mvhd, ...traks);
  }

  // sources: list of piece lists; every piece list is Blob / Uint8Array pieces whose first holding a moov is the init segment.
  // One source = one fragmented MP4 -> progressive MP4. Two sources (video + audio) = both tracks in ONE progressive MP4.
  async function remuxCore(sources, onProgress) {
    const asBlob = (p) => (p instanceof Blob ? p : new Blob([p]));
    const tracks = {};
    let ftyp = null;
    const src = sources.map((parts) => ({ blobs: parts.map(asBlob), idMap: {}, first: -1, firstBytes: null }));
    // 1) find every source's init segment and register its tracks (track ids made unique across sources)
    for (const s of src) {
      for (let i = 0; i < s.blobs.length && s.first < 0; i++) {
        const b = new Uint8Array(await s.blobs[i].arrayBuffer());
        if (!boxes(b, 0, b.length).some((x) => x.t === 'moov')) continue;
        const init = parseInit(b);
        if (!ftyp && init.ftyp) ftyp = init.ftyp;
        for (const rawId of Object.keys(init.tracks).map(Number)) {
          const T = init.tracks[rawId];
          let nid = rawId;
          if (tracks[nid]) nid = Math.max(...Object.keys(tracks).map(Number)) + 1;
          if (nid !== rawId) { // write the new id into the copied tkhd
            const dv = new DataView(T.tkhd.buffer, T.tkhd.byteOffset, T.tkhd.byteLength);
            dv.setUint32(8 + (T.tkhd[8] === 1 ? 20 : 12), nid);
            T.id = nid;
          }
          s.idMap[rawId] = nid;
          tracks[nid] = T;
        }
        s.first = i; s.firstBytes = b;
      }
      if (s.first < 0) throw new Error('no init segment found');
    }
    // 2) fragments in time order across sources (video and audio alternate), so the file is interleaved like a normal MP4
    const queues = src.map((s) => { const q = []; for (let i = s.first; i < s.blobs.length; i++) q.push(i); return q; });
    const ptr = queues.map(() => 0), order = [];
    for (;;) {
      let best = -1, bestF = Infinity;
      queues.forEach((q, k) => { if (ptr[k] < q.length) { const f = (ptr[k] + 1) / q.length; if (f < bestF) { bestF = f; best = k; } } });
      if (best < 0) break;
      order.push([best, queues[best][ptr[best]++]]);
    }
    const slices = [], chunkMeta = [];
    let nDone = 0;
    for (const [k, i] of order) {
      if (onProgress && ++nDone % 8 === 0) await onProgress(nDone / order.length); // lets the page show progress and breathe
      const s = src[k], bl = s.blobs[i];
      const b = i === s.first ? s.firstBytes : new Uint8Array(await bl.arrayBuffer());
      const sink = { runs: [], idMap: s.idMap };
      parseFragments(b, tracks, sink);
      for (const r of sink.runs) {
        if (r.off < 0 || r.off + r.len > b.length) throw new Error('fragment data out of range');
        slices.push({ blob: bl, off: r.off, len: r.len });
        chunkMeta.push(r);
      }
      if (i === s.first) s.firstBytes = null;
    }
    // position of every run inside the new mdat
    let pos = 0;
    slices.forEach((s, i) => { const m = chunkMeta[i]; tracks[m.id].chunks.push({ pos, n: m.n }); pos += s.len; });
    if (!pos) throw new Error('no samples found');
    const ft = ftyp && String.fromCharCode(...ftyp.slice(8, 12)) !== 'iso5' ? ftyp : mk('ftyp', new Uint8Array([...enc('isom'), 0, 0, 2, 0, ...enc('isom'), ...enc('iso2'), ...enc('avc1'), ...enc('mp41')]));
    const mdatHdr = pos + 8 > 4294967295 ? 16 : 8;
    let co64 = false;
    let moov = buildMoov(tracks, 0, false);
    if (ft.length + moov.length + mdatHdr + pos > 4294967295) { co64 = true; moov = buildMoov(tracks, 0, true); }
    const mdatStart = ft.length + moov.length + mdatHdr;
    moov = buildMoov(tracks, mdatStart, co64);
    let mh;
    if (mdatHdr === 16) { mh = new Uint8Array(16); const v = new DataView(mh.buffer); v.setUint32(0, 1); mh.set(enc('mdat'), 4); v.setUint32(8, Math.floor((pos + 16) / 4294967296)); v.setUint32(12, (pos + 16) >>> 0); }
    else { mh = new Uint8Array(8); new DataView(mh.buffer).setUint32(0, pos + 8); mh.set(enc('mdat'), 4); }
    const out = [ft, moov, mh];
    for (const s of slices) out.push(s.blob.slice(s.off, s.off + s.len));
    return new Blob(out, { type: 'video/mp4' });
  }
  const remux = (parts, onProgress) => remuxCore([parts], onProgress);

  // ----- two ordinary (non-fragmented) MP4 files: video-only + audio-only -> one MP4 (no re-encoding) -----
  async function topBoxes(blob) { // reads box headers only, never the media data
    const out = [];
    let p = 0;
    while (p + 8 <= blob.size) {
      const h = new Uint8Array(await blob.slice(p, p + 16).arrayBuffer());
      const v = new DataView(h.buffer);
      let len = v.getUint32(0), hdr = 8;
      const t = type4(h, 4);
      if (len === 1) { len = v.getUint32(8) * 4294967296 + v.getUint32(12); hdr = 16; }
      else if (len === 0) len = blob.size - p;
      if (len < hdr || p + len > blob.size) break;
      out.push({ t, s: p, e: p + len, hdr });
      p += len;
    }
    return out;
  }
  async function readProgressive(blob) {
    const top = await topBoxes(blob);
    if (top.some((x) => x.t === 'moof')) throw new Error('fragmented');
    const moov = top.find((x) => x.t === 'moov'), mdats = top.filter((x) => x.t === 'mdat'), ft = top.find((x) => x.t === 'ftyp');
    if (!moov || mdats.length !== 1) throw new Error('unsupported MP4 layout (needs exactly one moov and one mdat)');
    const get = async (x) => new Uint8Array(await blob.slice(x.s, x.e).arrayBuffer());
    return { moovBytes: await get(moov), ftyp: ft ? await get(ft) : null, mdat: mdats[0], blob };
  }
  const CONTAINERS = new Set(['trak', 'mdia', 'minf', 'stbl', 'edts']);
  // copy of a trak with: new track id, durations rescaled to the movie timescale, chunk offsets moved by delta
  function patchTrak(trak, o) {
    const b = trak.slice();
    const v = new DataView(b.buffer);
    const rd = (x) => x * 4294967296;
    (function walk(start, end) {
      for (const x of boxes(b, start, end)) {
        if (x.t === 'tkhd') {
          const ver = b[x.d];
          if (o.newId != null) v.setUint32(x.d + (ver === 1 ? 20 : 12), o.newId);
          if (o.scale !== 1) {
            if (ver === 1) { const d = Math.round((v.getUint32(x.d + 28) * 4294967296 + v.getUint32(x.d + 32)) * o.scale); v.setUint32(x.d + 28, Math.floor(d / 4294967296)); v.setUint32(x.d + 32, d >>> 0); }
            else v.setUint32(x.d + 20, Math.round(v.getUint32(x.d + 20) * o.scale));
          }
        } else if (x.t === 'elst' && o.scale !== 1) {
          const ver = b[x.d], n = v.getUint32(x.d + 4), step = ver === 1 ? 20 : 12;
          for (let i = 0; i < n; i++) {
            const q = x.d + 8 + i * step;
            if (ver === 1) { const d = Math.round((v.getUint32(q) * 4294967296 + v.getUint32(q + 4)) * o.scale); v.setUint32(q, Math.floor(d / 4294967296)); v.setUint32(q + 4, d >>> 0); }
            else v.setUint32(q, Math.round(v.getUint32(q) * o.scale));
          }
        } else if (x.t === 'stco') {
          const n = v.getUint32(x.d + 4);
          for (let i = 0; i < n; i++) { const q = x.d + 8 + i * 4, val = v.getUint32(q) + o.delta; if (val < 0 || val > 4294967295) throw new Error('file too large for 32-bit offsets'); v.setUint32(q, val); }
        } else if (x.t === 'co64') {
          const n = v.getUint32(x.d + 4);
          for (let i = 0; i < n; i++) { const q = x.d + 8 + i * 8, val = v.getUint32(q) * 4294967296 + v.getUint32(q + 4) + o.delta; v.setUint32(q, Math.floor(val / 4294967296)); v.setUint32(q + 4, val >>> 0); }
        } else if (CONTAINERS.has(x.t)) walk(x.d, x.e);
      }
    })(0, b.length);
    void rd;
    return b;
  }
  const mvhdInfo = (b, kid) => { const v = new DataView(b.buffer, b.byteOffset + kid.d); const ver = b[kid.d]; return ver === 1 ? { ts: v.getUint32(20), dur: v.getUint32(24) * 4294967296 + v.getUint32(28), durOff: 24, nextOff: 108, ver } : { ts: v.getUint32(12), dur: v.getUint32(16), durOff: 16, nextOff: 96, ver }; };
  async function mergeFiles(blobV, blobA) {
    const V = await readProgressive(blobV), A = await readProgressive(blobA);
    const vm = boxes(V.moovBytes, 0, V.moovBytes.length)[0], am = boxes(A.moovBytes, 0, A.moovBytes.length)[0];
    const vk = boxes(V.moovBytes, vm.d, vm.e), ak = boxes(A.moovBytes, am.d, am.e);
    const vh = vk.find((x) => x.t === 'mvhd'), ah = ak.find((x) => x.t === 'mvhd');
    if (!vh || !ah) throw new Error('mvhd missing');
    const vI = mvhdInfo(V.moovBytes, vh), aI = mvhdInfo(A.moovBytes, ah);
    const scale = vI.ts / aI.ts;
    const trakIds = (b, kids) => kids.filter((x) => x.t === 'trak').map((t) => { const tk = boxes(b, t.d, t.e).find((x) => x.t === 'tkhd'); return b[tk.d] === 1 ? new DataView(b.buffer, b.byteOffset + tk.d).getUint32(20) : new DataView(b.buffer, b.byteOffset + tk.d).getUint32(12); });
    const used = new Set(trakIds(V.moovBytes, vk));
    let nextId = Math.max(0, ...used) + 1;
    const aIds = trakIds(A.moovBytes, ak);
    const build = (dV, dA) => {
      const parts = [];
      for (const x of vk) {
        if (x.t === 'trak') parts.push(patchTrak(V.moovBytes.slice(x.s, x.e), { delta: dV, scale: 1 }));
        else if (x.t === 'mvhd') {
          const m = V.moovBytes.slice(x.s, x.e), dv = new DataView(m.buffer);
          const aDur = Math.round(aI.dur * scale);
          if (aDur > vI.dur) { if (vI.ver === 1) { dv.setUint32(8 + vI.durOff, Math.floor(aDur / 4294967296)); dv.setUint32(12 + vI.durOff, aDur >>> 0); } else dv.setUint32(8 + vI.durOff, aDur); }
          dv.setUint32(8 + vI.nextOff, nextId + aIds.length);
          parts.push(m);
        } else parts.push(V.moovBytes.slice(x.s, x.e));
      }
      let k = 0;
      for (const x of ak) if (x.t === 'trak') {
        const want = aIds[k++];
        const nid = used.has(want) ? nextId++ : want;
        parts.push(patchTrak(A.moovBytes.slice(x.s, x.e), { newId: nid, delta: dA, scale }));
      }
      nextId = Math.max(0, ...used) + 1;
      return mk('moov', ...parts);
    };
    const ft = V.ftyp || mk('ftyp', new Uint8Array([...enc('isom'), 0, 0, 2, 0, ...enc('isom'), ...enc('mp41')]));
    const moovLen = build(0, 0).length;
    const vLen = V.mdat.e - V.mdat.s;
    const dV = ft.length + moovLen - V.mdat.s;
    const dA = ft.length + moovLen + vLen - A.mdat.s;
    const moov = build(dV, dA);
    return new Blob([ft, moov, V.blob.slice(V.mdat.s, V.mdat.e), A.blob.slice(A.mdat.s, A.mdat.e)], { type: 'video/mp4' });
  }
  async function isProgressive(arr) {
    if (arr.length !== 1) return false;
    const bl = arr[0] instanceof Blob ? arr[0] : new Blob([arr[0]]);
    const top = await topBoxes(bl);
    return top.some((x) => x.t === 'moov') && top.some((x) => x.t === 'mdat') && !top.some((x) => x.t === 'moof');
  }
  // video + audio (each a list of pieces; or one whole file) -> one MP4 Blob
  async function mergeMedia(v, a, onProgress) {
    const pv = await isProgressive(v), pa = await isProgressive(a);
    if (pv && pa) return mergeFiles(v[0] instanceof Blob ? v[0] : new Blob([v[0]]), a[0] instanceof Blob ? a[0] : new Blob([a[0]]));
    if (pv !== pa && (v.length === 1 || a.length === 1) && false) throw new Error('mixed layouts');
    return remuxCore([v, a], onProgress);
  }

  root.remuxFmp4 = remux;
  root.mergeMedia = mergeMedia;
  if (typeof module !== 'undefined' && module.exports) module.exports = { remux, mergeMedia };
})(typeof self !== 'undefined' ? self : this);
