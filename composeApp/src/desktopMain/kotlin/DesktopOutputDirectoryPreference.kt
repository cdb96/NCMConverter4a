package com.cdb96.ncmconverter4a

import java.io.File
import java.nio.file.Path

internal fun defaultDesktopOutputDirectory(): File =
    File(System.getProperty("user.home"), "Music/NCMConverter4A").absoluteFile

/** Keep the selected output folder across restarts, including portable builds. */
internal class DesktopOutputDirectoryPreference(
    private val file: Path = desktopConfigFile("conversion.properties"),
) {
    fun load(): File = runCatching {
        val directory = loadProperties(file).getProperty("outputDirectory")?.takeIf { it.isNotBlank() }
            ?: return@runCatching defaultDesktopOutputDirectory()
        Path.of(directory).toAbsolutePath().normalize().toFile()
    }.getOrElse { defaultDesktopOutputDirectory() }

    fun save(directory: File) {
        require(directory.isDirectory && directory.canWrite()) { "所选文件夹不可写，请选择其他文件夹" }
        saveProperties(file, "NCMConverter4a output directory") {
            setProperty("outputDirectory", directory.absoluteFile.normalize().path)
        }
    }
}
