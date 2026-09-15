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
import com.cdb96.ncmconverter4a.ui.screens.KggScreen
import com.cdb96.ncmconverter4a.ui.screens.KggUiState
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
    var currentScreen by remember { mutableStateOf("main") }

    App {
        when (currentScreen) {
            "main" -> DesktopMainScreen(
                onNavigateToKGG = { currentScreen = "kgg" }
            )
            "kgg" -> DesktopKggScreen(
                onNavigateBack = { currentScreen = "main" }
            )
        }
    }
}

@Composable
fun DesktopMainScreen(onNavigateToKGG: () -> Unit) {
    val scope = rememberCoroutineScope()
    var conversionState by remember { mutableStateOf(ConversionUiState()) }
    var settingsState by remember { mutableStateOf(SettingsUiState()) }
    var showBenchmark by remember { mutableStateOf(false) }
    val desktopFacade = remember { DesktopConversionFacade() }
    val benchmarkService = remember { BenchmarkService() }

    MainScreen(
        conversionState = conversionState,
        settingsState = settingsState,
        onSettingsExpandedToggle = {
            settingsState = settingsState.copy(isExpanded = !settingsState.isExpanded)
        },
        onRawWriteModeChange = { settingsState = settingsState.copy(rawWriteMode = it) },
        onDuplicateConflictMitigationChange = {
            settingsState = settingsState.copy(duplicateConflictMitigation = it)
        },
        onThreadCountChange = { settingsState = settingsState.copy(threadCount = it) },
        onPickFiles = {
            scope.launch {
                val files = withContext(Dispatchers.Swing) {
                    DesktopFilePicker.pickFiles()
                }
                if (files.isEmpty()) return@launch

                val selectedSettings = settingsState
                withContext(Dispatchers.Swing) {
                    conversionState = conversionState.start(files.size)
                }

                try {
                    val result = withContext(Dispatchers.IO) {
                        desktopFacade.processFiles(
                            filePaths = files,
                            threadCount = selectedSettings.threadCount,
                            rawWriteMode = selectedSettings.rawWriteMode,
                            duplicateConflictMitigation = selectedSettings.duplicateConflictMitigation,
                        ) { processed, total, fileName ->
                            withContext(Dispatchers.Swing) {
                                conversionState = conversionState.copy(
                                    processedCount = processed,
                                    totalCount = total,
                                    currentFile = fileName,
                                )
                            }
                        }
                    }
                    withContext(Dispatchers.Swing) {
                        conversionState = conversionState.complete(result)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Swing) {
                        conversionState = conversionState.copy(
                            isProcessing = false,
                            convertResult = "处理过程中发生错误: ${e.message}",
                        )
                    }
                }
            }
        },
        onBenchmark = { showBenchmark = true },
        onNavigateToKGG = onNavigateToKGG,
    )

    if (showBenchmark) {
        BenchmarkDialog(
            onDismiss = { showBenchmark = false },
            onRunBenchmark = { onProgress -> benchmarkService.runBenchmark(onProgress) }
        )
    }
}

@Composable
fun DesktopKggScreen(onNavigateBack: () -> Unit) {
    var state by remember { mutableStateOf(KggUiState()) }
    val scope = rememberCoroutineScope()

    // Auto-detect Kugou database file on Windows
    LaunchedEffect(Unit) {
        val kgDbPath = System.getenv("APPDATA")?.let { appData ->
            File(appData, "Kugou8/KGMusicV3.db")
        }
        if (kgDbPath != null && kgDbPath.exists()) {
            state = state.copy(dbFileName = kgDbPath.absolutePath)
        }
    }

    KggScreen(
        state = state,
        supportsRoot = false,
        onNavigateBack = onNavigateBack,
        onSelectDbFile = {
            scope.launch {
                val files = withContext(Dispatchers.Swing) {
                    DesktopFilePicker.pickFiles(multiSelect = false)
                }
                if (files.isNotEmpty()) {
                    withContext(Dispatchers.Swing) {
                        state = state.copy(dbFileName = files.first(), decryptResult = null)
                    }
                }
            }
        },
        onSelectAudioFile = {
            scope.launch {
                val files = withContext(Dispatchers.Swing) {
                    DesktopFilePicker.pickFiles(multiSelect = false)
                }
                if (files.isNotEmpty()) {
                    withContext(Dispatchers.Swing) {
                        state = state.copy(audioFileName = files.first(), decryptResult = null)
                    }
                }
            }
        },
        onDecrypt = {
            val audioFileName = state.audioFileName
            val dbFileName = state.dbFileName
            if (!state.isProcessing && audioFileName != null && dbFileName != null) {
                state = state.copy(isProcessing = true, resultIsError = false, decryptResult = "正在解密文件...")
                scope.launch(Dispatchers.IO) {
                    try {
                        val decrypter = DesktopKggDecrypt()
                        // Desktop KGG decrypt - simplified since root mode not available
                        decrypter.decrypt(audioFileName, dbFileName)
                        withContext(Dispatchers.Swing) {
                            state = state.copy(isProcessing = false, decryptResult = "文件解密完成！")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Swing) {
                            state = state.copy(isProcessing = false, resultIsError = true, decryptResult = "文件解密失败: ${e.message}")
                        }
                    }
                }
            }
        },
        onRootedChange = { state = state.copy(isRooted = it, decryptResult = null) },
    )
}
