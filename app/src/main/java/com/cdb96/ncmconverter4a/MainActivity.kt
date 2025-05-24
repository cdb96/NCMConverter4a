package com.cdb96.ncmconverter4a

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    private var threadCount by mutableStateOf(4) // 默认4个线程
    @OptIn(ExperimentalCoroutinesApi::class)
    private var fileProcessingDispatcher = Dispatchers.IO.limitedParallelism(threadCount)

    // 更新线程池的函数
    private fun updateThreadPool(newThreadCount: Int) {
        threadCount = newThreadCount
        fileProcessingDispatcher = Dispatchers.IO.limitedParallelism(threadCount)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                val success = withContext(fileProcessingDispatcher) {
                    solveFile(it, this@MainActivity, false)
                }
                showSingleFileResult(success)
            }
        }
    }

    private fun handleMultipleFiles(intent: Intent) {
        val uris: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        uris?.let { processMultipleFilesParallel(it) }
    }

    private fun handleViewAction(intent: Intent) {
        intent.data?.let { uri ->
            CoroutineScope(Dispatchers.Main).launch {
                val success = withContext(fileProcessingDispatcher) {
                    solveFile(uri, this@MainActivity, false)
                }
                showSingleFileResult(success)
            }
        }
    }

    private fun processMultipleFilesParallel(uris: List<Uri>) {
        CoroutineScope(Dispatchers.Main).launch {
            val startTime = System.currentTimeMillis()
            val successCount = AtomicInteger(0)
            val failureCount = AtomicInteger(0)

            try {
                // 并行处理所有文件，使用统一的线程池
                val jobs = uris.map { uri ->
                    async(fileProcessingDispatcher) {
                        try {
                            val success = solveFile(uri, this@MainActivity, false)
                            if (success) {
                                successCount.incrementAndGet()
                            } else {
                                failureCount.incrementAndGet()
                            }
                            success
                        } catch (e: Exception) {
                            e.printStackTrace()
                            failureCount.incrementAndGet()
                            false
                        }
                    }
                }

                // 等待所有任务完成
                jobs.awaitAll()

                val duration = System.currentTimeMillis() - startTime
                val total = successCount.get()
                val failed = failureCount.get()

                showMultipleFilesResult(total, failed, duration)

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "批量转换过程中发生错误: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showSingleFileResult(success: Boolean) {
        val message = if (success) {
            "转换完成！存储于Music文件夹"
        } else {
            "转换失败！可能不是NCM文件"
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


    // 将函数移到MainActivity类内部，使其可以访问fileProcessingDispatcher
    internal fun processFilesWithUI(
        uris: List<Uri>,
        context: Context,
        rawWriteMode: Boolean,
        coroutineScope: CoroutineScope,
        onProgress: (String, String, Long?) -> Unit,
        onComplete: () -> Unit
    ) {
        coroutineScope.launch {
            val startTime = System.currentTimeMillis()
            val successCount = AtomicInteger(0)
            val failureCount = AtomicInteger(0)
            val fileNames = mutableListOf<String>()

            try {
                // 使用统一的线程池处理所有文件
                val jobs = uris.map { uri ->
                    async(fileProcessingDispatcher) {
                        val fileName = uri.getFileName(context) ?: "未知文件"
                        synchronized(fileNames) {
                            fileNames.add(fileName)
                        }

                        val success = solveFile(uri, context, rawWriteMode)
                        if (success) {
                            successCount.incrementAndGet()
                        } else {
                            failureCount.incrementAndGet()
                        }
                        success
                    }
                }

                jobs.awaitAll()

                val duration = System.currentTimeMillis() - startTime
                val result = "处理完成：成功${successCount.get()}个，失败${failureCount.get()}个"
                val names = fileNames.joinToString(", ")

                onProgress(result, names, duration)

            } catch (_: Exception) {
                onProgress("处理过程中发生错误", "错误", null)
            } finally {
                onComplete()
            }
        }
    }
}

// 扩展函数：获取文件名
private fun Uri.getFileName(context: Context): String? {
    return DocumentFile.fromSingleUri(context, this)?.name
}

// 优化后的文件处理函数，移除冗余的withContext调用
private suspend fun solveFile(
    uri: Uri,
    context: Context,
    rawWriteMode: Boolean
): Boolean {
    try {
        // 先检测是否为 KGM 文件
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val isKGM = KGMConverter.KGMDetect(inputStream)
            if (isKGM) {
                // 处理 KGM 文件
                processKGMFile(uri, context, inputStream)
            } else {
                // 处理 NCM 文件
                if (!processNCMFile(uri, context, rawWriteMode)) return false
            }
        } == true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
    return true
}

private fun processNCMFile(
    uri: Uri,
    context: Context,
    rawWriteMode: Boolean
): Boolean {
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->

            val result = NCMConverter.convert(inputStream, false)
            val fileName = getMusicInfoData(result.musicInfoStringArrayValue, "musicName")
            val format = getMusicInfoData(result.musicInfoStringArrayValue, "format")

            getOutputStream(format, context, fileName)?.use { outputStream ->
                NCMConverter.outputMusic(
                    outputStream,
                    inputStream,
                    result.RC4key,
                    result.coverData,
                    rawWriteMode,
                    result.musicInfoStringArrayValue
                )
            }
        }
        return true
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}

private fun processKGMFile(
    uri: Uri,
    context: Context,
    inputStream: InputStream
): Boolean {
    try {
        // 获取音乐格式
        val musicFormat = KGMConverter.detectFormat(inputStream)

        if (musicFormat.isNullOrBlank()) {
            return false
        }

        // 获取文件名并处理
        val originalFileName = uri.getFileName(context) ?: return false
        val fileName = originalFileName.replace(Regex("(.kgm)|(.flac)", RegexOption.IGNORE_CASE), "")


        // 创建输出流并转换
        getOutputStream(musicFormat, context, fileName)?.use { outputStream ->
            KGMConverter.write(inputStream, outputStream, musicFormat)
            return true
        }

        false

    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
    return false
}

private fun getOutputStream(format: String, context: Context, fileName: String): OutputStream? {
    val mimeType = when (format.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        else -> "audio/mpeg"
    }

    val musicName = "$fileName.${format.lowercase()}"

    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, musicName)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A")
    }

    return context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ?.let { context.contentResolver.openOutputStream(it) }
}

private fun getMusicInfoData(arrayList: ArrayList<String>, key: String): String {
    return try {
        val index = arrayList.indexOf(key)
        if (index != -1 && index + 1 < arrayList.size) {
            arrayList[index + 1]
        } else {
            "unknown"
        }
    } catch (_: Exception) {
        "unknown"
    }
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

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = { uris: List<Uri>? ->
            uris?.let { selectedUris ->
                if (selectedUris.isNotEmpty()) {
                    isProcessing = true
                    // 这里调用MainActivity内部的processFilesWithUI函数
                    (context as? MainActivity)?.processFilesWithUI(
                        uris = selectedUris,
                        context = context,
                        rawWriteMode = rawWriteMode,
                        coroutineScope = coroutineScope,
                        onProgress = { result, names, duration ->
                            convertResult = result
                            musicName = names
                            conversionDurationMillis = duration
                        },
                        onComplete = {
                            isProcessing = false
                        }
                    )
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
                isProcessing = isProcessing
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
    isProcessing: Boolean
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
            }

            Text(
                text = convertResult.orEmpty(),
                modifier = Modifier.padding(bottom = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )

            Text(
                text = "文件名: $musicName",
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )

            conversionDurationMillis?.let { duration ->
                val seconds = duration / 1000.0
                Text(
                    text = String.format(Locale.US, "处理耗时: %.3f 秒", seconds),
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