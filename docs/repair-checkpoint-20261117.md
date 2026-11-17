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


Review follow-through removes the whole-world snapshot clone from Android
single-body inspection. The runtime now lends one active body while JNI
keeps the registry lock; only ten numerical values cross the boundary.
Tests check reference identity, unknown IDs and visibility of atomic edits.
This removes unnecessary allocation, not a measured phone-speedup claim.

Native transitions avoid building a previous-body lookup when body counts
already make interpolation impossible. Earth/follow target discovery still
scans the new packet, preserving navigation during scene replacement.
