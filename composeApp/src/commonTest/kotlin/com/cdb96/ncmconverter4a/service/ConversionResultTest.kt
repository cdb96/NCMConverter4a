package com.cdb96.ncmconverter4a.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversionResultTest {
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
