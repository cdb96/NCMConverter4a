package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.cdb96.ncmconverter4a.service.ConversionResult
import com.cdb96.ncmconverter4a.service.FileConversionResult
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val failedFiles: List<FileConversionResult> = emptyList(),
) {
    val existingFileCount: Int get() = failedFiles.count { it.outputAlreadyExists }

    fun start(total: Int) = copy(
        isProcessing = true,
        hasConversionStarted = true,
        totalCount = total,
        processedCount = 0,
        successCount = 0,
        failureCount = 0,
        currentFile = "",
        convertResult = null,
        successfulFileNames = emptyList(),
        failedFileNames = emptyList(),
        failedFiles = emptyList(),
    )

    fun complete(result: ConversionResult) = copy(
        isProcessing = false,
        convertResult = "done",
        successCount = result.successCount,
        failureCount = result.failureCount,
        successfulFileNames = result.successfulFileNames,
        failedFileNames = result.failedFileNames,
        failedFiles = result.failedFiles,
        conversionDurationMillis = result.durationMillis,
        currentFile = result.allFileNames,
    )
}

data class SettingsUiState(
    val rawWriteMode: Boolean = false,
    val duplicateConflictMitigation: Boolean = false,
    val threadCount: Int = 4,
    val enabled: Boolean = true,
    val kggDatabase: String? = null,
    val kggDatabaseName: String? = null,
    val kggRootMode: Boolean = false,
)

// ======================== Main Screen ========================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    conversionState: ConversionUiState,
    settingsState: SettingsUiState,
    onRawWriteModeChange: (Boolean) -> Unit,
    onDuplicateConflictMitigationChange: (Boolean) -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onPickFiles: () -> Unit,
    onBenchmark: () -> Unit,
    onSelectKggDatabase: () -> Unit,
    onKggRootModeChange: (Boolean) -> Unit = {},
    supportsRoot: Boolean = false,
) {
    var settingsSelected by remember { mutableStateOf(false) }
    val conversionScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val enabled = settingsState.enabled && !conversionState.isProcessing
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (settingsSelected) "设置" else "NCMConverter4A", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onBenchmark, enabled = !conversionState.isProcessing) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = "基准测试"
                        )
                    }

                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        bottomBar = {
            AppBottomBar(settingsSelected, onSelectSettings = { settingsSelected = it })
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1120.dp)
                    .fillMaxSize()
                    .verticalScroll(if (settingsSelected) settingsScroll else conversionScroll)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                if (settingsSelected) {
                    SettingsScreen(
                        state = settingsState.copy(enabled = enabled),
                        onRawWriteModeChange = onRawWriteModeChange,
                        onDuplicateConflictMitigationChange = onDuplicateConflictMitigationChange,
                        onThreadCountChange = onThreadCountChange,
                        onSelectKggDatabase = onSelectKggDatabase,
                        onKggRootModeChange = onKggRootModeChange,
                        supportsRoot = supportsRoot,
                    )
                } else {
                    ConversionDashboard(
                        conversionState = conversionState,
                        settings = settingsState,
                        supportsRoot = supportsRoot,
                        onPickFiles = onPickFiles,
                        onOpenSettings = { settingsSelected = true },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ======================== Inline Conversion Status ========================

@Composable
internal fun ConversionStatusContent(
    conversionState: ConversionUiState,
) {
    val isError = conversionState.convertResult != null &&
            conversionState.convertResult != "done" &&
            !conversionState.isProcessing
    val isDone = conversionState.convertResult == "done" && !conversionState.isProcessing

    Column(modifier = Modifier.fillMaxWidth()) {
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
                isDone && conversionState.successCount == 0 -> Triple(
                    Icons.Outlined.Warning,
                    if (conversionState.existingFileCount == conversionState.failureCount)
                        "目标文件已存在" else "转换未完成",
                    MaterialTheme.colorScheme.tertiary
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
                    Text("进度", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "${conversionState.processedCount} / ${conversionState.totalCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
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
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            conversionState.currentFile,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Error state
            if (isError) {
                Text(
                    text = conversionState.convertResult.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
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
                        icon = Icons.Outlined.Warning, label = "未转换 · 查看原因",
                        value = "${conversionState.failureCount}",
                        tint = if (conversionState.existingFileCount == conversionState.failureCount && conversionState.failureCount > 0)
                            MaterialTheme.colorScheme.tertiary
                        else if (conversionState.failureCount > 0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                        fileNames = conversionState.failedFileNames,
                        fileResults = conversionState.failedFiles,
                    )
                }
                conversionState.conversionDurationMillis?.let { duration ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Timer, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "耗时 ${"%.3f".format(duration / 1000.0)} 秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
                if (conversionState.currentFile.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.Folder, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            conversionState.currentFile,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                        )
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
    fileResults: List<FileConversionResult> = emptyList(),
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.1f),
        modifier = modifier,
        onClick = { showDialog = fileNames.isNotEmpty() }
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
        ConversionFilesDialog(
            fileNames = fileNames,
            fileResults = fileResults,
            onDismiss = { showDialog = false },
        )
    }
}

// ======================== Settings Card ========================
