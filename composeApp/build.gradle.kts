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

// ---------------------------------------------------------------------------
// Desktop native core
//
// native/ holds the single C++ implementation of RC4 and KGM. nativeLib builds
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
val ncmNativeResourceDir = layout.projectDirectory
    .dir("src/desktopMain/resources/ncmc4a/$ncmNativeOsName-$ncmNativeArchName")

private fun findOnPath(executable: String): File? =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.map { File(it, executable) }
        ?.firstOrNull { it.isFile }

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
        ?.map { File(File(it, "bin"), "ninja.exe") }
        ?.firstOrNull { it.isFile }
}

val ncmCmake = findOnPath("cmake")
    ?: findBundledNinja()?.let { ninja ->
        // The SDK CMake sits next to the bundled Ninja.
        File(ninja.parentFile.parentFile, "bin/cmake.exe").takeIf { it.isFile }
    }
val ncmNinja = findBundledNinja() ?: findOnPath("ninja")

// Resolved eagerly: a task holding the toolchain provider would not be
// serializable for the configuration cache.
val ncmJniIncludeDir = File(desktopJdk25Home.get(), "include")

/** Runs a build command from the project root and fails the build on error. */
private fun runNativeCommand(command: List<String>, workingDirectory: File) {
    val process = ProcessBuilder(command)
        .directory(workingDirectory)
        .inheritIO()
        .start()
    val exitCode = process.waitFor()
    require(exitCode == 0) {
        "命令失败 (exit=$exitCode): ${command.joinToString(" ")}"
    }
}

/**
 * The native tasks shell out to cmake, so they are exempt from configuration
 * cache serialization.
 */
private fun Task.disableConfigurationCache() {
    notCompatibleWithConfigurationCache("shells out to cmake")
}

private val ncmCmakeGeneratorArgs = ncmNinja
    ?.let { listOf("-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${it.absolutePath}") }
    ?: emptyList()

val nativeConfigure by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the shared native core build for the Desktop JVM"
    onlyIf { providers.gradleProperty("ncmSkipNativeBuild").orNull != "true" && ncmCmake != null }
    disableConfigurationCache()
    workingDir = rootProject.projectDir
    commandLine(
        listOf(
            requireNotNull(ncmCmake) { "cmake 不可用" }.absolutePath,
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

val nativeBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the shared native core for the Desktop JVM and bundles it as a resource"
    dependsOn(nativeConfigure)
    onlyIf { providers.gradleProperty("ncmSkipNativeBuild").orNull != "true" && ncmCmake != null }
    disableConfigurationCache()
    workingDir = rootProject.projectDir
    commandLine(
        requireNotNull(ncmCmake) { "cmake 不可用" }.absolutePath,
        "--build", nativeBuildDir.asFile.absolutePath,
        "--config", "Release"
    )

    val projectDirectory = rootProject.projectDir
    val buildDirectory = nativeBuildDir.asFile
    val resourceDirectory = ncmNativeResourceDir.asFile
    val nativeFileName = "ncmc4a.$ncmNativeExtension"

    inputs.dir(nativeSourceDir)
    outputs.file(ncmNativeResourceDir.file(nativeFileName))

    doLast {
        val cmake = requireNotNull(ncmCmake) { "cmake 不可用" }
        runNativeCommand(
            listOf(
                cmake.absolutePath, "--build", buildDirectory.absolutePath,
                "--config", "Release", "--target", "ncm_core_selftest"
            ),
            projectDirectory
        )
        val built = listOf(
            File(buildDirectory, nativeFileName),
            File(buildDirectory, "Release/$nativeFileName")
        ).firstOrNull { it.isFile } ?: throw GradleException("native 库未生成: $nativeFileName")

        resourceDirectory.mkdirs()
        built.copyTo(File(resourceDirectory, nativeFileName), overwrite = true)
        logger.lifecycle("native core 已打包: ${File(resourceDirectory, nativeFileName)}")
    }
}

// The library is produced straight into a source directory, so declare it as a
// processResources input; otherwise packaging treats the old resource set as up
// to date and can ship a jar without the native core.
tasks.named("desktopProcessResources") {
    dependsOn(nativeBuild)
    inputs.dir(ncmNativeResourceDir)
}

tasks.named("desktopTest") {
    dependsOn(nativeBuild)
}

// ---------------------------------------------------------------------------

compose.desktop {
    application {
        // Compose packaging tasks default to the Gradle process JDK. Use the
        // JDK 25 toolchain so the packaged runtime matches the compiled target.
        javaHome = desktopJdk25Home.get()
        mainClass = "com.cdb96.ncmconverter4a.MainKt"
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
            )
        }
    }
}

afterEvaluate {
    tasks.withType<AbstractProguardTask>().configureEach {
        javaHome.set(desktopJdk25Home)
    }
}


