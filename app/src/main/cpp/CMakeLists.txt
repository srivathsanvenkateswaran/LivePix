cmake_minimum_required(VERSION 3.22.1)
project(livepix_native CXX C)

# libde265 decodes the monochrome HEVC depth maps and mattes that Android's codecs reject.
set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_SIMD OFF CACHE BOOL "" FORCE)
set(ENABLE_DECODER OFF CACHE BOOL "" FORCE)
set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(libde265 EXCLUDE_FROM_ALL)

add_library(livepix_native SHARED depth_decoder.cpp)
target_link_libraries(livepix_native PRIVATE de265 log)
target_link_options(livepix_native PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(livepix_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libde265
    ${CMAKE_CURRENT_BINARY_DIR}/libde265)
target_compile_definitions(livepix_native PRIVATE LIBDE265_STATIC_BUILD)
