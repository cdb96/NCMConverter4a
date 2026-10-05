package com.cdb96.ncmconverter4a

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.cdb96.ncmconverter4a.service.*
import com.cdb96.ncmconverter4a.ui.screens.*
import com.cdb96.ncmconverter4a.ui.theme.NCMConverter4aTheme
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DirectoryScanUiTest {
    @Test
    fun desktopDirectoryDialogKeepsTheCurrentPageAndSavesFolderChanges() {
        for ((width, height) in listOf(1180 to 800, 600 to 600)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val browser = object : ScanDirectoryBrowser {
                override fun root(directory: ScanDirectory) = ScanNode("root", "root", "root", directory.path, true)
                override suspend fun children(directory: ScanNode) = emptyList<ScanNode>()
            }
            val savedDirectories = mutableMapOf<ScanFormat, ScanDirectory>()
            val ncmDirectory = ScanDirectory("C:\\Users\\音乐用户\\Music\\网易云音乐\\下载的音乐文件\\专辑与歌手\\收藏的完整无损音乐目录")
            val controller = DirectoryScanController(DirectoryScanService(browser), scope,
                Dispatchers.Unconfined, Dispatchers.Unconfined,
                defaultScanDirectories(false) + (ScanFormat.NCM to ncmDirectory),
                { format, directory -> savedDirectories[format] = directory })
            val scene = ImageComposeScene(width = width, height = height)
            var frame = 0L
            fun render() { frame += 16000000; scene.render(frame).close() }
            fun nodes(): List<SemanticsNode> {
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                return scene.semanticsOwners.flatMap { collect(it.rootSemanticsNode) }
            }
            fun hasText(text: String) = nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
            }
            fun click(text: String, description: Boolean = false) {
                val node = nodes().firstOrNull { node ->
                    (if (description) node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
                    else node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) &&
                        node.config.getOrNull(SemanticsProperties.Disabled) == null &&
                        node.config.getOrNull(SemanticsActions.OnClick)?.action != null
                }
                assertNotNull(node, "Missing clickable UI: $text ($width x $height)")
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
                render()
            }
            try {
                scene.setContent {
                    NCMConverter4aTheme(darkTheme = false) {
                        MainScreen(ConversionUiState(), SettingsUiState(), desktopMode = true,
                            onRawWriteModeChange = {}, onDuplicateConflictMitigationChange = {}, onThreadCountChange = {},
                            onPickFiles = {}, onBenchmark = {}, onSelectKggDatabase = {}, scanController = controller,
                            onSelectScanDirectory = { controller.setDirectory(it, ScanDirectory("D:\\酷狗音乐\\${it.name}")) })
                    }
                }
                render()
                click("扫描")
                controller.showMessage("扫描完成，共 0 个文件")
                click("配置扫描目录")
                assertTrue(hasText("NCM 目录"))
                assertTrue(hasText(ncmDirectory.path))
                assertFalse(hasText("扫描完成，共 0 个文件"))
                click("选择 KGG 目录")
                assertEquals(ScanDirectory("D:\\酷狗音乐\\KGG"), savedDirectories[ScanFormat.KGG])
                assertTrue(hasText("D:\\酷狗音乐\\KGG"))
                repeat(30) { render() }
                val screenshot = File("build/scan-directory-dialog-$width.png")
                screenshot.parentFile.mkdirs()
                scene.render(frame).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { screenshot.writeBytes(it.bytes) }
                }
                click("清除 KGG 扫描目录", description = true)
                assertEquals(ScanDirectory(), savedDirectories[ScanFormat.KGG])
                assertFalse(controller.state.directories.getValue(ScanFormat.KGG).configured)
                click("完成")
                assertFalse(hasText("NCM 目录"))
                assertTrue(hasText("查找音乐文件"))
                click("设置")
                assertFalse(hasText("NCM 目录"))
                assertFalse(hasText("扫描目录"))
                assertFalse(hasText("配置扫描目录"))
                assertTrue(hasText("KGG 数据库"))
                click("扫描")
                click("配置扫描目录")
                assertTrue(hasText("NCM 目录"))
                click("关闭扫描目录配置", description = true)
                assertFalse(hasText("NCM 目录"))
                assertTrue(hasText("查找音乐文件"))
            } finally {
                scene.close()
                scope.cancel()
            }
        }
    }

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
