# Shared native core.
#
# Built two ways:
#   * Desktop: standalone, `cmake -S native -B native/build` (see README.md).
#   * Android: included from nativeLib/src/main/cpp/CMakeLists.txt, which already
#     defined the `ncmc4a` project and sets NCM_BUILD_* before including this file.
#
# The algorithms live in RC4Core.cpp / KGMCore.cpp behind the C ABI declared in
# NativeApi.h; JniBridge.cpp is the only file that knows about JNI.
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    cmake_minimum_required(VERSION 3.22)
    project(ncmc4a CXX)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

option(NCM_BUILD_JNI "Build the JNI bridge (needs a JDK)" ON)
option(NCM_BUILD_SELFTEST "Build the standalone core self-test" ON)
option(NCM_ENABLE_SIMD "Enable the historical NEON / NEON_2_SSE SIMD paths" ON)

set(NCM_CORE_SOURCES
    "${CMAKE_CURRENT_LIST_DIR}/RC4Core.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/KGMCore.cpp")

# SimdCompat.h uses this to pick the SIMD backend. NCM_ENABLE_SIMD=OFF selects
# the scalar fallbacks, which exist so both builds can be compared byte for byte.
if(NCM_ENABLE_SIMD)
    set(NCM_SIMD_DEFINITION NCM_ENABLE_SIMD=1)
else()
    set(NCM_SIMD_DEFINITION NCM_ENABLE_SIMD=0)
endif()

if(NCM_BUILD_JNI)
    add_library(ncmc4a SHARED
        ${NCM_CORE_SOURCES}
        "${CMAKE_CURRENT_LIST_DIR}/JniBridge.cpp")

    target_include_directories(ncmc4a PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
    target_compile_definitions(ncmc4a PRIVATE ${NCM_SIMD_DEFINITION})
    if(NCM_JNI_INCLUDE_DIR AND EXISTS "${NCM_JNI_INCLUDE_DIR}/jni.h")
        target_include_directories(ncmc4a PRIVATE
            "${NCM_JNI_INCLUDE_DIR}"
            "${NCM_JNI_INCLUDE_DIR}/win32"
            "${NCM_JNI_INCLUDE_DIR}/darwin"
            "${NCM_JNI_INCLUDE_DIR}/linux")
    elseif(NOT ANDROID)
        message(FATAL_ERROR
            "NCM_JNI_INCLUDE_DIR must point at a JDK 'include' directory "
            "(pass -DNCM_JNI_INCLUDE_DIR=<jdk>/include)")
    endif()

    target_compile_features(ncmc4a PRIVATE cxx_std_17)

    # The library is loaded from a Blob rather than through java.library.path, so
    # the Android and Desktop artifacts can share one file name.
    set_target_properties(ncmc4a PROPERTIES PREFIX "")
    if(NOT ANDROID)
        set_target_properties(ncmc4a PROPERTIES
            CXX_VISIBILITY_PRESET hidden
            VISIBILITY_INLINES_HIDDEN ON)
    endif()
endif()

if(NCM_BUILD_SELFTEST)
    add_executable(ncm_core_selftest
        "${CMAKE_CURRENT_LIST_DIR}/selftest/SelfTest.cpp"
        ${NCM_CORE_SOURCES})
    target_include_directories(ncm_core_selftest PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
    target_compile_definitions(ncm_core_selftest PRIVATE ${NCM_SIMD_DEFINITION})
    target_compile_features(ncm_core_selftest PRIVATE cxx_std_17)
endif()
