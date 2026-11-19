# Authoring spacecraft and scenarios

SGL accepts spacecraft and scenario definitions as versioned JSON. These are
initial conditions for the **same Rust `WorldRuntime`** that runs the Android
flight experience and portable clients. They do not create a second simulation,
execute scripts, or require edits to application logic.

The complete examples are in `engine/data/authoring/`:

| File | What it demonstrates |
| --- | --- |
| `orbital-workshop.scenario.json` | Iris electric courier and Sable chemical tug, with different masses, engines, propellant and orbital placements |
| `lunar-depot.scenario.json` | A lunar tender and passive depot with explicitly composed hulls and mating ports; the tender can precede its target in the file |
| `pathfinder.scenario.json` | A third spacecraft with its own original glTF visual asset, generated registry binding and physical components |
| `iris.spacecraft.json` | Adding a reusable design to an existing paused Earth–Moon experiment |
| `spacecraft-assets.catalog.json` | The build-time visual asset catalog shared by the runtime and native renderer |

## Validate and fly from the native engine

From the repository root, no application source changes are needed:

```sh
cargo run -p solarlab-runtime --example authoring_lab -- \
  engine/data/authoring/orbital-workshop.scenario.json

cargo run -p solarlab-runtime --example authoring_lab -- \
  engine/data/authoring/orbital-workshop.scenario.json \
  --ship workshop.sable --burn 10 --throttle 1 --attitude prograde --coast 60 \
  --save /tmp/sable.experiment.json --state /tmp/sable.state.json

cargo run -p solarlab-runtime --example authoring_lab -- \
  engine/data/authoring/pathfinder.scenario.json --burn 10 --coast 60

cargo run -p solarlab-runtime --example authoring_lab -- \
  engine/data/authoring/orbital-workshop.scenario.json \
  --add engine/data/authoring/iris.spacecraft.json
```

Without burn/coast options the example validates and reports the constructed
world. Flight options apply ordinary runtime controls and staged advances. The
reported delta-v and propellant come from the real engine state. Every run saves,
reloads and compares the coast future against the uninterrupted world. `--state`
writes a clearly labelled observation; `--save` writes a resumable experiment.
The CLI is a native validation/demo host, not a desktop graphics application.
Use the desktop cockpit for interactive presentation and controls.

A new design can start as a copy of a supplied definition. Change its `id`,
`display_name`, structure, tank and engines; give its instance a fresh `body_id`.
The existing application does not need a new menu enum, spawn function or scenario
switch. Scenario imports return a new paused world. Craft additions require a
paused world and commit the whole collection atomically.

## Document and component contract

A scenario has `format: "solarlab.scenario"`, `version: 1`, an `id`, a descriptive
`title`, and these collections:

- `base`: `empty` or `earth-moon`. The latter copies the pinned Earth–Moon lab
  initial conditions and preserves their provenance. Added craft are authored
  states, not new ephemeris predictions.
- `bodies`: optional explicitly specified celestial bodies. A spacecraft must
  use a spacecraft definition/instance rather than masquerading as a bare body.
- `spacecraft_definitions`: reusable physical and visual designs, keyed by `id`.
- `spacecraft`: instances referring to those designs through `definition_id`.
- `focus_body_id`: optional initial selection.
- `scientific_settings`: optional branch-owned scientific settings, with the
  same defaults and validation as the ordinary runtime command.

A craft collection uses `format: "solarlab.spacecraft"`, `version: 1`, and the
same `spacecraft_definitions` and `spacecraft` arrays. Definitions are immutable
construction inputs. A successful import composes ordinary `BodyState` and
`SpacecraftState` components; those authoritative states, including assembly and
asset identity, are what saves and checkpoints retain.

The definition contains `structure`, `propellant`, `engines`, `visual_asset` and
optional `contact`. The structure gives dry mass, principal moments of inertia,
ideal wheel torque limits and an enclosing framing radius. The tank gives
capacity; an instance may start with less fuel through `propellant_kg`. Each
engine gives an applied-force direction, mounting point, maximum thrust,
specific impulse and control channel. Engine torque is the real mounting-point
cross force. Mass flow is `thrust / (9.80665 × specific_impulse_seconds)`.

All physical numbers use **metres, seconds, kilograms, newtons and radians**.
Vectors are JSON objects with `x`, `y`, `z`; quaternions use `w`, `x`, `y`, `z`.
The right-handed spacecraft body frame is +X right, +Y up, +Z forward.
`direction_body` points along force, so +Z main thrust produces a plume behind
−Z. Principal inertia is kept constant as propellant is consumed; this release
does not model tank slosh, deformable structures or a changing inertia tensor.

Engine channels are `main`, `positive_x`, `negative_x`, `positive_y`,
`negative_y`, `positive_z`, `negative_z`. Multiple engines may share a channel.
Directions must be unit vectors. The main channel follows throttle; the six
translation channels follow the corresponding signed body-axis control.
Docking/rendezvous guidance requires the actual needed control authority, not
just a channel name. Import always starts with neutral manual controls.

Dry mass and inertia must be positive; principal moments must obey the triangle
inequalities. Fuel cannot exceed capacity. The enclosing radius must contain the
selected visual asset and the conservative contact bound. Counts, names,
orientations, numeric ranges and references are bounded and validated. Unknown
component/document fields are rejected rather than silently becoming settings.
The document limit is 1 MiB, with at most 64 definitions, 64 craft instances,
64 engines per definition and 256 additional celestial bodies.

## Placement and dependency resolution

Each instance chooses one `placement`:

| `kind` | Required values and meaning |
| --- | --- |
| `inertial` | `position_m`, `velocity_mps` in the world's inertial axes |
| `relative_state` | `host_body_id`, plus inertial-axis position and velocity offsets added to that host |
| `circular_orbit` | `host_body_id`, positive `altitude_m`, perpendicular nonzero `radial_direction` and `orbit_normal` |

Circular placement is a two-body circular initial estimate. The complete world
force law immediately acts on it, including other sources and selected
harmonics. It does not continuously force a circular path. The two input axes
are normalized. With no explicit attitude, circular placement points +Z along
its inserted tangential velocity; other placements use identity orientation.
An explicit `attitude` must already be a unit quaternion.

`reference_body_id` selects the navigation/attitude reference. If omitted, a
placement host becomes that reference. A separate `docking_target_body_id`
opts into a local interaction relation with another spacecraft. Setting
`align_to_docking_target: true` constructs the initial mating attitude once; it
cannot be combined with an explicit attitude. It never repairs an in-flight pose.

Dependencies can point to earlier or later instances. SGL resolves them before
committing. Cyclic, missing, duplicate and self references fail. In a craft
addition, even a failure after another instance has been staged leaves the live
world, history, counters and prior save unchanged.

## Physical local interaction worlds

`contact.hull` is a collection of body-relative boxes and spheres, each with its
own position and optional quaternion. Boxes specify `half_extents_m`; spheres
specify `radius_m`. At most 32 parts are supported, within a conservative 50 m
body-centred contact bound. `contact.port` specifies a front-face position,
orientation, inner and outer radius, and depth. Port-local +Z points outward;
+Y fixes keyed roll. The physical flange lies behind the front face and uses a
bounded 24-pad approximation with an open aperture.

An omitted contact definition selects the documented Asteria or Relay physical
approximation only for its original built-in visual asset. A custom visual asset
requires explicit contact components. The engine never infers mass, inertia,
propulsion or collision geometry from glTF metadata.

Both ports must have overlapping annular faces. Capture still requires real
contact evidence, suitable arrival velocity, alignment and spin, and an armed
capture relation. Contact transfers momentum; it is not a teleport or magnetic
snap. The current relation is one active chaser/port to one passive target/port;
port graphs, articulated assemblies, terrain and an unrestricted collision world
are not implemented by this schema.

All opted-in bodies retain their inertial position/velocity and attitude in the
ordinary world. Gravity and propulsion kick once; a bounded local Rapier drift
handles rotation, contact and fixed attachments. Separate encounters get separate
translating, nonrotating local origins. Groups merge when conservative swept
bounds can meet. Shape compilation is reused throughout an advance, but hidden
warm-start/contact state is not persisted. Save/resume therefore has a complete
physical state. Contact still uses the configured gravity law; conservative
higher-order coast methods do not claim to raise the whole powered/contact split
above second order.

The local solver has a tested motion-resolution envelope, not a guarantee for
arbitrary collision geometry or arbitrarily fast motion. Island grouping includes
the full translational and rotational kinetic energy, so an impact can transfer
energy between different craft and unequal masses. Each candidate union is
rechecked after a merge. Far-apart physical shapes take bounded free drifts;
near contact, the solver limits point motion to one eighth of the smallest
primitive thickness or diameter in the island. Boxes, spheres and the actual
24-pad flanges supply that scale. All refinement stays in one translating,
inertially oriented frame and returns to astronomical coordinates once.

There are at most 2,048 Rapier solves, including rejected retries, per island per
0.01-second contact drift. Motion and final velocities are checked before a solve
is accepted, and accumulated motion must remain inside the original island's
envelope. The explicitly configured 3 m/s stabilization speed, scaled by island
membership, is a checked numerical allowance; it is not a rigorous bound on an
arbitrary coupled iterative solver. Attachment rest-pose residuals have a separate
allowance. Exceeding a motion or work bound rejects the staged operation. Reducing
the requested step, moderating relative speed/spin, or simplifying extremely thin
geometry can bring a scenario inside the supported envelope.

The solver uses ordinary dynamic contact with this bounded refinement. It does
not depend on Rapier's bullet-versus-bullet exclusion or its endpoint approximation
for moving CCD targets. Speculative contact is disabled so docking faces must
actually meet before restitution or capture. Tests cover fast two-body impacts,
physical restitution and momentum, rotating structures, island independence and
rejected-work rollback. Simultaneous multi-body rigid impacts are resolved by
Rapier's contact model; they are not a uniquely validated material-impact law.

Saved experiments containing docking state in any branch or checkpoint include
the top-level guard `contact_model: "bounded-feature-contact-v1"`. This keeps the
existing v4/v5 flight-model labels and makes older readers, which reject unknown
document fields, reject the corrected contact model. New readers reject unknown
contact-model values. A legacy contact save without this guard imports its
physical state and history unchanged, then uses the corrected solver for future
contacts; its next export includes the guard. There is no legacy CCD replay mode.

## Add a new visual asset without application-source edits

The asset catalog is an **offline build input**. Its output is compiled into the
portable runtime and native renderer. It is not a hot-loaded arbitrary glTF file.
Existing JSON designs can be imported immediately; adding a new catalog mesh
requires rebuilding the engine/client.

Author a rigid glTF 2.0 model in metres and add one catalog entry with a new
stable asset `id`, a fresh `mesh_kind` in 3..65535, a contained `source` path,
a C++ `symbol_prefix`, plain generated `header` filename, `framing_radius_m`
and `contact_profile: null`. Keep old identifiers and geometry stable so existing
saves can resolve their exact visual bindings. Then run:

```sh
python3 tools/build_spacecraft_catalog.py
python3 tools/build_spacecraft_catalog.py --check
python3 tools/test_spacecraft_catalog.py
```

Use that `id` as `visual_asset` in the external definition, and supply its physical
contact components explicitly. `example:pathfinder` is the checked-in example.
Its source can be reproduced with `tools/generate_pathfinder_spacecraft.py`.
The generator updates the Rust lookup, native mesh catalog, geometry registry and
mesh headers. The application has no per-asset upload/draw branch to edit.

The existing importer supports rigid triangles, position/normal attributes,
node transforms and material factors. Unsupported textures, skins, animation,
morphs and required extensions fail explicitly. Catalog inputs are limited to
32 assets and 250,000 total triangle vertices; framing bounds must contain solid
geometry. Source/output directories are chosen by the operator with `--asset-root`
and `--output-root`, not by the document. Buffer paths remain local and contained.

The runtime binding stores the stable key, mesh kind and SHA-256 of the imported
vertex/material payload. The payload digest includes external buffer content.
A save referring to an unknown asset, a forged mesh kind, or changed geometry
fails validation. It cannot quietly display a different vehicle. The v5 flight
model guard similarly stops older readers from silently discarding authored
physical components.

## Public native API and validation

```rust
let runtime = WorldRuntime::import_authored_scenario(
    bytes, HardwareProfile::offline_reference(), recorded_at_unix_ms,
)?;
let events = existing_paused_runtime.add_authored_spacecraft(bytes, recorded_at_unix_ms)?;
```

Host clients should swap a scenario only after construction succeeds and retain
or save the prior world according to their document UI. Craft insertion performs
its own complete staging/commit. Parsing errors identify JSON syntax/unknown
fields; definition errors name the design; dependency failures identify the
unresolved instance. `AuthoringError` is available from `solarlab_runtime` and
the schema types live in its public `authoring` module.

Relevant tests cover independent rocket-equation results for two externally
authored engines, late-failure rollback, bounded bad inputs, dependency ordering,
custom-port capture, incompatible ports, contact island independence, exact
save/resume futures, forged asset bindings, old save-model labels, catalog
bounds/reproducibility and mixed native draw/cache ranges. Run the focused suites:

```sh
cargo test -p solarlab-runtime authoring::tests
cargo test -p solarlab-physics docking
python3 tools/test_spacecraft_catalog.py
python3 tools/test_import_spacecraft_gltf.py
```
