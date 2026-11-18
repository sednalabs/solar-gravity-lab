# Original spacecraft and station assets

## Asteria orbital tug

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

## Relay orbital station

`relay-station.gltf` is a second original SednaLabs asset under the same
AGPL-3.0-or-later terms, authored on 18 November 2026. Its three pressure modules,
four bifacial solar wings, service and axial ports, radiators, antenna and
navigation lights are real triangle geometry. Material factors distinguish
ceramic, aluminium, thermal blankets, sapphire cells and emissive beacons. It
uses no external geometry or textures. The geometry is a fictional orbital
outpost, not an engineering validation or a claim that docking is implemented.

The station has 15,480 triangle vertices, a 19.55 m wing span and a maximum
vertex radius of 10.853 m, inside its authoritative **12 m hull radius**. It has
no engine or exhaust geometry. Its axial ports face ±Z; its service port faces
the rendezvous scenario's −Y stand-off direction. The scenario's 75 m stand-off
stays outside the two physical hulls plus their safety margin.

## Reproducible import and native rendering

Regenerate the glTF asset and import its geometry with:

```sh
python3 tools/generate_reference_spacecraft.py
python3 tools/import_spacecraft_gltf.py render/android-vulkan/assets-src/asteria.gltf render/android-vulkan/src/main/cpp/ReferenceSpacecraftMesh.h
python3 tools/generate_relay_station.py
python3 tools/import_spacecraft_gltf.py render/android-vulkan/assets-src/relay-station.gltf render/android-vulkan/src/main/cpp/RelayStationMesh.h --symbol-prefix RelayStation
python3 tools/test_import_spacecraft_gltf.py
```

The deterministic **offline glTF 2.0 importer** supports rigid indexed or unindexed
triangle meshes, FLOAT positions/normals, node hierarchies with matrix or TRS,
and metallic/roughness/base-colour/emission material factors. The compiled
geometry header records the source SHA-256. Native Vulkan uploads this immutable
geometry once per device lifetime; per-frame instances contain only the exported
pose. The two assets share the 52-byte vertex format, metallic/roughness shader
and graphics pipeline. Stable instance ranges select each asset's geometry;
only Asteria's range receives its exhaust draw. Changing asset counts invalidates
recorded draw commands even when the total instance count stays constant.
Geometry allocation, upload retry and destruction follow the existing graphics
fence/device lifetime. Textures, skins, animation, morph targets, sparse accessors and required
extensions are rejected explicitly. This is not a general runtime glTF loader.

The renderer uses the actual scene Sun direction with GGX-style material shading
and a small illustrative fill for readability. All physical state remains in
Rust. The mesh transport retains its double-precision position, then splits it
into high/low float pairs before GPU camera subtraction. Unit tests cover local
precision at astronomical offsets, quaternion conventions and chase orientation.

Flight camera modes use a 60-degree short-axis perspective with a 0.1 m near
plane. The mesh and analytically intersected celestial surfaces share logarithmic
depth through 1e16 m, so nearby triangle geometry and the real Earth horizon can
coexist. The planet surface maps and scene illumination remain the existing
renderer assets. Camera-follow changes presentation only; it never edits physics.
The laboratory/map retains its orthographic projection, tracer compaction and
trajectory rendering. Close flight hides those map layers; switch to Map for
orbital paths. Flight's analytic background currently covers spherical bodies
and atmospheres; ring/comet effects remain in the laboratory projection.

The **Encounter** camera (mode 3) accepts an explicit target body ID, independent
of the selected ship. It follows the midpoint of the two authoritative poses and
fits both physical envelopes at the previous and current submitted epochs. The
same interpolation fraction moves the meshes and camera, preserving relative
motion across astronomical origins. Its initial side view becomes closer as
separation falls; Orbit and Chase remain available for inspecting the ship.
If the target is absent, Encounter falls back to the selected ship's close view.
No camera action changes physical position, velocity, attitude or selection.
