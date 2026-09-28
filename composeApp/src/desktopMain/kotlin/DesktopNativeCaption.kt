package com.cdb96.ncmconverter4a

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.cdb96.ncmconverter4a.jni.NativeLibrary
import kotlinx.coroutines.delay

/** Styles only the Windows system caption; the native frame and window controls stay intact. */
@Composable
internal fun DesktopNativeCaption(window: ComposeWindow) {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return

    val colors = MaterialTheme.colorScheme
    val caption = colors.surface.toArgb()
    val text = colors.onSurface.toArgb()
    val dark = colors.surface.luminance() < 0.5f
    LaunchedEffect(window, caption, text, dark) {
        repeat(20) {
            val handle = window.windowHandle
            if (handle != 0L) {
                runCatching { NativeCaption.setColors(handle, caption, text, dark) }
                return@LaunchedEffect
            }
            delay(50)
        }
    }
}

object NativeCaption {
    init { NativeLibrary.load() }

    external fun setColors(windowHandle: Long, captionArgb: Int, textArgb: Int, dark: Boolean): Boolean
}
