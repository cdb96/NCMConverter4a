package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanFormat
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onRawWriteModeChange: (Boolean) -> Unit,
    onDuplicateConflictMitigationChange: (Boolean) -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onSelectKggDatabase: () -> Unit,
    onKggRootModeChange: (Boolean) -> Unit,
    supportsRoot: Boolean,
    onGpuRenderingChange: ((Boolean) -> Unit)? = null,
    gpuRenderingBusy: Boolean = false,
    gpuRenderingError: String? = null,
    onSelectOutputDirectory: (() -> Unit)? = null,
    outputDirectoryBusy: Boolean = false,
    outputDirectoryError: String? = null,
    scanDirectories: Map<ScanFormat, ScanDirectory>? = null,
    scanDirectoryBusy: Boolean = false,
    onSelectScanDirectory: (ScanFormat) -> Unit = {},
    onClearScanDirectory: (ScanFormat) -> Unit = {},
) {
    val conversionSettings: @Composable () -> Unit = {
        SettingsCard(
            rawWriteMode = state.rawWriteMode,
            onRawWriteModeChange = onRawWriteModeChange,
            duplicateConflictMitigation = state.duplicateConflictMitigation,
            onDuplicateConflictMitigationChange = onDuplicateConflictMitigationChange,
            threadCount = state.threadCount,
            onThreadCountChange = onThreadCountChange,
            enabled = state.enabled,
            gpuRenderingEnabled = state.gpuRenderingEnabled,
            onGpuRenderingChange = onGpuRenderingChange,
            gpuRenderingBusy = gpuRenderingBusy,
            gpuRenderingError = gpuRenderingError,
        )
    }
    val fileSettings: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            onSelectOutputDirectory?.let { onSelect ->
                OutputDirectoryCard(
                    directory = state.outputDirectory.orEmpty(),
                    enabled = state.enabled && !outputDirectoryBusy,
                    busy = outputDirectoryBusy,
                    error = outputDirectoryError,
                    onSelect = onSelect,
                )
            }
            scanDirectories?.let { directories ->
                ScanDirectorySettingsCard(
                    directories = directories,
                    enabled = state.enabled && !scanDirectoryBusy,
                    onSelect = onSelectScanDirectory,
                    onClear = onClearScanDirectory,
                )
            }
            KggDatabaseCard(
                state = state,
                onSelectClick = onSelectKggDatabase,
                onRootModeChange = onKggRootModeChange,
                supportsRoot = supportsRoot,
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 720.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { conversionSettings() }
                Column(Modifier.weight(1f)) { fileSettings() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                conversionSettings()
                fileSettings()
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            SettingDescription(description)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingError(error: String?) {
    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun OutputDirectoryCard(
    directory: String,
    enabled: Boolean,
    busy: Boolean,
    error: String?,
    onSelect: () -> Unit,
) {
    SettingsSection("输出文件夹") {
        SettingDescription("NCM、KGM、KGG 转换结果保存至")
        Text(directory, style = MaterialTheme.typography.bodyMedium)
        SettingError(error)
        OutlinedButton(onClick = onSelect, enabled = enabled) {
            Text(if (busy) "正在保存…" else "更换文件夹")
        }
    }
}

@Composable
private fun ScanDirectorySettingsCard(
    directories: Map<ScanFormat, ScanDirectory>,
    enabled: Boolean,
    onSelect: (ScanFormat) -> Unit,
    onClear: (ScanFormat) -> Unit,
) {
    SettingsSection("扫描目录") {
        SettingDescription("为各格式选择文件夹，然后在“扫描”页查找音乐文件。")
        ScanFormat.entries.forEachIndexed { index, format ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val directory = directories[format] ?: ScanDirectory()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${format.name} 目录", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (directory.configured) {
                        IconButton(onClick = { onClear(format) }, enabled = enabled) {
                            Icon(Icons.Outlined.Close, "清除 ${format.name} 扫描目录", Modifier.size(18.dp))
                        }
                    }
                }
                Text(directory.path.ifBlank { "待配置" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = { onSelect(format) }, enabled = enabled) {
                    Text("${if (directory.configured) "更改" else "选择"} ${format.name} 目录")
                }
            }
        }
    }
}

@Composable
private fun KggDatabaseCard(
    state: SettingsUiState,
    onSelectClick: () -> Unit,
    onRootModeChange: (Boolean) -> Unit,
    supportsRoot: Boolean,
) {
    val automatic = supportsRoot && state.kggRootMode
    val hasDatabase = state.kggDatabase != null
    SettingsSection("KGG 数据库") {
        SettingDescription("仅转换 KGG 时需要，用于读取音频密钥。")
        if (supportsRoot) {
            SettingSwitch(
                title = "自动获取数据库",
                description = "需要 Root 权限",
                checked = state.kggRootMode,
                enabled = state.enabled,
                onChange = onRootModeChange,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (automatic) {
            Text("转换时自动读取酷狗数据库", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (hasDatabase) {
                        state.kggDatabaseName?.takeIf { it.isNotBlank() } ?: "已选择数据库文件"
                    } else "尚未选择数据库",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingDescription(if (supportsRoot) "支持 MMKV 文件" else "支持 DB / MMKV 文件")
            }
            OutlinedButton(onClick = onSelectClick, enabled = state.enabled) {
                Text(if (hasDatabase) "更换数据库" else "选择数据库")
            }
        }
    }
}

@Composable
fun SettingsCard(
    rawWriteMode: Boolean,
    onRawWriteModeChange: (Boolean) -> Unit,
    duplicateConflictMitigation: Boolean,
    onDuplicateConflictMitigationChange: (Boolean) -> Unit,
    threadCount: Int,
    onThreadCountChange: (Int) -> Unit,
    enabled: Boolean,
    gpuRenderingEnabled: Boolean = true,
    onGpuRenderingChange: ((Boolean) -> Unit)? = null,
    gpuRenderingBusy: Boolean = false,
    gpuRenderingError: String? = null,
) {
    SettingsSection("转换与性能") {
        Column {
            SettingSwitch(
                title = "原始写入模式",
                description = "仅解密，不补写 NCM 元数据",
                checked = rawWriteMode,
                enabled = enabled,
                onChange = onRawWriteModeChange,
            )
            SettingSwitch(
                title = "处理重名文件",
                description = "重名时添加序号，保留已有文件",
                checked = duplicateConflictMitigation,
                enabled = enabled,
                onChange = onDuplicateConflictMitigationChange,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("转换线程数", style = MaterialTheme.typography.bodyLarge)
                    SettingDescription("1–8 个线程，推荐 4 个")
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "$threadCount",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            Slider(
                value = threadCount.toFloat(),
                onValueChange = { onThreadCountChange(it.roundToInt()) },
                valueRange = 1f..8f,
                steps = 6,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        onGpuRenderingChange?.let { onChange ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingSwitch(
                title = "GPU 渲染",
                description = if (gpuRenderingBusy) "正在切换…"
                    else "关闭后使用软件渲染；切换会重启程序",
                checked = gpuRenderingEnabled,
                enabled = enabled && !gpuRenderingBusy,
                onChange = onChange,
            )
            SettingError(gpuRenderingError)
        }
    }
}
