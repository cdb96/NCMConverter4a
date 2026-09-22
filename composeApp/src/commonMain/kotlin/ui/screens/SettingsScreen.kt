package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.compose.material.icons.outlined.Settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SwitchDefaults

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onRawWriteModeChange: (Boolean) -> Unit,
    onDuplicateConflictMitigationChange: (Boolean) -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onSelectKggDatabase: () -> Unit,
    onKggRootModeChange: (Boolean) -> Unit,
    supportsRoot: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard(
            rawWriteMode = state.rawWriteMode,
            onRawWriteModeChange = onRawWriteModeChange,
            duplicateConflictMitigation = state.duplicateConflictMitigation,
            onDuplicateConflictMitigationChange = onDuplicateConflictMitigationChange,
            threadCount = state.threadCount,
            onThreadCountChange = onThreadCountChange,
            enabled = state.enabled,
        )
        KggDatabaseCard(
            state = state,
            onSelectClick = onSelectKggDatabase,
            onRootModeChange = onKggRootModeChange,
            supportsRoot = supportsRoot,
        )
    }
}

@Composable
private fun KggDatabaseCard(
    state: SettingsUiState,
    onSelectClick: () -> Unit,
    onRootModeChange: (Boolean) -> Unit,
    supportsRoot: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val automatic = supportsRoot && state.kggRootMode
    val hasDatabase = state.kggDatabase != null

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null,
                    tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("KGG 数据库", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium)
                    Text("用于读取 KGG 音频密钥，支持与 NCM、KGM 一起批量转换。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = colors.onSurfaceVariant.copy(alpha = 0.12f))

            if (supportsRoot) {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = state.kggRootMode,
                        enabled = state.enabled,
                        role = Role.Switch,
                        onValueChange = onRootModeChange,
                    ).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("自动获取数据库", style = MaterialTheme.typography.bodyLarge)
                        Text("需要设备已 Root 并授予访问权限",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(checked = state.kggRootMode, onCheckedChange = null, enabled = state.enabled)
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (automatic) colors.secondaryContainer else colors.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = if (automatic) Icons.Outlined.Key
                            else Icons.AutoMirrored.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = if (automatic) colors.onSecondaryContainer else colors.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = when {
                                automatic -> "转换时自动获取"
                                hasDatabase -> "已选择数据库"
                                else -> "尚未选择数据库"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (automatic) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        )
                        Text(
                            text = when {
                                automatic -> "开始转换 KGG 时读取酷狗数据库，无需手动选择文件。"
                                hasDatabase -> state.kggDatabaseName?.takeIf { it.isNotBlank() }
                                    ?: "已选择数据库文件"
                                else -> "添加对应的酷狗数据库后，即可转换 KGG 文件。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (automatic) colors.onSecondaryContainer else colors.onSurface,
                            maxLines = if (hasDatabase && !automatic) 3 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!automatic) {
                Text(
                    text = if (supportsRoot) "请选择包含音频密钥的 MMKV 文件"
                        else "请选择包含音频密钥的 DB 或 MMKV 文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Button(
                    onClick = onSelectClick,
                    enabled = state.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (hasDatabase) "更换数据库" else "选择数据库")
                }
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
    enabled: Boolean
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Settings, contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "兼容性设置", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

            }

            Box {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    )

                    // Raw write mode
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.SaveAlt, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("原始写入模式", color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Switch(
                            checked = rawWriteMode, onCheckedChange = onRawWriteModeChange, enabled = enabled,
                            thumbContent = if (rawWriteMode) {
                                { Icon(Icons.Filled.Check, "原始写入模式已开启", modifier = Modifier.size(SwitchDefaults.IconSize)) }
                            } else null
                        )
                    }

                    // Duplicate conflict mitigation
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("重名文件冲突缓解", color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Switch(
                            checked = duplicateConflictMitigation, onCheckedChange = onDuplicateConflictMitigationChange,
                            enabled = enabled,
                            thumbContent = if (duplicateConflictMitigation) {
                                { Icon(Icons.Filled.Check, "重名文件冲突缓解已开启", modifier = Modifier.size(SwitchDefaults.IconSize)) }
                            } else null
                        )
                    }

                    // Thread count
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Memory, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("线程数: $threadCount", color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = threadCount.toFloat(), onValueChange = { onThreadCountChange(it.toInt()) },
                            valueRange = 1f..8f, steps = 6, enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("1", style = MaterialTheme.typography.bodySmall,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            Text("8", style = MaterialTheme.typography.bodySmall,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                        Text(
                            "请根据设备情况合理选择，推荐为4",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
