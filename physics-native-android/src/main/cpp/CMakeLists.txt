cmake_minimum_required(VERSION 3.22.1)
project(solarlab_physics_native LANGUAGES CXX)

add_library(
    solarlab_physics_native
    SHARED
    SolarLabPhysicsBridge.cpp
)

target_compile_features(solarlab_physics_native PRIVATE cxx_std_20)
target_compile_options(solarlab_physics_native PRIVATE -Wall -Wextra)

find_library(log-lib log)

target_link_libraries(
    solarlab_physics_native
    PRIVATE
    ${log-lib}
)
