## Earth–Moon laboratory

This preview delivers a complete native Android experiment: explore Earth and the
Moon, inspect and edit their state, add an orbiting probe, run time, save the world,
and reopen it paused. The Rust FP64 simulation, branch history and Vulkan renderer
remain the foundation.

- A fresh portrait and landscape interface with persistent playback controls.
- Credited offline NASA surface maps and a recorded JPL Horizons DE441 initial
  Sun/Earth/Moon state at J2000.
- Exact position/velocity authoring, non-perturbing probes, bounded playback,
  smooth local orbit predictions and atomic local experiment saves.
- Model, source, epoch and limitations available inside the app.

### Install

Download **solar-gravity-lab-0.2.0-alpha.1-internal-dev-preview.apk** below.
It requires Android 12 or newer and includes ARM64 and x86-64 native libraries.
Its package is `com.sednalabs.solarlab.internal`, with version code `2001001`.

This is an internal/device-testing preview with debug-key signing. Its signing
certificate differs from the previously published alpha.8, so it cannot update
that installation in place. Uninstalling an older preview removes its local data.
Stable signing and saved-experiment upgrade continuity remain separate work.

### Qualification and source

- Exact application source: [1ea632a92d6f46aa05545d7d08f96882a18290b7](https://github.com/sednalabs/solar-gravity-lab/commit/1ea632a92d6f46aa05545d7d08f96882a18290b7).
- [Full validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/43782411299) passed, including 12 Android instrumentation tests across seven classes, the complete experiment journey, Rust/ARM64, FFI, ISA, JVM and lint checks. Actual native screenshots were reviewed in portrait and landscape.
- [The original packaging run](https://github.com/sednalabs/solar-gravity-lab/actions/runs/43783497749) passed APK assembly, R8/JNI verification, packaged startup and ARM64 proof. Only publication failed because the historical changelog exceeded GitHub's body limit.
- This release publishes the **unchanged APK from that run**. Original build
  provenance is retained; separate recovery provenance identifies the publishing
  workflow and verifies the source runs, tag, archive and uploaded assets.

The complete historical changelog remains attached in JSON and Markdown. Its
recorded baseline is empty, so it includes repository history rather than only
this laboratory's changes. Neither it nor this release implies physical Galaxy
S25 performance qualification. Surface orientation, atmosphere and display colour
are illustrative; the documented Newtonian model is not a live ephemeris feed.

APK SHA-256: `262ae930c9225160d6c6252a7cd25dc30bb8494a5f1322c25732db11636facd7`
