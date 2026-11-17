# Native validation and mass-edit repair checkpoint

> **Historical intermediate checkpoint.** The restrictions and fixes below
> were subsequently qualified in the full
> [native validation run](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943)
> and merged via [PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237)
> into `main` at `bd79d5de`. The installed APK is still alpha.2.

Product parent: b3520d4e415a09d9051983c3dffea92b075a6300. This checkpoint
preserves the already committed probe-mass implementation rather than
reapplying the older bundle. Authoritative body-property edits now require
a paused native world and reject before consuming history or an ordinal.
A regression proves rejection leaves state/history intact and the paused
retry succeeds.

Search catalogue qualification explicitly pauses its canonical fixture.
Continuous playback is still exercised by the separate native moving-focus,
gesture cancellation and saved-world journeys; those checks are not removed.
This addresses Compose idle synchronisation, not a claimed frame-rate gain.

Tracer draw suppression records the actual IDs included in the authoritative
billboard pass. Packet-selected and UI-selected IDs can differ transiently;
both must remain single-owner. Spacecraft remain billboards because the
runtime does not export them as SceneTracer.

The canonical ARM64 FFI expectation now matches the existing 8,128 useful
pairs-per-worker gate for both multicore and single-CPU affinity. The 5,355
useful-pair scene uses the ordinary single-worker NEON path in either case.

At the time of this checkpoint, promotion was blocked on exact-source
hosted native proof and captures. Those **source** gates later passed
at `88700324`; the code merged in PR237. A newer APK and installed S25
acceptance remain separate. Tracking: w18944, w18954, w18955, w18967.


A second native-diagnostics pass found draft creation after Activity recreation
could offer a forecast from an older command revision. Forecast reuse and
Create now require matching candidate, native session, revision, paused state
and exact epoch. Restoration of a draft camera no longer reissues unrelated
observer commands. Native commit's stale-preview rejection is unchanged.
Unit cases cover every identity component and the native recreation journey
still requires successful actual creation. Rejection now captures its error.

Laboratory tools now have an explicit accessible Done action. Native compute
qualification uses that ordinary control instead of an injected Android Back
event that failed on the hosted system. GPU pixel, readback, resize and resource
reuse assertions are unchanged.
