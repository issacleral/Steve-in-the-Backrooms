"""Read files out of Escape the Backrooms' pak (UE 4.27, pak v11, Zlib). Read-only.

    python -I recon/etbpak.py index            build the index cache
    python -I recon/etbpak.py find <text>      list paths containing <text>
"""
import json
import os
import struct
import sys
import zlib

PAK = r"C:/Program Files (x86)/Steam/steamapps/common/EscapeTheBackrooms/EscapeTheBackrooms/Content/Paks/EscapeTheBackrooms-WindowsNoEditor.pak"
CACHE = os.environ.get("ETB_CACHE") or os.path.join(os.environ["TEMP"], "etb-recon")
INDEX_FILE = os.path.join(CACHE, "index.json")
_index = None


def fstr(b, o):
    n, = struct.unpack_from('<i', b, o); o += 4
    if n >= 0:
        s = b[o:o + n].split(b'\0')[0].decode('latin1'); o += n
    else:
        s = b[o:o - 2 * n].decode('utf-16-le').rstrip('\0'); o += -2 * n
    return s, o


def decode(enc, o):
    v, = struct.unpack_from('<I', enc, o); o += 4
    bs = v & 0x3f
    if bs == 0x3f:
        bs, = struct.unpack_from('<I', enc, o); o += 4
    else:
        bs <<= 11
    nblocks = (v >> 6) & 0xffff; encr = (v >> 22) & 1; meth = (v >> 23) & 0x3f
    if v >> 31: off, = struct.unpack_from('<I', enc, o); o += 4
    else: off, = struct.unpack_from('<Q', enc, o); o += 8
    if (v >> 30) & 1: usz, = struct.unpack_from('<I', enc, o); o += 4
    else: usz, = struct.unpack_from('<Q', enc, o); o += 8
    if meth:
        if (v >> 29) & 1: sz, = struct.unpack_from('<I', enc, o); o += 4
        else: sz, = struct.unpack_from('<Q', enc, o); o += 8
    else:
        sz = usz
    blocks = []
    hdr = 53 + (4 + 16 * nblocks if meth else 0)
    if nblocks == 1 and not encr: blocks = [sz]
    elif nblocks > 0:
        blocks = list(struct.unpack_from('<%dI' % nblocks, enc, o)); o += 4 * nblocks
    return dict(off=off, size=sz, usize=usz, meth=meth, enc=encr, bs=bs, blocks=blocks, hdr=hdr)


def build_index():
    sz = os.path.getsize(PAK)
    with open(PAK, 'rb') as h:
        h.seek(sz - 221); ft = h.read(221)
        assert struct.unpack_from('<I', ft, 17)[0] == 0x5A6F12E1
        ver, ioff, isz = struct.unpack_from('<IQQ', ft, 21)
        h.seek(ioff); b = h.read(isz)
        mount, o = fstr(b, 0)
        n, seed = struct.unpack_from('<iQ', b, o); o += 12
        has, = struct.unpack_from('<I', b, o); o += 4
        if has: o += 8 + 8 + 20
        has, = struct.unpack_from('<I', b, o); o += 4
        assert has
        doff, dsz = struct.unpack_from('<qq', b, o); o += 16 + 20
        esz, = struct.unpack_from('<i', b, o); o += 4
        enc = b[o:o + esz]
        h.seek(doff); d = h.read(dsz)
    out = {}
    nd, = struct.unpack_from('<i', d, 0); o = 4
    for _ in range(nd):
        dn, o = fstr(d, o); nf, = struct.unpack_from('<i', d, o); o += 4
        for _ in range(nf):
            fn, o = fstr(d, o); eo, = struct.unpack_from('<i', d, o); o += 4
            if eo >= 0: out[(mount + dn + fn).replace('../../../', '')] = decode(enc, eo)
    os.makedirs(CACHE, exist_ok=True)
    json.dump(out, open(INDEX_FILE, 'w'))
    return out


def index():
    global _index
    if _index is None:
        _index = json.load(open(INDEX_FILE)) if os.path.isfile(INDEX_FILE) else build_index()
    return _index


def exists(path):
    return path in index()


def read(path):
    e = index()[path]
    with open(PAK, 'rb') as h:
        h.seek(e['off'] + e['hdr'])
        if not e['meth']: return h.read(e['usize'])
        out = bytearray(); left = e['usize']; bs = e['bs'] or e['usize']
        for csz in e['blocks']:
            src = h.read(csz); n = min(bs, left)
            out += zlib.decompress(src) if e['meth'] == 1 else src
            left -= n
        assert len(out) == e['usize'], (path, len(out), e['usize'])
        return bytes(out)


def find(sub):
    return sorted(k for k in index() if sub.lower() in k.lower())


if __name__ == '__main__':
    if sys.argv[1] == 'index':
        print(len(build_index()), 'files')
    elif sys.argv[1] == 'find':
        for k in find(sys.argv[2]):
            print('%12d  %s' % (index()[k]['usize'], k))
