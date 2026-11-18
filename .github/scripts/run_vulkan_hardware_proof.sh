#!/usr/bin/env bash
set -euo pipefail
ndk_root="${1:?Android NDK root required}"
output="${2:-dist/vulkan-hardware-proof}"
mkdir -p "$output/headers" "$output/shaders"
output="$(realpath "$output")"
git rev-parse HEAD > "$output/source.txt"
cp "$ndk_root/source.properties" "$output/ndk.properties"
ndk_include="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
# Isolate Vulkan declarations so host g++ never consumes Android libc headers.
cp -R "$ndk_include/vulkan" "$output/headers/"
if [[ -d "$ndk_include/vk_video" ]]; then cp -R "$ndk_include/vk_video" "$output/headers/"; fi
cpp=render/android-vulkan/src/main/cpp
tests=render/android-vulkan/src/test/cpp
for name in QcomTileMemory SolarLabVulkanCapabilities; do
  g++ -std=c++20 -Wall -Wextra -Werror -Wno-missing-field-initializers \
    -I "$output/headers" "$tests/${name}Test.cpp" "$cpp/$name.cpp" \
    -o "$output/${name}Test"
  "$output/${name}Test" | tee "$output/${name}Test.txt"
done
python3 render/android-vulkan/src/test/python/test_compute_contract.py
python3 .github/scripts/test_canonical_android_renderer_module.py
for shader in render/android-vulkan/src/main/shaders/solarlab/*; do
  "$ndk_root/shader-tools/linux-x86_64/glslc" --target-env=vulkan1.1 -O \
    "$shader" -o "$output/shaders/$(basename "$shader").spv"
done
python3 render/android-vulkan/src/test/python/verify_medium_bda_spirv.py \
  "$output/shaders/compact_medium_bda.comp.spv" > "$output/bda-spirv-contract.json"
minimum_api="$(python3 -c 'import tomllib; print(tomllib.load(open("gradle/libs.versions.toml", "rb"))["versions"]["minSdk"])')"
cmake -S "$cpp" -B "$output/native" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$minimum_api" -DCMAKE_BUILD_TYPE=Debug \
  -DSOLARLAB_CAPTURE_PIPELINE_STATISTICS=ON -DSOLARLAB_BUFFER_ADDRESS_COMPACTION=ON
cmake --build "$output/native" --parallel 4
printf '%s\n' 'Host capability/fault tests, all packaged shaders and optional-path Arm64 NDK build passed. This is not device extension execution.' > "$output/summary.txt"
