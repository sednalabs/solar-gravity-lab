#!/usr/bin/env python3
"""Compile the supported glTF 2.0 mesh subset into deterministic native geometry.

Triangles, FLOAT POSITION/NORMAL, unsigned indices, node TRS/matrix hierarchy,
and metallic-roughness material factors are supported. Textured/skinned/morphed
assets are rejected explicitly instead of being rendered incorrectly. This is
an offline asset importer, not a parser on the Android render thread.
Source and output paths are confined to independently selected trusted roots
(the working directory by default). External buffers stay inside the source
asset's directory; the document cannot select filesystem roots or remote URLs.
"""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import struct
from urllib.parse import unquote, urlsplit

IDENTITY = [1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.]


def path_within_root(path, root, label):
    """Resolve one file beneath a caller-selected root, including symlinks.

    Roots are operator configuration, never values read from an asset document.
    The trailing separator prevents a similarly named sibling from matching.
    """
    root = os.path.realpath(os.getcwd() if root is None else root)
    resolved = os.path.realpath(os.path.join(root, path))
    if not resolved.startswith(os.path.join(root, '')):
        raise ValueError(label + ' escapes its configured directory')
    return Path(resolved)


def external_buffer_path(uri, asset_directory):
    """Accept only local relative glTF URIs beneath this asset's directory."""
    if not isinstance(uri, str) or any(ord(c) < 32 for c in uri):
        raise ValueError('buffer URI must be a local relative path')
    parts = urlsplit(uri)
    if parts.scheme or parts.netloc or parts.query or parts.fragment:
        raise ValueError('buffer URI must be a local relative path')
    relative = unquote(parts.path, encoding='utf-8', errors='strict')
    if not relative or '\\' in relative or ':' in relative or '\x00' in relative or os.path.isabs(relative):
        raise ValueError('buffer URI must be a local relative path')
    return path_within_root(relative, asset_directory, 'buffer')


def multiply(a, b):
    return [sum(a[k * 4 + row] * b[col * 4 + k] for k in range(4))
            for col in range(4) for row in range(4)]


def transform(m, v):
    return [sum(m[k * 4 + row] * v[k] for k in range(3)) + m[12 + row]
            for row in range(3)]


def normal_transform(m, n):
    a, b, c, d, e, f, g, h, i = (m[0], m[4], m[8], m[1], m[5], m[9], m[2], m[6], m[10])
    cofactor = [e*i-f*h, f*g-d*i, d*h-e*g, c*h-b*i, a*i-c*g, b*g-a*h, b*f-c*e, c*d-a*f, a*e-b*d]
    det = a*cofactor[0] + b*cofactor[1] + c*cofactor[2]
    if not math.isfinite(det) or abs(det) < 1e-15:
        raise ValueError('singular node transform')
    v = [sum(cofactor[row*3+k] * n[k] / det for k in range(3)) for row in range(3)]
    length = math.sqrt(sum(x*x for x in v))
    if length < 1e-12:
        raise ValueError('zero normal')
    return [x/length for x in v]


def node_matrix(node):
    if 'matrix' in node:
        if any(k in node for k in ('translation', 'rotation', 'scale')):
            raise ValueError('matrix and TRS cannot be combined')
        if len(node['matrix']) != 16:
            raise ValueError('node matrix must have 16 values')
        return node['matrix']
    x, y, z, w = node.get('rotation', [0, 0, 0, 1])
    if abs(x*x+y*y+z*z+w*w-1) > 1e-5:
        raise ValueError('node quaternion must be normalised')
    sx, sy, sz = node.get('scale', [1, 1, 1])
    tx, ty, tz = node.get('translation', [0, 0, 0])
    return [(1-2*(y*y+z*z))*sx, 2*(x*y+z*w)*sx, 2*(x*z-y*w)*sx, 0,
            2*(x*y-z*w)*sy, (1-2*(x*x+z*z))*sy, 2*(y*z+x*w)*sy, 0,
            2*(x*z+y*w)*sz, 2*(y*z-x*w)*sz, (1-2*(x*x+y*y))*sz, 0, tx, ty, tz, 1]


def import_gltf(path, *, asset_root=None):
    return import_gltf_with_digest(path, asset_root=asset_root)[0]


def import_gltf_with_digest(path, *, asset_root=None):
    path = path_within_root(path, asset_root, 'source')
    source_bytes = path.read_bytes()
    doc = json.loads(source_bytes)
    if doc.get('asset', {}).get('version') != '2.0':
        raise ValueError('only glTF 2.0 is supported')
    if doc.get('extensionsRequired') or doc.get('skins') or doc.get('animations'):
        raise ValueError('required extensions, skins and animation are not supported')
    buffers = []
    for item in doc.get('buffers', []):
        uri = item['uri']
        if not isinstance(uri, str):
            raise ValueError('buffer URI must be a string')
        if uri.startswith('data:application/octet-stream;base64,'):
            data = base64.b64decode(uri.split(',', 1)[1], validate=True)
        else:
            source = external_buffer_path(uri, path.parent)
            data = source.read_bytes()
        if len(data) != item['byteLength']:
            raise ValueError('buffer byteLength mismatch')
        buffers.append(data)

    def accessor(index, expected_type=None):
        acc = doc['accessors'][index]
        if acc.get('sparse') or acc.get('normalized'):
            raise ValueError('sparse/normalised accessor unsupported')
        if expected_type and (acc['type'] != expected_type or acc['componentType'] != 5126):
            raise ValueError('attribute must be FLOAT ' + expected_type)
        fmt = {5121:'B', 5123:'H', 5125:'I', 5126:'f'}.get(acc['componentType'])
        width = {'SCALAR':1, 'VEC2':2, 'VEC3':3, 'VEC4':4}.get(acc['type'])
        if not fmt or not width or acc['count'] < 0 or acc['count'] > 1000000:
            raise ValueError('unsupported or excessive accessor')
        view = doc['bufferViews'][acc['bufferView']]
        size = struct.calcsize('<'+fmt*width)
        stride = view.get('byteStride', size)
        offset = acc.get('byteOffset', 0)
        if stride < size or offset < 0 or offset + max(0, acc['count']-1)*stride+size > view['byteLength']:
            raise ValueError('accessor exceeds buffer view')
        base = view.get('byteOffset', 0)
        data = buffers[view['buffer']]
        if base < 0 or base + view['byteLength'] > len(data):
            raise ValueError('buffer view exceeds buffer')
        return [struct.unpack_from('<'+fmt*width, data, base+offset+j*stride) for j in range(acc['count'])]

    vertices = []
    active = set()
    def visit(index, parent):
        if index in active:
            raise ValueError('cyclic scene hierarchy')
        active.add(index)
        node = doc['nodes'][index]
        if 'skin' in node or 'weights' in node:
            raise ValueError('skinning and morphing are not supported')
        matrix = multiply(parent, node_matrix(node))
        if 'mesh' in node:
            for primitive in doc['meshes'][node['mesh']]['primitives']:
                if primitive.get('mode', 4) != 4 or primitive.get('targets'):
                    raise ValueError('only rigid triangle primitives are supported')
                positions = accessor(primitive['attributes']['POSITION'], 'VEC3')
                normals = accessor(primitive['attributes']['NORMAL'], 'VEC3')
                if len(positions) != len(normals):
                    raise ValueError('attribute count mismatch')
                indices = [v[0] for v in accessor(primitive['indices'])] if 'indices' in primitive else list(range(len(positions)))
                if len(indices) % 3:
                    raise ValueError('triangle index count must be divisible by three')
                material = doc.get('materials', [{}])[primitive.get('material', 0)]
                pbr = material.get('pbrMetallicRoughness', {})
                if any('Texture' in k for k in material) or any('Texture' in k for k in pbr):
                    raise ValueError('texture materials require the future texture import lane')
                color = pbr.get('baseColorFactor', [1, 1, 1, 1])
                metallic = pbr.get('metallicFactor', 1)
                roughness = pbr.get('roughnessFactor', 1)
                emissive = max(material.get('emissiveFactor', [0, 0, 0]))
                effect = 1.0 if material.get('extras', {}).get('solarlab_exhaust') else 0.0
                for idx in indices:
                    if not isinstance(idx, int) or idx < 0 or idx >= len(positions):
                        raise ValueError('index outside position accessor')
                    vertex = (*transform(matrix, positions[idx]), *normal_transform(matrix, normals[idx]),
                              *color[:3], metallic, roughness, emissive, effect)
                    if not all(math.isfinite(v) for v in vertex):
                        raise ValueError('nonfinite geometry or material')
                    vertices.append(vertex)
        for child in node.get('children', []):
            visit(child, matrix)
        active.remove(index)
    for root in doc['scenes'][doc.get('scene', 0)]['nodes']:
        visit(root, IDENTITY)
    if not vertices:
        raise ValueError('asset has no triangle geometry')
    return vertices, hashlib.sha256(source_bytes).hexdigest()


def write_header(source, target, *, asset_root=None, output_root=None):
    target = path_within_root(target, output_root, 'output')
    vertices, digest = import_gltf_with_digest(source, asset_root=asset_root)
    lines = ['// Generated by tools/import_spacecraft_gltf.py; do not edit.',
             '// Source SHA-256: ' + digest, '#pragma once', '#include <array>',
             'namespace solarlab::spacecraft {',
             'struct MeshVertex { float x,y,z,nx,ny,nz,r,g,b,metallic,roughness,emissive,effect; };',
             ]
    def number(v):
        out = format(v, '.9g')
        return out+('f' if '.' in out or 'e' in out else '.0f')
    for name, selected in [('kReferenceMesh', [v for v in vertices if v[-1] == 0]), ('kReferenceExhaust', [v for v in vertices if v[-1] != 0])]:
        lines += ['inline constexpr std::array<MeshVertex, '+str(len(selected))+'> '+name+' = {{']
        lines += ['    {'+','.join(number(v) for v in vertex)+'},' for vertex in selected]
        lines += ['}};']
    lines += ['} // namespace solarlab::spacecraft', '']
    target.write_text('\n'.join(lines), encoding='utf-8')
    return len(vertices)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path)
    p.add_argument('header', type=Path)
    p.add_argument('--asset-root', type=Path, default=Path.cwd(),
                   help='trusted source root; source paths are relative to this directory (default: working directory)')
    p.add_argument('--output-root', type=Path, default=Path.cwd(),
                   help='trusted generated-header root; header paths are relative to this directory (default: working directory)')
    args = p.parse_args()
    count = write_header(args.source, args.header, asset_root=args.asset_root, output_root=args.output_root)
    print(f'Imported {count} triangle vertices from {args.source.name}')
