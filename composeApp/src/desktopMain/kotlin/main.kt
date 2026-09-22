package com.cdb96.ncmconverter4a

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.cdb96.ncmconverter4a.service.BenchmarkService
import com.cdb96.ncmconverter4a.ui.BenchmarkDialog
import com.cdb96.ncmconverter4a.ui.screens.ConversionUiState
import com.cdb96.ncmconverter4a.ui.screens.MainScreen
import com.cdb96.ncmconverter4a.ui.screens.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "NCMConverter4a") {
        NCMConverter4aDesktopApp()
    }
}

@Composable
fun NCMConverter4aDesktopApp() {
    App { DesktopMainScreen() }
}

@Composable
fun DesktopMainScreen() {
    val scope = rememberCoroutineScope()
    var conversionState by remember { mutableStateOf(ConversionUiState()) }
    var settingsState by remember { mutableStateOf(SettingsUiState()) }
    var showBenchmark by remember { mutableStateOf(false) }
    val desktopFacade = remember { DesktopConversionFacade() }
    val benchmarkService = remember { BenchmarkService() }

    LaunchedEffect(Unit) {
        val detected = withContext(Dispatchers.IO) {
            System.getenv("APPDATA")?.let { File(it, "Kugou8/KGMusicV3.db") }?.takeIf { it.isFile }
        }
        if (detected != null && settingsState.kggDatabase == null) {
            settingsState = settingsState.copy(kggDatabase = detected.absolutePath, kggDatabaseName = detected.name)
        }
    }

    fun startConversion(files: List<String>) {
        if (files.isEmpty() || conversionState.isProcessing) return
        val selectedSettings = settingsState
        conversionState = conversionState.start(files.size)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    desktopFacade.processFiles(
                        filePaths = files,
                        threadCount = selectedSettings.threadCount,
                        rawWriteMode = selectedSettings.rawWriteMode,
                        duplicateConflictMitigation = selectedSettings.duplicateConflictMitigation,
                        kggDatabase = selectedSettings.kggDatabase,
                    ) { processed, total, fileName ->
                        withContext(Dispatchers.Swing) {
                            conversionState = conversionState.copy(
                                processedCount = processed, totalCount = total, currentFile = fileName,
                            )
                        }
                    }
                }
                conversionState = conversionState.complete(result)
            } catch (e: Exception) {
                conversionState = conversionState.copy(
                    isProcessing = false, convertResult = "处理过程中发生错误: ${e.message}",
                )
            }
        }
    }

    DesktopFileDropArea(
        enabled = !conversionState.isProcessing && !showBenchmark,
        onFiles = { files -> startConversion(files.map { it.absolutePath }) },
    ) {
        MainScreen(
            conversionState = conversionState,
            settingsState = settingsState,
            onRawWriteModeChange = { settingsState = settingsState.copy(rawWriteMode = it) },
            onDuplicateConflictMitigationChange = {
                settingsState = settingsState.copy(duplicateConflictMitigation = it)
            },
            onThreadCountChange = { settingsState = settingsState.copy(threadCount = it) },
            onPickFiles = { startConversion(DesktopFilePicker.pickFiles()) },
            onBenchmark = { showBenchmark = true },
            onSelectKggDatabase = {
                DesktopFilePicker.pickFiles(multiSelect = false, database = true).firstOrNull()?.let {
                    settingsState = settingsState.copy(kggDatabase = it, kggDatabaseName = File(it).name)
                }
            },
        )
    }

    if (showBenchmark) {
        BenchmarkDialog(
            onDismiss = { showBenchmark = false },
            onRunBenchmark = { onProgress -> benchmarkService.runBenchmark(onProgress) }
        )
    }
}
