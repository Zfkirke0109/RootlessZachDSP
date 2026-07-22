# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -Ofast -ftree-vectorize -DDEBUG")

# Declares and names the project.
project(jamesdsp LANGUAGES C)
project(jamesdsp-wrapper LANGUAGES CXX)

include(libcrashlytics-connector/CMakeLists.txt)
include(libjdspimptoolbox/CMakeLists.txt)
include(libjamesdsp-wrapper/CMakeLists.txt)

# The Direct Player decodes FLAC itself instead of relying on device-specific MediaCodec limits.
# dr_flac is already vendored for the convolver import path under its public-domain/MIT dual
# license; this separate target gives the player a small, seekable-file-descriptor JNI surface.
add_library(
        flac-decoder
        SHARED
        flac_decoder/flac_decoder_jni.cpp)
target_compile_features(flac-decoder PRIVATE cxx_std_17)
target_compile_options(flac-decoder PRIVATE -O2 -Wall -Wextra)
target_include_directories(
        flac-decoder
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/libjdspimptoolbox)

# WavPack 5.9.0 is fetched from an immutable upstream commit. Only the decoder library is
# built; command-line programs, install rules, DSD conversion, worker threads, and assembly
# are intentionally disabled for the small Android JNI surface below.
include(FetchContent)
set(WAVPACK_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(WAVPACK_INSTALL_CMAKE_MODULE OFF CACHE BOOL "" FORCE)
set(WAVPACK_INSTALL_DOCS OFF CACHE BOOL "" FORCE)
set(WAVPACK_INSTALL_PKGCONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_DSD OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_THREADS OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_ASM OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
        wavpack_upstream
        GIT_REPOSITORY https://github.com/dbry/WavPack.git
        GIT_TAG 5803634a030e2a11dba602ba057b89cc34486c67
        GIT_SHALLOW FALSE
        GIT_SUBMODULES "")
FetchContent_MakeAvailable(wavpack_upstream)

add_library(
        wavpack-decoder
        SHARED
        wavpack_decoder/wavpack_decoder_jni.cpp)
target_compile_features(wavpack-decoder PRIVATE cxx_std_17)
target_compile_options(wavpack-decoder PRIVATE -O2 -Wall -Wextra -Werror=return-type)
target_link_libraries(wavpack-decoder PRIVATE WavPack::WavPack)

file(GLOB_RECURSE LIBJAMESDSP_SOURCE_FILES CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.c
        ${CMAKE_CURRENT_LIST_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/*.h)

add_library( # Sets the name of the library.
        jamesdsp

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        ${LIBJAMESDSP_SOURCE_FILES}
        EELStdOutExtension.c
        EELStdOutExtension.h)

target_compile_options(jamesdsp PRIVATE -O2)
target_compile_options(jamesdsp PRIVATE -std=gnu11)
target_include_directories(jamesdsp PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/)

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

target_link_libraries( # Specifies the target library.
        jamesdsp

        # Links the target library to the log library
        # included in the NDK.
        ${log-lib})
