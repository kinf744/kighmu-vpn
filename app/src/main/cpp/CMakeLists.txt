cmake_minimum_required(VERSION 3.22.1)
project("kighmu_native")

# Network optimization native library
add_library(
    kighmu_native
    SHARED
    kighmu_tun.c
    kighmu_packet.c
)

# Link Android system libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    kighmu_native
    ${log-lib}
    ${android-lib}
)

# Optimization flags
target_compile_options(kighmu_native PRIVATE
    -O2
    -fPIC
    -Wall
)
