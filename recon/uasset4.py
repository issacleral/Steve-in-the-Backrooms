"""Minimal reader for Escape the Backrooms' cooked UE 4.27 packages: names, imports, exports, tagged properties."""
import struct

import etbpak as p


class Pkg:
    def __init__(s, path):
        """path without extension, e.g. EscapeTheBackrooms/Content/Maps/Level0 (.umap or .uasset is found)."""
        s.path = path
        head = path + ('.umap' if p.exists(path + '.umap') else '.uasset')
        a = s.a = p.read(head); s.u = p.read(path + '.uexp')
        assert a[:4] == b'\xc1\x83\x2a\x9e'
        o = 4 + 4 * 4; ncv, = struct.unpack_from('<i', a, o); o += 4 + ncv * 20
        s.hdr, = struct.unpack_from('<i', a, o); o += 4
        n, = struct.unpack_from('<i', a, o); o += 4 + n
        s.flags, = struct.unpack_from('<I', a, o); o += 4
        nc, no, gc, go, ec, eo, ic, io, do = struct.unpack_from('<9i', a, o)
        s.names = []; q = no
        for _ in range(nc):
            l, = struct.unpack_from('<i', a, q); q += 4
            if l >= 0: s.names.append(a[q:q + l - 1].decode('latin1')); q += l
            else: s.names.append(a[q:q - 2 * l - 2].decode('utf-16-le')); q += -2 * l
            q += 4
        isz = (eo - io) // ic if ic else 0; s.imports = []
        for i in range(ic):
            q = io + i * isz; cp, _, cn, _, outer, on, num = struct.unpack_from('<iiiiiii', a, q)
            s.imports.append(dict(cls=s.names[cn], outer=outer, name=s.names[on] + ('_%d' % (num - 1) if num else '')))
        esz = (do - eo) // ec if ec else 0; s.exports = []
        for i in range(ec):
            q = eo + i * esz; ci, si, ti, oi, on, num, fl, ssz, sof = struct.unpack_from('<iiiiiiIqq', a, q)
            s.exports.append(dict(cls=ci, sup=si, tmpl=ti, outer=oi, name=s.names[on] + ('_%d' % (num - 1) if num else ''),
                                  size=ssz, off=sof - s.hdr, flags=fl))

    def fname(s, o):
        i, n = struct.unpack_from('<ii', s.u, o); return s.names[i] + ('_%d' % (n - 1) if n else '')

    def clsname(s, e):
        c = e['cls']
        if c < 0: return s.imports[-c - 1]['name']
        if c > 0: return s.exports[c - 1]['name']
        return 'Class'

    def objname(s, idx):
        if idx < 0: return s.imports[-idx - 1]['name']
        if idx > 0: return s.exports[idx - 1]['name']
        return None

    def objpath(s, idx):
        """Package path (/Game/...) of an imported object, or None for exports/null."""
        if idx >= 0: return None
        im = s.imports[-idx - 1]
        while im['outer'] < 0: im = s.imports[-im['outer'] - 1]
        return im['name']

    def props(s, e):
        """Tagged properties of an export -> (dict name -> value, offset after the None terminator)."""
        return read_props(s, e['off'])


def game_path(pkgpath):
    """/Game/Foo/Bar -> EscapeTheBackrooms/Content/Foo/Bar"""
    if pkgpath.startswith('/Game/'): return 'EscapeTheBackrooms/Content/' + pkgpath[6:]
    if pkgpath.startswith('/Engine/'): return 'Engine/Content/' + pkgpath[8:]
    return None


STRUCTS = {
    'Vector': '<3f', 'Rotator': '<3f', 'Vector2D': '<2f', 'Quat': '<4f', 'Vector4': '<4f',
    'LinearColor': '<4f', 'Color': '<4B', 'IntPoint': '<2i', 'Guid': '<4I', 'Box': '<6fB',
}


def read_value(s, typ, o, size, tag):
    u = s.u
    if typ == 'ObjectProperty': return ('obj', struct.unpack_from('<i', u, o)[0])
    if typ == 'FloatProperty': return struct.unpack_from('<f', u, o)[0]
    if typ == 'IntProperty': return struct.unpack_from('<i', u, o)[0]
    if typ == 'UInt32Property': return struct.unpack_from('<I', u, o)[0]
    if typ == 'NameProperty': return s.fname(o)
    if typ == 'EnumProperty': return s.fname(o)
    if typ == 'ByteProperty': return s.fname(o) if size == 8 else u[o]
    if typ == 'StrProperty': return p.fstr(u, o)[0]
    if typ == 'StructProperty':
        fmt = STRUCTS.get(tag.get('struct'))
        if fmt and struct.calcsize(fmt) <= size: return struct.unpack_from(fmt, u, o)
        if tag.get('struct') in ('Transform',) or size > 8:
            try:
                d, end = read_props(s, o)
                if end == o + size: return d
            except Exception:
                pass
        return ('raw', o, size)
    if typ == 'ArrayProperty':
        n, = struct.unpack_from('<i', u, o)
        inner = tag.get('inner')
        if inner == 'ObjectProperty': return [('obj', x) for x in struct.unpack_from('<%di' % n, u, o + 4)]
        if inner == 'FloatProperty': return list(struct.unpack_from('<%df' % n, u, o + 4))
        if inner == 'IntProperty': return list(struct.unpack_from('<%di' % n, u, o + 4))
        if inner == 'NameProperty': return [s.fname(o + 4 + 8 * i) for i in range(n)]
        if inner == 'StructProperty':
            # one inner tag (name, type, size, index, struct name, guid, has-guid byte), then n structs
            q = o + 4 + 8 + 8 + 8
            sname = s.fname(q); q += 8 + 16 + 1
            fmt = STRUCTS.get(sname)
            out = []
            try:
                for _ in range(n):
                    if fmt:
                        out.append(struct.unpack_from(fmt, u, q)); q += struct.calcsize(fmt)
                    else:
                        d, q = read_props(s, q); out.append(d)
                if q == o + size: return out
            except Exception:
                pass
        return ('array', inner, n, o + 4, size - 4)
    return ('raw', o, size)


def read_props(s, o):
    u = s.u; out = {}
    while True:
        name = s.fname(o); o += 8
        if name == 'None': return out, o
        typ = s.fname(o); o += 8
        size, aidx = struct.unpack_from('<ii', u, o); o += 8
        tag = {}
        if typ == 'StructProperty': tag['struct'] = s.fname(o); o += 8 + 16
        elif typ == 'BoolProperty': tag['bool'] = u[o]; o += 1
        elif typ in ('ByteProperty', 'EnumProperty'): tag['enum'] = s.fname(o); o += 8
        elif typ in ('ArrayProperty', 'SetProperty'): tag['inner'] = s.fname(o); o += 8
        elif typ == 'MapProperty': tag['inner'] = s.fname(o); tag['value'] = s.fname(o + 8); o += 16
        if u[o]: o += 16
        o += 1
        val = tag['bool'] != 0 if typ == 'BoolProperty' else read_value(s, typ, o, size, tag)
        key = name if aidx == 0 else '%s[%d]' % (name, aidx)
        out[key] = val
        o += size
