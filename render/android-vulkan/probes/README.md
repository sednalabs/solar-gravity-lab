# QCOM offscreen qualification

These executable experiments exercise `VK_QCOM_tile_shading` and
`VK_QCOM_image_processing`. They create no surface and alter no physics. Their
device is separate from the app renderer, which has its own subgroup
compaction path and tile-memory counter allocator.

| Mode | Workload | Independent check |
| --- | --- | --- |
| `control` | Ordinary compute inverts a 129 by 73 RGBA8 image. | Every readback channel matches the CPU result within one 8-bit step. |
| `tile-shading` | The same inversion runs inside a colour render pass, once per graphics tile. | The same full-image oracle catches missing tiles and edge writes. |
| `box-control` | Downsample a 64 by 64 nonlinear hash fixture to 16 by 16 with sixteen texture fetches per output. | Every output component matches a CPU 4 by 4 average. |
| `image-processing` | The same downsample uses `textureBoxFilterQCOM` and an image-processing sampler. | The same CPU average, with finite-value checks and one normalised 8-bit step tolerance. |

The image experiment is a candidate for preview downsampling, photometric
overviews or image-based visual diagnostics. It does not justify replacing
analytic body shaders. Weighted kernels and block matching need their own
formats, descriptors and useful product workloads.

Tile compute uses the runtime tile origin/extent and intersects edge tiles
with image bounds. It touches no neighbouring tile or apron. One 8 by 8
workgroup loops over each tile. A framebuffer-local sync2 self-dependency
orders attachment clear writes before tile reads/writes. This is not a
conversion of the existing global far-tracer scatter into tile-local work.

## Build and run

Use Vulkan headers 1.3.280 or newer, CMake 3.22+, C++20 and
`glslangValidator` 15.4+. The reproducible compiler baseline is glslang
16.6.0, commit `e1b562a8bed273a02f30b59b66a5d499793cede5`; the modern-header
check uses Vulkan-Headers 1.4.368, commit
`88487f687f3100ebe1d3559a8a34d971ee85b977`. Upstream introduced tile GLSL support in 15.4.0 and
image processing in 13.0.0. The compatibility header supplies missing QCOM
declarations from pinned Khronos header 1.4.368; it grants no runtime support.
These optional shader sources are separate from the normal APK shader build.

```sh
cmake -S render/android-vulkan/probes -B /tmp/solarlab-qcom-probes
cmake --build /tmp/solarlab-qcom-probes --parallel
/tmp/solarlab-qcom-probes/solarlab-qcom-offscreen-probe \
  --mode control --shaders /tmp/solarlab-qcom-probes/shaders --require-validation
/tmp/solarlab-qcom-probes/solarlab-qcom-offscreen-probe \
  --mode box-control --shaders /tmp/solarlab-qcom-probes/shaders --require-validation
/tmp/solarlab-qcom-probes/solarlab-qcom-offscreen-probe \
  --mode tile-shading --shaders /tmp/solarlab-qcom-probes/shaders --require-validation
/tmp/solarlab-qcom-probes/solarlab-qcom-offscreen-probe \
  --mode image-processing --shaders /tmp/solarlab-qcom-probes/shaders --require-validation
```

Android compilation can use the NDK CMake toolchain with
`ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=android-35`,
`ANDROID_STL=c++_static` and a host `GLSLANG_VALIDATOR` path. Copy the executable
and generated `shaders` directory to one writable device directory. The probe
requires Vulkan 1.3 for explicit sync2/render-pass APIs; the app keeps its
Vulkan 1.1 minimum.

JSON records mode, status, reason, device, raw driver version, validation-layer
use/error count and maximum absolute error. `SKIP` means the required driver,
layer, extension, feature, format or queue is unavailable, and is never proof
of execution. Both `PASS` and `SKIP` exit zero so a census can continue;
automation must inspect `status` and require `PASS` at an acceptance gate.
Mismatches, failures after eligibility and validation errors exit nonzero.
The host fixture test independently confirms that nearest, centred bilinear
and shifted-box alternatives differ from the oracle by more than eight
8-bit steps; the readback tolerance is only one step.
Retain each result with exact source SHA, compiler version and SPIR-V hashes.

## Eligibility and promotion

Tile mode requires extension enumeration, `tileShading`,
`tileShadingColorAttachments`, `tileShadingPerTileDispatch`, the
`VK_QCOM_tile_properties` dependency and usable entry points. It uses no
optional apron, atomic, fragment-stage or `vkCmdDispatchTileQCOM` feature.

Image mode requires `textureBoxFilter`, a maximum block of at least 4 by 4 and
RGBA8 support for QCOM box filtering. Its dedicated sampler is never reused by
the ordinary sampling control.

These are correctness probes, not speed measurements. A pass must be followed
by representative image sizes, GPU timing, bandwidth/power measurements,
thermal runs and screenshot comparisons before production policy changes.
Tile shading disables Adreno FlexRender selection, making whole-frame
measurement necessary even when the isolated kernel is faster.

Initial host evidence: the C++ harness compiles against Vulkan 1.3.280 headers
with warnings treated as errors; the local loader has no usable driver and
reports `SKIP`. That is not a QCOM dispatch, APK result or Galaxy S25 acceptance.

## Primary sources

- [Khronos tile shading extension](https://docs.vulkan.org/refpages/latest/refpages/source/VK_QCOM_tile_shading.html)
- [Tile shading feature bits](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceTileShadingFeaturesQCOM.html)
- [Final GLSL tile syntax](https://github.com/KhronosGroup/GLSL/blob/main/extensions/qcom/GLSL_QCOM_tile_shading.txt)
- [Image processing extension](https://docs.vulkan.org/refpages/latest/refpages/source/VK_QCOM_image_processing.html)
- [GLSL image processing operations](https://github.com/KhronosGroup/GLSL/blob/main/extensions/qcom/GLSL_QCOM_image_processing.txt)
- [glslang version history](https://github.com/KhronosGroup/glslang/blob/main/CHANGES.md)
- [Tile-memory lifetimes and usages](https://docs.vulkan.org/features/latest/features/proposals/VK_QCOM_tile_memory_heap.html)
- [Tile-specific resource requirements](https://docs.vulkan.org/refpages/latest/refpages/source/VkTileMemoryRequirementsQCOM.html)

The final qualifier is `tile_attachmentQCOM`. Early proposal text uses an
obsolete spelling; these shaders follow the final language extension.
