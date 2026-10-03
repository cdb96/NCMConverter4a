package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.service.ScanFormat
import com.cdb96.ncmconverter4a.service.ScannedAudioFile

@Composable
internal fun DirectoryScanScreen(
    state: DirectoryScanUiState,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    onScan: (Set<ScanFormat>) -> Unit,
    onCancel: () -> Unit,
    onRecursiveChange: (Boolean) -> Unit,
    onSelectFile: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConvert: () -> Unit,
) {
    val editable = enabled && !state.isScanning
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("查找音乐文件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("查找加密音乐文件，勾选后批量转换。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            TextButton(onClick = onOpenSettings) { Text("配置扫描目录") }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.recursive, onCheckedChange = onRecursiveChange, enabled = editable)
                Text("包含子目录", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (state.isScanning) onCancel() else onScan(ScanFormat.entries.toSet()) },
                    enabled = enabled && (state.isScanning || state.directories.values.any { it.configured }),
                ) {
                    Icon(if (state.isScanning) Icons.Outlined.Close else Icons.Outlined.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isScanning) "取消扫描" else "扫描全部")
                }
                FilledTonalButton(onClick = onConvert, enabled = editable && state.selectedIds.isNotEmpty()) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("转换 (${state.selectedIds.size})")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScanFormat.entries.forEach { format ->
                    TextButton(
                        onClick = { onScan(setOf(format)) },
                        enabled = editable && state.directories[format]?.configured == true,
                    ) { Text("扫描 ${format.name}") }
                }
            }
        }
        if (state.isScanning || state.message != null || state.warnings.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isScanning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("已扫描 ${state.visitedDirectories} 个目录 · 找到 ${state.foundFiles} 个文件",
                            style = MaterialTheme.typography.bodySmall)
                    } else state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    state.warnings.forEach { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (state.files.isNotEmpty()) {
            item {
                val selection = when (state.selectedIds.size) {
                    0 -> ToggleableState.Off
                    state.files.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(selection, onClick = { onSelectAll(selection != ToggleableState.On) }, enabled = editable)
                    Column(Modifier.weight(1f)) {
                        Text("已选 ${state.selectedIds.size} / ${state.files.size}", fontWeight = FontWeight.SemiBold)
                        Text(ScanFormat.entries.joinToString(" · ") { format ->
                            "${format.name} ${state.files.count { it.format == format }}"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
            items(state.files, key = { it.id }) { file ->
                ScanFileRow(file, file.id in state.selectedIds, editable) { onSelectFile(file.id, it) }
            }
        }
    }
}

@Composable
private fun ScanFileRow(file: ScannedAudioFile, selected: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(!selected) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(selected, onCheckedChange = onSelect, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.directory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.format.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            file.size?.let { size ->
                Text(if (size >= 1024 * 1024) "${size / (1024 * 1024)} MB" else "${size / 1024} KB",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
