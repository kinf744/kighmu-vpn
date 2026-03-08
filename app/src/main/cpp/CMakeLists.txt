cmake_minimum_required(VERSION 3.22.1)
project("kighmu_native")

add_library(
    kighmu_native
    SHARED
    kighmu_packet.c
)

find_library(log-lib log)

target_link_libraries(
    kighmu_native
    ${log-lib}
)
