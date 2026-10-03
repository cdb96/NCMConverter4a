package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cdb96.ncmconverter4a.service.DirectoryScanService
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanFormat
import com.cdb96.ncmconverter4a.service.ScannedAudioFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DirectoryScanUiState(
    val directories: Map<ScanFormat, ScanDirectory> = emptyMap(),
    val recursive: Boolean = true,
    val isScanning: Boolean = false,
    val visitedDirectories: Int = 0,
    val foundFiles: Int = 0,
    val files: List<ScannedAudioFile> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

class DirectoryScanController(
    private val scanner: DirectoryScanService,
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val uiDispatcher: CoroutineDispatcher,
    directories: Map<ScanFormat, ScanDirectory>,
    private val saveDirectory: (ScanFormat, ScanDirectory) -> Unit,
) {
    var state by mutableStateOf(DirectoryScanUiState(directories = directories))
        private set
    private var scanJob: Job? = null

    fun setDirectory(format: ScanFormat, directory: ScanDirectory) {
        if (state.isScanning) return
        try {
            saveDirectory(format, directory)
            state = state.copy(directories = state.directories + (format to directory),
                files = emptyList(), selectedIds = emptySet(), foundFiles = 0, visitedDirectories = 0,
                message = null, warnings = emptyList())
        } catch (error: Exception) {
            showMessage("无法保存扫描目录: ${error.message}")
        }
    }

    fun setRecursive(value: Boolean) { if (!state.isScanning) state = state.copy(recursive = value) }
    fun showMessage(message: String) { state = state.copy(message = message) }
    fun selectFile(id: String, selected: Boolean) {
        if (!state.isScanning && state.files.any { it.id == id }) {
            state = state.copy(selectedIds = if (selected) state.selectedIds + id else state.selectedIds - id)
        }
    }
    fun selectAll(selected: Boolean) {
        if (!state.isScanning) state = state.copy(selectedIds = if (selected) state.files.map { it.id }.toSet() else emptySet())
    }
    fun selectedSources(): List<String> = state.files.filter { it.id in state.selectedIds }.map { it.source }
    fun cancel() { scanJob?.cancel() }

    fun start(formats: Set<ScanFormat>) {
        if (state.isScanning) return
        val directories = state.directories.filter { it.key in formats && it.value.configured }
        if (directories.isEmpty()) { showMessage("请先选择要扫描的目录"); return }
        val recursive = state.recursive
        state = state.copy(isScanning = true, files = emptyList(), selectedIds = emptySet(),
            visitedDirectories = 0, foundFiles = 0, message = null, warnings = emptyList())
        scanJob = scope.launch {
            try {
                val result = withContext(workerDispatcher) {
                    scanner.scan(directories, recursive) { visited, found ->
                        withContext(uiDispatcher) {
                            state = state.copy(visitedDirectories = visited, foundFiles = found)
                        }
                    }
                }
                state = state.copy(files = result.files, selectedIds = result.files.map { it.id }.toSet(),
                    foundFiles = result.files.size, warnings = result.warnings,
                    message = if (result.files.isEmpty()) "未找到匹配的文件" else "扫描完成，共 ${result.files.size} 个文件")
            } catch (cancelled: CancellationException) {
                state = state.copy(message = "已取消扫描")
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(message = "扫描失败: ${error.message}")
            } finally {
                state = state.copy(isScanning = false)
            }
        }
    }
}
