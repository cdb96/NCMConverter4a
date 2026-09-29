package com.cdb96.ncmconverter4a

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer

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

/** Skiko can replace its redrawer on the AWT event thread without recreating the window. */
internal object DesktopRendering {
    fun switch(window: ComposeWindow, gpuEnabled: Boolean): Boolean = runCatching {
        val layer = findLayer(window) ?: return false
        val requested = if (gpuEnabled) GraphicsApi.DIRECT3D else GraphicsApi.SOFTWARE_FAST
        layer.renderApi = requested
        layer.needRender(true)
        layer.renderApi == requested
    }.getOrDefault(false)

    private fun findLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component is Container) {
            for (child in component.components) {
                findLayer(child)?.let { return it }
            }
        }
        return null
    }
}
