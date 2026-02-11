package com.cdb96.ncmconverter4a

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.service.FileConversionService
import com.cdb96.ncmconverter4a.ui.BenchmarkDialog
import com.cdb96.ncmconverter4a.ui.theme.NCMConverter4aTheme
import com.cdb96.ncmconverter4a.util.DirectBufferPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var threadCount by mutableIntStateOf(4) // 默认4个线程
    lateinit var fileConversionService: FileConversionService

    @OptIn(ExperimentalCoroutinesApi::class)
    private var fileProcessingDispatcher = Dispatchers.Default.limitedParallelism(threadCount)

    // 更新线程池的函数
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun updateThreadPool(newThreadCount: Int) {
        threadCount = newThreadCount
        fileProcessingDispatcher = Dispatchers.Default.limitedParallelism(threadCount)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        warmup()
        fileConversionService = FileConversionService(this)

        handleIntent(intent)
        setContent {
            MainFrame(
                threadCount = threadCount,
                onThreadCountChange = ::updateThreadPool
            )
        }
    }

    fun warmup() {
        DirectBufferPool.updateSlotBuffer(1)
        /*
        val dummyRC4Data = ByteArray(256);
        val dummyKGMData = ByteArray(17);
        RC4Decrypt.ksa(dummyRC4Data);
        KGMDecrypt.init(dummyKGMData);
        */
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> handleSingleFile(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleFiles(intent)
            Intent.ACTION_VIEW -> handleViewAction(intent)
        }
    }

    private fun handleSingleFile(intent: Intent) {
        val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        uri?.let {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = fileConversionService.processFiles(
                        uris = listOf(it),
                        threadCount = 1,
                        rawWriteMode = false,
                        fileCoroutineDispatcher = fileProcessingDispatcher
                    ) { _, _, _ -> }

                    showSingleFileResult(result.successCount > 0)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "转换失败: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun handleMultipleFiles(intent: Intent) {
        val uris: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        uris?.let {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = fileConversionService.processFiles(
                        uris = it,
                        threadCount = threadCount,
                        rawWriteMode = false,
                        fileCoroutineDispatcher = fileProcessingDispatcher
                    ) { _, _, _ -> }

                    showMultipleFilesResult(
                        result.successCount,
                        result.failureCount,
                        result.durationMillis
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "批量转换失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun handleViewAction(intent: Intent) {
        intent.data?.let { uri ->
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = fileConversionService.processFiles(
                        uris = listOf(uri),
                        threadCount = 1,
                        rawWriteMode = false,
                        fileCoroutineDispatcher = fileProcessingDispatcher
                    ) { _, _, _ -> }

                    showSingleFileResult(result.successCount > 0)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "转换失败: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun showSingleFileResult(success: Boolean) {
        val message = if (success) {
            "转换完成！存储于Music文件夹"
        } else {
            "转换失败！可能不是支持的文件格式"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showMultipleFilesResult(successCount: Int, failureCount: Int, duration: Long) {
        val message = buildString {
            append("转换完成！")
            if (successCount > 0) append("成功${successCount}个")
            if (failureCount > 0) {
                if (successCount > 0) append("，")
                append("失败${failureCount}个")
            }
            append("\n耗时：${String.format(Locale.US, "%.2f", duration / 1000.0)}秒")
            append("\n存储于Music文件夹")
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainFrame(
        threadCount: Int,
        onThreadCountChange: (Int) -> Unit
    ) {
        var convertResult by remember { mutableStateOf<String?>(null) }
        var rawWriteMode by remember { mutableStateOf(false) }
        var conversionDurationMillis by remember { mutableStateOf<Long?>(null) }
        var isSettingsExpanded by remember { mutableStateOf(false) }
        var isProcessing by remember { mutableStateOf(false) }
        var currentFile by remember { mutableStateOf("") }
        var processedCount by remember { mutableIntStateOf(0) }
        var totalCount by remember { mutableIntStateOf(0) }
        var showBenchmarkDialog by remember { mutableStateOf(false) }
        var successCount by remember { mutableIntStateOf(0) }
        var failureCount by remember { mutableIntStateOf(0) }
        var successfulFileNames by remember { mutableStateOf<List<String>>(emptyList()) }
        var failedFileNames by remember { mutableStateOf<List<String>>(emptyList()) }
        // 是否曾经进行过转换（用于控制转换状态卡片显隐）
        var hasConversionStarted by remember { mutableStateOf(false) }

        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val activity = context as? MainActivity

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents(),
            onResult = { uris: List<Uri>? ->
                uris?.let { selectedUris ->
                    if (selectedUris.isNotEmpty() && activity != null) {
                        isProcessing = true
                        hasConversionStarted = true
                        convertResult = null
                        conversionDurationMillis = null
                        processedCount = 0
                        totalCount = selectedUris.size
                        successCount = 0
                        failureCount = 0
                        currentFile = ""

                        coroutineScope.launch {
                            try {
                                val result = activity.fileConversionService.processFiles(
                                    uris = selectedUris,
                                    threadCount = threadCount,
                                    rawWriteMode = rawWriteMode,
                                    fileCoroutineDispatcher = fileProcessingDispatcher
                                ) { processed, total, fileName ->
                                    processedCount = processed
                                    totalCount = total
                                    currentFile = fileName
                                }

                                successCount = result.successCount
                                failureCount = result.failureCount
                                conversionDurationMillis = result.durationMillis
                                currentFile = result.allFileNames
                                successfulFileNames = result.successfulFileNames
                                failedFileNames = result.failedFileNames
                                convertResult = "done"
                            } catch (e: Exception) {
                                convertResult = "处理过程中发生错误: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    }
                }
            }
        )

        NCMConverter4aTheme {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    "NCMConverter4A",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { showBenchmarkDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Speed,
                                    contentDescription = "基准测试"
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
                        onClick = {
                            if (!isProcessing) {
                                filePickerLauncher.launch("*/*")
                            }
                        },
                        containerColor = if (isProcessing)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.primaryContainer,
                        icon = {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null
                                )
                            }
                        },
                        text = {
                            Text(if (isProcessing) "转换中…" else "选择文件")
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
                        .padding(bottom = 88.dp) // 为 FAB 留出空间
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // 欢迎卡片（始终显示）
                    WelcomeCard(
                        hasConversionStarted = hasConversionStarted,
                        totalCount = totalCount
                    )

                    // 转换状态卡片（仅在启动过转换后显示）
                    AnimatedVisibility(
                        visible = hasConversionStarted,
                        enter = fadeIn(animationSpec = tween(300)) +
                                expandVertically(animationSpec = tween(300)),
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            ConversionStatusCard(
                                isProcessing = isProcessing,
                                convertResult = convertResult,
                                conversionDurationMillis = conversionDurationMillis,
                                processedCount = processedCount,
                                totalCount = totalCount,
                                currentFile = currentFile,
                                successCount = successCount,
                                failureCount = failureCount,
                                successfulFilesNames = successfulFileNames,
                                failedFilesNames = failedFileNames
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsCard(
                        isExpanded = isSettingsExpanded,
                        onExpandToggle = { isSettingsExpanded = !isSettingsExpanded },
                        rawWriteMode = rawWriteMode,
                        onRawWriteModeChange = { rawWriteMode = it },
                        threadCount = threadCount,
                        onThreadCountChange = onThreadCountChange,
                        enabled = !isProcessing
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 基准测试Dialog
            if (showBenchmarkDialog) {
                BenchmarkDialog(
                    onDismiss = { showBenchmarkDialog = false }
                )
            }
        }
    }

    // ======================== 欢迎卡片 ========================

    @Composable
    private fun WelcomeCard(
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
            modifier = Modifier
                .fillMaxWidth()
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
                    // 左侧图标
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

                    // 右侧文案
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

    // ======================== 转换状态卡片 ========================

    @Composable
    private fun ConversionStatusCard(
        isProcessing: Boolean,
        convertResult: String?,
        conversionDurationMillis: Long?,
        processedCount: Int,
        totalCount: Int,
        currentFile: String,
        successCount: Int,
        failureCount: Int,
        successfulFilesNames: List<String>,
        failedFilesNames: List<String>
    ) {
        val isError = convertResult != null && convertResult != "done" && !isProcessing
        val isDone = convertResult == "done" && !isProcessing

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
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                // 标题行：图标 + 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val (statusIcon, statusText, iconTint) = when {
                        isProcessing -> Triple(
                            Icons.Outlined.Sync,
                            "正在转换",
                            MaterialTheme.colorScheme.primary
                        )
                        isError -> Triple(
                            Icons.Outlined.ErrorOutline,
                            "转换异常",
                            MaterialTheme.colorScheme.error
                        )
                        isDone && failureCount == 0 -> Triple(
                            Icons.Outlined.CheckCircle,
                            "转换完成",
                            MaterialTheme.colorScheme.primary
                        )
                        else -> Triple(
                            Icons.Outlined.Warning,
                            "部分完成",
                            MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ---- 处理中 ----
                if (isProcessing && totalCount > 0) {
                    // 进度文字
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "进度",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$processedCount / $totalCount",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 进度条
                    LinearProgressIndicator(
                        progress = {
                            if (totalCount > 0) processedCount.toFloat() / totalCount else 0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    )

                    // 当前文件
                    if (currentFile.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentFile,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // ---- 出错 ----
                if (isError) {
                    Text(
                        text = convertResult,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                // ---- 完成 ----
                if (isDone) {
                    // 统计数据行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 成功数量
                        StatChip(
                            icon = Icons.Outlined.CheckCircle,
                            label = "成功",
                            value = "$successCount",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            fileNames = successfulFilesNames
                        )
                        // 失败数量
                        StatChip(
                            icon = Icons.Outlined.Cancel,
                            label = "失败",
                            value = "$failureCount",
                            tint = if (failureCount > 0)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f),
                            fileNames = failedFilesNames
                        )
                    }

                    // 耗时
                    conversionDurationMillis?.let { duration ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = String.format(Locale.US, "耗时 %.3f 秒", duration / 1000.0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // 文件列表
                    if (currentFile.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentFile,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .heightIn(max = 120.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 小型统计卡片，用于"成功/失败"数字展示
     */
    @Composable
    private fun StatChip(
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
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tint
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (showDialog && fileNames.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                confirmButton = {
                    TextButton(onClick = { showDialog = false }) { Text("确定") }
                },
                title = { Text("详细内容") },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(fileNames) { fileName ->
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                            )
                        }
                    }
                }
            )
        }
    }

    // ======================== 设置卡片 ========================

    @Composable
    private fun SettingsCard(
        isExpanded: Boolean,
        onExpandToggle: () -> Unit,
        rawWriteMode: Boolean,
        onRawWriteModeChange: (Boolean) -> Unit,
        threadCount: Int,
        onThreadCountChange: (Int) -> Unit,
        enabled: Boolean
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            Column {
                // 标题行
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
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = if (enabled)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "兼容性设置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (enabled)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "收起设置" else "展开设置",
                        tint = if (enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 20.dp)
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.padding(bottom = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                        )

                        // 原始写入模式设置
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.SaveAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (enabled)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "原始写入模式",
                                    color = if (enabled)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Switch(
                                checked = rawWriteMode,
                                onCheckedChange = onRawWriteModeChange,
                                enabled = enabled,
                                thumbContent = if (rawWriteMode) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "原始写入模式已开启",
                                            modifier = Modifier.size(SwitchDefaults.IconSize),
                                        )
                                    }
                                } else null
                            )
                        }

                        // 线程数设置
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (enabled)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "并发线程数: $threadCount",
                                    color = if (enabled)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Slider(
                                value = threadCount.toFloat(),
                                onValueChange = { value ->
                                    onThreadCountChange(value.toInt())
                                },
                                valueRange = 1f..8f,
                                steps = 6,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "1",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (enabled)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Text(
                                    text = "8",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (enabled)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }

                            Text(
                                text = "请根据设备情况合理选择，推荐为4",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabled)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}