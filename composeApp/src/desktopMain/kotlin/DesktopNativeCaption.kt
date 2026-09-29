package com.cdb96.ncmconverter4a

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.jni.NativeLibrary
import kotlinx.coroutines.delay

/** Extends the decorated native window's client area into a taller title bar. */
@Composable
internal fun DesktopNativeCaption(window: ComposeWindow) {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

    val colors = MaterialTheme.colorScheme
    val caption = colors.surface.toArgb()
    val text = colors.onSurface.toArgb()
    val dark = colors.surface.luminance() < 0.5f
    val density = LocalDensity.current
    val height = with(density) { 52.dp.roundToPx() }
    val buttonsWidth = with(density) { 156.dp.roundToPx() }
    LaunchedEffect(window, caption, text, dark, height, buttonsWidth) {
        repeat(20) {
            val handle = window.windowHandle
            if (handle != 0L) {
                runCatching {
                    NativeCaption.setColors(handle, caption, text, dark)
                    NativeCaption.installTallTitleBar(handle, height, buttonsWidth)
                }
                return@LaunchedEffect
            }
            delay(50)
        }
    }
}

object NativeCaption {
    init { NativeLibrary.load() }

    external fun setColors(windowHandle: Long, captionArgb: Int, textArgb: Int, dark: Boolean): Boolean
    external fun installTallTitleBar(windowHandle: Long, heightPx: Int, buttonWidthPx: Int): Boolean
    external fun isMaximized(windowHandle: Long): Boolean
}
