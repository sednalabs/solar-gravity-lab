cmake_minimum_required(VERSION 3.22.1)
project(solarlab_vulkan LANGUAGES CXX)

add_library(
    solarlab_vulkan
    SHARED
    SolarLabVulkanBridge.cpp
    SolarLabStageController.cpp
    SolarLabVulkanRenderer.cpp
    PipelineCacheStore.cpp
    SolarLabVulkanCapabilities.cpp
    SolarLabVulkanDiagnostics.cpp
    QcomTileMemory.cpp
    BenchmarkNative.cpp
    BenchmarkGpu.cpp
    BenchmarkOpenCl.cpp
    BenchmarkOverlap.cpp
    BenchmarkNnapi.cpp
)

target_compile_features(solarlab_vulkan PRIVATE cxx_std_20)
target_compile_options(solarlab_vulkan PRIVATE -Wall -Wextra)
if(ANDROID)
    target_link_options(solarlab_vulkan PRIVATE
        "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endif()

# Embed the isolated research shaders in the native library. JNI deliberately
# takes no Activity or assets pointer, so a lost surface cannot invalidate a run.
find_program(SOLARLAB_BENCH_GLSLC NAMES glslc
    HINTS "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64"
          "${ANDROID_NDK}/shader-tools/linux-x86_64"
          "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64"
          "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64" REQUIRED)
set(BENCH_SHADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../benchmarks/shaders")
set(BENCH_GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/benchmark-shaders")
file(MAKE_DIRECTORY "${BENCH_GENERATED_DIR}")
function(solarlab_benchmark_shader NAME SOURCE)
    set(SPIRV "${BENCH_GENERATED_DIR}/${NAME}.spv")
    set(HEADER "${BENCH_GENERATED_DIR}/${NAME}.h")
    add_custom_command(OUTPUT "${HEADER}"
        COMMAND "${SOLARLAB_BENCH_GLSLC}" -O --target-env=vulkan1.1 ${ARGN}
            "${BENCH_SHADER_DIR}/${SOURCE}" -o "${SPIRV}"
        COMMAND "${CMAKE_COMMAND}" "-DINPUT=${SPIRV}" "-DOUTPUT=${HEADER}"
            "-DSYMBOL=${NAME}" -P "${CMAKE_CURRENT_SOURCE_DIR}/EmbedBenchmarkSpirv.cmake"
        DEPENDS "${BENCH_SHADER_DIR}/${SOURCE}" "${CMAKE_CURRENT_SOURCE_DIR}/EmbedBenchmarkSpirv.cmake"
        VERBATIM)
    target_sources(solarlab_vulkan PRIVATE "${HEADER}")
endfunction()
solarlab_benchmark_shader(bench_passive_fp32 passive.comp)
solarlab_benchmark_shader(bench_passive_fp64 passive.comp -DUSE_FP64=1)
solarlab_benchmark_shader(bench_visual_fp32 visual.comp)
solarlab_benchmark_shader(bench_visual_fp16 visual.comp -DUSE_FP16=1)
solarlab_benchmark_shader(bench_compact_atomic compact.comp)
solarlab_benchmark_shader(bench_compact_subgroup compact.comp -DUSE_SUBGROUP=1)
solarlab_benchmark_shader(bench_compact_bda compact_bda.comp)
solarlab_benchmark_shader(bench_dispatch_args dispatch_args.comp)
solarlab_benchmark_shader(bench_descriptor_control descriptor.comp)
solarlab_benchmark_shader(bench_descriptor_indexed descriptor.comp -DUSE_NONUNIFORM=1)
target_include_directories(solarlab_vulkan PRIVATE "${BENCH_GENERATED_DIR}")

# The NDK shader compiler predates the QCOM tile-shading language extension.
# The benchmark packaging lane builds these exact sources with pinned glslang
# 16.6 and passes the output directory. Other variants report this path absent.
set(SOLARLAB_QCOM_SHADER_DIR "" CACHE PATH "Pinned glslang output for embedded QCOM probes")
if(SOLARLAB_QCOM_SHADER_DIR)
    foreach(NAME invert_control tile_invert box_control box_filter)
        set(INPUT "${SOLARLAB_QCOM_SHADER_DIR}/${NAME}.comp.spv")
        set(OUTPUT "${BENCH_GENERATED_DIR}/qcom_${NAME}.h")
        if(NOT EXISTS "${INPUT}")
            message(FATAL_ERROR "Missing required pinned QCOM shader: ${INPUT}")
        endif()
        add_custom_command(OUTPUT "${OUTPUT}"
            COMMAND "${CMAKE_COMMAND}" "-DINPUT=${INPUT}" "-DOUTPUT=${OUTPUT}"
                "-DSYMBOL=qcom_${NAME}" -P "${CMAKE_CURRENT_SOURCE_DIR}/EmbedBenchmarkSpirv.cmake"
            DEPENDS "${INPUT}" "${CMAKE_CURRENT_SOURCE_DIR}/EmbedBenchmarkSpirv.cmake" VERBATIM)
        target_sources(solarlab_vulkan PRIVATE "${OUTPUT}")
    endforeach()
    target_sources(solarlab_vulkan PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../probes/QcomOffscreenProbe.cpp")
    target_compile_definitions(solarlab_vulkan PRIVATE SOLARLAB_EMBED_QCOM_SHADERS=1 SOLARLAB_QCOM_PROBE_LIBRARY=1)
endif()

option(SOLARLAB_CAPTURE_PIPELINE_STATISTICS "Capture driver compiler statistics for profiling" OFF)
if(SOLARLAB_CAPTURE_PIPELINE_STATISTICS)
    target_compile_definitions(solarlab_vulkan PRIVATE SOLARLAB_CAPTURE_PIPELINE_STATISTICS=1)
endif()

option(SOLARLAB_BUFFER_ADDRESS_COMPACTION "Use address-based medium compaction for device A/B qualification" OFF)
if(SOLARLAB_BUFFER_ADDRESS_COMPACTION)
    target_compile_definitions(solarlab_vulkan PRIVATE SOLARLAB_BUFFER_ADDRESS_COMPACTION=1)
endif()

# Limit the capability probe to configurations that can consume IPO. Android's
# Debug configuration uses a cross-linker that cannot execute CMake's generic
# IPO probe even though the NDK release toolchain supports ThinLTO.
if(CMAKE_BUILD_TYPE MATCHES "^(Release|RelWithDebInfo)$")
    include(CheckIPOSupported)
    check_ipo_supported(RESULT solarlab_ipo_supported OUTPUT solarlab_ipo_error LANGUAGES CXX)
    if(solarlab_ipo_supported)
        set_property(TARGET solarlab_vulkan PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
    else()
        message(STATUS "SolarLab native IPO unavailable: ${solarlab_ipo_error}")
    endif()
endif()

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib vulkan)
find_library(dl-lib dl)
find_library(neuralnetworks-lib neuralnetworks)

get_filename_component(
    SOLARLAB_REPOSITORY_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.."
    ABSOLUTE
)

target_include_directories(
    solarlab_vulkan
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${SOLARLAB_REPOSITORY_ROOT}/engine/ffi/include
)

target_link_libraries(
    solarlab_vulkan
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${vulkan-lib}
    ${dl-lib}
    ${neuralnetworks-lib}
)
