package com.cdb96.ncmconverter4a

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.cdb96.ncmconverter4a.converter.kgg.KggDecoder
import com.cdb96.ncmconverter4a.service.BenchmarkService
import com.cdb96.ncmconverter4a.service.FileConversionService
import com.cdb96.ncmconverter4a.ui.BenchmarkDialog
import com.cdb96.ncmconverter4a.ui.screens.ConversionUiState
import com.cdb96.ncmconverter4a.ui.screens.KggScreen
import com.cdb96.ncmconverter4a.ui.screens.KggUiState
import com.cdb96.ncmconverter4a.ui.screens.MainScreen
import com.cdb96.ncmconverter4a.ui.screens.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var threadCount by mutableIntStateOf(4)
    private val pendingIntentUris = mutableStateOf<List<Uri>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private var fileProcessingDispatcher = Dispatchers.Default.limitedParallelism(threadCount)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun updateThreadPool(newThreadCount: Int) {
        threadCount = newThreadCount
        fileProcessingDispatcher = Dispatchers.Default.limitedParallelism(threadCount)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIntentUris.value = extractIntentUris(intent)

        setContent {
            val fileConversionService = remember { FileConversionService(this@MainActivity) }
            val benchmarkService = remember { BenchmarkService() }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            var currentScreen by remember { mutableStateOf("main") }
            var conversionState by remember { mutableStateOf(ConversionUiState()) }
            var settingsState by remember { mutableStateOf(SettingsUiState(threadCount = threadCount)) }
            var showBenchmark by remember { mutableStateOf(false) }

            fun startConversion(selectedUris: List<Uri>) {
                if (selectedUris.isEmpty()) return
                val selectedSettings = settingsState
                conversionState = conversionState.copy(
                    isProcessing = true, hasConversionStarted = true,
                    totalCount = selectedUris.size, processedCount = 0,
                    successCount = 0, failureCount = 0,
                    currentFile = "", convertResult = null,
                )
                scope.launch {
                    try {
                        val result = fileConversionService.processFiles(
                            uris = selectedUris,
                            rawWriteMode = selectedSettings.rawWriteMode,
                            duplicateConflictMitigation = selectedSettings.duplicateConflictMitigation,
                            fileCoroutineDispatcher = fileProcessingDispatcher,
                        ) { processed, total, fileName ->
                            conversionState = conversionState.copy(
                                processedCount = processed,
                                totalCount = total,
                                currentFile = fileName,
                            )
                        }
                        conversionState = conversionState.copy(
                            isProcessing = false, convertResult = "done",
                            successCount = result.successCount, failureCount = result.failureCount,
                            successfulFileNames = result.successfulFileNames,
                            failedFileNames = result.failedFileNames,
                            conversionDurationMillis = result.durationMillis,
                            currentFile = result.allFileNames,
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "转换失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        conversionState = conversionState.copy(isProcessing = false)
                    }
                }
            }

            if (currentScreen == "kgg") {
                val kggDecoder = remember { KggDecoder(context) }
                var kggState by remember { mutableStateOf(KggUiState()) }

                val dbPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) kggState = kggState.copy(dbFileName = uri.toString())
                }
                val audioPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) kggState = kggState.copy(audioFileName = uri.toString())
                }

                KggScreen(
                    state = kggState,
                    onNavigateBack = { currentScreen = "main" },
                    onSelectDbFile = { if (!kggState.isRooted) dbPicker.launch("*/*") },
                    onSelectAudioFile = { audioPicker.launch("*/*") },
                    onDecrypt = {
                        val audioFileName = kggState.audioFileName
                        val dbFileName = kggState.dbFileName
                        val isRooted = kggState.isRooted
                        if (audioFileName != null) {
                            kggState = kggState.copy(isProcessing = true, decryptResult = "正在解密文件...")
                            scope.launch {
                                try {
                                    val audioUri = android.net.Uri.parse(audioFileName)
                                    val dbUri = dbFileName?.let { android.net.Uri.parse(it) }
                                    withContext(Dispatchers.IO) {
                                        kggDecoder.decryptWithUri(audioUri, dbUri, isRooted)
                                    }
                                    kggState = kggState.copy(isProcessing = false, decryptResult = "文件解密完成！")
                                } catch (e: Exception) {
                                    kggState = kggState.copy(isProcessing = false, decryptResult = "文件解密失败: ${e.message}")
                                }
                            }
                        }
                    },
                    onRootedChange = { kggState = kggState.copy(isRooted = it) },
                )
                return@setContent
            }

            val filePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetMultipleContents()
            ) { selectedUris ->
                startConversion(selectedUris)
            }

            val sharedUris = pendingIntentUris.value
            LaunchedEffect(sharedUris) {
                if (sharedUris.isNotEmpty()) {
                    pendingIntentUris.value = emptyList()
                    startConversion(sharedUris)
                }
            }

            MainScreen(
                conversionState = conversionState,
                settingsState = settingsState,
                onSettingsExpandedToggle = { settingsState = settingsState.copy(isExpanded = !settingsState.isExpanded) },
                onRawWriteModeChange = { settingsState = settingsState.copy(rawWriteMode = it) },
                onDuplicateConflictMitigationChange = { settingsState = settingsState.copy(duplicateConflictMitigation = it) },
                onThreadCountChange = { updateThreadPool(it); settingsState = settingsState.copy(threadCount = it) },
                onPickFiles = { filePicker.launch("*/*") },
                onBenchmark = { showBenchmark = true },
                onNavigateToKGG = { currentScreen = "kgg" },
            )

            if (showBenchmark) {
                BenchmarkDialog(
                    onDismiss = { showBenchmark = false },
                    onRunBenchmark = { onProgress -> benchmarkService.runBenchmark(onProgress) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntentUris.value = extractIntentUris(intent)
    }

    @Suppress("DEPRECATION")
    private fun extractIntentUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        val result = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(result::add)
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(result::add)
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(result::addAll)
            }
        }
        intent.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(result::add)
            }
        }
        return result.distinct()
    }
}
