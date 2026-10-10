package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cdb96.ncmconverter4a.service.FileConversionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    fun start(sources: List<String>, settings: SettingsUiState): Boolean {
        if (sources.isEmpty() || state.isProcessing || !scope.isActive) return false
        val batch = sources.toList()
        state = state.start(batch.size)
        scope.launch {
            try {
                val result = createService(settings).processFiles(
                    sources = batch,
                    threadCount = settings.threadCount,
                    rawWriteMode = settings.rawWriteMode,
                    duplicateConflictMitigation = settings.duplicateConflictMitigation,
                    kggDatabase = settings.kggDatabase,
                    kggRootMode = settings.kggRootMode,
                ) { processed, total, fileName ->
                    withContext(uiDispatcher) {
                        // Workers can reach the UI dispatcher out of completion order.
                        if (processed > state.processedCount) {
                            state = state.copy(
                                processedCount = processed,
                                totalCount = total,
                                currentFile = fileName,
                            )
                        }
                    }
                }
                state = state.complete(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(convertResult = "转换失败: ${error.message}")
                onError(error)
            } finally {
                state = state.copy(isProcessing = false)
            }
        }
        return true
    }
}
