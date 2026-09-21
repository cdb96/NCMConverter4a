package com.cdb96.ncmconverter4a

import androidx.compose.runtime.Composable
import com.cdb96.ncmconverter4a.ui.theme.NCMConverter4aTheme

/**
 * Shared Compose Multiplatform app shell.
 * Platform entry points (MainActivity for Android, main() for Desktop)
 * should wrap their content in this composable to get the shared theme.
 */
@Composable
fun App(content: @Composable () -> Unit) {
    NCMConverter4aTheme {
        content()
    }
}
