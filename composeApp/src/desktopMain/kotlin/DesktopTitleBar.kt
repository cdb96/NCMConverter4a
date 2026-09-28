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
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.event.MouseEvent

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun FrameWindowScope.DesktopTitleBar(state: WindowState, onClose: () -> Unit) {
    val maximized = state.placement == WindowPlacement.Maximized
    val colors = MaterialTheme.colorScheme
    val lastTitleBarToggle = remember { longArrayOf(0L) }
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp).background(colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowDraggableArea(
            Modifier.weight(1f).fillMaxHeight()
                .onPointerEvent(PointerEventType.Release) { event ->
                    val mouseEvent = event.nativeEvent as? MouseEvent
                    if (mouseEvent?.button == MouseEvent.BUTTON1 && mouseEvent.clickCount >= 2 &&
                        mouseEvent.`when` - lastTitleBarToggle[0] > 400
                    ) {
                        lastTitleBarToggle[0] = mouseEvent.`when`
                        state.placement = if (state.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxHeight().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.primary)
                Text(
                    "NCMConverter4a",
                    modifier = Modifier.padding(start = 9.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        TitleBarButton(Icons.Outlined.Remove, "最小化") { state.isMinimized = true }
        TitleBarButton(
            icon = if (maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare,
            label = if (maximized) "还原" else "最大化",
        ) {
            state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
        }
        TitleBarButton(Icons.Outlined.Close, "关闭", close = true, onClick = onClose)
    }
    HorizontalDivider(color = colors.outlineVariant)
}

@Composable
private fun TitleBarButton(
    icon: ImageVector,
    label: String,
    close: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        !hovered -> Color.Transparent
        close -> colors.error
        else -> colors.surfaceVariant
    }
    val tint = if (close && hovered) colors.onError else colors.onSurface
    Box(
        modifier = Modifier.width(46.dp).fillMaxHeight()
            .background(background)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = tint)
    }
}
