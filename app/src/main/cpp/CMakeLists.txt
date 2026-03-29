cmake_minimum_required(VERSION 3.22.1)
project("kighmu_relay")

add_library(kighmu_relay SHARED socks_relay.cpp)
target_link_libraries(kighmu_relay log)
