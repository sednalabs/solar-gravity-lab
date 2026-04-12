# Offline build bundle requirements

To run a full Android + NDK assemble in a network-restricted environment, the build bundle should include:

- Gradle wrapper distribution zip, not just wrapper files
- prewarmed Gradle caches (`~/.gradle/caches`, `~/.gradle/wrapper`)
- Android SDK with matching compileSdk/build-tools/licenses
- Android NDK matching the repo's expected `ndkVersion`
- CMake (SDK-managed or standalone)
- Cargo cache and Android Rust target if the build invokes Rust

The ideal artifact is a tarball of a working local build environment containing:

- repo checkout
- `~/Android/Sdk`
- `~/.gradle`
- `~/.cargo`

That is enough to verify build correctness, JNI integration, shader compilation, Vulkan pipeline creation, and native linking without network access.
