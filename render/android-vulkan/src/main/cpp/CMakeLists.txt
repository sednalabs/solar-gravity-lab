cmake_minimum_required(VERSION 3.22.1)
project(solarlab_vulkan LANGUAGES CXX)

add_library(
    solarlab_vulkan
    SHARED
    SolarLabVulkanBridge.cpp
    SolarLabStageController.cpp
    SolarLabVulkanRenderer.cpp
)

target_compile_features(solarlab_vulkan PRIVATE cxx_std_20)
target_compile_options(solarlab_vulkan PRIVATE -Wall -Wextra)

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
find_library(vulkan-lib vulkan)
find_library(dl-lib dl)

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
    ${vulkan-lib}
    ${dl-lib}
)
