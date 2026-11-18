import base64
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('import_spacecraft_gltf',ROOT/'tools/import_spacecraft_gltf.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
ASSET=ROOT/'render/android-vulkan/assets-src/asteria.gltf'
STATION=ROOT/'render/android-vulkan/assets-src/relay-station.gltf'

class SpacecraftAssetTests(unittest.TestCase):
    def load_modified(self, change):
        doc=json.loads(ASSET.read_text());change(doc)
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)/'test.gltf';source.write_text(json.dumps(doc))
            return module.import_gltf(source,asset_root=directory)

    def test_original_asset_import_and_deterministic_header(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'mesh.h'
            self.assertEqual(module.write_header(ASSET,target,asset_root=ROOT,output_root=directory),5784)
            self.assertEqual(target.read_bytes(),(ROOT/'render/android-vulkan/src/main/cpp/ReferenceSpacecraftMesh.h').read_bytes())

    def test_hierarchy_translation_applies_to_all_meshes(self):
        baseline=module.import_gltf(ASSET,asset_root=ROOT)
        changed=self.load_modified(lambda d:d['nodes'][-1].update(translation=[13,-5,9]))
        for a,b in zip(baseline,changed):
            for axis,offset in enumerate([13,-5,9]):self.assertAlmostEqual(b[axis]-a[axis],offset)
            self.assertEqual(a[3:],b[3:])

    def test_station_uses_shared_importer_with_distinct_native_symbols(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'station.h'
            self.assertEqual(module.write_header(STATION,target,'RelayStation',asset_root=ROOT,output_root=directory),15480)
            self.assertEqual(target.read_bytes(),(ROOT/'render/android-vulkan/src/main/cpp/RelayStationMesh.h').read_bytes())
            self.assertIn('kRelayStationMesh',target.read_text())
            self.assertNotIn('kReferenceMesh',target.read_text())

    def test_station_rigid_hull_materials_and_no_engine_exhaust(self):
        vertices=module.import_gltf(STATION,asset_root=ROOT)
        # The simulation's keep-out radius is based on a 12 m station hull.
        self.assertLessEqual(max(math.sqrt(sum(x*x for x in v[:3])) for v in vertices),12)
        self.assertGreater(max(v[0] for v in vertices)-min(v[0] for v in vertices),19)
        self.assertGreater(len(set(v[6:12] for v in vertices)),6)
        for v in vertices:
            self.assertAlmostEqual(sum(x*x for x in v[3:6]),1)
            self.assertEqual(v[-1],0)

    def test_symbol_prefix_rejects_invalid_cpp_identifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError,'identifier'):
                module.write_header(STATION,Path(directory)/'station.h','station-name',asset_root=ROOT,output_root=directory)

    def test_nonuniform_scale_normals_remain_unit(self):
        vertices=self.load_modified(lambda d:d['nodes'][-1].update(scale=[2,3,4]))
        for v in vertices:self.assertAlmostEqual(sum(x*x for x in v[3:6]),1)

    def test_camera_axes_and_source_bounds(self):
        geometry=[v for v in module.import_gltf(ASSET,asset_root=ROOT) if v[-1]==0]
        self.assertGreater(max(v[2] for v in geometry),3.5)
        self.assertLess(min(v[2] for v in geometry),-4)
        self.assertGreater(max(v[0] for v in geometry)-min(v[0] for v in geometry),13)

    def test_rejects_truncated_buffer(self):
        with self.assertRaisesRegex(ValueError,'byteLength'):self.load_modified(lambda d:d['buffers'][0].update(byteLength=1))

    def test_rejects_accessor_overrun(self):
        with self.assertRaisesRegex(ValueError,'buffer view'):self.load_modified(lambda d:d['accessors'][0].update(count=999999))

    def test_rejects_unimplemented_textures(self):
        with self.assertRaisesRegex(ValueError,'texture'):self.load_modified(lambda d:d['materials'][0]['pbrMetallicRoughness'].update(baseColorTexture={'index':0}))

    def test_rejects_cyclic_scene(self):
        with self.assertRaisesRegex(ValueError,'cyclic'):self.load_modified(lambda d:d['nodes'][-1]['children'].append(len(d['nodes'])-1))

    def test_rejects_nonfinite_node_transform(self):
        with self.assertRaises(ValueError):self.load_modified(lambda d:d['nodes'][-1].update(translation=[math.inf,0,0]))

    def test_local_nested_buffer_uri_preserves_geometry(self):
        baseline=module.import_gltf(ASSET,asset_root=ROOT)
        doc=json.loads(ASSET.read_text())
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'geometry').mkdir()
            for index,buffer in enumerate(doc['buffers']):
                data=base64.b64decode(buffer['uri'].split(',',1)[1],validate=True)
                (root/'geometry'/f'mesh {index}.bin').write_bytes(data)
                buffer['uri']=f'geometry/mesh%20{index}.bin'
            source=root/'mesh.gltf';source.write_text(json.dumps(doc))
            self.assertEqual(module.import_gltf(source,asset_root=root),baseline)

    def test_document_buffers_require_local_relative_uris(self):
        for uri in ['/absolute.bin','https://example.invalid/mesh.bin','file:mesh.bin',
                    '//example.invalid/mesh.bin','mesh.bin?version=1','mesh.bin#part',
                    'folder\\mesh.bin','',None]:
            with self.subTest(uri=uri),self.assertRaisesRegex(ValueError,'buffer URI'):
                self.load_modified(lambda d:d['buffers'][0].update(uri=uri))

    def test_document_buffer_stays_inside_asset_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);assets=root/'assets';assets.mkdir()
            (root/'assets-extra').mkdir()
            for uri in ['../assets-extra/mesh.bin','%2e%2e/assets-extra/mesh.bin']:
                doc=json.loads(ASSET.read_text());doc['buffers'][0]['uri']=uri
                source=assets/'mesh.gltf';source.write_text(json.dumps(doc))
                with self.subTest(uri=uri),self.assertRaisesRegex(ValueError,'buffer escapes'):
                    module.import_gltf(source,asset_root=root)

    def test_document_buffer_symlinks_cannot_leave_asset_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);assets=root/'assets';assets.mkdir()
            outside=root/'separate-buffer.bin';outside.write_bytes(b'fixture')
            (assets/'buffer.bin').symlink_to(outside)
            doc=json.loads(ASSET.read_text());doc['buffers'][0]['uri']='buffer.bin'
            source=assets/'mesh.gltf';source.write_text(json.dumps(doc))
            with self.assertRaisesRegex(ValueError,'buffer escapes'):
                module.import_gltf(source,asset_root=root)

    def test_operator_paths_remain_inside_independent_roots(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);assets=root/'assets';assets.mkdir()
            outputs=root/'outputs';outputs.mkdir()
            sibling=root/'assets-extra';sibling.mkdir()
            source=assets/'mesh.gltf';source.write_bytes(ASSET.read_bytes())
            other_source=sibling/'mesh.gltf';other_source.write_bytes(ASSET.read_bytes())
            (assets/'linked.gltf').symlink_to(other_source)
            for candidate in [other_source,assets/'linked.gltf']:
                with self.subTest(source=candidate),self.assertRaisesRegex(ValueError,'source escapes'):
                    module.import_gltf(candidate,asset_root=assets)
            untouched=sibling/'untouched.h';untouched.write_text('fixture')
            (outputs/'linked.h').symlink_to(untouched)
            for target in [untouched,outputs/'linked.h']:
                with self.subTest(output=target),self.assertRaisesRegex(ValueError,'output escapes'):
                    module.write_header(source,target,asset_root=assets,output_root=outputs)
            self.assertEqual(untouched.read_text(),'fixture')
            self.assertEqual(module.write_header('mesh.gltf','mesh.h',asset_root=assets,output_root=outputs),5784)
            self.assertEqual((outputs/'mesh.h').read_bytes(),(ROOT/'render/android-vulkan/src/main/cpp/ReferenceSpacecraftMesh.h').read_bytes())

    def test_cli_explicit_roots_preserve_offline_workflow(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);assets=root/'assets';assets.mkdir()
            outputs=root/'outputs';outputs.mkdir()
            (assets/'mesh.gltf').write_bytes(ASSET.read_bytes())
            completed=subprocess.run([sys.executable,str(ROOT/'tools/import_spacecraft_gltf.py'),
                                      'mesh.gltf','mesh.h','--asset-root',str(assets),
                                      '--output-root',str(outputs)],cwd=ROOT,capture_output=True,text=True,check=True)
            self.assertIn('Imported 5784 triangle vertices',completed.stdout)
            self.assertEqual((outputs/'mesh.h').read_bytes(),(ROOT/'render/android-vulkan/src/main/cpp/ReferenceSpacecraftMesh.h').read_bytes())

if __name__=='__main__':unittest.main()
