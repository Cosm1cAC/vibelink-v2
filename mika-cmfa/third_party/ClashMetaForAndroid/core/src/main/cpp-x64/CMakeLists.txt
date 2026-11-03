cmake_minimum_required(VERSION 3.10)
project(clash-bridge C)

set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")
link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
