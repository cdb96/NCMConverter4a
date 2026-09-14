
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("ncmc4a")

# Shared implementation. The Android APK and the Desktop JVM link the exact same
# RC4Core.cpp / KGMCore.cpp; JniBridge.cpp is the per-platform glue.
#
# NCM_SHARED_DIR comes from nativeLib/build.gradle.kts as an absolute path: AGP
# copies these CMake files into an intermediate directory, so a path computed
# here with '..' segments resolves against the wrong tree.
set(NCM_BUILD_JNI ON)
set(NCM_BUILD_SELFTEST OFF)
if(NOT NCM_SHARED_DIR)
    message(FATAL_ERROR "NCM_SHARED_DIR is not set; nativeLib/build.gradle.kts must pass -DNCM_SHARED_DIR=<repo>/native")
endif()
if(NOT EXISTS "${NCM_SHARED_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "NCM_SHARED_DIR does not contain the shared native core: ${NCM_SHARED_DIR}")
endif()
include("${NCM_SHARED_DIR}/CMakeLists.txt")

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
        android
        log)
