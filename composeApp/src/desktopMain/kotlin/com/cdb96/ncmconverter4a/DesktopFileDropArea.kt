package com.cdb96.ncmconverter4a

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    hint: String,
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
    Column(
        Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = {
                currentEnabled && it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            },
            target = target,
        ).then(if (hovering && enabled) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
    ) {
        Text(
            text = if (!enabled) "处理中，暂不接受拖入文件" else if (hovering) "松开鼠标导入文件" else hint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f)) { content() }
    }
}
