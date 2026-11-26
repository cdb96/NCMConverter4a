package com.cdb96.ncmconverter4a

import java.nio.file.Files
import java.nio.file.Path

/** Persist the user's renderer choice outside the portable application directory. */
internal object DesktopRenderingPreference {
    private val file: Path
        get() = desktopConfigFile("rendering.properties")

    fun loadGpuEnabled(): Boolean = runCatching {
        loadProperties(file).getProperty("gpuRendering")?.toBooleanStrictOrNull() ?: true
    }.getOrDefault(true)

    fun saveGpuEnabled(enabled: Boolean): Boolean = runCatching {
        saveProperties(file, "NCMConverter4a desktop rendering") {
            setProperty("gpuRendering", enabled.toString())
        }
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
