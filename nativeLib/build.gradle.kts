plugins {
    alias(libs.plugins.android.native.library)
}

android {
    namespace = "com.cdb96.ncmconverter4a.nativelib"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndkVersion = "30.0.15729638"
        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -Wno-gnu-inline-cpp-without-extern -Wno-deprecated-declarations"
                arguments("-DANDROID_ARM_NEON=TRUE")
                targets("ncmc4a")
            }
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17 -Wno-gnu-inline-cpp-without-extern -Wno-deprecated-declarations"
                    arguments("-DANDROID_ARM_NEON=TRUE")
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
