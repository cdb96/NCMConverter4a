package com.cdb96.ncmconverter4a.jni

import com.cdb96.ncmconverter4a.isMacOs
import com.cdb96.ncmconverter4a.isWindows
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

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

    private val posixOwnerRwx = PosixFilePermissions.fromString("rwx------")

    internal fun extract(
        stream: InputStream,
        resource: String,
        baseTempDir: Path = Path.of(System.getProperty("java.io.tmpdir")),
    ): File {
        val bytes = stream.use { it.readBytes() }
        val extension = resource.substringAfterLast('.')
        return if (baseTempDir.fileSystem.supportedFileAttributeViews().contains("posix")) {
            extractPrivatePosix(bytes, extension, baseTempDir)
        } else {
            extractWindowsCached(bytes, extension, baseTempDir)
        }
    }

    private fun extractPrivatePosix(bytes: ByteArray, extension: String, baseTempDir: Path): File {
        if (!Files.exists(baseTempDir)) {
            Files.createDirectories(baseTempDir)
        }
        val ownerOnly = PosixFilePermissions.asFileAttribute(posixOwnerRwx)
        val directory = Files.createTempDirectory(baseTempDir, "$BASE_NAME-native-", ownerOnly)
        val file = directory.resolve("$BASE_NAME.$extension")
        try {
            Files.createFile(file, ownerOnly)
            Files.write(file, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(file) }
            runCatching { Files.deleteIfExists(directory) }
            throw error
        }
        // deleteOnExit runs in LIFO order: register directory first so file is deleted before it.
        directory.toFile().deleteOnExit()
        file.toFile().deleteOnExit()
        return file.toFile()
    }

    private fun extractWindowsCached(bytes: ByteArray, extension: String, baseTempDir: Path): File {
        val directory = baseTempDir.resolve("$BASE_NAME-native")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(directory)
        }
        val dirAttrs = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!dirAttrs.isDirectory || dirAttrs.isSymbolicLink || dirAttrs.isOther) {
            throw IOException("Refusing to extract native library into unsafe directory: $directory")
        }
        // Different builds must not reuse an older DLL locked by another process.
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(16) // Keep the filename short for Windows native loader path limits.
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val file = directory.resolve("$digest.$extension")
        if (!hasExpectedContent(file, bytes)) {
            val temporary = Files.createTempFile(directory, BASE_NAME, ".tmp")
            try {
                Files.write(temporary, bytes)
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (error: IOException) {
                // A concurrent process may have extracted and loaded this SAME build.
                if (!hasExpectedContent(file, bytes)) throw error
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        file.toFile().deleteOnExit()
        return file.toFile()
    }

    private fun hasExpectedContent(file: Path, expected: ByteArray): Boolean =
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
            Files.readAllBytes(file).contentEquals(expected)

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
        isWindows -> "windows"
        isMacOs -> "macos"
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
