package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.service.*
import com.cdb96.ncmconverter4a.ui.screens.DirectoryScanController
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DirectoryScanTest {
    @Test
    fun defaultAndroidDirectoryAndUnconfiguredKugouFormats() {
        val defaults = defaultScanDirectories(android = true)
        assertEquals("/sdcard/Download/netease/cloudmusic/Music", defaults[ScanFormat.NCM]?.path)
        assertNull(defaults[ScanFormat.NCM]?.treeUri)
        assertFalse(defaults.getValue(ScanFormat.KGG).configured)
        assertFalse(defaults.getValue(ScanFormat.KGM).configured)
        assertTrue(defaultScanDirectories(android = false).values.none { it.configured })
    }

    @Test
    fun realDirectoriesMatchAllFormatsOnceAndIncludeUnicodeSubdirectories() = runBlocking {
        withFiles { root ->
            File(root, "One.NCM").writeText("one")
            File(root, "Two.kGg").writeText("two")
            File(root, "Three.KGM").writeText("three")
            File(root, "ignored.ncm.bak").writeText("ignored")
            val child = File(root, "中文/目录.ncm").apply { mkdirs() }
            File(child, "歌曲.ncm").writeText("nested")
            val directories = ScanFormat.entries.associateWith { ScanDirectory(root.path) }
            val calls = mutableListOf<String>()
            val delegate = DesktopDirectoryBrowser()
            val browser = object : ScanDirectoryBrowser by delegate {
                override suspend fun children(directory: ScanNode): List<ScanNode> {
                    calls.add(directory.id)
                    return delegate.children(directory)
                }
            }
            var visited = 0
            var found = 0
            val result = DirectoryScanService(browser).scan(directories, true) { count, files -> visited = count; found = files }
            assertEquals(setOf("One.NCM", "Two.kGg", "Three.KGM", "歌曲.ncm"), result.files.map { it.name }.toSet())
            assertEquals(3, calls.size)
            assertEquals(calls.size, calls.distinct().size)
            assertEquals(3, visited)
            assertEquals(4, found)
            assertTrue(result.warnings.isEmpty())
            assertEquals(2, result.files.count { it.format == ScanFormat.NCM })
        }
    }

    @Test
    fun nonrecursiveScanAndFormatDirectoryFilterAreRespected() = runBlocking {
        withFiles { root ->
            File(root, "top.ncm").writeText("top")
            File(root, "top.kgg").writeText("different format")
            File(File(root, "nested").apply { mkdirs() }, "nested.ncm").writeText("nested")
            val result = DirectoryScanService(DesktopDirectoryBrowser()).scan(mapOf(ScanFormat.NCM to ScanDirectory(root.path)), false)
            assertEquals(listOf("top.ncm"), result.files.map { it.name })
        }
    }

    @Test
    fun overlappingTreesDeduplicateStableIdsAndDirectoryCyclesTerminate() = runBlocking {
        val browser = object : ScanDirectoryBrowser {
            override fun root(directory: ScanDirectory) = folder(directory.path)
            override suspend fun children(directory: ScanNode) = when (directory.id) {
                "a" -> listOf(folder("b"), audio("shared", "song.ncm"))
                "b" -> listOf(folder("a"), audio("shared", "song.ncm"), audio("other", "song.kgg"))
                else -> emptyList()
            }
        }
        val result = DirectoryScanService(browser).scan(mapOf(ScanFormat.NCM to ScanDirectory("a"), ScanFormat.KGG to ScanDirectory("b")), true)
        assertEquals(setOf("shared", "other"), result.files.map { it.id }.toSet())
        assertEquals(2, result.files.size)
    }

    @Test
    fun inaccessibleFolderProducesWarningWithoutLosingReadableFiles() = runBlocking<Unit> {
        val browser = object : ScanDirectoryBrowser {
            override fun root(directory: ScanDirectory) = folder(directory.path)
            override suspend fun children(directory: ScanNode): List<ScanNode> {
                if (directory.id == "blocked") throw SecurityException("permission revoked")
                return listOf(audio("song", "song.ncm"), folder("blocked"))
            }
        }
        val result = DirectoryScanService(browser).scan(mapOf(ScanFormat.NCM to ScanDirectory("root")), true)
        assertEquals(1, result.files.size)
        assertTrue(result.warnings.single().contains("permission revoked"))
        val cancelled = object : ScanDirectoryBrowser by browser {
            override suspend fun children(directory: ScanNode): List<ScanNode> = throw CancellationException()
        }
        assertFailsWith<CancellationException> {
            DirectoryScanService(cancelled).scan(mapOf(ScanFormat.NCM to ScanDirectory("root")), true)
        }
    }

    @Test
    fun directoriesPersistIndependentlyIncludingClearedSelections() {
        withFiles { root ->
            val preferenceFile = File(root, "settings/scan.properties").toPath()
            val preference = DesktopScanDirectoryPreference(preferenceFile)
            val ncm = File(root, "网易云").apply { mkdirs() }
            val kgg = File(root, "酷狗").apply { mkdirs() }
            preference.save(ScanFormat.NCM, ScanDirectory(ncm.path))
            preference.save(ScanFormat.KGG, ScanDirectory(kgg.path))
            val restored = DesktopScanDirectoryPreference(preferenceFile).load()
            assertEquals(ncm.path, restored[ScanFormat.NCM]?.path)
            assertEquals(kgg.path, restored[ScanFormat.KGG]?.path)
            assertFalse(restored.getValue(ScanFormat.KGM).configured)
            preference.save(ScanFormat.NCM, ScanDirectory())
            assertFalse(preference.load().getValue(ScanFormat.NCM).configured)
            assertEquals(kgg.path, preference.load()[ScanFormat.KGG]?.path)
        }
    }

    @Test
    fun selectionFeedsOnlyCheckedSourcesAndChangingDirectoryClearsOldResults() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val browser = object : ScanDirectoryBrowser {
                override fun root(directory: ScanDirectory) = folder(directory.path)
                override suspend fun children(directory: ScanNode) = listOf(audio("one", "one.ncm"), audio("two", "two.ncm"))
            }
            val controller = DirectoryScanController(DirectoryScanService(browser), scope,
                Dispatchers.Unconfined, Dispatchers.Unconfined, mapOf(ScanFormat.NCM to ScanDirectory("root")), { _, _ -> })
            controller.start(setOf(ScanFormat.NCM))
            assertFalse(controller.state.isScanning)
            assertEquals(2, controller.selectedSources().size)
            controller.selectFile("one", false)
            assertEquals(listOf("source-two"), controller.selectedSources())
            controller.selectAll(false)
            assertTrue(controller.selectedSources().isEmpty())
            controller.selectAll(true)
            assertEquals(2, controller.selectedSources().size)
            controller.setDirectory(ScanFormat.NCM, ScanDirectory("new-root"))
            assertTrue(controller.state.files.isEmpty())
            assertTrue(controller.selectedSources().isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    fun cancelledScanRestoresControlsAndDoesNotPublishIncompleteResults() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pending = CompletableDeferred<Unit>()
            val browser = object : ScanDirectoryBrowser {
                override fun root(directory: ScanDirectory) = folder(directory.path)
                override suspend fun children(directory: ScanNode): List<ScanNode> { pending.await(); return emptyList() }
            }
            val controller = DirectoryScanController(DirectoryScanService(browser), scope,
                Dispatchers.Unconfined, Dispatchers.Unconfined, mapOf(ScanFormat.NCM to ScanDirectory("root")), { _, _ -> })
            controller.start(setOf(ScanFormat.NCM))
            assertTrue(controller.state.isScanning)
            controller.cancel()
            assertFalse(controller.state.isScanning)
            assertEquals("已取消扫描", controller.state.message)
            assertTrue(controller.state.files.isEmpty())
        } finally { scope.cancel() }
    }

    private fun folder(id: String) = ScanNode(id, id, id, id, true)
    private fun audio(id: String, name: String) = ScanNode(id, "source-$id", name, name, false, 123L)
    private inline fun withFiles(test: (File) -> Unit) {
        val root = Files.createTempDirectory("directory-scan").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
