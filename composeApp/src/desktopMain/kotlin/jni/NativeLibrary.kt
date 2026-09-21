package com.cdb96.ncmconverter4a.jni

import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Loads the shared native core (`ncmc4a`) on Desktop.
 *
 * The library is shipped as a resource so the same code works from a Jar, from
 * an installed distribution and from a plain test run:
 *
 * 1. `-Dncmc4a.library=<path>` for an explicit override;
 * 2. `ncmc4a/<os>-<arch>/ncmc4a.<ext>` on the classpath, extracted to a temp file;
 * 3. a local build output (`native/build/...`), which keeps `desktopTest` working
 *    without packaging anything.
 */
internal object NativeLibrary {
    private const val BASE_NAME = "ncmc4a"
    private val loaded = mutableSetOf<String>()

    @Synchronized
    fun load() {
        val os = osName()
        val arch = archName()
        val key = "$os-$arch"
        if (key in loaded) return

        val override = System.getProperty("ncmc4a.library")
        if (override != null) {
            System.load(File(override).absolutePath)
            loaded.add(key)
            return
        }

        val candidates = buildList {
            addAll(classpathCandidates(os, arch))
            addAll(localBuildCandidates(os, arch))
        }
        val failure = StringBuilder()
        for (candidate in candidates) {
            try {
                System.load(candidate.absolutePath)
                // Recorded only after success, so a failed attempt keeps its
                // detailed error on the next call instead of returning silently.
                loaded.add(key)
                return
            } catch (error: UnsatisfiedLinkError) {
                failure.append("\n  ").append(candidate.absolutePath).append(": ").append(error.message)
            }
        }
        throw UnsatisfiedLinkError(
            "无法加载 native 库 $BASE_NAME ($os-$arch)，已尝试:$failure"
        )
    }

    private fun classpathCandidates(os: String, arch: String): List<File> {
        val resource = "$BASE_NAME/$os-$arch/$BASE_NAME.${libraryExtension(os)}"
        val classLoader = NativeLibrary::class.java.classLoader ?: return emptyList()
        val stream: InputStream = classLoader.getResourceAsStream(resource) ?: return emptyList()
        return listOf(extract(stream, resource))
    }

    private fun extract(stream: InputStream, resource: String): File {
        val directory = File(System.getProperty("java.io.tmpdir"), "$BASE_NAME-native")
        directory.mkdirs()
        val file = File(directory, UUID.nameUUIDFromBytes(resource.toByteArray()).toString() + "." +
            resource.substringAfterLast('.'))
        stream.use { input ->
            val bytes = input.readBytes()
            // Always refresh the copy: a cached file would otherwise keep an old
            // build alive after the bundled library changed. Overwriting fails
            // while another JVM has the file loaded on Windows, in which case the
            // existing copy is still the right one to use.
            try {
                if (file.length() != bytes.size.toLong() ||
                    !file.readBytes().contentEquals(bytes)
                ) {
                    file.outputStream().use { output -> output.write(bytes) }
                }
            } catch (error: java.io.IOException) {
                if (!file.isFile) throw error
            }
        }
        file.deleteOnExit()
        return file
    }

    private fun localBuildCandidates(os: String, arch: String): List<File> {
        val fileName = "$BASE_NAME.${libraryExtension(os)}"
        val roots = listOf(
            File("native/build"),
            File("../native/build"),
            File("../../native/build")
        )
        return roots.flatMap { root ->
            listOf(
                File(root, "bin/$fileName"),
                File(root, "$os-$arch/$fileName"),
                File(root, "lib/$fileName")
            )
        }
    }

    private fun osName(): String = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
        else -> "linux"
    }

    private fun archName(): String = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        else -> "x86_64"
    }

    private fun libraryExtension(os: String): String = when (os) {
        "windows" -> "dll"
        "macos" -> "dylib"
        else -> "so"
    }
}
