#!/usr/bin/env python3
"""Compile a declarative glTF catalog for portable runtime and native rendering.

The operator chooses input/output roots. Documents select only contained source
files, stable asset identifiers and bounded mesh IDs. This is an offline build
step; it does not derive mass, inertia, engines or contacts from visual meshes.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct
from import_spacecraft_gltf import import_gltf_with_digest, path_within_root, write_header

MAX_ASSETS = 32
MAX_VERTICES = 250_000
MAX_SOURCE_BYTES = 16 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = 'engine/data/authoring/spacecraft-assets.catalog.json'


def compile_catalog(catalog=DEFAULT_CATALOG, *, asset_root=ROOT, output_root=ROOT, check=False):
    source = path_within_root(catalog, asset_root, 'catalog')
    if source.stat().st_size > 128 * 1024:
        raise ValueError('catalog exceeds 128 KiB')
    doc = json.loads(source.read_bytes())
    if set(doc) != {'format', 'version', 'assets'} or doc['format'] != 'solarlab.asset-catalog' or doc['version'] != 1:
        raise ValueError('unsupported catalog format or fields')
    assets = doc['assets']
    if not isinstance(assets, list) or not 1 <= len(assets) <= MAX_ASSETS:
        raise ValueError('catalog requires 1..32 assets')
    names, kinds, symbols, headers = set(), set(), set(), set()
    compiled = []
    count = 0
    for entry in assets:
        if set(entry) != {'id', 'mesh_kind', 'source', 'symbol_prefix', 'header', 'framing_radius_m', 'contact_profile'}:
            raise ValueError('unknown or missing asset fields')
        key, kind, symbol, header = (entry[n] for n in ('id', 'mesh_kind', 'symbol_prefix', 'header'))
        if not isinstance(key, str) or not re.fullmatch(r'[a-z][a-z0-9_.:-]{0,95}', key):
            raise ValueError('invalid asset id')
        if type(kind) is not int or not 1 <= kind <= 65535:
            raise ValueError('mesh_kind must be an integer in 1..65535')
        if not isinstance(symbol, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]{0,63}', symbol):
            raise ValueError('invalid C++ symbol prefix')
        if not isinstance(header, str) or not re.fullmatch(r'[A-Za-z][A-Za-z_0-9]{0,79}\.h', header):
            raise ValueError('header must be a plain C++ filename')
        if key in names or kind in kinds or symbol in symbols or header in headers:
            raise ValueError('duplicate asset id, mesh_kind, symbol or header')
        if (kind == 1 and key != 'builtin:asteria') or (kind == 2 and key != 'builtin:relay-station'):
            raise ValueError('stable built-in mesh ids cannot be rebound')
        names.add(key); kinds.add(kind); symbols.add(symbol); headers.add(header)
        profile = entry['contact_profile']
        if profile not in ('asteria', 'relay-station', None):
            raise ValueError('unknown built-in contact profile')
        if (profile == 'asteria' and (key != 'builtin:asteria' or kind != 1)) or (profile == 'relay-station' and (key != 'builtin:relay-station' or kind != 2)):
            raise ValueError('built-in physical defaults are reserved for their stable assets')
        radius = entry['framing_radius_m']
        if type(radius) not in (int, float) or not math.isfinite(radius) or not 0.001 <= radius <= 1000:
            raise ValueError('invalid framing radius')
        mesh_source = path_within_root(entry['source'], asset_root, 'source')
        if mesh_source.stat().st_size > MAX_SOURCE_BYTES:
            raise ValueError('glTF source exceeds 16 MiB')
        vertices, source_hash = import_gltf_with_digest(mesh_source, asset_root=asset_root)
        count += len(vertices)
        if count > MAX_VERTICES:
            raise ValueError('catalog exceeds 250000 triangle vertices')
        solid = [v for v in vertices if v[-1] == 0]
        if not solid or len(vertices) % 3:
            raise ValueError('asset needs solid triangle geometry')
        actual_radius = max(math.hypot(*v[:3]) for v in solid)
        if actual_radius > radius + 1e-6:
            raise ValueError('framing radius does not enclose solid mesh')
        if any(any(abs(x) > 1e5 for x in v[:3]) for v in vertices):
            raise ValueError('visual vertices exceed supported metre bounds')
        # Hash the actual imported vertex/material payload, including external
        # buffers. A source-only JSON hash would miss changes to a .bin file.
        payload = b''.join(struct.pack('<13f', *v) for v in vertices)
        digest = hashlib.sha256(payload).hexdigest()
        compiled.append(dict(entry, vertices=vertices, vertex_payload=payload, geometry_sha256=digest, source_sha256=source_hash))
    compiled.sort(key=lambda a: a['mesh_kind'])
    if not {1, 2}.issubset(kinds):
        raise ValueError('catalog must retain stable built-in mesh ids 1 and 2')
    ids = ', '.join(str(a['mesh_kind']) for a in compiled)
    meta = ['// Generated by tools/build_spacecraft_catalog.py; do not edit.', '#pragma once', '#include <array>', '#include <cstdint>', 'namespace solarlab::spacecraft {', f'inline constexpr std::array<uint32_t, {len(compiled)}> kMeshKinds{{{ids}}};', '} // namespace solarlab::spacecraft', '']
    geometry = ['// Generated by tools/build_spacecraft_catalog.py; do not edit.', '#pragma once', '#include <array>', '#include <span>', '#include "SpacecraftMeshCatalog.h"']
    geometry += ['#include "'+a['header']+'"' for a in compiled]
    geometry += ['namespace solarlab::spacecraft {', 'struct RegisteredMesh {', '    uint32_t kind;', '    const char* assetId;', '    std::span<const MeshVertex> solid;', '    std::span<const MeshVertex> exhaust;', '};', f'inline constexpr std::array<RegisteredMesh, {len(compiled)}> kRegisteredMeshes{{{{']
    geometry += [f'    {{{a["mesh_kind"]}, "{a["id"]}", k{a["symbol_prefix"]}Mesh, k{a["symbol_prefix"]}Exhaust}},' for a in compiled]
    geometry += ['}};', '} // namespace solarlab::spacecraft', '']
    rust = ['// Generated by tools/build_spacecraft_catalog.py; do not edit.', 'use solarlab_domain::SpacecraftAppearance;', '#[derive(Clone, Copy, Debug, PartialEq)]', 'pub struct SpacecraftVisualAssetRecord {', "    pub asset_id: &'static str,", '    pub mesh_kind: u32,', "    pub geometry_sha256: &'static str,", '    /// Canonical little-endian position, normal, RGB, metallic, roughness, emission, exhaust (13 floats).', "    pub vertex_payload: &'static [u8],", '    pub framing_radius_m: f64,', '    pub contact_profile: Option<SpacecraftAppearance>,', '}', 'pub const SPACECRAFT_VISUAL_ASSETS: &[SpacecraftVisualAssetRecord] = &[']
    for a in compiled:
        profile = {'asteria': 'Some(SpacecraftAppearance::Asteria)', 'relay-station': 'Some(SpacecraftAppearance::RelayStation)', None: 'None'}[a['contact_profile']]
        rust += ['    SpacecraftVisualAssetRecord {', f'        asset_id: "{a["id"]}",', f'        mesh_kind: {a["mesh_kind"]},', f'        geometry_sha256: "{a["geometry_sha256"]}",', f'        vertex_payload: include_bytes!("../authoring/assets/compiled/{a["mesh_kind"]}.vertices.bin"),', f'        framing_radius_m: {float(a["framing_radius_m"]):.9g}_f64,', f'        contact_profile: {profile},', '    },']
    rust += ['];', '#[must_use]', "pub fn spacecraft_visual_asset(asset_id: &str) -> Option<&'static SpacecraftVisualAssetRecord> {", '    SPACECRAFT_VISUAL_ASSETS', '        .iter()', '        .find(|asset| asset.asset_id == asset_id)', '}', '']
    outputs = {'engine/data/src/spacecraft_assets.rs': '\n'.join(rust), 'render/android-vulkan/src/main/cpp/SpacecraftMeshCatalog.h': '\n'.join(meta), 'render/android-vulkan/src/main/cpp/SpacecraftAssetGeometry.h': '\n'.join(geometry)}
    for a in compiled:
        outputs[f'engine/data/authoring/assets/compiled/{a["mesh_kind"]}.vertices.bin'] = a['vertex_payload']
    # Compile every header before publishing any outputs, including generated
    # source digests in the established importer format.
    import tempfile
    with tempfile.TemporaryDirectory() as temporary:
        for a in compiled:
            write_header(a['source'], a['header'], a['symbol_prefix'], asset_root=asset_root, output_root=temporary)
            outputs['render/android-vulkan/src/main/cpp/'+a['header']] = (Path(temporary)/a['header']).read_text()
    for relative, content in outputs.items():
        target = path_within_root(relative, output_root, 'output')
        encoded = content if isinstance(content, bytes) else content.encode('utf-8')
        if check:
            if not target.exists() or target.read_bytes() != encoded:
                raise ValueError('stale generated output: '+relative)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(encoded)
    return compiled


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', default=DEFAULT_CATALOG)
    parser.add_argument('--asset-root', type=Path, default=ROOT)
    parser.add_argument('--output-root', type=Path, default=ROOT)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    assets = compile_catalog(args.catalog, asset_root=args.asset_root, output_root=args.output_root, check=args.check)
    print(f'{"Verified" if args.check else "Compiled"} {len(assets)} assets and {sum(len(a["vertices"]) for a in assets)} triangle vertices')
if __name__ == '__main__': main()
