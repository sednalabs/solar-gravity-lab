cmake_minimum_required(VERSION 3.22.1)
project(solarlab_vulkan LANGUAGES CXX)

include(CheckIPOSupported)

add_library(
    solarlab_vulkan
    SHARED
    SolarLabVulkanBridge.cpp
    SolarLabStageController.cpp
    SolarLabVulkanRenderer.cpp
)

target_compile_features(solarlab_vulkan PRIVATE cxx_std_20)
target_compile_options(solarlab_vulkan PRIVATE -Wall -Wextra)

# NDK Clang supports interprocedural optimization for release shared objects,
# but keep the project portable by enabling it only when the active toolchain
# proves support.
check_ipo_supported(RESULT solarlab_ipo_supported OUTPUT solarlab_ipo_error LANGUAGES CXX)
if(solarlab_ipo_supported)
    set_property(TARGET solarlab_vulkan PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
    set_property(TARGET solarlab_vulkan PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELWITHDEBINFO TRUE)
else()
    message(STATUS "SolarLab native IPO unavailable: ${solarlab_ipo_error}")
endif()

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(dl-lib dl)

target_include_directories(
    solarlab_vulkan
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/ffi/include
)

target_link_libraries(
    solarlab_vulkan
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${dl-lib}
)
