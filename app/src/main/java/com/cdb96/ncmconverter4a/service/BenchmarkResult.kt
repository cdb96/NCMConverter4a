package com.cdb96.ncmconverter4a.service

/**
 * 基准测试结果数据类
 * @param kgmResults KGM解密性能测试结果
 * @param ncmBufferResults NCM解密性能测试结果 (ByteBuffer模式)
 * @param ncmArrayResults NCM解密性能测试结果 (ByteArray模式)
 * @param totalDuration 总测试耗时 (毫秒)
 */
data class BenchmarkResult(
    val kgmResults: List<SizeThroughput>,
    val ncmBufferResults: List<SizeThroughput>,
    val ncmArrayResults: List<SizeThroughput>,
    val totalDuration: Long
)

data class SizeThroughput(
    val sizeKB: Int,
    val throughputKBps: Double,
    val durationMs: Long
)

/**
 * 测试状态枚举
 */
enum class TestStatus {
    IDLE,           // 空闲
    WARMING,        // 预热中
    TESTING_KGM,    // 测试KGM解密
    TESTING_NCM_BUFFER, // 测试NCM解密 (ByteBuffer)
    TESTING_NCM_ARRAY,  // 测试NCM解密 (ByteArray)
    COMPLETED       // 测试完成
}

