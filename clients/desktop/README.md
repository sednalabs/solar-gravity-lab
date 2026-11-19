# Solar Gravity Lab desktop flight

A runnable first desktop cockpit on the portable Rust engine. The browser is a
WebGL 2 presentation/input adapter: there is no JavaScript flight propagator.
`WorldRuntime` owns motion, attitude, finite burns, fuel, scientific settings,
checkpoints, saves, authored spacecraft/scenarios and coast/burn prediction.

For a published Linux x64 preview, extract the desktop `.tar.gz` asset from the
release and run `./solarlab-desktop --port 8787` in the extracted directory.
The packaged binary is qualified on Ubuntu 24.04; other operating systems do not
yet have packaged previews. The neighbouring checksum and provenance files tie
it to the release source and native browser proof.

To build from the repository root with the Rust toolchain installed:

```sh
cargo run -p solarlab-desktop -- --port 8787
```

Open `http://127.0.0.1:8787` in a local browser supporting WebGL 2. The binary
embeds the UI, catalog-generated Asteria, Relay and Pathfinder geometry and NASA Earth/Moon assets; there is no
Node build, CDN or external asset service. Ctrl-C stops the host. A built binary
can be run directly as `solarlab-desktop --port 8787`.

## Fly

Drag the scene to orbit the camera and scroll to zoom. Orbit, Chase, Map and
Centre change presentation independently of the spacecraft's attitude.

Choose Prograde, run time until aligned, then ease up the throttle. Periapsis and
apoapsis are native instantaneous orbital estimates. Map requests a native
engine-off forecast, labelled with its source epoch. It does not extrapolate a
burn in JavaScript.

W/A/S/D pitch/yaw; Q/E roll. Switch Rotate to RCS for ship-axis translation.
Fine uses 20% control authority. Plus/minus adjusts throttle; X cuts all manual
thrust; Space pauses/resumes when the scene has keyboard focus. Opposite held
inputs cancel. Focus loss clears controls and pauses; the native host also
pauses after 1.5 seconds without its visible flight deck heartbeat.

Mark cuts/pauses and stores a native checkpoint. Reset branches from it. Preview
a burn pauses and asks Rust for a read-only finite burn from the actual attitude;
thrust starts while the controller turns. The result reports native warnings,
fuel, orbit altitudes and source revision. Docking craft are outside this first
burn-preview scope.

## Build from data

Open accepts `solarlab.scenario`, `solarlab.spacecraft` and portable
`solarlab.experiment` JSON, bounded to 2 MiB. Definitions are passed to the native
authoring API. No desktop internals or hardcoded spacecraft IDs need editing.

Workshop loads `engine/data/authoring/orbital-workshop.scenario.json`: two
spacecraft sharing one world but using different propulsion. Open can load
modified or new definitions instead. The spacecraft selector exposes their
authored names. `pathfinder.scenario.json` demonstrates a distinct third mesh.
The existing asset catalog/compiler emits identical 13-float vertex payloads and
digests for both hosts. The desktop embeds every registered payload automatically;
adding a catalog entry and rerunning the compiler needs no desktop source edit.
The browser verifies the payload digest, uses the native asset binding and draws
its authored exhaust only during actual powered flight. Opening a replacement world downloads the current flight first;
adding a spacecraft retains the current world. Save downloads a native portable
experiment without introducing a desktop-specific format.

## Scope and validation

This is the first desktop flight preview. Seven focused Rust tests cover
preview immutability, CUT ordering and
lost-contact pause, general authored vehicles, and the loopback request boundary:

```sh
cargo test -p solarlab-desktop
```

The server binds only `127.0.0.1`, serves fixed embedded asset routes, bounds
headers/bodies and concurrency, checks the exact local Host/Origin, and requires
an unpredictable per-launch request token for native APIs. It never accepts a
server filesystem path. Commands are tied to the current flight generation and
manual control sequence. Native failures are shown in the cockpit.

The visual adapter reuses the source meshes and maps, with a lightweight
perspective planet/atmosphere display. It is not the Android Vulkan renderer and
does not imply comparable driver, timing or thermal results. The star background
is illustrative. Native browser screenshots and exact-source journey evidence
must be reported separately from Rust compilation and unit tests.

## Repeatable browser proof

With Chromium and an installed `playwright-core` available:

```sh
cargo build -p solarlab-desktop
SGL_CHROMIUM=/usr/bin/chromium node clients/desktop/tools/smoke.mjs
```

`SGL_PLAYWRIGHT_MODULE` can name an installed module path, `SGL_DESKTOP_BINARY`
selects a built host, and `SGL_DESKTOP_EVIDENCE` selects output (default
`out/desktop-proof`). The script launches its own loopback host and isolated
Chromium with software WebGL, then shuts them down. It checks preview
immutability, real fuel consumption, CUT, keyboard pause, checkpoint restore,
native coast prediction, download preservation and a new data-authored Ariadne
spacecraft using the distinct Pathfinder visual. Keyboard pitch, opposing held keys,
release and 20% fine control must reach native state. Actual WebGL pixel diversity,
selected mesh identity and core control reachability are checked at both sizes. Six screenshots and a JSON receipt require actual browser execution.

The managed command sandbox can deny Chromium's local sockets. An automatically
rejected escalation is not browser evidence: use a permitted hosted runner.
Software WebGL proof establishes functionality/appearance, not GPU performance.

## Source and credits

Solar Gravity Lab is by Sedna Labs and distributed under AGPL-3.0-or-later,
without warranty. The full licence is in `LICENSE`. Complete source and build
instructions are at <https://github.com/sednalabs/solar-gravity-lab>; the packaged
`source.txt` identifies the exact commit used for this executable.
`ASSET_CREDITS.md` records the bundled planetary imagery sources.
