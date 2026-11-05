#!/usr/bin/env python3
"""Read-only HEIF inspector: lists items, aux types, hvcC chroma format, sizes, irot.

Mirrors the Kotlin parser in the app so the two can be checked against each other.
"""
import struct, sys

def boxes(buf, start, end):
    p = start
    while p + 8 <= end:
        size, typ = struct.unpack('>I4s', buf[p:p+8])
        hdr = 8
        if size == 1:
            size = struct.unpack('>Q', buf[p+8:p+16])[0]; hdr = 16
        elif size == 0:
            size = end - p
        yield typ.decode('latin1'), p + hdr, p + size
        p += size

def rd(buf, p, n):
    return int.from_bytes(buf[p:p+n], 'big') if n else 0

def inspect(path):
    buf = open(path, 'rb').read()
    meta = next((b, e) for t, b, e in boxes(buf, 0, len(buf)) if t == 'meta')
    ms, me = meta[0] + 4, meta[1]
    items, props, assoc, refs, locs, primary = {}, [], {}, [], {}, None
    for t, b, e in boxes(buf, ms, me):
        if t == 'pitm':
            primary = rd(buf, b + 4, 2 if buf[b] == 0 else 4)
        elif t == 'iinf':
            v = buf[b]; p = b + 4 + (2 if v == 0 else 4)
            for t2, b2, e2 in boxes(buf, p, e):
                iv = buf[b2]; q = b2 + 4
                iid = rd(buf, q, 2 if iv < 3 else 4); q += 2 if iv < 3 else 4
                q += 2
                items[iid] = buf[q:q+4].decode('latin1')
        elif t == 'iref':
            v = buf[b]; w = 2 if v == 0 else 4
            for t2, b2, e2 in boxes(buf, b + 4, e):
                frm = rd(buf, b2, w); n = rd(buf, b2 + w, 2)
                to = [rd(buf, b2 + w + 2 + i * w, w) for i in range(n)]
                refs.append((t2, frm, to))
        elif t == 'iloc':
            v = buf[b]; p = b + 4
            os_, ls = buf[p] >> 4, buf[p] & 15; bs, xs = buf[p+1] >> 4, buf[p+1] & 15; p += 2
            cnt = rd(buf, p, 2 if v < 2 else 4); p += 2 if v < 2 else 4
            for _ in range(cnt):
                iid = rd(buf, p, 2 if v < 2 else 4); p += 2 if v < 2 else 4
                method = 0
                if v in (1, 2): method = rd(buf, p, 2) & 15; p += 2
                p += 2
                base = rd(buf, p, bs); p += bs
                ec = rd(buf, p, 2); p += 2
                ext = []
                for _ in range(ec):
                    if v in (1, 2) and xs: p += xs
                    off = rd(buf, p, os_); p += os_
                    ln = rd(buf, p, ls); p += ls
                    ext.append((base + off, ln))
                locs[iid] = (method, ext)
        elif t == 'iprp':
            for t2, b2, e2 in boxes(buf, b, e):
                if t2 == 'ipco':
                    for t3, b3, e3 in boxes(buf, b2, e2):
                        props.append((t3, buf[b3:e3]))
                elif t2 == 'ipma':
                    v = buf[b2]; fl = rd(buf, b2 + 1, 3); p = b2 + 4
                    n = rd(buf, p, 4); p += 4
                    for _ in range(n):
                        iid = rd(buf, p, 2 if v < 1 else 4); p += 2 if v < 1 else 4
                        k = buf[p]; p += 1
                        lst = []
                        for _ in range(k):
                            if fl & 1: x = rd(buf, p, 2) & 0x7fff; p += 2
                            else: x = buf[p] & 0x7f; p += 1
                            lst.append(x)
                        assoc[iid] = lst
    print(f'== {path}\n primary={primary} items={len(items)}')
    for r in refs:
        if r[0] in ('auxl', 'dimg') and len(r[2]) < 5: print('  ref', r)
        elif r[0] == 'auxl' or r[0] == 'dimg': print('  ref', r[0], r[1], f'-> {len(r[2])} items')
    for iid, typ in items.items():
        desc = []
        for idx in assoc.get(iid, []):
            if idx == 0: continue
            pt, pd = props[idx - 1]
            if pt == 'ispe': desc.append('%dx%d' % struct.unpack('>II', pd[4:12]))
            elif pt == 'auxC': desc.append('aux=' + pd[4:].split(b'\0')[0].decode())
            elif pt == 'hvcC': desc.append('chroma=%d bitdepth=%d' % (pd[16] & 3, (pd[17] & 7) + 8))
            elif pt == 'irot': desc.append('irot=%d' % (pd[0] & 3))
            elif pt == 'imir': desc.append('imir')
        if typ != 'hvc1' or desc and any(d.startswith('aux') for d in desc) or iid == primary:
            print(f'  item {iid} {typ} {" ".join(desc)} loc={locs.get(iid)}' if typ != 'hvc1' or 'aux' in ' '.join(desc) or iid==primary else '')

for f in sys.argv[1:]:
    try: inspect(f)
    except Exception as ex: print('ERR', f, ex)
