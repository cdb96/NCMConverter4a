package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanFormat
import com.cdb96.ncmconverter4a.service.ScannedAudioFile
import kotlin.math.roundToLong

@Composable
internal fun DirectoryScanScreen(
    state: DirectoryScanUiState,
    enabled: Boolean,
    onConfigureDirectories: () -> Unit,
    onSelectDirectory: (ScanFormat) -> Unit,
    onClearDirectory: (ScanFormat) -> Unit,
    onScan: (Set<ScanFormat>) -> Unit,
    onCancel: () -> Unit,
    onFormatSelectionChange: (ScanFormat, Boolean) -> Unit,
    onRecursiveChange: (Boolean) -> Unit,
    onSelectFile: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConvert: () -> Unit,
) {
    val editable = enabled && !state.isScanning
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Match the other pages' 720 dp breakpoint after their horizontal padding.
        if (maxWidth - 40.dp >= 720.dp) {
            val sidebarWidth = if (maxWidth >= 960.dp) 340.dp else 312.dp
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.width(sidebarWidth).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ScanOptionsCard(
                        state = state,
                        enabled = enabled,
                        showConfigureButton = false,
                        onConfigureDirectories = onConfigureDirectories,
                        onScan = onScan,
                        onCancel = onCancel,
                        onFormatSelectionChange = onFormatSelectionChange,
                        onRecursiveChange = onRecursiveChange,
                    )
                    ScanDirectoriesCard(
                        directories = state.directories,
                        enabled = editable,
                        onSelectDirectory = onSelectDirectory,
                        onClearDirectory = onClearDirectory,
                    )
                }
                ScanResultsCard(
                    state = state,
                    editable = editable,
                    onSelectFile = onSelectFile,
                    onSelectAll = onSelectAll,
                    onConvert = onConvert,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            ScanResultsPane(
                state = state,
                editable = editable,
                onSelectFile = onSelectFile,
                onSelectAll = onSelectAll,
                onConvert = onConvert,
                modifier = Modifier.fillMaxSize(),
                scanOptions = {
                    ScanOptionsCard(
                        state = state,
                        enabled = enabled,
                        showConfigureButton = true,
                        onConfigureDirectories = onConfigureDirectories,
                        onScan = onScan,
                        onCancel = onCancel,
                        onFormatSelectionChange = onFormatSelectionChange,
                        onRecursiveChange = onRecursiveChange,
                    )
                },
            )
        }
    }
}

@Composable
private fun ScanSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun ScanOptionsCard(
    state: DirectoryScanUiState,
    enabled: Boolean,
    showConfigureButton: Boolean,
    onConfigureDirectories: () -> Unit,
    onScan: (Set<ScanFormat>) -> Unit,
    onCancel: () -> Unit,
    onFormatSelectionChange: (ScanFormat, Boolean) -> Unit,
    onRecursiveChange: (Boolean) -> Unit,
) {
    val editable = enabled && !state.isScanning
    val selectedFormats = state.selectedFormats.filter { state.directories[it]?.selectable == true }.toSet()
    ScanSectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "查找音乐文件",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (showConfigureButton) {
                    TextButton(
                        onClick = onConfigureDirectories,
                        enabled = editable,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("配置扫描目录") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFormat.entries.forEach { format ->
                    val selected = format in selectedFormats
                    FilterChip(
                        selected = selected,
                        onClick = { onFormatSelectionChange(format, !selected) },
                        modifier = Modifier.weight(1f),
                        enabled = editable && state.directories[format]?.selectable == true,
                        label = { Text(format.name, maxLines = 1, textAlign = TextAlign.Center) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = state.recursive,
                            enabled = editable,
                            role = Role.Checkbox,
                            onValueChange = onRecursiveChange,
                        )
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.recursive, onCheckedChange = null, enabled = editable)
                    Text("包含子目录", style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = { if (state.isScanning) onCancel() else onScan(selectedFormats) },
                    enabled = enabled && (state.isScanning || selectedFormats.isNotEmpty()),
                ) {
                    Icon(
                        if (state.isScanning) Icons.Outlined.Close else Icons.Outlined.Search,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isScanning) "取消扫描" else "开始扫描")
                }
            }
            if (state.isScanning || state.message != null || state.warnings.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isScanning) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                        Text(
                            "已扫描 ${state.visitedDirectories} 个目录 · 找到 ${state.foundFiles} 个文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    state.warnings.forEach { warning ->
                        Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanDirectoriesCard(
    directories: Map<ScanFormat, ScanDirectory>,
    enabled: Boolean,
    onSelectDirectory: (ScanFormat) -> Unit,
    onClearDirectory: (ScanFormat) -> Unit,
) {
    val configuredCount = ScanFormat.entries.count { directories[it]?.configured == true }
    ScanSectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("扫描目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "已配置 $configuredCount/${ScanFormat.entries.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScanFormat.entries.forEachIndexed { index, format ->
                if (index > 0) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                val directory = directories[format] ?: ScanDirectory()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FormatTag(format, highlighted = directory.configured)
                        Spacer(Modifier.weight(1f))
                        if (directory.configured) {
                            IconButton(
                                onClick = { onClearDirectory(format) },
                                enabled = enabled,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Outlined.Close, "清除 ${format.name} 扫描目录", Modifier.size(16.dp))
                            }
                        }
                        TextButton(
                            onClick = { onSelectDirectory(format) },
                            enabled = enabled,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (directory.configured) "更改" else "选择")
                        }
                    }
                    Text(
                        text = if (directory.suggested) "建议目录：${directory.path}"
                        else directory.path.ifBlank { "未选择目录" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (directory.configured || directory.suggested) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanResultsCard(
    state: DirectoryScanUiState,
    editable: Boolean,
    onSelectFile: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConvert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedBytes = state.files.asSequence()
        .filter { it.id in state.selectedIds }
        .mapNotNull { it.size }
        .sum()
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScanResultsHeader(
                state = state,
                editable = editable,
                onSelectAll = onSelectAll,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.files.isEmpty()) {
                    ScanEmptyState(
                        state = state,
                        inlineDirectories = true,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        itemsIndexed(state.files, key = { _, file -> file.id }) { index, file ->
                            Column {
                                ScanFileRow(
                                    file = file,
                                    selected = file.id in state.selectedIds,
                                    enabled = editable,
                                    horizontalPadding = 16.dp,
                                    onSelect = { onSelectFile(file.id, it) },
                                )
                                if (index < state.files.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (state.selectedIds.isNotEmpty()) {
                        buildString {
                            append("已选择 ${state.selectedIds.size} 个文件")
                            if (selectedBytes > 0L) append(" · 共 ${formatFileSize(selectedBytes)}")
                        }
                    } else {
                        "请勾选需要转换的文件"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    onClick = onConvert,
                    enabled = editable && state.selectedIds.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("转换 (${state.selectedIds.size})")
                }
            }
        }
    }
}

@Composable
private fun ScanResultsPane(
    state: DirectoryScanUiState,
    editable: Boolean,
    onSelectFile: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onConvert: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
    scanOptions: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
        ) {
            if (scanOptions != null) {
                item {
                    Box(Modifier.padding(bottom = 12.dp)) { scanOptions() }
                }
            }
            item {
                ScanResultsHeader(
                    state = state,
                    editable = editable,
                    onSelectAll = onSelectAll,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                if (state.files.isNotEmpty()) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                } else {
                    ScanEmptyState(
                        state = state,
                        inlineDirectories = false,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    )
                }
            }
            itemsIndexed(state.files, key = { _, file -> file.id }) { index, file ->
                Column {
                    ScanFileRow(
                        file = file,
                        selected = file.id in state.selectedIds,
                        enabled = editable,
                        horizontalPadding = 0.dp,
                        onSelect = { onSelectFile(file.id, it) },
                    )
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("转换 (${state.selectedIds.size})")
        }
    }
}

@Composable
private fun ScanResultsHeader(
    state: DirectoryScanUiState,
    editable: Boolean,
    onSelectAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.files.isNotEmpty()) {
        val selection = when (state.selectedIds.size) {
            0 -> ToggleableState.Off
            state.files.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
        val formatSummary = ScanFormat.entries.mapNotNull { format ->
            val count = state.files.count { it.format == format }
            if (count > 0) "${format.name} $count" else null
        }.joinToString(" · ")
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TriStateCheckbox(
                state = selection,
                onClick = { onSelectAll(selection != ToggleableState.On) },
                enabled = editable,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("扫描结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (formatSummary.isNotEmpty()) {
                    Text(
                        formatSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    "已选 ${state.selectedIds.size} / ${state.files.size}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("扫描结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.isScanning) {
                Text(
                    "已找到 ${state.foundFiles} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ScanEmptyState(
    state: DirectoryScanUiState,
    inlineDirectories: Boolean,
    modifier: Modifier = Modifier,
) {
    val noDirectories = state.directories.values.none { it.selectable }
    val title = when {
        state.isScanning -> "正在查找音乐文件…"
        noDirectories -> "尚未配置扫描目录"
        else -> "暂无扫描结果"
    }
    val helper = when {
        state.isScanning -> "已扫描 ${state.visitedDirectories} 个目录 · 找到 ${state.foundFiles} 个文件"
        noDirectories && inlineDirectories -> "在左侧为对应格式选择文件夹后，即可开始扫描。"
        noDirectories -> "点击右上角“配置扫描目录”选择文件夹后，即可开始扫描。"
        else -> "点击“开始扫描”查找文件，完成后在此勾选并转换。"
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                if (state.isScanning) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(
                        Icons.Outlined.Search,
                        null,
                        Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScanFileRow(
    file: ScannedAudioFile,
    selected: Boolean,
    enabled: Boolean,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onSelect,
            )
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                file.directory,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FormatTag(file.format, highlighted = true)
            file.size?.let { size ->
                Text(
                    formatFileSize(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FormatTag(format: ScanFormat, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Text(
            format.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes >= 1024L * 1024L -> {
        val tenths = ((bytes * 10.0) / (1024.0 * 1024.0)).roundToLong()
        "${tenths / 10}.${tenths % 10} MB"
    }
    bytes >= 1024L -> "${(bytes.toDouble() / 1024.0).roundToLong()} KB"
    else -> "< 1 KB"
}
