package com.cdb96.ncmconverter4a

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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

fun main() {
    val gpuEnabled = DesktopRenderingPreference.loadGpuEnabled()
    if (System.getenv("SKIKO_RENDER_API") == null && System.getProperty("skiko.renderApi") == null) {
        val renderer = if (!gpuEnabled) "SOFTWARE" else when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "DIRECT3D"
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "METAL"
            else -> "OPENGL"
        }
        System.setProperty("skiko.renderApi", renderer)
    }
    application {
        val windowState = rememberWindowState()
        Window(onCloseRequest = ::exitApplication, state = windowState, title = "NCMConverter4a") {
            NCMConverter4aDesktopApp(window, gpuEnabled, ::exitApplication)
        }
    }
}

@Composable
fun NCMConverter4aDesktopApp(window: ComposeWindow, gpuEnabled: Boolean, onClose: () -> Unit) {
    App {
        DesktopNativeCaption(window)
        Column(Modifier.fillMaxSize()) {
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                DesktopTallTitleBar(window, onClose)
            }
            DesktopMainScreen(gpuEnabled, onClose)
        }
    }
}

@Composable
fun DesktopMainScreen(gpuEnabled: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var conversionState by remember { mutableStateOf(ConversionUiState()) }
    var settingsState by remember { mutableStateOf(SettingsUiState(gpuRenderingEnabled = gpuEnabled)) }
    var showBenchmark by remember { mutableStateOf(false) }
    var gpuSwitching by remember { mutableStateOf(false) }
    var gpuError by remember { mutableStateOf<String?>(null) }
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
            desktopMode = true,
            onRawWriteModeChange = { settingsState = settingsState.copy(rawWriteMode = it) },
            onDuplicateConflictMitigationChange = {
                settingsState = settingsState.copy(duplicateConflictMitigation = it)
            },
            onThreadCountChange = { settingsState = settingsState.copy(threadCount = it) },
            onPickFiles = { startConversion(DesktopFilePicker.pickFiles()) },
            onBenchmark = { showBenchmark = true },
            onGpuRenderingChange = { enabled ->
                if (!gpuSwitching) {
                    gpuSwitching = true
                    gpuError = null
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            DesktopRenderingPreference.saveGpuEnabled(enabled)
                        }
                        if (saved) {
                            settingsState = settingsState.copy(gpuRenderingEnabled = enabled)
                            if (DesktopRendererRestart.restart()) {
                                onClose()
                            } else {
                                gpuError = "设置已保存，请手动重启程序后生效"
                            }
                        } else {
                            gpuError = "无法保存渲染设置"
                        }
                        gpuSwitching = false
                    }
                }
            },
            gpuRenderingBusy = gpuSwitching,
            gpuRenderingError = gpuError,
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
