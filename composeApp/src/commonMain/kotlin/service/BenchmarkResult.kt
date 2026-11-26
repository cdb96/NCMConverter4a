package com.cdb96.ncmconverter4a.service

data class BenchmarkResult(
    val kgmResults: List<SizeThroughput>,
    val ncmResults: List<SizeThroughput>,
    val kggRc4Results: List<SizeThroughput>,
    val kggMapResults: List<SizeThroughput>,
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
    TESTING_KGG,
    COMPLETED
}

data class ConversionResult(
    val durationMillis: Long,
    val allFileNames: String,
    val successfulFileNames: List<String>,
    val failedFiles: List<FileConversionResult> = emptyList(),
) {
    val successCount: Int get() = successfulFileNames.size
    val failureCount: Int get() = failedFiles.size
    val failedFileNames: List<String> get() = failedFiles.map { it.fileName }

    companion object {
        fun from(
            results: List<FileConversionResult>,
            durationMillis: Long,
            sourceNames: Collection<String>,
        ): ConversionResult {
            val (successful, failed) = results.partition { it.success }
            return ConversionResult(
                durationMillis = durationMillis,
                allFileNames = sourceNames.joinToString(", "),
                successfulFileNames = successful.map { it.fileName },
                failedFiles = failed,
            )
        }
    }
}

data class FileConversionResult(
    val fileName: String,
    val success: Boolean,
    val error: String? = null,
    val outputAlreadyExists: Boolean = false,
)
