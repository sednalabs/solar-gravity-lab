import copy
import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('import_spacecraft_gltf',ROOT/'tools/import_spacecraft_gltf.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
ASSET=ROOT/'render/android-vulkan/assets-src/asteria.gltf'

class SpacecraftAssetTests(unittest.TestCase):
    def load_modified(self, change):
        doc=json.loads(ASSET.read_text());change(doc)
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)/'test.gltf';source.write_text(json.dumps(doc))
            return module.import_gltf(source)

    def test_original_asset_import_and_deterministic_header(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'mesh.h'
            self.assertEqual(module.write_header(ASSET,target),5784)
            self.assertEqual(target.read_bytes(),(ROOT/'render/android-vulkan/src/main/cpp/ReferenceSpacecraftMesh.h').read_bytes())

    def test_hierarchy_translation_applies_to_all_meshes(self):
        baseline=module.import_gltf(ASSET)
        changed=self.load_modified(lambda d:d['nodes'][-1].update(translation=[13,-5,9]))
        for a,b in zip(baseline,changed):
            for axis,offset in enumerate([13,-5,9]):self.assertAlmostEqual(b[axis]-a[axis],offset)
            self.assertEqual(a[3:],b[3:])

    def test_nonuniform_scale_normals_remain_unit(self):
        vertices=self.load_modified(lambda d:d['nodes'][-1].update(scale=[2,3,4]))
        for v in vertices:self.assertAlmostEqual(sum(x*x for x in v[3:6]),1)

    def test_camera_axes_and_source_bounds(self):
        geometry=[v for v in module.import_gltf(ASSET) if v[-1]==0]
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

if __name__=='__main__':unittest.main()
