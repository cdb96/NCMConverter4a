package com.cdb96.ncmconverter4a

import androidx.compose.ui.awt.ComposeWindow
import java.awt.BorderLayout
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

/** Reattach the Skia layer on the AWT event thread to recreate its actual redrawer. */
internal object DesktopRendering {
    fun switchTo(window: ComposeWindow, gpuEnabled: Boolean): Boolean = runCatching {
        val layer = findLayer(window) ?: return false
        val requested = if (gpuEnabled) GraphicsApi.DIRECT3D else GraphicsApi.SOFTWARE_FAST
        val previous = layer.renderApi
        if (previous == requested) return true
        val parent = layer.parent ?: return false
        val index = parent.getComponentZOrder(layer)
        val constraints = (parent.layout as? BorderLayout)?.getConstraints(layer)

        fun reattach() {
            parent.remove(layer)
            parent.add(layer, constraints, index)
            parent.revalidate()
            parent.repaint()
            layer.needRender(true)
        }

        layer.renderApi = requested
        try {
            reattach()
            if (layer.renderApi == requested) return true
        } catch (_: Exception) {
            // Restore the old renderer below if the new one cannot be initialized.
        }
        layer.renderApi = previous
        reattach()
        false
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
