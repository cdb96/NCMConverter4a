package com.cdb96.ncmconverter4a.service

data class BenchmarkResult(
    val kgmResults: List<SizeThroughput>,
    val ncmResults: List<SizeThroughput>,
    val totalDuration: Long
)

data class SizeThroughput(
    val sizeKB: Int,
    val throughputKBps: Double,
    val durationMs: Long
)

enum class TestStatus {
    IDLE,
    WARMING,
    TESTING_KGM,
    TESTING_NCM,
    COMPLETED
}

data class ConversionResult(
    val successCount: Int,
    val failureCount: Int,
    val durationMillis: Long,
    val allFileNames: String,
    val successfulFileNames: List<String>,
    val failedFileNames: List<String>
)
