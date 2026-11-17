# Probe mass and atomic body editing

The placement panel now has Position, Motion and Mass sections. New probes default
to 1,000 kg and do not exert gravity. Set kilograms (scientific notation accepted)
and explicitly enable **Attracts other bodies** for gravitational backreaction.
The same controls are available for existing probes in **Edit mass & motion**.

Physical mass and gravitational source mass remain distinct authoritative f64
fields. With attraction off, source mass is zero; the mass does not change the
probe's free-fall acceleration. With attraction on, an explicitly edited mass is
also its source mass. A kinematics-only edit preserves a previously saved unequal
physical/source-mass ratio. Neither class nor identity/history is replaced.

Circular starting speed includes the new probe's source mass in the relative
two-body estimate. It is not a stable-orbit guarantee in the full multi-body
model. Large masses may substantially alter Earth and Moon. Radius remains 2 m
at placement and is independently editable; presets do not assert realistic
material density, relativity, or a collision model.

`SetBodyProperties` is additive ABI command 12, using existing command fields.
Validation rejects nonfinite/negative masses, nonpositive radius, invalid vectors
and overflowing proposed invariants before history or ordinals change. One
accepted command updates mass/source/radius/position/velocity atomically and
refreshes source-aware solver selection. Saves retain both masses unchanged.

Regression coverage includes invalid-edit atomicity, saved-state round trips,
passive-mass invariance, Earth backreaction, placement values and bit-exact
unchanged editor fields. **Exact-source native hosted acceptance passed** in
[validation 44008781943](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44008781943),
including the real Mass-panel/create/edit/save/reopen journey, and
[PR #237](https://github.com/sednalabs/solar-gravity-lab/pull/237)
merged to `main` at `bd79d5de`. That is source delivery, **not**
an assertion that alpha.2 includes this UI or that a new APK/physical
S25 performance test has been published.

Delivery tracking: Ops w18967 under w18944, with native integration w18954.
Use the remote merged commit, validation run and Ops checkpoints as truth.


Additional refinement: accepted body edits reuse their already-validated invariant
result, avoiding a second all-pairs reduction. No approximate result is cached.
Native qualification adds an actual Mass-panel/create/edit/save/reopen journey,
invalid UI input and draft recreation. Distant density points get a readable
minimum screen footprint and an explicit 1px hardware fallback, instead of fading
away their only raster sample. Native projected-pixel assertions remain unchanged.
