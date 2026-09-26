# Shared native core.
#
# Built two ways:
#   * Desktop: standalone, `cmake -S native -B native/build` (see README.md).
#   * Android: androidApp builds this file directly with NCM_BUILD_SELFTEST=OFF.
#
# The algorithms live in RC4Core.cpp / KGMCore.cpp behind the C ABI declared in
# NativeApi.h; JniBridge.cpp is the only file that knows about JNI.
cmake_minimum_required(VERSION 3.22)
project(ncmc4a CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

option(NCM_BUILD_JNI "Build the JNI bridge (needs a JDK)" ON)
option(NCM_BUILD_SELFTEST "Build the standalone core self-test" ON)

set(NCM_CORE_SOURCES
    "${CMAKE_CURRENT_LIST_DIR}/RC4Core.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/KGMCore.cpp")

if(NCM_BUILD_JNI)
    add_library(ncmc4a SHARED
        ${NCM_CORE_SOURCES}
        "${CMAKE_CURRENT_LIST_DIR}/JniBridge.cpp")

    target_include_directories(ncmc4a PUBLIC "${CMAKE_CURRENT_LIST_DIR}")
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

    if(ANDROID)
        target_link_libraries(ncmc4a PRIVATE android log)
    else()
        # Desktop extracts its bundled library; Android uses libncmc4a.so.
        set_target_properties(ncmc4a PROPERTIES
            PREFIX ""
            CXX_VISIBILITY_PRESET hidden
            VISIBILITY_INLINES_HIDDEN ON)

        if(WIN32 AND CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
            # System.load extracts this DLL to a temp directory. A MinGW-built
            # DLL must not require libgcc_s_seh-1.dll or libstdc++-6.dll on the
            # user's machine, where those toolchain runtimes are not installed.
            target_link_options(ncmc4a PRIVATE -static-libgcc -static-libstdc++)
        endif()
    endif()
endif()

if(NCM_BUILD_SELFTEST)
    add_executable(ncm_core_selftest
        "${CMAKE_CURRENT_LIST_DIR}/selftest/SelfTest.cpp"
        ${NCM_CORE_SOURCES})
    target_include_directories(ncm_core_selftest PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
    target_compile_features(ncm_core_selftest PRIVATE cxx_std_17)
endif()

if(WIN32 AND NOT ANDROID)
    enable_language(C)
    # Embedded in the MSI to reuse the directory of a related installation.
    add_library(ncm_msi_restore_install_dir SHARED
        "${CMAKE_CURRENT_LIST_DIR}/MsiRestoreInstallDir.c")
    target_link_libraries(ncm_msi_restore_install_dir PRIVATE msi)
    set_target_properties(ncm_msi_restore_install_dir PROPERTIES PREFIX "")
endif()
