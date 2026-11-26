package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.scrollBy
import androidx.compose.ui.unit.dp
import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanFormat
import com.cdb96.ncmconverter4a.service.ScannedAudioFile
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DirectoryScanScreenTest {

    @Test
    fun formatFileSizeFormatsExpectedUnits() {
        assertEquals("0 KB", formatFileSize(0))
        assertEquals("< 1 KB", formatFileSize(500))
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("2 KB", formatFileSize(1536))
        assertEquals("1.0 MB", formatFileSize(1024L * 1024L))
        assertEquals("3.5 MB", formatFileSize(3_670_016L))
    }

    @Test
    fun wideScreenShowsInlineDirectoryCardAndSupportsActions() = runDesktopComposeUiTest(width = 1280, height = 720) {
        val selected = mutableListOf<ScanFormat>()
        val cleared = mutableListOf<ScanFormat>()
        val recursiveChanges = mutableListOf<Boolean>()
        val directories = mapOf(
            ScanFormat.NCM to ScanDirectory("C:\\Music\\NCM"),
            ScanFormat.KGG to ScanDirectory(),
            ScanFormat.KGM to ScanDirectory(),
        )
        setContent {
            TestScanScreen(
                state = DirectoryScanUiState(directories = directories),
                onSelectDirectory = { selected += it },
                onClearDirectory = { cleared += it },
                onRecursiveChange = { recursiveChanges += it },
            )
        }

        onNodeWithText("扫描目录").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("配置扫描目录").fetchSemanticsNodes().size)

        onAllNodesWithText("选择").onFirst().performClick()
        onNodeWithContentDescription("清除 NCM 扫描目录").performClick()
        onNodeWithText("包含子目录").performClick()
        waitForIdle()

        assertEquals(listOf(ScanFormat.KGG), selected)
        assertEquals(listOf(ScanFormat.NCM), cleared)
        assertEquals(listOf(false), recursiveChanges)
    }

    @Test
    fun mediumWideScreenAlsoShowsInlineDirectoryCard() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent {
            TestScanScreen(state = DirectoryScanUiState())
        }

        onNodeWithText("扫描目录").assertIsDisplayed()
        onNodeWithText("尚未配置扫描目录").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("配置扫描目录").fetchSemanticsNodes().size)
    }

    @Test
    fun wideScreenResultsCardShowsSummaryAndSupportsWheelScroll() = runDesktopComposeUiTest(width = 1280, height = 720) {
        val files = List(30) { index ->
            ScannedAudioFile(
                id = "id-$index",
                source = "C:\\Music\\song-$index.ncm",
                name = "Track $index.ncm",
                directory = "C:\\Music",
                format = ScanFormat.NCM,
                size = 3_670_016L,
            )
        }
        setContent {
            TestScanScreen(
                state = DirectoryScanUiState(
                    directories = mapOf(ScanFormat.NCM to ScanDirectory("C:\\Music")),
                    files = files,
                    selectedIds = setOf("id-0", "id-1"),
                ),
            )
        }

        onNodeWithText("已选 2 / 30").assertIsDisplayed()
        onAllNodesWithText("3.5 MB").onFirst().assertIsDisplayed()

        val resultsList = onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            .indices
            .map { onAllNodes(hasScrollAction())[it] }
            .last()
        repeat(4) { resultsList.scrollBy(y = 240.dp, density = density) }
        repeat(4) { resultsList.scrollBy(y = (-240).dp, density = density) }
        waitForIdle()
    }

    @Test
    fun narrowScreenUsesDialogTriggerInsteadOfInlineDirectoryCard() = runDesktopComposeUiTest(width = 500, height = 600) {
        setContent {
            TestScanScreen(state = DirectoryScanUiState())
        }

        onNodeWithText("配置扫描目录").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("扫描目录").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithContentDescription("清除 NCM 扫描目录").fetchSemanticsNodes().size)
    }

    @Composable
    private fun TestScanScreen(
        state: DirectoryScanUiState,
        onSelectDirectory: (ScanFormat) -> Unit = {},
        onClearDirectory: (ScanFormat) -> Unit = {},
        onRecursiveChange: (Boolean) -> Unit = {},
    ) {
        MaterialTheme {
            DirectoryScanScreen(
                state = state,
                enabled = true,
                onConfigureDirectories = {},
                onSelectDirectory = onSelectDirectory,
                onClearDirectory = onClearDirectory,
                onScan = {},
                onCancel = {},
                onFormatSelectionChange = { _, _ -> },
                onRecursiveChange = onRecursiveChange,
                onSelectFile = { _, _ -> },
                onSelectAll = {},
                onConvert = {},
            )
        }
    }
}
