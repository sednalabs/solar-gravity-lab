import base64
import struct
import hashlib
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from build_spacecraft_catalog import ROOT, DEFAULT_CATALOG, compile_catalog


class SpacecraftCatalogTests(unittest.TestCase):
    def catalog(self):
        return json.loads((ROOT / DEFAULT_CATALOG).read_text())

    def build(self, mutate):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc = self.catalog()
            mutate(doc)
            for entry in doc['assets']:
                source = ROOT / entry['source']
                if source.exists():
                    target = root / entry['source']
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
            (root / 'catalog.json').write_text(json.dumps(doc))
            return compile_catalog('catalog.json', asset_root=root, output_root=root)

    def test_committed_catalog_and_generated_outputs_are_reproducible(self):
        assets = compile_catalog(check=True)
        self.assertEqual([a['mesh_kind'] for a in assets], [1, 2, 3])
        self.assertEqual([len(a['vertices']) for a in assets], [7428, 17556, 3348])
        for asset in assets:
            payload = (ROOT / f'engine/data/authoring/assets/compiled/{asset["mesh_kind"]}.vertices.bin').read_bytes()
            self.assertEqual(len(payload), len(asset['vertices']) * 13 * 4)
            self.assertEqual(hashlib.sha256(payload).hexdigest(), asset['geometry_sha256'])
            self.assertEqual(payload, b''.join(struct.pack('<13f', *v) for v in asset['vertices']))

    def test_added_catalog_entry_generates_runtime_binding_and_native_draw_registration(self):
        def add(doc):
            fourth = copy.deepcopy(doc['assets'][2])
            fourth.update(id='external:new-surveyor', mesh_kind=4,
                          symbol_prefix='NewSurveyor', header='NewSurveyorMesh.h')
            doc['assets'].append(fourth)
        assets = self.build(add)
        self.assertEqual(assets[-1]['id'], 'external:new-surveyor')
        self.assertEqual(assets[-1]['mesh_kind'], 4)
        self.assertEqual(assets[-1]['geometry_sha256'], assets[-2]['geometry_sha256'])

    def test_unknown_fields_duplicate_id_kind_header_and_nonfinite_bounds_fail(self):
        cases = [
            lambda d: d.update(unknown=True),
            lambda d: d['assets'][2].update(id=d['assets'][0]['id']),
            lambda d: d['assets'][2].update(mesh_kind=2),
            lambda d: d['assets'][2].update(header='../escape.h'),
            lambda d: d['assets'][2].update(symbol_prefix='not-a-symbol'),
            lambda d: d['assets'][2].update(framing_radius_m=float('nan')),
            lambda d: d['assets'][2].update(framing_radius_m=1),
            lambda d: d['assets'][2].update(mesh_kind=65536),
            lambda d: d['assets'][2].update(contact_profile='asteria'),
            lambda d: d.update(assets=d['assets']*11),
        ]
        for mutation in cases:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.build(mutation)

    def test_global_vertex_budget_is_enforced_before_output(self):
        with patch('build_spacecraft_catalog.MAX_VERTICES', 100):
            with self.assertRaisesRegex(ValueError, 'triangle vertices'):
                self.build(lambda doc: None)

    def test_external_buffer_edit_changes_geometry_binding_without_json_edit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc = self.catalog()
            for entry in doc['assets']:
                target = root / entry['source']
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / entry['source'], target)
            source = root / doc['assets'][2]['source']
            asset = json.loads(source.read_text())
            binary = bytearray(base64.b64decode(asset['buffers'][0]['uri'].split(',')[1]))
            asset['buffers'][0]['uri'] = 'pathfinder.bin'
            buffer = source.with_suffix('.bin')
            buffer.write_bytes(binary)
            source.write_text(json.dumps(asset))
            (root/'catalog.json').write_text(json.dumps(doc))
            before = compile_catalog('catalog.json', asset_root=root, output_root=root)[2]
            first = struct.unpack_from('<f', binary)[0]
            struct.pack_into('<f', binary, 0, first + 0.01)
            buffer.write_bytes(binary)
            after = compile_catalog('catalog.json', asset_root=root, output_root=root)[2]
            self.assertEqual(before['source_sha256'], after['source_sha256'])
            self.assertNotEqual(before['geometry_sha256'], after['geometry_sha256'])

    def test_stale_generated_output_is_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            compile_catalog(output_root=directory)
            generated = Path(directory)/'engine/data/src/spacecraft_assets.rs'
            generated.write_text(generated.read_text().replace('example:pathfinder', 'stale:pathfinder'))
            with self.assertRaisesRegex(ValueError, 'stale generated'):
                compile_catalog(output_root=directory, check=True)

    def test_stale_binary_payload_is_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            compile_catalog(output_root=directory)
            generated = Path(directory)/'engine/data/authoring/assets/compiled/3.vertices.bin'
            changed = bytearray(generated.read_bytes())
            changed[0] ^= 1
            generated.write_bytes(changed)
            with self.assertRaisesRegex(ValueError, 'stale generated output.*3.vertices.bin'):
                compile_catalog(output_root=directory, check=True)

    def test_source_geometry_change_changes_bound_digest(self):
        baseline = compile_catalog(check=True)[2]['geometry_sha256']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc = self.catalog()
            for entry in doc['assets']:
                target = root / entry['source']
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / entry['source'], target)
            source = root / doc['assets'][2]['source']
            asset = json.loads(source.read_text())
            asset['nodes'][-1]['translation'] = [0, 0.1, 0]
            source.write_text(json.dumps(asset))
            (root/'catalog.json').write_text(json.dumps(doc))
            actual = compile_catalog('catalog.json', asset_root=root, output_root=root)[2]['geometry_sha256']
            self.assertNotEqual(actual, baseline)


if __name__ == '__main__': unittest.main()
