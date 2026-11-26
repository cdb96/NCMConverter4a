package com.cdb96.ncmconverter4a

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private val osNameProperty: String
    get() = System.getProperty("os.name").orEmpty()

internal val isWindows: Boolean
    get() = osNameProperty.startsWith("Windows", ignoreCase = true)

internal val isMacOs: Boolean
    get() = osNameProperty.startsWith("Mac", ignoreCase = true)

internal fun desktopConfigFile(fileName: String): Path =
    Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"), "NCMConverter4a", fileName)

internal fun loadProperties(path: Path): Properties = Properties().apply {
    if (Files.isRegularFile(path)) Files.newInputStream(path).use(::load)
}

internal fun saveProperties(path: Path, comment: String, update: Properties.() -> Unit) {
    val properties = Properties().apply(update)
    Files.createDirectories(path.toAbsolutePath().parent)
    Files.newOutputStream(path).use { properties.store(it, comment) }
}
