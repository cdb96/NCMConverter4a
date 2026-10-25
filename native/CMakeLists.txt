# Shared native core.
#
# Built two ways:
#   * Desktop: standalone, `cmake -S native -B native/build` (see README.md).
#   * Android: androidApp builds this file directly with NCM_BUILD_SELFTEST=OFF.
#
# The algorithm sources implement the JNI entry points directly.
cmake_minimum_required(VERSION 3.22)
project(ncmc4a CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

option(NCM_BUILD_JNI "Build the JNI library (needs a JDK)" ON)
option(NCM_BUILD_SELFTEST "Build the standalone core self-test" ON)

if(NOT ANDROID AND (NCM_BUILD_JNI OR NCM_BUILD_SELFTEST))
    if(NOT NCM_JNI_INCLUDE_DIR OR NOT EXISTS "${NCM_JNI_INCLUDE_DIR}/jni.h")
        message(FATAL_ERROR
            "NCM_JNI_INCLUDE_DIR must point at a JDK 'include' directory "
            "(pass -DNCM_JNI_INCLUDE_DIR=<jdk>/include)")
    endif()
    set(NCM_JNI_INCLUDE_DIRS
        "${NCM_JNI_INCLUDE_DIR}"
        "${NCM_JNI_INCLUDE_DIR}/win32"
        "${NCM_JNI_INCLUDE_DIR}/darwin"
        "${NCM_JNI_INCLUDE_DIR}/linux")
endif()

set(NCM_CORE_SOURCES
    "${CMAKE_CURRENT_LIST_DIR}/cpp/RC4Core.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/cpp/KGMCore.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/cpp/KGGCore.cpp")

# Keep the decryption library optimized for throughput independently of the
# Desktop Native Image optimization settings. Android releases use RelWithDebInfo.
function(ncm_optimize_core target)
    if(MSVC)
        # MSVC's speed optimization level is /O2; it has no /O3 option.
        target_compile_options(${target} PRIVATE
            "$<$<CONFIG:Release,RelWithDebInfo,MinSizeRel>:/O2>")
    else()
        target_compile_options(${target} PRIVATE
            "$<$<CONFIG:Release,RelWithDebInfo,MinSizeRel>:-O3>")
    endif()
endfunction()

if(NCM_BUILD_JNI)
    add_library(ncmc4a SHARED ${NCM_CORE_SOURCES})
    ncm_optimize_core(ncmc4a)
    target_include_directories(ncmc4a PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/cpp" ${NCM_JNI_INCLUDE_DIRS})

    target_compile_features(ncmc4a PRIVATE cxx_std_17)
    set_target_properties(ncmc4a PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

    if(MSVC)
        # Keep the bundled DLL independent of the Visual C++ redistributable.
        set_property(TARGET ncmc4a PROPERTY MSVC_RUNTIME_LIBRARY
            "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        set_property(TARGET ncmc4a PROPERTY INTERPROCEDURAL_OPTIMIZATION_RELEASE TRUE)
    endif()

    if(ANDROID)
        # JNI reports errors as Java exceptions; the core needs neither C++
        # exceptions nor RTTI. Keep static runtime symbols private so the linker
        # can discard unused archive code instead of exporting it from this .so.
        target_compile_options(ncmc4a PRIVATE -fno-exceptions -fno-rtti)
        target_link_options(ncmc4a PRIVATE "-Wl,--exclude-libs,ALL")
        target_link_libraries(ncmc4a PRIVATE android log)
    else()
        # Desktop extracts its bundled library; Android uses libncmc4a.so.
        set_target_properties(ncmc4a PROPERTIES
            PREFIX "")

        if(WIN32)
            target_sources(ncmc4a PRIVATE "${CMAKE_CURRENT_LIST_DIR}/windows/NativeCaption.cpp")
            target_link_libraries(ncmc4a PRIVATE dwmapi)
        endif()

        if(WIN32 AND CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
            # System.load extracts this DLL to a temp directory. A MinGW-built
            # DLL must not require MinGW runtime DLLs on the user's machine.
            # -static-libgcc/-static-libstdc++ alone can leave libwinpthread
            # dynamically linked when std::thread is used.
            target_link_options(ncmc4a PRIVATE -static -static-libgcc -static-libstdc++)
        endif()
    endif()
endif()

if(NCM_BUILD_SELFTEST)
    add_executable(ncm_core_selftest
        "${CMAKE_CURRENT_LIST_DIR}/tests/SelfTest.cpp"
        ${NCM_CORE_SOURCES})
    target_include_directories(ncm_core_selftest PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/cpp" ${NCM_JNI_INCLUDE_DIRS})
    target_compile_features(ncm_core_selftest PRIVATE cxx_std_17)
    ncm_optimize_core(ncm_core_selftest)
endif()
