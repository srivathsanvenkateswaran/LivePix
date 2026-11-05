#!/usr/bin/env python3
"""Print the Live Photo content identifier (Apple MakerNote tag 0x11) of each file, if any."""
import struct, sys

def cid(path):
    data = open(path, 'rb').read(2_000_000)
    i = data.find(b'Apple iOS\x00')
    if i < 0:
        return None
    note = data[i:]
    big = note[12:14] == b'MM'
    e = '>' if big else '<'
    count = struct.unpack(e + 'H', note[14:16])[0]
    for k in range(count):
        p = 16 + k * 12
        tag, typ, n, val = struct.unpack(e + 'HHII', note[p:p + 12])
        if tag == 0x11 and typ == 2:
            raw = note[p + 8:p + 8 + n] if n <= 4 else note[val:val + n]
            return raw.rstrip(b'\x00').decode()
    return None

for f in sys.argv[1:]:
    print(f, cid(f))
