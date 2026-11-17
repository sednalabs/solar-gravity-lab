# Laboratory recovery and qualification, 17 November 2026

> **Historical recovery note.** The intermediate failures described
> below were real at the named commits, but are no longer outstanding.
> [PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237)
> passed [final exact-source validation](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943)
> and merged into `main` as `bd79d5de`. No later APK has been released.

This records PR237 and Ops w18944/w18954 during feature-branch recovery.

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

This intermediate snapshot was not final acceptance. A later 365-body
Search-focus test also hit Compose-Espresso idling while Vulkan remained
active. The qualified head controlled only the **test frame clock** for
that focus assertion while retaining selection and runtime checks;
the full native suite and other CI lanes passed on `88700324`.
Source was merged as `bd79d5de`. A future APK still needs exact-source
packaging, signature, checksum and installed launch proof. Physical Galaxy S25
throughput, power and thermal behaviour are not established by hosted tests.
Preview signing and old private saves remain governed by the release guide.
