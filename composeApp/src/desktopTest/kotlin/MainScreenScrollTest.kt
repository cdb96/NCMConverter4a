package com.cdb96.ncmconverter4a.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.scrollBy
import androidx.compose.ui.unit.dp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MainScreenScrollTest {

    @Composable
    private fun TestMainScreen(state: ConversionUiState) {
        MaterialTheme {
            MainScreen(
                conversionState = state,
                settingsState = SettingsUiState(outputDirectory = "C:\\out"),
                desktopMode = true,
                onRawWriteModeChange = {},
                onDuplicateConflictMitigationChange = {},
                onThreadCountChange = {},
                onPickFiles = {},
                onBenchmark = {},
                onSelectKggDatabase = {},
            )
        }
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.wheel() {
        val scrollable = onAllNodes(hasScrollAction()).onFirst()
        repeat(6) { scrollable.scrollBy(y = 240.dp, density = density) }
        repeat(6) { scrollable.scrollBy(y = (-240).dp, density = density) }
        waitForIdle()
    }

    @Test
    fun wheelScrollFreshConversionPage() = runDesktopComposeUiTest(width = 1280, height = 600) {
        setContent { TestMainScreen(ConversionUiState()) }
        wheel()
    }

    @Test
    fun wheelScrollAfterConversionDone() = runDesktopComposeUiTest(width = 1280, height = 600) {
        setContent {
            TestMainScreen(
                ConversionUiState(
                    hasConversionStarted = true,
                    convertResult = "done",
                    successCount = 2,
                    failureCount = 1,
                    successfulFileNames = listOf("a.mp3", "b.mp3"),
                    failedFileNames = listOf("c.ncm"),
                    conversionDurationMillis = 1234,
                    currentFile = List(40) { "文件 $it.mp3" }.joinToString("\n"),
                )
            )
        }
        wheel()
    }

    @Test
    fun wheelScrollErrorState() = runDesktopComposeUiTest(width = 1280, height = 600) {
        setContent {
            TestMainScreen(
                ConversionUiState(
                    hasConversionStarted = true,
                    convertResult = "转换失败: " + "错误详情 ".repeat(60),
                )
            )
        }
        wheel()
    }

    @Test
    fun wheelScrollSettingsPage() = runDesktopComposeUiTest(width = 1280, height = 600) {
        setContent { TestMainScreen(ConversionUiState()) }
        onAllNodesWithText("设置").onLast().performClick()
        waitForIdle()
        wheel()
    }

    @Test
    fun wheelScrollNarrowWindow() = runDesktopComposeUiTest(width = 500, height = 600) {
        setContent {
            TestMainScreen(
                ConversionUiState(
                    hasConversionStarted = true,
                    convertResult = "done",
                    successCount = 1,
                    currentFile = List(40) { "文件 $it.mp3" }.joinToString("\n"),
                )
            )
        }
        wheel()
    }
}