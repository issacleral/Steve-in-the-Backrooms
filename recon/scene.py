"""Rebuild where everything is in an Escape the Backrooms map: mesh instances, lights, player starts, actors.

    python recon/scene.py Maps/Level0 [more maps...]     -> <cache>/scene_<name>.json and a summary

Component values come from the level instance first, then from the blueprint template it was made from.
Matrices are UE row-vector 4x4 (world = v * M), in centimetres, Z up.
"""
import collections
import json
import math
import os
import struct
import sys

import etbpak as p
import uasset4

_pkgs = {}


def pkg(path):
    if path not in _pkgs:
        try:
            _pkgs[path] = uasset4.Pkg(path)
        except Exception:
            _pkgs[path] = None
    return _pkgs[path]


def rot_matrix(pitch, yaw, roll):
    sp, cp = math.sin(math.radians(pitch)), math.cos(math.radians(pitch))
    sy, cy = math.sin(math.radians(yaw)), math.cos(math.radians(yaw))
    sr, cr = math.sin(math.radians(roll)), math.cos(math.radians(roll))
    return [[cp * cy, cp * sy, sp],
            [sr * sp * cy - cr * sy, sr * sp * sy + cr * cy, -sr * cp],
            [-(cr * sp * cy + sr * sy), cy * sr - cr * sp * sy, cr * cp]]


def trs(loc, rot, scale):
    r = rot_matrix(*rot)
    return [[r[0][0] * scale[0], r[0][1] * scale[0], r[0][2] * scale[0], 0.0],
            [r[1][0] * scale[1], r[1][1] * scale[1], r[1][2] * scale[1], 0.0],
            [r[2][0] * scale[2], r[2][1] * scale[2], r[2][2] * scale[2], 0.0],
            [loc[0], loc[1], loc[2], 1.0]]


def mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


IDENT = [[1.0, 0, 0, 0], [0, 1.0, 0, 0], [0, 0, 1.0, 0], [0, 0, 0, 1.0]]


class Scene:
    def __init__(s, map_path):
        s.k = pkg(map_path)
        s.cache = {}
        s.world = {}

    def template_props(s, k, e, depth=0):
        """Properties of an export merged over its template chain. Object values become ('ref', path, name)."""
        out = {}
        t = e['tmpl']
        if depth < 6:
            if t > 0:
                out.update(s.template_props(k, k.exports[t - 1], depth + 1))
            elif t < 0:
                im = k.imports[-t - 1]
                home = uasset4.game_path(k.objpath(t) or '')
                tk = pkg(home) if home and p.exists(home + '.uexp') else None
                if tk:
                    outer_name = k.imports[-im['outer'] - 1]['name'] if im['outer'] < 0 else None
                    for te in tk.exports:
                        if te['name'] == im['name'] and (outer_name is None or tk.objname(te['outer']) in (outer_name, None)):
                            out.update(s.template_props(tk, te, depth + 1))
                            break
        try:
            d, end = k.props(e)
        except Exception:
            return out
        for n, v in d.items():
            out[n] = s.portable(k, v)
        out['__end'] = (k, end, e)
        return out

    def portable(s, k, v):
        if isinstance(v, tuple) and v and v[0] == 'obj':
            return ('ref', k.objpath(v[1]), k.objname(v[1]), (k, v[1]))
        if isinstance(v, list):
            return [s.portable(k, x) for x in v]
        return v

    def props(s, i):
        if i not in s.cache:
            s.cache[i] = s.template_props(s.k, s.k.exports[i - 1])
        return s.cache[i]

    def world_matrix(s, i):
        """World matrix of component export i (1-based)."""
        if i in s.world:
            return s.world[i]
        s.world[i] = IDENT
        d = s.props(i)
        m = trs(d.get('RelativeLocation', (0, 0, 0)), d.get('RelativeRotation', (0, 0, 0)), d.get('RelativeScale3D', (1, 1, 1)))
        parent = d.get('AttachParent')
        if parent and parent[3][0] is s.k and parent[3][1] > 0:
            m = mul(m, s.world_matrix(parent[3][1]))
        s.world[i] = m
        return m

    def instances(s, i):
        """Per-instance matrices of an (H)ISM component, read from the native data after its own properties."""
        k, end, e = s.props(i)['__end']
        if k is not s.k:
            return []
        u = k.u; o = end + 4
        nlod, = struct.unpack_from('<i', u, o); o += 4
        for _ in range(nlod):
            strip = u[o + 1]; o += 2
            o += 16
            if not strip & 1:
                has, = struct.unpack_from('<B', u, o); o += 1
                if has:
                    raise IOError('vertex colour overrides not handled')
        cooked, = struct.unpack_from('<i', u, o); o += 4
        size, n = struct.unpack_from('<ii', u, o); o += 8
        if size != 64 or o + n * 64 > e['off'] + e['size']:
            raise IOError('instance block not found (%d,%d)' % (size, n))
        out = []
        for j in range(n):
            f = struct.unpack_from('<16f', u, o + j * 64)
            out.append([list(f[0:4]), list(f[4:8]), list(f[8:12]), list(f[12:16])])
        return out


def build(map_rel):
    sc = Scene('EscapeTheBackrooms/Content/' + map_rel)
    k = sc.k
    meshes, lights, starts, actors, problems = [], [], [], [], collections.Counter()
    for n, e in enumerate(k.exports):
        i = n + 1
        c = k.clsname(e)
        outer_cls = k.clsname(k.exports[e['outer'] - 1]) if e['outer'] > 0 else ''
        if c in ('StaticMeshComponent', 'InstancedStaticMeshComponent', 'HierarchicalInstancedStaticMeshComponent'):
            d = sc.props(i)
            sm = d.get('StaticMesh')
            if not sm or not sm[1]:
                problems['mesh component without a mesh (%s)' % outer_cls] += 1
                continue
            if d.get('bVisible') is False or d.get('bHiddenInGame') is True:
                problems['hidden mesh'] += 1
                continue
            mats = [(m[1] if m else None) for m in d.get('OverrideMaterials', [])]
            w = sc.world_matrix(i)
            body = d.get('BodyInstance') or {}
            coll = body.get('CollisionEnabled', '') if isinstance(body, dict) else ''
            base = dict(mesh=sm[1], mats=mats, owner=k.objname(e['outer']), ownercls=outer_cls, coll=str(coll).split('::')[-1])
            if c == 'StaticMeshComponent':
                meshes.append(dict(base, m=sum(w, [])))
            else:
                try:
                    for im in sc.instances(i):
                        meshes.append(dict(base, m=sum(mul(im, w), [])))
                except Exception as ex:
                    problems['instances: %s' % ex] += 1
        elif c in ('SpotLightComponent', 'PointLightComponent', 'RectLightComponent'):
            d = sc.props(i); w = sc.world_matrix(i)
            lights.append(dict(type=c, pos=w[3][:3], dir=w[0][:3], intensity=d.get('Intensity'), color=d.get('LightColor'),
                               radius=d.get('AttenuationRadius'), outer=d.get('OuterConeAngle'), inner=d.get('InnerConeAngle'),
                               owner=k.objname(e['outer']), visible=d.get('bVisible', True)))
        elif e['outer'] > 0 and k.clsname(k.exports[e['outer'] - 1]) == 'Level' and c not in ('Model',):
            try:
                d = sc.props(i)
            except Exception:
                continue
            root = d.get('RootComponent')
            pos = None
            if root and root[3][0] is k and root[3][1] > 0:
                pos = sc.world_matrix(root[3][1])[3][:3]
            if c == 'PlayerStart':
                starts.append(dict(pos=pos, tags=d.get('Tags', []), name=e['name']))
            elif c != 'StaticMeshActor':
                actors.append(dict(cls=c, name=e['name'], pos=pos))
    return dict(map=map_rel, meshes=meshes, lights=lights, starts=starts, actors=actors), problems


if __name__ == '__main__':
    for map_rel in sys.argv[1:]:
        scene, problems = build(map_rel)
        out = os.path.join(p.CACHE, 'scene_%s.json' % map_rel.replace('/', '_'))
        json.dump(scene, open(out, 'w'))
        ms = scene['meshes']
        print('=====', map_rel, '->', out)
        print('mesh instances', len(ms), 'distinct meshes', len(set(m['mesh'] for m in ms)), 'lights', len(scene['lights']),
              'starts', len(scene['starts']), 'actors', len(scene['actors']))
        if ms:
            xs = [m['m'][12] for m in ms]; ys = [m['m'][13] for m in ms]; zs = [m['m'][14] for m in ms]
            print('bounds cm x %.0f..%.0f  y %.0f..%.0f  z %.0f..%.0f' % (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)))
            print('instances at the origin:', sum(1 for m in ms if abs(m['m'][12]) + abs(m['m'][13]) + abs(m['m'][14]) < 1))
        for name, cnt in collections.Counter(m['mesh'] for m in ms).most_common(25):
            print('   %5d %s' % (cnt, name))
        print('problems:', dict(problems))
        print('starts:', [(s['tags'], [round(v) for v in s['pos']] if s['pos'] else None) for s in scene['starts']])
        print('actor classes:', collections.Counter(a['cls'] for a in scene['actors']).most_common(60))
