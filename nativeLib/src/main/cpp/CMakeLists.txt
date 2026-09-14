
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see: https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("ncmc4a")

# Shared implementation. The Android APK and the Desktop JVM link the exact same
# RC4Core.cpp / KGMCore.cpp; JniBridge.cpp is the per-platform glue. Keep this
# source list in sync with native/CMakeLists.txt, which defines the `ncmc4a`
# target for the Desktop JVM.
#
# include() resolves the relative path at parse time against the real source
# directory, which survives AGP copying these CMake files into an intermediate
# build directory (add_subdirectory with a computed path does not). The trailing
# .. must be resolved explicitly: include() does not normalise it.
set(NCM_BUILD_JNI ON)
set(NCM_BUILD_SELFTEST OFF)
# Resolve the shared sources. CMake collapses consecutive '..' segments in one
# normalization pass, so a plain relative path silently lands on nativeLib/native;
# resolving level by level avoids that. CMAKE_CURRENT_LIST_DIR is used because AGP
# copies these CMake files into an intermediate directory.
cmake_path(ABSOLUTE_PATH CMAKE_CURRENT_LIST_DIR NORMALIZE)
cmake_path(GET CMAKE_CURRENT_LIST_DIR PARENT_PATH NCM_SHARED_DIR)   # cpp -> main
cmake_path(GET NCM_SHARED_DIR PARENT_PATH NCM_SHARED_DIR)           # main -> src
cmake_path(GET NCM_SHARED_DIR PARENT_PATH NCM_SHARED_DIR)           # src -> nativeLib
cmake_path(GET NCM_SHARED_DIR PARENT_PATH NCM_SHARED_DIR)           # nativeLib -> repo root
cmake_path(APPEND NCM_SHARED_DIR "native")
include("${NCM_SHARED_DIR}/CMakeLists.txt")

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
        android
        log)
