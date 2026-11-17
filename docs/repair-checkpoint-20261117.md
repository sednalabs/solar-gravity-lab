# Native validation and mass-edit repair checkpoint

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

Do not promote this checkpoint until its exact-source hosted native run and
real captures pass. Current delivery: w18944, w18954, w18955, w18967; PR237.
