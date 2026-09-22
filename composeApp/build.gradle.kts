import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val desktopNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")

val desktopJdk25Home = extensions.getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    .map { it.metadata.installationPath.asFile.absolutePath }

// JDK 25 product feature: smaller headers for UI/state objects, with no
// change to the streaming buffers or native SIMD conversion path.
// Keep an explicit off switch for same-runtime performance comparisons.
val desktopObjectHeaderArgs = listOf(
    if (providers.gradleProperty("ncmCompactObjectHeaders").orNull != "false")
        "-XX:+UseCompactObjectHeaders"
    else
        "-XX:-UseCompactObjectHeaders"
)

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
        getByName("commonMain") {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.12.0-beta03")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.0-beta03")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.compose.ui:ui:1.12.0-beta03")
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime.ktx)
                implementation(libs.androidx.documentfile)
            }
        }
        getByName("desktopMain") {
            resources.srcDir(desktopNativeResources)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Desktop native core
//
// native/ holds the single C++ implementation of RC4 and KGM. androidApp builds
// it for Android; this task builds the same sources into the Desktop JVM library
// and bundles it as a resource, so both platforms share one bridge as well.
//
// Needs cmake plus a C++ compiler. Skipped with -PncmSkipNativeBuild=true, or
// automatically when cmake is not installed; the desktop native tests then fail
// with an explicit "native core 不可用" message.
// ---------------------------------------------------------------------------
val nativeSourceDir = rootProject.layout.projectDirectory.dir("native")
val nativeBuildDir = nativeSourceDir.dir("build")
val ncmHostOs = org.gradle.internal.os.OperatingSystem.current()
val ncmNativeOsName = when {
    ncmHostOs.isWindows -> "windows"
    ncmHostOs.isMacOsX -> "macos"
    else -> "linux"
}
val ncmNativeArchName = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    else -> "x86_64"
}
val ncmNativeExtension = when (ncmNativeOsName) {
    "windows" -> "dll"
    "macos" -> "dylib"
    else -> "so"
}
val ncmNativeResourceDir = desktopNativeResources.get()
    .dir("ncmc4a/$ncmNativeOsName-$ncmNativeArchName")

private fun findOnPath(executable: String): File? {
    // Windows resolves `cmake` to `cmake.exe`; File.isFile does not.
    val names = if (ncmHostOs.isWindows) listOf("$executable.exe", executable) else listOf(executable)
    return System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.flatMap { directory -> names.asSequence().map { File(directory, it) } }
        ?.firstOrNull { it.isFile }
}

private val ncmExecutableSuffix = if (ncmHostOs.isWindows) ".exe" else ""

/** Ninja shipped with the Android SDK, so a JDK-only machine still works. */
private fun findBundledNinja(): File? {
    val localProperties = rootProject.file("local.properties")
    if (!localProperties.isFile) return null
    val sdkDir = localProperties.readLines()
        .firstOrNull { it.startsWith("sdk.dir=") }
        ?.removePrefix("sdk.dir=")
        ?.replace("\\:", ":")
        ?.replace("\\\\", "\\")
        ?.trim()
        ?: return null
    val cmakeDir = File(sdkDir, "cmake")
    return cmakeDir.listFiles()
        ?.asSequence()
        ?.map { File(File(it, "bin"), "ninja$ncmExecutableSuffix") }
        ?.firstOrNull { it.isFile }
}

val ncmCmake = findOnPath("cmake")
    ?: findBundledNinja()?.let { ninja ->
        // The SDK CMake sits next to the bundled Ninja.
        File(ninja.parentFile, "cmake$ncmExecutableSuffix").takeIf { it.isFile }
    }
val ncmNinja = findBundledNinja() ?: findOnPath("ninja")

// Resolved eagerly: a task holding the toolchain provider would not be
// serializable for the configuration cache.
val ncmJniIncludeDir = File(desktopJdk25Home.get(), "include")

// Decided once at configuration time. The task specs below copy these values
// into locals so they do not capture the build script, which the configuration
// cache cannot serialize; the property is still tracked as a cache input.
val ncmNativeBuildEnabled =
    providers.gradleProperty("ncmSkipNativeBuild").orNull != "true" && ncmCmake != null
// A missing cmake must only skip the tasks, not fail their configuration.
val ncmCmakeExecutable = ncmCmake?.absolutePath ?: "cmake"

private val ncmCmakeGeneratorArgs = ncmNinja
    ?.let { listOf("-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${it.absolutePath}") }
    ?: emptyList()

val nativeConfigure = tasks.register<Exec>("nativeConfigure") {
    group = "build"
    description = "Configures the shared native core build for the Desktop JVM"
    val enabled = ncmNativeBuildEnabled
    onlyIf("cmake is available and ncmSkipNativeBuild is not set") { enabled }
    workingDir = rootProject.projectDir
    commandLine(
        listOf(
            ncmCmakeExecutable,
            "-S", nativeSourceDir.asFile.absolutePath,
            "-B", nativeBuildDir.asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DNCM_JNI_INCLUDE_DIR=" + ncmJniIncludeDir.absolutePath,
            "-DNCM_BUILD_JNI=ON",
            "-DNCM_BUILD_SELFTEST=ON"
        ) + ncmCmakeGeneratorArgs
    )
    // Declared so a deleted build directory triggers a fresh configure instead of
    // being skipped as up to date.
    outputs.dir(nativeBuildDir)
    outputs.file(nativeBuildDir.file("CMakeCache.txt"))
}

val nativeBuild = tasks.register<Exec>("nativeBuild") {
    group = "build"
    description = "Builds the shared native core for the Desktop JVM and bundles it as a resource"
    dependsOn(nativeConfigure)
    val enabled = ncmNativeBuildEnabled
    onlyIf("cmake is available and ncmSkipNativeBuild is not set") { enabled }
    workingDir = rootProject.projectDir
    commandLine(
        ncmCmakeExecutable,
        "--build", nativeBuildDir.asFile.absolutePath,
        "--config", "Release"
    )

    val buildDirectory = nativeBuildDir.asFile
    val resourceDirectory = ncmNativeResourceDir.asFile
    val nativeFileName = "ncmc4a.$ncmNativeExtension"

    // The cmake build directory lives inside native/; excluding it keeps the
    // task's own output from invalidating its inputs on every run.
    inputs.files(fileTree(nativeSourceDir) { exclude("build/**") })
    outputs.file(ncmNativeResourceDir.file(nativeFileName))

    doLast {
        val built = listOf(
            File(buildDirectory, nativeFileName),
            File(buildDirectory, "Release/$nativeFileName")
        ).firstOrNull { it.isFile } ?: throw GradleException("native 库未生成: $nativeFileName")

        resourceDirectory.mkdirs()
        built.copyTo(File(resourceDirectory, nativeFileName), overwrite = true)
        logger.lifecycle("native core 已打包: ${File(resourceDirectory, nativeFileName)}")
    }
}

// Generate the native resource before packaging; generated binaries stay in build/.
tasks.named("desktopProcessResources") {
    dependsOn(nativeBuild)
    inputs.files(fileTree(ncmNativeResourceDir))
}

tasks.named<Test>("desktopTest") {
    dependsOn(nativeBuild)
    // Exercise JNI and conversion tests with the same header layout and GC
    // as the shipped desktop runtime.
    jvmArgs(desktopObjectHeaderArgs + "-XX:+UseSerialGC")
}

// ---------------------------------------------------------------------------

compose.desktop {
    application {
        // Compose packaging tasks default to the Gradle process JDK. Use the
        // JDK 25 toolchain so the packaged runtime matches the compiled target.
        javaHome = desktopJdk25Home.get()
        mainClass = "com.cdb96.ncmconverter4a.MainKt"
        jvmArgs += desktopObjectHeaderArgs
        // These options reach both Gradle run and the installed launcher. A raw
        // `java -jar` invocation needs to supply its own JVM options.
        // The UI has a small live heap; reserve room for concurrent conversions
        // without sizing the initial heap and GC infrastructure from host RAM.
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Xms32m",
            "-Xmx768m",
            "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=20",
            "-XX:-ShrinkHeapInSteps",
            "-XX:CICompilerCount=2",
        )
        if (ncmHostOs.isWindows) {
            // This small form-based UI does not need a persistent Direct3D
            // device. Skiko's native software renderer also avoids driver RAM.
            jvmArgs += listOf("-Dskiko.renderApi=SOFTWARE", "-Dsun.java2d.d3d=false")
        }
        buildTypes.release {
            proguard {
                version.set("7.10.0")
                isEnabled = true
                configurationFiles.from(project.file("compose-desktop.pro"))
            }
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "NCMConverter4a"
            packageVersion = "4.0.0"
            appResourcesRootDir.set(desktopNativeResources)

            // JRE modules — trimmed to the minimum needed at runtime
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "jdk.crypto.ec",
            )
        }
    }
}

// Some JDK 25 distributions omit jmods. Compose's ProGuard task only searches
// that directory, so export the SAME toolchain's runtime classes as libraries.
// This jar is build-only and must never be packaged with the application.
val proguardJdkHome = File(desktopJdk25Home.get())
val proguardNeedsRuntimeExport = !File(proguardJdkHome, "jmods/java.base.jmod").isFile
val proguardRuntimeDir = layout.buildDirectory.dir("generated/proguardRuntime")
val exportProguardRuntime = tasks.register<Exec>("exportProguardRuntime") {
    val exporter = layout.projectDirectory.file("tools/ExportRuntimeClasses.java")
    val runtimeJar = proguardRuntimeDir.get().file("jdk-library.jar")
    val runtimeConfig = proguardRuntimeDir.get().file("jdk-library.pro")
    inputs.file(exporter)
    inputs.file(File(proguardJdkHome, "lib/modules"))
    outputs.file(runtimeJar)
    outputs.file(runtimeConfig)
    commandLine(
        File(proguardJdkHome, "bin/java$ncmExecutableSuffix").absolutePath,
        exporter.asFile.absolutePath,
        runtimeJar.asFile.absolutePath,
        runtimeConfig.asFile.absolutePath,
    )
}

afterEvaluate {
    tasks.withType<AbstractProguardTask>().configureEach {
        javaHome.set(desktopJdk25Home)
        if (proguardNeedsRuntimeExport) {
            dependsOn(exportProguardRuntime)
            configurationFiles.from(proguardRuntimeDir.map { it.file("jdk-library.pro") })
            inputs.file(proguardRuntimeDir.map { it.file("jdk-library.jar") })
        }
    }
}
