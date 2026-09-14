import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val desktopJdk25Home = extensions.getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    .map { it.metadata.installationPath.asFile.absolutePath }

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

    android {
        namespace = "com.cdb96.ncmconverter4a.lib"
        compileSdk = 37
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
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
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
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
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// Java 编译启用 Vector API 模块（Kotlin 无法直接解析 Vector API，用 Java 封装）
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_25.toString()
    targetCompatibility = JavaVersion.VERSION_25.toString()
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules=jdk.incubator.vector")
}

compose.desktop {
    application {
        // Compose packaging tasks default to the Gradle process JDK. Use the
        // full JDK 25 toolchain because Android Studio's JBR omits jdk.incubator.vector.
        javaHome = desktopJdk25Home.get()
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

// A Jar manifest cannot carry the JVM's --add-modules option. Use the small
// relauncher above for the published Uber Jar; jpackage launchers continue to
// receive the option directly through application.jvmArgs.
tasks.withType<Jar>().configureEach {
    if (name.endsWith("UberJarForCurrentOS")) {
        // The Compose plugin assigns Main-Class in its own task action. Set
        // ours immediately before the Jar task runs so that action cannot
        // overwrite the launcher manifest.
        doFirst {
            manifest.attributes["Main-Class"] = "com.cdb96.ncmconverter4a.UberJarLauncherKt"
        }
    }
}

afterEvaluate {
    tasks.withType<AbstractProguardTask>().configureEach {
        javaHome.set(desktopJdk25Home)
    }
}


