package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cdb96.ncmconverter4a.service.FileConversionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Called on the UI thread; the service owns the conversion workers and buffers. */
class ConversionController(
    private val scope: CoroutineScope,
    private val uiDispatcher: CoroutineDispatcher,
    private val createService: (SettingsUiState) -> FileConversionService,
    private val onError: (Exception) -> Unit = {},
) {
    var state by mutableStateOf(ConversionUiState())
        private set

    /** Reject an empty or overlapping batch before changing any UI state. */
    fun start(
        sources: List<String>,
        settings: SettingsUiState,
        knownFileNames: Map<String, String> = emptyMap(),
    ): Boolean {
        if (sources.isEmpty() || state.isProcessing || !scope.isActive) return false
        val batch = sources.toList()
        state = state.start(batch.size)
        scope.launch {
            val progressFlow = MutableStateFlow<Triple<Int, Int, String>?>(null)
            val progressJob = launch(uiDispatcher) {
                progressFlow.filterNotNull().collect { (processed, total, fileName) ->
                    if (processed > state.processedCount) {
                        state = state.copy(
                            processedCount = processed,
                            totalCount = total,
                            currentFile = fileName,
                        )
                    }
                }
            }
            try {
                val result = createService(settings).processFiles(
                    sources = batch,
                    threadCount = settings.threadCount,
                    rawWriteMode = settings.rawWriteMode,
                    duplicateConflictMitigation = settings.duplicateConflictMitigation,
                    kggDatabase = settings.kggDatabase,
                    kggRootMode = settings.kggRootMode,
                    knownFileNames = knownFileNames,
                ) { processed, total, fileName ->
                    progressFlow.update { current ->
                        if (current == null || processed > current.first) {
                            Triple(processed, total, fileName)
                        } else {
                            current
                        }
                    }
                }
                progressJob.cancel()
                state = state.complete(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(convertResult = "转换失败: ${error.message}")
                onError(error)
            } finally {
                progressJob.cancel()
                state = state.copy(isProcessing = false)
            }
        }
        return true
    }
}
