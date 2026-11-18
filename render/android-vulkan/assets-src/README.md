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
outpost, not an engineering validation of an operational spacecraft.

The station has 17,556 triangle vertices, a 19.55 m wing span and a maximum
vertex radius of 10.853 m, inside its authoritative **12 m hull radius**. It has
no engine or exhaust geometry. Its axial ports face ±Z; its service port faces
the rendezvous scenario's −Y stand-off direction. The scenario's 75 m stand-off
stays outside the two physical hulls plus their safety margin.

## Docking port refinement

The 19 November 2026 docking extension is original SednaLabs geometry under the
same AGPL-3.0-or-later terms. Both active collars are true annular sleeves with
recessed sealed hatches and three copper alignment keys. Relay adds a cyan
annular beacon. No outside assets or textures were introduced. Asteria now has
7,428 triangle vertices including its illustrative exhaust.

The authored mating frames are recorded in each active port node's
`extras.sglDockingPort`. Asteria's frame is at **(0, 0, 3.52) m**, with identity
rotation and +Z outward. Its mating flange has **0.72 m outer radius** and
**0.43 m inner radius**; the sleeve remains 0.52 m in radius. Relay's frame is at
**(0, −4.04, 0) m**, rotated +90° around X, with −Y outward and station +Z as
key-up. The station rim has **1.05 m outer radius** and **0.62 m opening radius**;
its beacon opening is 0.64 m. The station hatch sits 0.24 m behind the mating
plane. These overlapping solid annuli let the two physical flanges touch without
using a filled disc across the opening. The native docking model owns contact
colliders and attachment; these meshes carry no mutable physical state.

The separate docking scenario uses those port frames and real contact response.
The existing rendezvous scenario retains its 75 m stand-off and keep-out model.
The assets' 8 m and 12 m enclosing radii remain presentation/picking envelopes,
not contact spheres. Native mesh tests check the actual imported face positions
and open centres, as well as unit normals, source hashes and rigid extents.

## Reproducible import and native rendering

Regenerate the glTF asset and import its geometry with:

```sh
python3 tools/generate_reference_spacecraft.py
python3 tools/import_spacecraft_gltf.py render/android-vulkan/assets-src/asteria.gltf render/android-vulkan/src/main/cpp/ReferenceSpacecraftMesh.h
python3 tools/generate_relay_station.py
python3 tools/import_spacecraft_gltf.py render/android-vulkan/assets-src/relay-station.gltf render/android-vulkan/src/main/cpp/RelayStationMesh.h --symbol-prefix RelayStation
python3 tools/test_import_spacecraft_gltf.py
```

Run these commands from the repository root. The importer confines source and
output files to the working directory by default. For a separate asset workspace,
select trusted directories with `--asset-root` and `--output-root`; the two file
arguments are then relative to their respective roots. Document-supplied buffer
URIs remain confined to the glTF file's own directory, including symlink targets.
The source hash records the same bytes used to generate the geometry.

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

Encounter framing also uses the actual Compose camera-row and mission-panel
bounds, expressed in window pixels and intersected with the native surface.
The native camera derives the available half angles and fits both complete
physical envelopes at both submitted poses, including after orbit gestures.
This keeps close docked hulls clear of the HUD without moving or scaling either
object. Native presentation tests cover the measured 1434 × 586 landscape view,
its 308-pixel clear band, a narrower portrait band, and astronomical origins.
Only analytically rendered celestial spheres take part in spherical anchor
occlusion; a mesh's enclosing radius is never treated as an opaque hull.
