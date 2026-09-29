package com.cdb96.ncmconverter4a

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.Frame
import java.awt.event.WindowStateListener

/** Client-side content in a native, resizable Windows frame. Win32 owns dragging and resizing. */
@Composable
internal fun DesktopTallTitleBar(window: ComposeWindow, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var maximized by remember(window) { mutableStateOf(false) }
    DisposableEffect(window) {
        val listener = WindowStateListener {
            maximized = NativeCaption.isMaximized(window.windowHandle)
        }
        window.addWindowStateListener(listener)
        maximized = NativeCaption.isMaximized(window.windowHandle)
        onDispose { window.removeWindowStateListener(listener) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).background(colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("NCMConverter4a", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        }
        CaptionButton(Icons.Outlined.Remove, "最小化") {
            window.extendedState = window.extendedState or Frame.ICONIFIED
        }
        CaptionButton(if (maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare,
            if (maximized) "还原" else "最大化") {
            val nowMaximized = NativeCaption.isMaximized(window.windowHandle)
            window.extendedState = if (nowMaximized) Frame.NORMAL else Frame.MAXIMIZED_BOTH
            maximized = !nowMaximized
        }
        CaptionButton(Icons.Outlined.Close, "关闭", close = true, onClick = onClose)
    }
}

@Composable
private fun CaptionButton(icon: ImageVector, label: String, close: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        !hovered -> Color.Transparent
        close -> Color(0xFFC42B1C)
        else -> colors.onSurface.copy(alpha = 0.08f)
    }
    Box(
        modifier = Modifier.width(52.dp).fillMaxHeight()
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp),
            tint = if (close && hovered) Color.White else colors.onSurface)
    }
}
