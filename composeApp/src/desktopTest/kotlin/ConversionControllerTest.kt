package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.service.ConversionFiles
import com.cdb96.ncmconverter4a.service.FileConversionService
import com.cdb96.ncmconverter4a.ui.screens.ConversionController
import com.cdb96.ncmconverter4a.ui.screens.ConversionUiState
import com.cdb96.ncmconverter4a.ui.screens.SettingsUiState
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class ConversionControllerTest {
    private class MissingFiles : ConversionFiles {
        var mitigation: Boolean? = null
        override fun fileName(source: String) = source
        override fun openInput(source: String): InputStream = throw IllegalArgumentException("Missing: $source")
        override fun prepareOutput(mitigateConflicts: Boolean) { mitigation = mitigateConflicts }
        override fun loadKggDatabase(database: String?, rootMode: Boolean): KggKeyDatabase = error("Unused")
        override fun writeOutput(fileName: String, extension: String, mitigateConflicts: Boolean,
            write: (OutputStream) -> Unit) = error("Unused")
    }

    @Test
    fun rejectsEmptyAndOverlappingBatchesAndKeepsASettingsSnapshot(): Unit = runBlocking {
        val files = MissingFiles()
        val settings = SettingsUiState(duplicateConflictMitigation = true, threadCount = 2)
        var receivedSettings: SettingsUiState? = null
        val controller = ConversionController(this, coroutineContext[ContinuationInterceptor] as CoroutineDispatcher, {
            receivedSettings = it
            FileConversionService(files)
        })
        assertFalse(controller.start(emptyList(), settings))
        assertEquals(ConversionUiState(), controller.state)
        val sources = mutableListOf("one.ncm", "two.kgm")
        assertTrue(controller.start(sources, settings))
        assertFalse(controller.start(listOf("other.kgg"), SettingsUiState()))
        sources.clear()
        coroutineContext.job.children.toList().joinAll()
        assertSame(settings, receivedSettings)
        assertEquals(true, files.mitigation)
        assertFalse(controller.state.isProcessing)
        assertEquals("done", controller.state.convertResult)
        assertEquals(2, controller.state.processedCount)
        assertEquals(2, controller.state.totalCount)
        assertEquals(listOf("one.ncm", "two.kgm"), controller.state.failedFileNames)
        assertNotNull(controller.state.conversionDurationMillis)
    }

    @Test
    fun reportsBatchSetupErrorsAndAllowsRetry() = runBlocking {
        val error = IllegalStateException("Output directory unavailable")
        var errors = 0
        val controller = ConversionController(this, Dispatchers.Unconfined, { throw error }, {
            assertSame(error, it)
            errors++
        })
        repeat(2) {
            assertTrue(controller.start(listOf("one.ncm"), SettingsUiState()))
            coroutineContext.job.children.toList().joinAll()
            assertFalse(controller.state.isProcessing)
            assertEquals("转换失败: ${error.message}", controller.state.convertResult)
        }
        assertEquals(2, errors)
    }

    @Test
    fun cancellationDoesNotBecomeAConversionError() = runBlocking {
        val controller = ConversionController(this, Dispatchers.Unconfined,
            { throw CancellationException("Closed") }, { fail("Cancellation must propagate") })
        assertTrue(controller.start(listOf("one.ncm"), SettingsUiState()))
        val jobs = coroutineContext.job.children.toList()
        jobs.joinAll()
        assertTrue(jobs.single().isCancelled)
        assertFalse(controller.state.isProcessing)
        assertNull(controller.state.convertResult)
        assertTrue(isActive)
    }

    @Test
    fun cancelledScopeCannotStartABatch() {
        val scope = CoroutineScope(Job().apply { cancel() })
        val controller = ConversionController(scope, Dispatchers.Unconfined, { error("Must not create service") })
        assertFalse(controller.start(listOf("one.ncm"), SettingsUiState()))
        assertEquals(ConversionUiState(), controller.state)
    }

    @Test
    fun lateProgressFromAnEarlierWorkerCannotMoveTheCountBackwards() = runBlocking {
        val queue = LinkedBlockingQueue<Runnable>()
        val ui = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val controller = ConversionController(this, ui, { FileConversionService(MissingFiles()) })
        // One worker suspends on each UI update before the next worker proceeds,
        // so the queue contains progress 1 followed by progress 2.
        controller.start(listOf("one.ncm", "two.ncm"), SettingsUiState(threadCount = 1))
        withTimeout(5000) { while (queue.size < 2) delay(1) }
        // Deliver progress 2 before progress 1.
        val updates = listOf(queue.remove(), queue.remove())
        updates.reversed().forEach { update ->
            val previousCount = controller.state.processedCount
            update.run()
            assertTrue(controller.state.processedCount >= previousCount)
        }
        coroutineContext.job.children.toList().joinAll()
        assertEquals(2, controller.state.processedCount)
    }

    @Test
    fun startingAnotherBatchClearsThePreviousDuration() {
        val state = ConversionUiState(conversionDurationMillis = 100, convertResult = "done")
        assertNull(state.start(2).conversionDurationMillis)
    }
}
