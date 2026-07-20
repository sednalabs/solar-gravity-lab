# Stage-first native runtime

The immersive Android stage binds directly to the long-lived Rust session
handle (`SlRuntimeHandle`). Native `SolarLabStageController` owns session
binding, camera state, picking, tracer/trail display policy, packet export, and
translation into Vulkan streams.

There is one execution path:

`Rust world -> scene extraction -> versioned FFI packet -> native controller -> Vulkan renderer`

Kotlin forwards lifecycle, gestures, selection intent, and command intent. It
may decode bounded packet metadata for search, labels, accessibility, and debug
presentation, but stage drawing and physical truth do not depend on that
managed projection.

Native stage ownership includes:

- pan, zoom, body-relative framing, and orbital camera gestures;
- native body picking against the current Rust scene;
- selected-body inclusion and renderer-local visual LOD;
- near, medium, and far tracer classification;
- trail display reduction; and
- physical-light-driven body shading.

Debug builds default to this stage-first runtime. The packet-oriented shell is
still available through `-Psolarlab.debugStageFirstClient=false` for deliberate
shell validation, but it binds the same Rust world.
