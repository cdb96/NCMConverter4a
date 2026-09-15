package com.cdb96.ncmconverter4a

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

internal fun droppedFiles(transferable: Transferable): List<File> = runCatching {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
        .orEmpty().filterIsInstance<File>().filter { it.isFile }.distinctBy { it.absolutePath }
}.getOrDefault(emptyList())

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopFileDropArea(
    enabled: Boolean,
    onFiles: (List<File>) -> Unit,
    content: @Composable () -> Unit,
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnFiles by rememberUpdatedState(onFiles)
    var hovering by remember { mutableStateOf(false) }
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                if (!currentEnabled) return false
                val files = droppedFiles(event.awtTransferable)
                if (files.isEmpty()) return false
                currentOnFiles(files)
                return true
            }
        }
    }
    Box(
        Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = {
                currentEnabled && it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            },
            target = target,
        ).then(if (hovering && enabled) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
    ) {
        content()
    }
}
