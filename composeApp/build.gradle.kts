import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.cdb96.ncmconverter4a.lib"
        compileSdk = 37
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.12.0-beta03")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.0-beta03")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.compose.ui:ui:1.12.0-beta03")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime.ktx)
                implementation(libs.androidx.documentfile)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
    }
}

// Java 编译启用 Vector API 模块（Kotlin 无法直接解析 Vector API，用 Java 封装）
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

compose.desktop {
    application {
        mainClass = "com.cdb96.ncmconverter4a.MainKt"
        jvmArgs += "--add-modules=jdk.incubator.vector"
        buildTypes.release {
            proguard {
                isEnabled = true
                configurationFiles.from(project.file("compose-desktop.pro"))
            }
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "NCMConverter4a"
            packageVersion = "4.0.0"
            appResourcesRootDir.set(layout.projectDirectory.dir("src/desktopMain/resources"))

            // JRE modules — trimmed to the minimum needed at runtime
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "jdk.crypto.ec",
                "jdk.incubator.vector",
            )
        }
    }
}

afterEvaluate {
    tasks.withType<AbstractProguardTask>().configureEach {
        javaHome.set(System.getProperty("java.home"))
    }
}


