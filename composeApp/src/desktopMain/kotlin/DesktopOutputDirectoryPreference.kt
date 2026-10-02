package com.cdb96.ncmconverter4a

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal fun defaultDesktopOutputDirectory(): File =
    File(System.getProperty("user.home"), "Music/NCMConverter4A").absoluteFile

/** Keep the selected output folder across restarts, including portable builds. */
internal class DesktopOutputDirectoryPreference(
    private val file: Path = Path.of(
        System.getenv("APPDATA") ?: System.getProperty("user.home"),
        "NCMConverter4a", "conversion.properties",
    ),
) {
    fun load(): File = runCatching {
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        val directory = properties.getProperty("outputDirectory")?.takeIf { it.isNotBlank() }
            ?: return@runCatching defaultDesktopOutputDirectory()
        Path.of(directory).toAbsolutePath().normalize().toFile()
    }.getOrElse { defaultDesktopOutputDirectory() }

    fun save(directory: File) {
        require(directory.isDirectory && directory.canWrite()) { "所选文件夹不可写，请选择其他文件夹" }
        Files.createDirectories(file.toAbsolutePath().parent)
        val properties = Properties().apply {
            setProperty("outputDirectory", directory.absoluteFile.normalize().path)
        }
        Files.newOutputStream(file).use { properties.store(it, "NCMConverter4a output directory") }
    }
}
