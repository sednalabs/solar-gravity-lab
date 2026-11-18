cmake_minimum_required(VERSION 3.22.1)
project(solarlab_qcom_probes LANGUAGES CXX)

find_package(Vulkan REQUIRED)
find_program(GLSLANG_VALIDATOR NAMES glslangValidator glslang REQUIRED)

add_executable(solarlab-qcom-offscreen-probe QcomOffscreenProbe.cpp)
target_compile_features(solarlab-qcom-offscreen-probe PRIVATE cxx_std_20)
target_compile_options(solarlab-qcom-offscreen-probe PRIVATE -Wall -Wextra -Werror)
target_link_libraries(solarlab-qcom-offscreen-probe PRIVATE Vulkan::Vulkan)

add_executable(solarlab-qcom-fixture-test ../src/test/cpp/QcomProbeFixtureTest.cpp)
target_compile_features(solarlab-qcom-fixture-test PRIVATE cxx_std_20)
target_compile_options(solarlab-qcom-fixture-test PRIVATE -Wall -Wextra -Werror)
enable_testing()
add_test(NAME qcom-fixture-separation COMMAND solarlab-qcom-fixture-test)

# Separate from the APK's NDK shader build: tile shading needs glslang 15.4+.
set(PROBE_SPIRV)
foreach(SHADER invert_control.comp tile_invert.comp box_control.comp box_filter.comp)
    set(OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/shaders/${SHADER}.spv")
    add_custom_command(
        OUTPUT "${OUTPUT}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${CMAKE_CURRENT_BINARY_DIR}/shaders"
        COMMAND "${GLSLANG_VALIDATOR}" -V --target-env vulkan1.3
            "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${SHADER}" -o "${OUTPUT}"
        DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${SHADER}"
        VERBATIM
    )
    list(APPEND PROBE_SPIRV "${OUTPUT}")
endforeach()
add_custom_target(solarlab-qcom-probe-shaders ALL DEPENDS ${PROBE_SPIRV})
