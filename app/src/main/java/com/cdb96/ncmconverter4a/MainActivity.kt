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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.service.FileConversionService
import kotlinx.coroutines.*

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

        fileConversionService = FileConversionService(this)

        handleIntent(intent)
        setContent {
            MainFrame(
                threadCount = threadCount,
                onThreadCountChange = ::updateThreadPool
            )
        }
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
            append("\n耗时：${String.format(java.util.Locale.US, "%.2f", duration / 1000.0)}秒")
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
        var convertResult by remember { mutableStateOf<String?>("尚未选择文件") }
        var musicName by remember { mutableStateOf("尚未选择文件") }
        var rawWriteMode by remember { mutableStateOf(false) }
        var conversionDurationMillis by remember { mutableStateOf<Long?>(null) }
        var isSettingsExpanded by remember { mutableStateOf(false) }
        var isProcessing by remember { mutableStateOf(false) }
        var currentFile by remember { mutableStateOf("") }
        var processedCount by remember { mutableStateOf(0) }
        var totalCount by remember { mutableStateOf(0) }

        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val activity = context as? MainActivity

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents(),
            onResult = { uris: List<Uri>? ->
                uris?.let { selectedUris ->
                    if (selectedUris.isNotEmpty() && activity != null) {
                        isProcessing = true
                        convertResult = "处理中..."
                        musicName = ""
                        processedCount = 0
                        totalCount = selectedUris.size

                        coroutineScope.launch {
                            try {
                                val result = activity.fileConversionService.processFiles(
                                    uris = selectedUris,
                                    threadCount = threadCount,
                                    rawWriteMode = rawWriteMode,
                                    fileCoroutineDispatcher = fileProcessingDispatcher
                                ) { processed, total, fileName ->
                                    // Update UI with progress
                                    processedCount = processed
                                    totalCount = total
                                    currentFile = fileName
                                    convertResult = "处理中 ($processed/$total)"
                                    musicName = fileName
                                }

                                // Update UI with final result
                                convertResult =
                                    "处理完成：成功${result.successCount}个，失败${result.failureCount}个"
                                conversionDurationMillis = result.durationMillis
                                currentFile = result.allFileNames
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("NCMConverter4A") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        if (!isProcessing) {
                            filePickerLauncher.launch("*/*")
                        }
                    },
                    containerColor = if (isProcessing)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.primaryContainer
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Add, contentDescription = "选择文件")
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                StatusCard(
                    convertResult = convertResult,
                    musicName = musicName,
                    conversionDurationMillis = conversionDurationMillis,
                    isProcessing = isProcessing,
                    processedCount = processedCount,
                    totalCount = totalCount,
                    currentFile = currentFile
                )

                Spacer(modifier = Modifier.height(16.dp))

                SettingsCard(
                    isExpanded = isSettingsExpanded,
                    onExpandToggle = { isSettingsExpanded = !isSettingsExpanded },
                    rawWriteMode = rawWriteMode,
                    onRawWriteModeChange = { rawWriteMode = it },
                    threadCount = threadCount,
                    onThreadCountChange = onThreadCountChange,
                    enabled = !isProcessing
                )
            }
        }
    }

    @Composable
    private fun StatusCard(
        convertResult: String?,
        musicName: String,
        conversionDurationMillis: Long?,
        isProcessing: Boolean,
        processedCount: Int = 0,
        totalCount: Int = 0,
        currentFile: String = ""
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))

                    if (totalCount > 0) {
                        Text(
                            text = "进度: $processedCount/$totalCount",
                            modifier = Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        LinearProgressIndicator(
                            progress = { processedCount.toFloat() / totalCount },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            color = ProgressIndicatorDefaults.linearColor,
                            trackColor = ProgressIndicatorDefaults.linearTrackColor,
                            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                        )
                    }
                }

                Text(
                    text = convertResult.orEmpty(),
                    modifier = Modifier.padding(bottom = 16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (currentFile.isNotEmpty()) {
                    Text(
                        text = "当前文件: $currentFile",
                        modifier = Modifier.padding(bottom = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "文件名: $musicName",
                        modifier = Modifier.padding(bottom = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                conversionDurationMillis?.let { duration ->
                    val seconds = duration / 1000.0
                    Text(
                        text = String.format(java.util.Locale.US, "处理耗时: %.3f 秒", seconds),
                        modifier = Modifier.padding(bottom = 16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onExpandToggle() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "兼容性设置",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
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
                            .padding(top = 8.dp, start = 8.dp)
                    ) {
                        // 原始写入模式设置
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "原始写入模式",
                                color = if (enabled)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
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
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "并发线程数: $threadCount",
                                color = if (enabled)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Slider(
                                value = threadCount.toFloat(),
                                onValueChange = { value ->
                                    onThreadCountChange(value.toInt())
                                },
                                valueRange = 1f..8f,
                                steps = 6, // 1, 2, 3, 4, 5, 6, 7, 8
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