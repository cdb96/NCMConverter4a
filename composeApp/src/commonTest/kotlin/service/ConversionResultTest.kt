package com.cdb96.ncmconverter4a.service

import com.cdb96.ncmconverter4a.ui.screens.ConversionUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversionResultTest {
    @Test
    fun mixedBatchPreservesConflictAndFailureReasonsThroughUiState() {
        val conflict = FileConversionResult(
            "same.ncm", false, "目标文件已存在：Music/same.mp3", outputAlreadyExists = true,
        )
        val invalid = FileConversionResult("same.ncm", false, "文件已损坏")
        val results = listOf(FileConversionResult("good.ncm", true), conflict, invalid)
        val summary = ConversionResult.from(results, 42, results.map { it.fileName })
        val ui = ConversionUiState().start(3).complete(summary)

        assertEquals(1, ui.successCount)
        assertEquals(2, ui.failureCount)
        assertEquals(1, ui.existingFileCount)
        assertEquals(listOf(conflict, invalid), ui.failedFiles)
        assertEquals(listOf("same.ncm", "same.ncm"), ui.failedFileNames)
        assertEquals(emptyList(), ui.start(1).failedFiles)
        assertEquals(0, ui.start(1).existingFileCount)
    }

    @Test
    fun summaryPreservesOrderDuplicatesAndSuppliedSourceNames() {
        val result = ConversionResult.from(
            results = listOf(
                FileConversionResult("same.ncm", true),
                FileConversionResult("bad.ncm", false),
                FileConversionResult("same.ncm", true),
                FileConversionResult("other.ncm", false),
            ),
            durationMillis = 123,
            sourceNames = listOf("same.ncm", "bad.ncm", "other.ncm"),
        )
        assertEquals(
            ConversionResult(
                successCount = 2,
                failureCount = 2,
                durationMillis = 123,
                allFileNames = "same.ncm, bad.ncm, other.ncm",
                successfulFileNames = listOf("same.ncm", "same.ncm"),
                failedFileNames = listOf("bad.ncm", "other.ncm"),
                failedFiles = listOf(
                    FileConversionResult("bad.ncm", false),
                    FileConversionResult("other.ncm", false),
                ),
            ),
            result,
        )
    }

    @Test
    fun emptyBatchHasNoResults() {
        assertEquals(
            ConversionResult(0, 0, 0, "", emptyList(), emptyList()),
            ConversionResult.from(emptyList(), 0, emptyList()),
        )
    }
}
