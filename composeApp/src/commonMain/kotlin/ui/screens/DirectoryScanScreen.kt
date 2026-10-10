package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
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
    onConfigureDirectories: () -> Unit,
    onScan: (Set<ScanFormat>) -> Unit,
    onCancel: () -> Unit,
    onFormatSelectionChange: (ScanFormat, Boolean) -> Unit,
    onRecursiveChange: (Boolean) -> Unit,
    onSelectFile: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConvert: () -> Unit,
) {
    val editable = enabled && !state.isScanning
    val selectedFormats = state.selectedFormats.filter { state.directories[it]?.configured == true }.toSet()
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("查找音乐文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = onConfigureDirectories, enabled = editable,
                                contentPadding = PaddingValues(horizontal = 8.dp)) { Text("配置扫描目录") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ScanFormat.entries.forEach { format ->
                                val selected = format in selectedFormats
                                FilterChip(
                                    selected = selected,
                                    onClick = { onFormatSelectionChange(format, !selected) },
                                    modifier = Modifier.weight(1f),
                                    enabled = editable && state.directories[format]?.configured == true,
                                    label = { Text(format.name, maxLines = 1) },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Outlined.Check, null, Modifier.size(14.dp)) }
                                    } else null,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = state.recursive, onCheckedChange = onRecursiveChange, enabled = editable)
                                Text("包含子目录", style = MaterialTheme.typography.bodyMedium)
                            }
                            Button(
                                onClick = { if (state.isScanning) onCancel() else onScan(selectedFormats) },
                                enabled = enabled && (state.isScanning || selectedFormats.isNotEmpty()),
                            ) {
                                Icon(if (state.isScanning) Icons.Outlined.Close else Icons.Outlined.Search, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.isScanning) "取消扫描" else "开始扫描")
                            }
                        }
                        if (state.isScanning || state.message != null || state.warnings.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                }
            }
            item {
                if (state.files.isNotEmpty()) {
                    val selection = when (state.selectedIds.size) {
                        0 -> ToggleableState.Off
                        state.files.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TriStateCheckbox(selection, onClick = { onSelectAll(selection != ToggleableState.On) }, enabled = editable)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("扫描结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(ScanFormat.entries.joinToString(" · ") { format ->
                                "${format.name} ${state.files.count { it.format == format }}"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("已选 ${state.selectedIds.size} / ${state.files.size}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                } else {
                    Text("扫描结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
                        Text(if (state.isScanning) "正在查找音乐文件…" else "暂无扫描结果",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("扫描完成后，在这里勾选文件并转换。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(state.files, key = { _, file -> file.id }) { index, file ->
                Column {
                    ScanFileRow(file, file.id in state.selectedIds, editable) { onSelectFile(file.id, it) }
                    if (index < state.files.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        FilledTonalButton(
            onClick = onConvert,
            enabled = editable && state.selectedIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("转换 (${state.selectedIds.size})")
        }
    }
}
@Composable
private fun ScanFileRow(
    file: ScannedAudioFile,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Boolean) -> Unit,
) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(!selected) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(selected, onCheckedChange = onSelect, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
