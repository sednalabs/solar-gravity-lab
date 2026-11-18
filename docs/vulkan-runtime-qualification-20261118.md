# Vulkan runtime acceleration qualification, 18 November 2026

This document describes the implementation following PR #242. Source implementation,
host contract tests, an Android build and execution on a physical Galaxy S25 Ultra
are separate evidence levels. No GPU speedup, thermal result or S25 extension
availability is established by the implementation or host tests below.

## Implemented workloads and their gates

| Path | Required capability | Actual consumer | Fallback / qualification |
| --- | --- | --- | --- |
| Subgroup compaction | Vulkan 1.1 compute-stage `BASIC` and `BALLOT` subgroup operations | Medium visibility reserves one output range per nonempty subgroup. Far visibility retains per-tile admission and aggregates accepted/drop counters across each subgroup. | Existing scalar-atomic shader if capability or pipeline creation fails. Subgroup width is queried, never fixed to 32/64. |
| QCOM tile memory | Advertised `VK_QCOM_tile_memory_heap`, `tileMemoryHeap`, `tileBufferTransfers`, compatible tile heap and callable bind command | Far tile counters, cleared, incremented and copied to ordinary readback memory in one submission batch | Ordinary device-local counters on any failed gate/allocation. Never put vertex/indirect buffers, authoritative state or persistent scene data in the tile heap. |
| Synchronization2 | Vulkan 1.3 core or advertised KHR extension, queried/enabled feature and callable commands | Actual buffer/memory barriers and frame/upload queue submissions | Existing Vulkan 1.1 barriers/submissions. Per-resource stage scopes remain compatible with their access masks. |
| Buffer device address | Queried/enabled BDA, compute subgroup operations, nonzero buffer addresses; explicit experiment build flag | Medium subgroup compaction receives source/output/draw addresses in a 40-byte push block, updating only the uniform descriptor | Descriptor-backed subgroup compaction. The experiment remains off by default until device A/B evidence justifies promotion. |
| Pipeline executable properties | Advertised extension, queried/enabled feature and both query functions; explicit profiling build flag | Pipeline creation captures statistics, then logs driver-supplied statistic names/values and executable subgroup sizes | Ordinary pipeline creation without capture. Driver statistics are not assumed to include any particular register/spill counter. |
| Calibrated timestamps | KHR or EXT extension, both device and `CLOCK_MONOTONIC` domains and callable device command | Paired GPU ticks/CPU monotonic nanoseconds with maximum deviation, at first timing sample and every 120 samples | GPU duration queries still work independently. Missing domains/functions leave calibration inactive. |

The tile reservation is bounded by the smaller of one eighth of the eligible heap
and 512 KiB. That is a conservative initial budget, not a measured optimum. Tile
memory uses tile-specific requirements, compatible memory-type bits, dedicated
allocation requirements when present, and offset zero for alignment. Generic
allocation explicitly excludes tile heaps. Binding occurs before action commands;
the same command buffer resets, computes and reads back the ephemeral counters.

The BDA path uses only the medium source/output/indirect allocations. Each has the
address usage bit and address allocation flag. Addresses are queried after binding,
checked before dispatch and retained only for the allocation's lifetime. Buffer
generation changes force command re-recording, which replaces the pushed addresses.
No address is exposed through JNI or substituted for the Rust FP64 world.

The shader uses typed physical-storage pointers, so it does not require the
separate `shaderInt64` feature. The NDK-compiled shader from
[run 44273135829](https://github.com/sednalabs/solar-gravity-lab/actions/runs/44273135829)
at source `2ca05d3aabecf6521d96397cdb4668d914b64cc0` declares `Shader`,
`GroupNonUniform`, `GroupNonUniformBallot` and `PhysicalStorageBufferAddresses`,
with no `Int64` capability or 64-bit integer types. Its SHA-256 is
`d5504bfab698d068017644c04dd7f5f336a5baac4332a9c7cad90deaeee06e14`.
The same run's later C++ build failed because its proof command selected Android
API 26; the shader evidence does not establish a successful Android build.
`verify_medium_bda_spirv.py` checks the actual compiled artifact's capability
requirements, push offsets, address types and source/output strides, so a future
shader change cannot silently introduce an unrequested integer64 requirement.

## Synchronisation and ownership

Host-visible coherent buffers are mapped once per allocation and unmapped before
freeing. The existing frame fence and eight staging-slot fences still determine
when the CPU may overwrite, read or retire each allocation. Mapping persistence
removes map/unmap API calls; it does not remove ownership waits.

Presentation wait semaphores now belong to swapchain images, indexed by the image
returned from acquisition. A rendering fence alone does not establish that the
presentation engine has consumed its wait semaphore. WSI remains on binary
semaphores. The renderer keeps one GPU frame in flight because its mutable uniforms,
compute output, depth target and camera/picking presentation epoch are shared.
Claiming frame overlap would require versioning those resources and proving
latency/picking behaviour. Timeline support is queried but not enabled merely to
increase the extension count.

Surface teardown retains the existing device-idle convention. Khronos documents
that unextended presentation lacks a formal presentation-completion fence;
`VK_EXT_swapchain_maintenance1` would be the follow-up for explicit retirement.

## Observable evidence

`HardwareSummary()` separates supported and enabled features from actual retired
work. Subgroup, BDA and QCOM tile counters have completed-frame counts, incremented
after the submission fence. Zero means no retired workload has been observed.
Pipeline statistics have their own returned-statistic count; calibration reports
the host/device anchor and `maxDeviationNs`.

`SceneSummary()` reports GPU frame and pre-graphics timestamp intervals, sample
count and cumulative mapping allocations. Queries are reset inside cached command
buffers and read only after the existing frame fence, with availability bits and
without a query WAIT. Timestamp deltas use the queue family's valid-bit width and
timestamp period. The pre-graphics interval includes setup/reset/compute work and
must not be reported as isolated shader execution time. GPU frame duration can
include dependencies; neither interval is CPU wall time or end-to-end latency.

## Qualification commands

Normal build retains descriptor compaction and avoids compiler-statistics capture:

```sh
./gradlew -p clients/android :app:assembleDebug
```

An experimental profiling build compiles and requests both optional consumers:

```sh
./gradlew -p clients/android :app:assembleDebug \
  -PsolarLabBufferAddressCompaction=true \
  -PsolarLabCapturePipelineStatistics=true
```

Host tests compile production probe/feature-chain/allocation code with mock Vulkan
entrypoints. `SolarLabVulkanCapabilitiesTest.cpp` covers advertised-but-false
features, extension/core promotion, missing calibration functions/domains, repeated
chain preparation and fallback reset, subgroup stage/operation gates and timestamp
wraparound, including BDA eligibility without `shaderInt64`. `QcomTileMemoryTest.cpp` covers unavailable features, transfers, entrypoints,
budgets, memory-type masks, protected memory, dedicated requirements and cleanup
after allocation/bind failures. These tests do not execute GPU instructions.

```sh
g++ -std=c++20 -Wall -Wextra -Werror -Wno-missing-field-initializers \
  -I /path/to/Vulkan-Headers/include \
  render/android-vulkan/src/test/cpp/SolarLabVulkanCapabilitiesTest.cpp \
  render/android-vulkan/src/main/cpp/SolarLabVulkanCapabilities.cpp \
  -o /tmp/solar-vulkan-capabilities-test
/tmp/solar-vulkan-capabilities-test

g++ -std=c++20 -Wall -Wextra -Werror \
  -I /path/to/Vulkan-Headers/include \
  render/android-vulkan/src/test/cpp/QcomTileMemoryTest.cpp \
  render/android-vulkan/src/main/cpp/QcomTileMemory.cpp \
  -o /tmp/solar-qcom-tile-test
/tmp/solar-qcom-tile-test

python3 render/android-vulkan/src/test/python/verify_medium_bda_spirv.py \
  /path/to/compiled/compact_medium_bda.comp.spv
```

Physical qualification must use the exact remotely validated APK, capture the
device/driver/capability summary, enable Vulkan validation, and exercise medium/far
populations, empty/partial subgroups, all-visible/all-culled cases, allocation growth,
resize/rotation, surface recreation and repeated camera-only redraws. Compare
visible counts, selected stable IDs, far occupancy/drop totals and rendered output
against the portable path. Record repeated GPU/CPU frame distributions and sustained
thermal/power observations before promoting an experiment or changing its budget.

## Primary references

- [Vulkan synchronization2](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_synchronization2.html)
- [Swapchain semaphore reuse and presentation teardown](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html)
- [Buffer address validity and alignment](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetBufferDeviceAddress.html)
- [Memory allocation flags](https://docs.vulkan.org/refpages/latest/refpages/source/VkMemoryAllocateFlagsInfo.html)
- [Pipeline executable properties](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_pipeline_executable_properties.html)
- [Calibrated timestamps, including KHR promotion](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_calibrated_timestamps.html)
- [Tile-memory binding scope](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBindTileMemoryQCOM.html)
- [QCOM tile-memory heap](https://docs.vulkan.org/refpages/latest/refpages/source/VK_QCOM_tile_memory_heap.html)
