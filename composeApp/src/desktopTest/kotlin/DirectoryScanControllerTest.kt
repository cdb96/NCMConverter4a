package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.service.ANDROID_NCM_SCAN_DIRECTORY
import com.cdb96.ncmconverter4a.service.DirectoryScanService
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanDirectoryBrowser
import com.cdb96.ncmconverter4a.service.ScanFormat
import com.cdb96.ncmconverter4a.service.ScanNode
import com.cdb96.ncmconverter4a.service.defaultScanDirectories
import com.cdb96.ncmconverter4a.ui.screens.DirectoryScanController
import com.cdb96.ncmconverter4a.ui.screens.DirectoryScanUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectoryScanControllerTest {
    @Test
    fun firstAndroidScanSelectsTheSuggestedNcmDirectory() {
        val state = DirectoryScanUiState(directories = defaultScanDirectories(android = true))

        assertEquals(setOf(ScanFormat.NCM), state.selectedFormats)
        val directory = state.directories.getValue(ScanFormat.NCM)
        assertEquals(ANDROID_NCM_SCAN_DIRECTORY, directory.path)
        assertTrue(directory.suggested)
        assertTrue(directory.selectable)
        assertFalse(directory.configured)
        assertNull(directory.treeUri)
        assertFalse(state.directories.getValue(ScanFormat.KGG).selectable)
        assertFalse(state.directories.getValue(ScanFormat.KGM).selectable)
        assertTrue(DirectoryScanUiState(directories = defaultScanDirectories(android = false)).selectedFormats.isEmpty())
    }

    @Test
    fun suggestedNcmCanBeDeselectedAndSelectedBeforeAuthorization() = runBlocking {
        val controller = controller(this, RecordingBrowser())

        controller.selectFormat(ScanFormat.NCM, false)
        assertTrue(controller.state.selectedFormats.isEmpty())
        controller.selectFormat(ScanFormat.NCM, true)
        controller.selectFormat(ScanFormat.KGG, true)
        assertEquals(setOf(ScanFormat.NCM), controller.state.selectedFormats)
    }

    @Test
    fun suggestedDirectoryIsNotReadBeforeAuthorization() = runBlocking {
        val browser = RecordingBrowser()
        val controller = controller(this, browser)

        controller.start(controller.state.selectedFormats)

        assertTrue(browser.roots.isEmpty())
        assertFalse(controller.state.isScanning)
        assertTrue(controller.state.files.isEmpty())
    }

    @Test
    fun authorizedDirectoryIsSavedAndScanned() = runBlocking {
        val browser = RecordingBrowser()
        val saved = mutableMapOf<ScanFormat, ScanDirectory>()
        val controller = controller(this, browser) { format, directory -> saved[format] = directory }
        val directory = ScanDirectory(ANDROID_NCM_SCAN_DIRECTORY, "content://provider/tree/music")

        controller.setDirectory(ScanFormat.NCM, directory)
        controller.start(controller.state.selectedFormats)
        coroutineContext.job.children.forEach { it.join() }

        assertEquals(directory, saved[ScanFormat.NCM])
        assertEquals(listOf(directory), browser.roots)
        assertEquals(listOf("song.ncm"), controller.state.files.map { it.name })
        assertEquals(listOf("song"), controller.selectedSources())
        assertFalse(controller.state.isScanning)
        assertNull(controller.state.message)
    }

    @Test
    fun clearingTheDefaultDirectoryDisablesItsFormat() = runBlocking {
        val controller = controller(this, RecordingBrowser())

        controller.setDirectory(ScanFormat.NCM, ScanDirectory())
        controller.selectFormat(ScanFormat.NCM, true)

        assertTrue(controller.state.selectedFormats.isEmpty())
        assertFalse(controller.state.directories.getValue(ScanFormat.NCM).selectable)
    }

    private fun controller(
        scope: CoroutineScope,
        browser: ScanDirectoryBrowser,
        save: (ScanFormat, ScanDirectory) -> Unit = { _, _ -> },
    ) = DirectoryScanController(
        DirectoryScanService(browser), scope, Dispatchers.Unconfined, Dispatchers.Unconfined,
        defaultScanDirectories(android = true), save,
    )

    private class RecordingBrowser : ScanDirectoryBrowser {
        val roots = mutableListOf<ScanDirectory>()

        override fun root(directory: ScanDirectory): ScanNode {
            roots.add(directory)
            return ScanNode("root", "root", "Music", directory.path, isDirectory = true)
        }

        override suspend fun children(directory: ScanNode): List<ScanNode> =
            listOf(ScanNode("song", "song", "song.ncm", "${directory.path}/song.ncm", isDirectory = false))
    }
}
