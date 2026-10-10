package com.cdb96.ncmconverter4a

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
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
    fun resultNamesWrapToTwoLinesAndConversionStaysVisibleWhileScrolling() {
        val shortName = "夜曲.ncm"
        val wrappingName = "歌手 - 这是一首需要换行的音乐文件.ncm"
        val overflowingName = "非常长的音乐文件名".repeat(10) + ".ncm"
        val names = listOf(shortName, wrappingName, overflowingName) + List(30) { "音乐文件 $it.ncm" }
        val files = names.mapIndexed { index, name ->
            ScannedAudioFile("file-$index", "source-$index", name, "/sdcard/Music/收藏", ScanFormat.NCM, 4 * 1024 * 1024L)
        }
        val state = DirectoryScanUiState(
            directories = mapOf(ScanFormat.NCM to ScanDirectory("/sdcard/Music/收藏")),
            files = files,
            selectedIds = files.map { it.id }.toSet(),
        )
        for ((width, height) in listOf(320 to 600, 390 to 780)) {
            val scene = ImageComposeScene(width = width, height = height)
            var frame = 0L
            var converted = false
            fun render() { frame += 16000000; scene.render(frame).close() }
            fun nodes(merged: Boolean = true): List<SemanticsNode> {
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                return scene.semanticsOwners.flatMap {
                    collect(if (merged) it.rootSemanticsNode else it.unmergedRootSemanticsNode)
                }
            }
            fun textNode(text: String, merged: Boolean = true) = nodes(merged).firstOrNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }.let { assertNotNull(it, "Missing text: $text ($width x $height)") }
            fun textLayout(text: String): TextLayoutResult {
                val layouts = mutableListOf<TextLayoutResult>()
                val action = textNode(text, merged = false).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                assertNotNull(action)
                assertTrue(action(layouts))
                return layouts.single()
            }
            fun capture(name: String) {
                scene.render(frame).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        File("build/$name-$width.png").apply { parentFile.mkdirs() }.writeBytes(it.bytes)
                    }
                }
            }
            try {
                scene.setContent {
                    NCMConverter4aTheme(darkTheme = false) {
                        Surface(Modifier.fillMaxSize()) {
                            DirectoryScanScreen(state, enabled = true, onConfigureDirectories = {}, onScan = {},
                                onCancel = {}, onFormatSelectionChange = { _, _ -> }, onRecursiveChange = {},
                                onSelectFile = { _, _ -> }, onSelectAll = {}, onConvert = { converted = true })
                        }
                    }
                }
                repeat(30) { render() }
                ScanFormat.entries.forEach { format ->
                    textLayout(format.name).let {
                        assertEquals(1, it.lineCount)
                        assertFalse(it.multiParagraph.didExceedMaxLines)
                        assertEquals(format.name.length, it.getLineEnd(0, visibleEnd = true))
                    }
                }
                assertEquals(1, textLayout(shortName).lineCount)
                textLayout(wrappingName).let {
                    assertEquals(2, it.lineCount)
                    assertFalse(it.hasVisualOverflow)
                    assertEquals(wrappingName.length, it.getLineEnd(1, visibleEnd = true))
                }
                textLayout(overflowingName).let {
                    assertEquals(2, it.lineCount)
                    // Skia's isLineEllipsized always returns false; check the visible text boundary instead.
                    assertTrue(it.hasVisualOverflow)
                    assertTrue(it.getLineEnd(1, visibleEnd = true) < overflowingName.length)
                }
                val buttonText = "转换 (${files.size})"
                val buttonBounds = textNode(buttonText).boundsInRoot
                assertTrue(buttonBounds.bottom <= height)
                assertTrue(buttonBounds.top >= height - 64)
                capture("scan-page-long-names")

                val scroll = nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null }
                assertTrue(scroll.config[SemanticsActions.ScrollToIndex].action!!.invoke(files.size + 1))
                repeat(30) { render() }
                assertEquals(buttonBounds, textNode(buttonText).boundsInRoot)
                assertTrue(textNode(files.last().name).boundsInRoot.bottom <= buttonBounds.top)
                capture("scan-page-scrolled")
                assertTrue(textNode(buttonText).config[SemanticsActions.OnClick].action!!.invoke())
                assertTrue(converted)
            } finally {
                scene.close()
            }
        }
    }

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
                controller.showMessage("扫描失败: 目录无法读取")
                click("配置扫描目录")
                assertTrue(hasText("NCM 目录"))
                assertTrue(hasText(ncmDirectory.path))
                assertFalse(hasText("扫描失败: 目录无法读取"))
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
                repeat(30) { render() }
                scene.render(frame).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        File("build/scan-page-$width.png").writeBytes(it.bytes)
                    }
                }
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
        val scene = ImageComposeScene(width = 390, height = 780)
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
                        onSelectScanDirectory = {
                            requestedDirectory = it
                            controller.setDirectory(it, ScanDirectory("/sdcard/Download/酷狗音乐/${it.name}", "content://test/tree/${it.name}"))
                        },
                        onScanDirectories = { requestedFormats = it }, onConvertScannedFiles = { convertedSources = it })
                }
            }
            render()
            click("扫描", Role.Tab)
            repeat(30) { render() }
            scene.render(frame).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    File("build/scan-page-android-empty.png").apply { parentFile.mkdirs() }.writeBytes(it.bytes)
                }
            }
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "NCM 目录" } == true })
            click("配置扫描目录")
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "建议目录：$ANDROID_NCM_SCAN_DIRECTORY" } == true })
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("清除 NCM 扫描目录") == true })
            click("选择 KGG 目录")
            assertEquals(ScanFormat.KGG, requestedDirectory)
            assertEquals("/sdcard/Download/酷狗音乐/KGG", controller.state.directories[ScanFormat.KGG]?.path)
            repeat(30) { render() }
            val screenshot = File("build/scan-directory-dialog-android.png")
            screenshot.parentFile.mkdirs()
            scene.render(frame).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { screenshot.writeBytes(it.bytes) }
            }
            click("完成")
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "NCM 目录" } == true })
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "查找音乐文件" } == true })
            click("设置", Role.Tab)
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "扫描目录" || text.text == "配置扫描目录" } == true })
            click("扫描", Role.Tab)
            click("开始扫描")
            assertEquals(setOf(ScanFormat.KGG), requestedFormats)
            click("配置扫描目录")
            click("选择 NCM 目录")
            assertEquals(ScanFormat.NCM, requestedDirectory)
            click("完成")
            click("开始扫描")
            assertEquals(setOf(ScanFormat.NCM, ScanFormat.KGG), requestedFormats)
            repeat(30) { render() }
            scene.render(frame).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    File("build/scan-page-android-formats.png").writeBytes(it.bytes)
                }
            }
            click("KGG")
            assertEquals(setOf(ScanFormat.NCM), controller.state.selectedFormats)
            assertEquals(setOf(ScanFormat.NCM, ScanFormat.KGG), requestedFormats)
            click("设置", Role.Tab)
            click("扫描", Role.Tab)
            assertEquals(setOf(ScanFormat.NCM), controller.state.selectedFormats)
            click("NCM")
            assertTrue(controller.state.selectedFormats.isEmpty())
            assertTrue(nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "开始扫描" } == true &&
                    node.config.getOrNull(SemanticsProperties.Disabled) != null
            })
            click("NCM")
            click("开始扫描")
            assertEquals(setOf(ScanFormat.NCM), requestedFormats)
            controller.start(setOf(ScanFormat.NCM))
            render()
            repeat(30) { render() }
            scene.render(frame).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    File("build/scan-page-android-results.png").writeBytes(it.bytes)
                }
            }
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
