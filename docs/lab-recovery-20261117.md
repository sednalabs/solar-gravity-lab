# Laboratory recovery and qualification, 17 November 2026

Continues PR237 and Ops w18944/w18954 on the existing feature branch.

The 649bf213 native artifact (validation43988855306, artifact14288640096,
SHA-256 f53144dfd906c9297aa547da977e49c0f66f2476346725af33a48fdcce36c52b)
contains successful mass create/edit/reopen, actual document-picker round trips,
position/velocity touch authoring, and both native camera tests. The latter
include medium/far computed-pixel output in portrait and landscape, plus GPU
buffer/command reuse. Earlier reports of a remaining far-pixel failure refer
to preceding sources, not that artifact. Its Search class timed out; the whole
revision was therefore not accepted.

The checked recovery commit c2aaf7d0 preserves exact native coordinate and velocity
bits for numerically equivalent display inputs, and preserves an authored source
mass ratio when only decimal/exponent spelling changes. The production helper
and integrated command tests cover this without changing the existing mass/gravity
switch semantics. The runtime already rejects property edits while playing with
BodyEditRequiresPause and tests unchanged world/history/ordinal.

Search filters before requiring an offscreen catalogue result. Its actual comet
alias filtering, Earth filtering, selection and correlated semantic acknowledgement
assertions remain mandatory. Framework idle timeouts are bounded to produce a
useful failure rather than hanging the entire class. The temporary source-repair
workflow has been removed; its patch and source archive remain in run43995419049.

The follow-up inspection optimization borrows one authoritative body rather than
cloning a complete WorldSnapshot for each JNI inspection. JNI copies ten scalar
response fields under the existing session-registry lock; no Rust borrow crosses
that boundary. A runtime test verifies pointer identity, unchanged world/history
and ordinal, missing IDs, and current values after an actual mass edit. This
addresses the PR's high-priority inspection-allocation review. It is not a change
to physics or ownership of the live world.

[Current ARM crossover evidence](../engine/physics/benchmarks/hosted-43995816398.md)
verifies useful-pair scheduling with full/single affinity on one hosted binary.
The separate native camera map-allocation suggestion was reviewed but not applied:
current code also needs its previous Earth entry for camera reference translation
when a probe insertion/removal changes the body count. Guarding that entire map on
matchingBodies would lose the reference and regress navigation.

This document records implementation and recovered evidence, not final acceptance.
Promotion requires a complete successful run at the final source, native evidence
review, and an APK tied to that exact commit. Do not infer physical Galaxy S25
throughput, power, or thermal behaviour from hosted tests. Preview signing and old
private saves remain governed by the release-channel warnings.
