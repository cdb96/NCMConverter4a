package com.cdb96.ncmconverter4a

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.cdb96.ncmconverter4a.service.*
import com.cdb96.ncmconverter4a.ui.screens.*
import com.cdb96.ncmconverter4a.ui.theme.NCMConverter4aTheme
import kotlinx.coroutines.*
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DirectoryScanUiTest {
    @Test
    fun scanNavigationSelectionAndConversionUseTheCheckedFiles() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val browser = object : ScanDirectoryBrowser {
            override fun root(directory: ScanDirectory) = ScanNode("root", "root", "root", directory.path, true)
            override suspend fun children(directory: ScanNode) = listOf(
                ScanNode("one", "source-one", "one.ncm", "one.ncm", false),
                ScanNode("two", "source-two", "two.ncm", "two.ncm", false),
            )
        }
        val controller = DirectoryScanController(DirectoryScanService(browser), scope,
            Dispatchers.Unconfined, Dispatchers.Unconfined, defaultScanDirectories(true), { _, _ -> })
        var requestedFormats: Set<ScanFormat>? = null
        var requestedDirectory: ScanFormat? = null
        var convertedSources: List<String>? = null
        val scene = ImageComposeScene(width = 390, height = 1000)
        var frame = 0L
        fun render() { frame += 16000000; scene.render(frame).close() }
        fun nodes(): List<SemanticsNode> {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            return scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
        }
        fun click(text: String, role: Role? = null) {
            val node = nodes().firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                    node.config.getOrNull(SemanticsProperties.Disabled) == null &&
                    (role == null || node.config.getOrNull(SemanticsProperties.Role) == role) &&
                    node.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            assertNotNull(node, "Missing clickable UI: $text")
            assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            render()
        }
        try {
            scene.setContent {
                NCMConverter4aTheme(darkTheme = false) {
                    MainScreen(ConversionUiState(), SettingsUiState(),
                        onRawWriteModeChange = {}, onDuplicateConflictMitigationChange = {}, onThreadCountChange = {},
                        onPickFiles = {}, onBenchmark = {}, onSelectKggDatabase = {}, scanController = controller,
                        onSelectScanDirectory = { requestedDirectory = it },
                        onScanDirectories = { requestedFormats = it }, onConvertScannedFiles = { convertedSources = it })
                }
            }
            render()
            click("扫描", Role.Tab)
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "NCM 目录" } == true })
            click("配置扫描目录")
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == ANDROID_NCM_SCAN_DIRECTORY } == true })
            click("选择 KGG 目录")
            assertEquals(ScanFormat.KGG, requestedDirectory)
            click("扫描", Role.Tab)
            click("扫描全部")
            assertEquals(ScanFormat.entries.toSet(), requestedFormats)
            click("扫描 NCM")
            assertEquals(setOf(ScanFormat.NCM), requestedFormats)
            controller.start(setOf(ScanFormat.NCM))
            render()
            click("one.ncm")
            assertEquals(listOf("source-two"), controller.selectedSources())
            click("转换 (1)")
            assertEquals(listOf("source-two"), convertedSources)
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "添加音乐，开始转换" } == true })
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
