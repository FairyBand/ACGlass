cmake_minimum_required(VERSION 3.18)
project(acglass_consumer C)

set(LIBDISPLAY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_library(acglass_consumer SHARED
    native_consumer.c
    ${LIBDISPLAY_DIR}/libdisplay_consumer/display_consumer.c
    ${LIBDISPLAY_DIR}/common/socket_utils.c
)

target_include_directories(acglass_consumer PRIVATE
    ${LIBDISPLAY_DIR}/libdisplay_consumer
    ${LIBDISPLAY_DIR}/common
)

target_compile_options(acglass_consumer PRIVATE -O3 -flto)
target_link_options(acglass_consumer PRIVATE -flto -fuse-ld=lld)

target_link_libraries(acglass_consumer
    android
    log
    dl
)
