package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanFormat

@Composable
internal fun ScanDirectoryDialog(
    directories: Map<ScanFormat, ScanDirectory>,
    enabled: Boolean,
    message: String?,
    onSelect: (ScanFormat) -> Unit,
    onClear: (ScanFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val compact = maxWidth < 480.dp
            Surface(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("扫描目录", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, "关闭扫描目录配置")
                        }
                    }
                    Text("为各格式选择音乐所在的文件夹，修改后自动保存。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ScanFormat.entries.forEachIndexed { index, format ->
                            if (index > 0) HorizontalDivider()
                            val directory = directories[format] ?: ScanDirectory()
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${format.name} 目录", style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f))
                                    if (directory.configured) {
                                        IconButton(onClick = { onClear(format) }, enabled = enabled) {
                                            Icon(Icons.Outlined.Close, "清除 ${format.name} 扫描目录", Modifier.size(18.dp))
                                        }
                                    }
                                    if (!compact) {
                                        SelectScanDirectoryButton(format, directory.configured, enabled, onSelect)
                                    }
                                }
                                SelectionContainer {
                                    Text(directory.path.ifBlank { "尚未选择文件夹" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (compact) {
                                    SelectScanDirectoryButton(format, directory.configured, enabled, onSelect,
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectScanDirectoryButton(
    format: ScanFormat,
    configured: Boolean,
    enabled: Boolean,
    onSelect: (ScanFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = { onSelect(format) }, enabled = enabled, modifier = modifier) {
        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("${if (configured) "更改" else "选择"} ${format.name} 目录")
    }
}
