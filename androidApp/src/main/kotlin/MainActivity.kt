package com.cdb96.ncmconverter4a

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.cdb96.ncmconverter4a.service.BenchmarkService
import com.cdb96.ncmconverter4a.service.FileConversionService
import com.cdb96.ncmconverter4a.ui.BenchmarkDialog
import com.cdb96.ncmconverter4a.ui.screens.ConversionUiState
import com.cdb96.ncmconverter4a.ui.screens.MainScreen
import com.cdb96.ncmconverter4a.ui.screens.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private fun getDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

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
        // Match system bar icons to the system theme used by the Compose UI.
        enableEdgeToEdge()
        pendingIntentUris.value = extractIntentUris(intent)

        setContent {
            val fileConversionService = remember { FileConversionService(this@MainActivity) }
            val benchmarkService = remember { BenchmarkService() }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            var conversionState by remember { mutableStateOf(ConversionUiState()) }
            var settingsState by remember { mutableStateOf(SettingsUiState(threadCount = threadCount)) }
            var showBenchmark by remember { mutableStateOf(false) }

            fun startConversion(selectedUris: List<Uri>) {
                if (selectedUris.isEmpty()) return
                if (conversionState.isProcessing) {
                    // A second batch (for example files shared while converting)
                    // would reset the progress state of the running one.
                    Toast.makeText(context, "正在转换中，请等待完成后再选择文件", Toast.LENGTH_SHORT).show()
                    return
                }
                val selectedSettings = settingsState
                conversionState = conversionState.start(selectedUris.size)
                scope.launch {
                    try {
                        val result = fileConversionService.processFiles(
                            uris = selectedUris,
                            rawWriteMode = selectedSettings.rawWriteMode,
                            duplicateConflictMitigation = selectedSettings.duplicateConflictMitigation,
                            fileCoroutineDispatcher = fileProcessingDispatcher,
                            kggDatabase = selectedSettings.kggDatabase?.let(Uri::parse),
                            kggRootMode = selectedSettings.kggRootMode,
                        ) { processed, total, fileName ->
                            conversionState = conversionState.copy(
                                processedCount = processed,
                                totalCount = total,
                                currentFile = fileName,
                            )
                        }
                        conversionState = conversionState.complete(result)
                    } catch (e: Exception) {
                        Toast.makeText(context, "转换失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        conversionState = conversionState.copy(isProcessing = false)
                    }
                }
            }

            val dbPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    settingsState = settingsState.copy(kggDatabase = uri.toString(), kggDatabaseName = getDisplayName(uri))
                }
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

            App {
                MainScreen(
                    conversionState = conversionState,
                    settingsState = settingsState,
                    onRawWriteModeChange = { settingsState = settingsState.copy(rawWriteMode = it) },
                    onDuplicateConflictMitigationChange = { settingsState = settingsState.copy(duplicateConflictMitigation = it) },
                    onThreadCountChange = { updateThreadPool(it); settingsState = settingsState.copy(threadCount = it) },
                    onPickFiles = { filePicker.launch("*/*") },
                    onBenchmark = { showBenchmark = true },
                    onSelectKggDatabase = { dbPicker.launch(arrayOf("*/*")) },
                    onKggRootModeChange = { settingsState = settingsState.copy(kggRootMode = it) },
                    supportsRoot = true,
                )

                if (showBenchmark) {
                    BenchmarkDialog(
                        onDismiss = { showBenchmark = false },
                        onRunBenchmark = { onProgress -> benchmarkService.runBenchmark(onProgress) }
                    )
                }
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
