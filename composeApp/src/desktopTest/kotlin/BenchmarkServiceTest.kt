package com.cdb96.ncmconverter4a.service

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkServiceTest {
    @Test
    fun benchmarkCompletesWithBothKggModesAndPreservesExistingResults() = runBlocking {
        val statuses = mutableListOf<TestStatus>()
        val result = BenchmarkService().runBenchmark { statuses.add(it) }

        assertEquals(
            listOf(TestStatus.WARMING, TestStatus.TESTING_KGM, TestStatus.TESTING_NCM,
                TestStatus.TESTING_KGG, TestStatus.COMPLETED),
            statuses,
        )
        for (results in listOf(result.kgmResults, result.ncmResults,
            result.kggRc4Results, result.kggMapResults)) {
            assertEquals(listOf(256, 1024), results.map { it.sizeKB })
            assertTrue(results.all { it.throughputKBps.isFinite() && it.throughputKBps > 0.0 })
            assertTrue(results.all { it.durationMs >= 0 })
        }
        assertTrue(result.totalDuration >= 0)
    }
}
