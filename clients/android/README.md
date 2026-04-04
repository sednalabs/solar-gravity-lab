# Android client shell

The v2 Android client is a native shell over the Rust runtime.

Design constraints:

- package namespace: `com.sednalabs.solarlab`
- Jetpack Compose UI
- unidirectional app state
- no authoritative simulation logic in the Android layer
- Rust runtime accessed through the `engine/ffi` ABI
- render host implemented as an Android adapter over the shared `RenderScene`
  backend contract

This directory will eventually replace the current v1 `app` and `feature-lab`
Android stack on the v2 branch.
