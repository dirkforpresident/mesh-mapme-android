cmake_minimum_required(VERSION 3.18.1)
project("h3android")

# Set H3 build options
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(BUILD_FILTERS OFF CACHE BOOL "" FORCE)
set(BUILD_GENERATORS OFF CACHE BOOL "" FORCE)
set(ENABLE_COVERAGE OFF CACHE BOOL "" FORCE)
set(ENABLE_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_FORMAT OFF CACHE BOOL "" FORCE)
set(ENABLE_LINTING OFF CACHE BOOL "" FORCE)

# Add H3 as subdirectory
add_subdirectory(h3lib)

# JNI wrapper library
add_library(h3android SHARED
    h3_jni.c
)

# Include H3 headers
target_include_directories(h3android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/h3lib/src/h3lib/include
    ${CMAKE_CURRENT_BINARY_DIR}/h3lib/src/h3lib/include
)

# Link H3 to JNI wrapper
target_link_libraries(h3android
    h3
    log
)
