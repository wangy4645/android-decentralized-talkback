cmake_minimum_required(VERSION 3.22.1)
project(gres3_capacity_native)

add_library(pnsrd_dliterate_probe SHARED pnsrd_dliterate_probe.cpp)
add_library(gres3_sendmmsg_probe SHARED gres3_sendmmsg_probe.cpp)

find_library(log-lib log)

target_link_libraries(
    pnsrd_dliterate_probe
    ${log-lib}
    dl
)

target_link_libraries(
    gres3_sendmmsg_probe
    ${log-lib}
)

target_compile_features(pnsrd_dliterate_probe PRIVATE cxx_std_17)
target_compile_features(gres3_sendmmsg_probe PRIVATE cxx_std_17)
