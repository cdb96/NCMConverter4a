package com.cdb96.ncmconverter4a

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Persist the user's renderer choice outside the portable application directory. */
internal object DesktopRenderingPreference {
    private val file: Path
        get() = Path.of(
            System.getenv("APPDATA") ?: System.getProperty("user.home"),
            "NCMConverter4a", "rendering.properties",
        )

    fun loadGpuEnabled(): Boolean = runCatching {
        val path = file
        if (!Files.isRegularFile(path)) return@runCatching true
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        properties.getProperty("gpuRendering")?.toBooleanStrictOrNull() ?: true
    }.getOrDefault(true)

    fun saveGpuEnabled(enabled: Boolean): Boolean = runCatching {
        val path = file
        Files.createDirectories(path.parent)
        val properties = Properties().apply {
            setProperty("gpuRendering", enabled.toString())
        }
        Files.newOutputStream(path).use { properties.store(it, "NCMConverter4a desktop rendering") }
    }.isSuccess
}

/** A fresh process lets Skiko initialize the selected backend without changing a live layer. */
internal object DesktopRendererRestart {
    fun restart(): Boolean = runCatching {
        val command = ProcessHandle.current().info().command().orElse(null) ?: return false
        val executable = Path.of(command).toAbsolutePath()
        val name = executable.fileName.toString()
        if ((!name.equals("NCMConverter4a.exe", ignoreCase = true)
                && !name.equals("NCMConverter4a", ignoreCase = true))
            || !Files.isRegularFile(executable)) return false
        ProcessBuilder(executable.toString()).directory(executable.parent.toFile()).start()
        true
    }.getOrDefault(false)
}
