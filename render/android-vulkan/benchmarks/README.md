# Isolated native hardware research

The Android benchmark screen calls `NativeHardwareBench` in `libsolarlab_vulkan`.
The native catalogue separates supported, implemented, enabled and executed
states. A capability query never claims that it ran a kernel. Each result is
versioned JSON with PASS, FAIL or SKIP, an explicit reason, a monotonic duration,
timing scope, an output digest and an independent correctness check where a
numerical output exists. All workloads use synthetic data and separate Vulkan
devices; they do not change the authoritative Rust world or private saves.

## Workloads and interpretation

* CPU and memory controls cover libc copy/clear, ordinary versus explicit
  prefetch, compiler versus gated SVE addition, ordinary versus gated FCMA
  complex products, portable versus ARM CRC32 and contended atomic counters.
  Optional instructions run only after this process's auxiliary vector permits
  them. The atomic comparison is not permission to weaken a publication protocol.
* Vulkan acceleration evaluates 16,387 synthetic targets against three sources
  and a CPU FP64 oracle. The deliberately incomplete final workgroup also checks
  compaction tails. FP64 and FP16 are gated independently; FP16 is confined to a
  colour transform. GPU gravity remains a non-authoritative research primitive.
* Ordinary atomics, subgroup ballots, buffer device addresses and QCOM tile-heap
  counters produce the same sorted visibility IDs. The tile counter is cleared,
  used and copied to ordinary memory in the same submission.
* Fixed buffer bindings and divergent `nonuniformEXT` indexing read identical
  two-buffer inputs. The latter enables only the storage-buffer indexing features
  it needs. Descriptor indexing and descriptor buffers are different features.
* Indirect dispatch generates its arguments on the GPU. Timeline and
  synchronization2 cases use the same acceleration kernel as their ordinary
  submission control. Persistent mapping and remapping compare the same upload
  and copy data. Pipeline cache export/reload is measured separately; driver
  internal caches are never described as cold or cleared.
* Android hardware-buffer import uses a CPU-written BLOB with GPU_DATA_BUFFER
  usage, queries importability and actual allocation/memory-type requirements,
  waits for the CPU unlock fence and performs foreign queue-family ownership
  transfers around compute. It checks ordinary readback against the same FP64
  oracle as the normal-buffer control. Allocation/upload is in `setup_ns`;
  submission time alone does not establish an end-to-end zero-copy benefit.
* QCOM inversion and box-filter controls and candidates reuse
  `probes/QcomOffscreenProbe.cpp`. The benchmark APK embeds the original shaders
  compiled by pinned glslang; missing payloads or device features produce SKIP.
  A passing primitive does not qualify a whole-frame renderer speedup.
* OpenCL loads only the public `libOpenCL.so` visible to the application. Its
  FP32/optional FP64 kernels use the same force fixture and CPU oracle. An absent
  loader or GPU device is a recorded SKIP, not an attempt to load private paths.
* NNAPI explicitly selects a public accelerator device with complete operation
  support and compares a dense FP32 primitive to a CPU FP64 oracle. Its output is
  not a learned orbital model or proof of a particular Hexagon driver.
* Serial and overlapped CPU/GPU cases retain both component results. Their wall
  time includes device creation, compilation, worker lifetime, verification and
  teardown. Recorded job-interval overlap includes CPU oracle work and is not a
  measurement of pure GPU/CPU kernel concurrency.

Timestamp samples report validity, period and valid bits. Supported calibrated
clocks and pipeline compiler statistics are captured as driver-provided data.
Pipeline statistics can perturb compilation and are a diagnostic case.

Calling-thread placement accepts `system`, `single` or `big_cluster`. It captures
the original allowed CPU set, intersects selections with it, applies and verifies
the actual result, and restores on the same dedicated worker thread. Missing
capacity/frequency topology produces an explicit reason rather than a guessed
performance cluster. Children inherit the selected placement.

## Host correctness proof

Install a C++20 compiler, CMake, Python, Vulkan development headers/loader, a
software Vulkan ICD and `glslc`. Then run:

```sh
cmake -S render/android-vulkan/benchmarks -B /tmp/solarlab-native-proof \
  -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/solarlab-native-proof -j2
ctest --test-dir /tmp/solarlab-native-proof --output-on-failure
```

Set `VK_ICD_FILENAMES` to a known software ICD if several devices are installed.
Use the APK workflow's pinned Vulkan headers for QCOM extension declarations.
Pass `-DSOLARLAB_QCOM_SHADER_DIR=/absolute/path/to/shaders` to include all four
pinned QCOM SPIR-V payloads. Missing required files fail configuration.

`verify_cases.py` executes every advertised case, checks provenance and numerical
oracles, and requires unknown IDs to remain unimplemented. A separate overlap
test injects unsupported and throwing GPU components to verify thread joining
and failure propagation. Host results establish implementation correctness for
that host and ICD; they provide no S25 timing, thermal, power or driver evidence.

## Public API references

* [Android NDK hardware-buffer API](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
* [Vulkan Android hardware-buffer import](https://docs.vulkan.org/refpages/latest/refpages/source/VkImportAndroidHardwareBufferInfoANDROID.html)
* [Vulkan memory allocation requirements](https://docs.vulkan.org/refpages/latest/refpages/source/VkMemoryAllocateInfo.html)
* [Vulkan external resource ownership](https://docs.vulkan.org/spec/latest/chapters/memory.html)
