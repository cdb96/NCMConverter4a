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
import com.cdb96.ncmconverter4a.service.DirectoryScanService
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.ui.BenchmarkDialog
import com.cdb96.ncmconverter4a.ui.screens.ConversionController
import com.cdb96.ncmconverter4a.ui.screens.MainScreen
import com.cdb96.ncmconverter4a.ui.screens.SettingsUiState
import com.cdb96.ncmconverter4a.ui.screens.DirectoryScanController
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
    val conversion = remember {
        ConversionController(scope, Dispatchers.Swing, { settings ->
            createFileConversionService(settings.outputDirectory?.let(::File) ?: defaultDesktopOutputDirectory())
        })
    }
    val conversionState = conversion.state
    val outputPreference = remember { DesktopOutputDirectoryPreference() }
    var settingsState by remember {
        mutableStateOf(SettingsUiState(
            gpuRenderingEnabled = gpuEnabled,
            outputDirectory = outputPreference.load().absolutePath,
        ))
    }
    var showBenchmark by remember { mutableStateOf(false) }
    var gpuSwitching by remember { mutableStateOf(false) }
    var gpuError by remember { mutableStateOf<String?>(null) }
    var outputDirectorySaving by remember { mutableStateOf(false) }
    var outputDirectoryError by remember { mutableStateOf<String?>(null) }
    val benchmarkService = remember { BenchmarkService() }
    val scanPreference = remember { DesktopScanDirectoryPreference() }
    val scanController = remember {
        DirectoryScanController(DirectoryScanService(DesktopDirectoryBrowser()), scope,
            Dispatchers.IO, Dispatchers.Swing, scanPreference.load(), scanPreference::save)
    }

    LaunchedEffect(Unit) {
        val detected = withContext(Dispatchers.IO) {
            System.getenv("APPDATA")?.let { File(it, "Kugou8/KGMusicV3.db") }?.takeIf { it.isFile }
        }
        if (detected != null && settingsState.kggDatabase == null) {
            settingsState = settingsState.copy(kggDatabase = detected.absolutePath, kggDatabaseName = detected.name)
        }
    }

    fun startConversion(files: List<String>) {
        conversion.start(files, settingsState)
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
            onSelectOutputDirectory = {
                if (!outputDirectorySaving && !conversionState.isProcessing) {
                    DesktopFilePicker.pickDirectory(settingsState.outputDirectory.orEmpty())?.let { selected ->
                        outputDirectorySaving = true
                        outputDirectoryError = null
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                runCatching { outputPreference.save(File(selected)) }
                            }
                            if (saved.isSuccess) {
                                settingsState = settingsState.copy(outputDirectory = selected)
                            } else {
                                outputDirectoryError = "无法保存输出文件夹：${saved.exceptionOrNull()?.message ?: "请重试"}"
                            }
                            outputDirectorySaving = false
                        }
                    }
                }
            },
            outputDirectoryBusy = outputDirectorySaving,
            outputDirectoryError = outputDirectoryError,
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
            scanController = scanController,
            onSelectScanDirectory = { format ->
                DesktopFilePicker.pickDirectory(scanController.state.directories[format]?.path.orEmpty(),
                    "选择 ${format.name} 扫描目录")?.let { scanController.setDirectory(format, ScanDirectory(it)) }
            },
            onScanDirectories = scanController::start,
            onConvertScannedFiles = ::startConversion,
        )
    }

    if (showBenchmark) {
        BenchmarkDialog(
            onDismiss = { showBenchmark = false },
            onRunBenchmark = { onProgress -> benchmarkService.runBenchmark(onProgress) }
        )
    }
}
