plugins {
    alias(libs.plugins.android.native.library)
}

// The RC4/KGM implementation lives in native/ so that the Android APK and the
// Desktop JVM link one copy. The absolute path is passed to CMake because AGP
// copies the CMake files into an intermediate directory, which makes relative
// paths from the script unreliable.
val nativeCoreDir = rootProject.layout.projectDirectory.dir("native").asFile.absolutePath

android {
    namespace = "com.cdb96.ncmconverter4a.nativelib"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndkVersion = "30.0.16248370"
        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -Wno-gnu-inline-cpp-without-extern -Wno-deprecated-declarations"
                arguments("-DANDROID_ARM_NEON=TRUE", "-DNCM_SHARED_DIR=$nativeCoreDir")
                targets("ncmc4a")
            }
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17 -Wno-gnu-inline-cpp-without-extern -Wno-deprecated-declarations"
                    arguments("-DANDROID_ARM_NEON=TRUE", "-DNCM_SHARED_DIR=$nativeCoreDir")
                    targets("ncmc4a")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
