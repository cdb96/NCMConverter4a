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
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

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
            // Run file picker on a separate thread to avoid blocking EDT
            thread(name = "file-picker", isDaemon = true) {
                val files = DesktopFilePicker.pickFiles()
                if (files.isNotEmpty()) {
                    conversionState = conversionState.copy(
                        isProcessing = true,
                        hasConversionStarted = true,
                        totalCount = files.size,
                        processedCount = 0,
                        successCount = 0,
                        failureCount = 0,
                        currentFile = "",
                        convertResult = null,
                    )
                    scope.launch {
                        try {
                            val result = desktopFacade.processFiles(
                                files,
                                settingsState.threadCount,
                                settingsState.rawWriteMode,
                                settingsState.duplicateConflictMitigation,
                            ) { processed, total, fileName ->
                                conversionState = conversionState.copy(
                                    processedCount = processed,
                                    totalCount = total,
                                    currentFile = fileName,
                                )
                            }
                            conversionState = conversionState.copy(
                                isProcessing = false,
                                convertResult = "done",
                                successCount = result.successCount,
                                failureCount = result.failureCount,
                                successfulFileNames = result.successfulFileNames,
                                failedFileNames = result.failedFileNames,
                                conversionDurationMillis = result.durationMillis,
                                currentFile = result.allFileNames,
                            )
                        } catch (e: Exception) {
                            conversionState = conversionState.copy(
                                isProcessing = false,
                                convertResult = "处理过程中发生错误: ${e.message}",
                            )
                        }
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
        onNavigateBack = onNavigateBack,
        onSelectDbFile = {
            thread(name = "file-picker", isDaemon = true) {
                val files = DesktopFilePicker.pickFiles(multiSelect = false)
                if (files.isNotEmpty()) state = state.copy(dbFileName = files.first())
            }
        },
        onSelectAudioFile = {
            thread(name = "file-picker", isDaemon = true) {
                val files = DesktopFilePicker.pickFiles(multiSelect = false)
                if (files.isNotEmpty()) state = state.copy(audioFileName = files.first())
            }
        },
        onDecrypt = {
            state = state.copy(isProcessing = true, decryptResult = "正在解密文件...")
            scope.launch {
                try {
                    val decrypter = DesktopKggDecrypt()
                    // Desktop KGG decrypt - simplified since root mode not available
                    decrypter.decrypt(state.audioFileName!!, state.dbFileName)
                    state = state.copy(isProcessing = false, decryptResult = "文件解密完成！")
                } catch (e: Exception) {
                    state = state.copy(isProcessing = false, decryptResult = "文件解密失败: ${e.message}")
                }
            }
        },
        onRootedChange = { state = state.copy(isRooted = it) },
    )
}
