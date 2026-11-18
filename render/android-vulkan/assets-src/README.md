# Asteria reference spacecraft

`asteria.gltf` is an original SednaLabs asset, authored for the Space Engine flight
milestone on 18 November 2026. Copyright 2026 SednaLabs, AGPL-3.0-or-later, as the
repository. No external geometry, texture, font, proprietary SDK or generated
raster image is used. It represents a fictional configurable orbital tug, not a
validated engineering design or a reconstruction of an existing spacecraft.

The body frame is right handed: **+X right, +Y up, +Z nose/thrust**, in metres.
The rigid model is about 7.6 m long with a 13.5 m solar-array span. Its ceramic
cabin, gold service module, engine bell, 64 solar tiles, conductors, RCS pods,
windows and docking collar have explicit triangle geometry and normals.
The main-engine plume is a separate additive mesh, scaled by the runtime's
fuel-aware throttle. Its colour and shape are illustrative, not plume physics.

Regenerate the glTF asset and import its geometry with:

```sh
python3 tools/generate_reference_spacecraft.py
python3 tools/import_spacecraft_gltf.py render/android-vulkan/assets-src/asteria.gltf render/android-vulkan/src/main/cpp/ReferenceSpacecraftMesh.h
python3 tools/test_import_spacecraft_gltf.py
```

The deterministic **offline glTF 2.0 importer** supports rigid indexed or unindexed
triangle meshes, FLOAT positions/normals, node hierarchies with matrix or TRS,
and metallic/roughness/base-colour/emission material factors. The compiled
geometry header records the source SHA-256. Native Vulkan uploads this immutable
geometry once per device lifetime; per-frame instances contain only the exported
pose. Textures, skins, animation, morph targets, sparse accessors and required
extensions are rejected explicitly. This is not a general runtime glTF loader.

The renderer uses the actual scene Sun direction with GGX-style material shading
and a small illustrative fill for readability. All physical state remains in
Rust. The mesh transport retains its double-precision position, then splits it
into high/low float pairs before GPU camera subtraction. Unit tests cover local
precision at astronomical offsets, quaternion conventions and chase orientation.
