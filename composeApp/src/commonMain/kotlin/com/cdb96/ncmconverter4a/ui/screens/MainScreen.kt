package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ======================== Data classes ========================

data class ConversionUiState(
    val isProcessing: Boolean = false,
    val convertResult: String? = null,
    val conversionDurationMillis: Long? = null,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val currentFile: String = "",
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val successfulFileNames: List<String> = emptyList(),
    val failedFileNames: List<String> = emptyList(),
    val hasConversionStarted: Boolean = false,
)

data class SettingsUiState(
    val isExpanded: Boolean = false,
    val rawWriteMode: Boolean = false,
    val duplicateConflictMitigation: Boolean = false,
    val threadCount: Int = 4,
    val enabled: Boolean = true,
)

// ======================== Main Screen ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    conversionState: ConversionUiState,
    settingsState: SettingsUiState,
    onSettingsExpandedToggle: () -> Unit,
    onRawWriteModeChange: (Boolean) -> Unit,
    onDuplicateConflictMitigationChange: (Boolean) -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onPickFiles: () -> Unit,
    onBenchmark: () -> Unit,
    onNavigateToKGG: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NCMConverter4A", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onBenchmark) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = "基准测试"
                        )
                    }
                    IconButton(onClick = onNavigateToKGG) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = "KGG解密"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!conversionState.isProcessing) onPickFiles() },
                containerColor = if (conversionState.isProcessing)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    MaterialTheme.colorScheme.primaryContainer,
                icon = {
                    if (conversionState.isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                },
                text = {
                    Text(if (conversionState.isProcessing) "转换中…" else "选择文件")
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 88.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            WelcomeCard(
                hasConversionStarted = conversionState.hasConversionStarted,
                totalCount = conversionState.totalCount
            )

            AnimatedVisibility(
                visible = conversionState.hasConversionStarted,
                enter = fadeIn(animationSpec = tween(300)) +
                        expandVertically(animationSpec = tween(300)),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    ConversionStatusCard(
                        conversionState = conversionState
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsCard(
                isExpanded = settingsState.isExpanded,
                onExpandToggle = onSettingsExpandedToggle,
                rawWriteMode = settingsState.rawWriteMode,
                onRawWriteModeChange = onRawWriteModeChange,
                duplicateConflictMitigation = settingsState.duplicateConflictMitigation,
                onDuplicateConflictMitigationChange = onDuplicateConflictMitigationChange,
                threadCount = settingsState.threadCount,
                onThreadCountChange = onThreadCountChange,
                enabled = settingsState.enabled
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ======================== Welcome Card ========================

@Composable
fun WelcomeCard(
    hasConversionStarted: Boolean,
    totalCount: Int
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.15f),
                            tertiaryColor.copy(alpha = 0.10f),
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasConversionStarted) "已选择 $totalCount 首歌曲" else "开始转换",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (hasConversionStarted)
                            "转换结果将保存至 Music 文件夹"
                        else
                            "点击右下角按钮选择 NCM / KGM 文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// ======================== Conversion Status Card ========================

@Composable
fun ConversionStatusCard(conversionState: ConversionUiState) {
    val isError = conversionState.convertResult != null &&
            conversionState.convertResult != "done" &&
            !conversionState.isProcessing
    val isDone = conversionState.convertResult == "done" && !conversionState.isProcessing

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> MaterialTheme.colorScheme.errorContainer
                isDone -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth()
        ) {
            val (statusIcon, statusText, iconTint) = when {
                conversionState.isProcessing -> Triple(
                    Icons.Outlined.Sync, "正在转换", MaterialTheme.colorScheme.primary
                )
                isError -> Triple(
                    Icons.Outlined.ErrorOutline, "转换异常", MaterialTheme.colorScheme.error
                )
                isDone && conversionState.failureCount == 0 -> Triple(
                    Icons.Outlined.CheckCircle, "转换完成", MaterialTheme.colorScheme.primary
                )
                else -> Triple(
                    Icons.Outlined.Warning, "部分完成", MaterialTheme.colorScheme.tertiary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(statusIcon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Processing state
            if (conversionState.isProcessing && conversionState.totalCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("进度", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${conversionState.processedCount} / ${conversionState.totalCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        if (conversionState.totalCount > 0)
                            conversionState.processedCount.toFloat() / conversionState.totalCount
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                )
                if (conversionState.currentFile.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            conversionState.currentFile,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Error state
            if (isError) {
                Text(
                    conversionState.convertResult,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            // Done state
            if (isDone) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip(
                        icon = Icons.Outlined.CheckCircle, label = "成功",
                        value = "${conversionState.successCount}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        fileNames = conversionState.successfulFileNames
                    )
                    StatChip(
                        icon = Icons.Outlined.Cancel, label = "失败",
                        value = "${conversionState.failureCount}",
                        tint = if (conversionState.failureCount > 0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                        fileNames = conversionState.failedFileNames
                    )
                }
                conversionState.conversionDurationMillis?.let { duration ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Timer, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "耗时 ${"%.3f".format(duration / 1000.0)} 秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
                if (conversionState.currentFile.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.Folder, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            conversionState.currentFile,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

// ======================== Stat Chip ========================

@Composable
fun StatChip(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
    fileNames: List<String>,
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.1f),
        modifier = modifier,
        onClick = { showDialog = true }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
                Text(label, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f))
            }
        }
    }
    if (showDialog && fileNames.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("确定") } },
            title = { Text("详细内容") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(fileNames) { fileName ->
                        Text(fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                    }
                }
            }
        )
    }
}

// ======================== Settings Card ========================

@Composable
fun SettingsCard(
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
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
                    .clickable(
                        enabled = enabled,
                        onClick = onExpandToggle,
                        indication = ripple(),
                        interactionSource = remember { MutableInteractionSource() }
                    )
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
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "收起设置" else "展开设置",
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
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
