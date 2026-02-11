package com.cdb96.ncmconverter4a.service

import android.util.Log
import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import com.cdb96.ncmconverter4a.util.DirectBufferPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Random

/**
 * 基准测试服务
 * 负责执行KGM和NCM解密性能测试
 */
class BenchmarkService {

    companion object {
        private const val TAG = "BenchmarkService"
        private const val ITERATIONS = 3 // 每个数据大小测试次数
        private const val POOL_SIZE_FOR_TEST = 1 // 测试期间 poolSize=1，slot 大小为 buffer 总容量
        private const val POOL_SIZE_AFTER_TEST = 4 // 测试后恢复为 poolSize=4
    }

    /**
     * 运行完整的基准测试
     * @param onProgress 进度回调
     * @return BenchmarkResult 测试结果
     */
    suspend fun runBenchmark(onProgress: (TestStatus) -> Unit): BenchmarkResult =
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()

            // 确保 DirectBufferPool 的 slot 足够大
            // 测试期间设置为 poolSize=1，这样每个 slot = 8MB
            DirectBufferPool.updateSlotBuffer(POOL_SIZE_FOR_TEST)

            try {
                // 步骤1: 预热
                onProgress(TestStatus.WARMING)
                warmup()

                // 步骤2: 测试KGM解密性能
                onProgress(TestStatus.TESTING_KGM)
                val kgmResults = testKGMPerformance()

                // 步骤3: 测试NCM解密性能 (ByteBuffer模式)
                onProgress(TestStatus.TESTING_NCM_BUFFER)
                val ncmBufferResults = testNCMBufferPerformance()

                // 步骤4: 测试NCM解密性能 (ByteArray模式)
                onProgress(TestStatus.TESTING_NCM_ARRAY)
                val ncmArrayResults = testNCMArrayPerformance()

                onProgress(TestStatus.COMPLETED)

                val totalDuration = System.currentTimeMillis() - startTime
                BenchmarkResult(
                    kgmResults = kgmResults,
                    ncmBufferResults = ncmBufferResults,
                    ncmArrayResults = ncmArrayResults,
                    totalDuration = totalDuration
                )
            } finally {
                // 测试完成后恢复为默认值，确保文件转换性能
                DirectBufferPool.updateSlotBuffer(POOL_SIZE_AFTER_TEST)
                Log.d(TAG, "基准测试完成，DirectBufferPool 已恢复为 poolSize=$POOL_SIZE_AFTER_TEST")
            }
        }

    /**
     * 预热JNI方法
     * 首次调用JNI方法会有性能损耗，需要预热
     */
    private fun warmup() {
        try {
            // 预热KGM解密
            val kgmKey = ByteArray(17)
            KGMDecrypt.init(kgmKey)

            // 预热NCM解密
            val ncmKey = ByteArray(256)
            RC4Decrypt.ksa(ncmKey)

            DirectBufferPool.acquireDirectBuffer()?.use { slot ->
                val buffer = slot.buffer
                // 直接在 ByteBuffer 中填充测试数据，避免 ByteArray 复制
                fillBufferWithRandomData(buffer, 100 * 1024)
                buffer.flip()
                RC4Decrypt.prgaDecryptByteBuffer(buffer, 100 * 1024)
            }

            DirectBufferPool.acquireDirectBuffer()?.use { slot ->
                val buffer = slot.buffer
                fillBufferWithRandomData(buffer, 100 * 1024)
                buffer.flip()
                KGMDecrypt.decrypt(buffer, 0, 100 * 1024)
            }

            Log.d(TAG, "JNI预热完成")
        } catch (e: Exception) {
            Log.e(TAG, "预热失败: ${e.message}", e)
        }
    }

    /**
     * 测试KGM解密性能
     */
    private fun testKGMPerformance(): List<SizeThroughput> {
        val sizes = listOf(256, 1024) // KB
        val key = ByteArray(17)

        return sizes.map { sizeKB ->
            val results = mutableListOf<Double>()
            Log.d(TAG, "开始 KGM ${sizeKB}KB 测试")

            repeat(ITERATIONS) {
                val dataSize = sizeKB * 1024

                DirectBufferPool.acquireDirectBuffer()?.use { slot ->
                    val buffer = slot.buffer

                    // 直接在 ByteBuffer 中生成测试数据，避免 ByteArray -> ByteBuffer 的复制
                    // fillBufferWithRandomData 内部会检查容量并调用 flip()
                    fillBufferWithRandomData(buffer, dataSize)

                    // 初始化KGM
                    KGMDecrypt.init(key)

                    // 测量解密性能
                    val startTime = System.nanoTime()
                    var pos = 0
                    val chunkSize = 256 * 1024 // 256KB每块
                    while (pos < dataSize) {
                        val bytesToProcess = minOf(chunkSize, dataSize - pos)
                        // JNI 不使用 ByteBuffer 的 position/limit，而是直接用 offset 参数
                        pos = KGMDecrypt.decrypt(buffer, pos, bytesToProcess)
                    }
                    val endTime = System.nanoTime()

                    // 验证是否处理完所有数据
                    if (pos != dataSize) {
                        Log.e(TAG, "未处理完所有数据！期望: $dataSize, 实际: $pos")
                    }

                    val durationSec = (endTime - startTime) / 1_000_000_000.0
                    val throughput = sizeKB / durationSec
                    Log.d(TAG, "KGM ${sizeKB}KB 第${results.size + 1}次: ${String.format("%.2f", throughput)} KB/s")
                    results.add(throughput)
                }
            }

            val avgThroughput = results.average()
            // durationMs = total_kb / throughput(kb/s) * 1000
            val avgDurationMs = (results.size * sizeKB / avgThroughput * 1000).toLong()

            SizeThroughput(sizeKB, avgThroughput, avgDurationMs)
        }
    }

    /**
     * 测试NCM解密性能 (ByteBuffer模式)
     */
    private fun testNCMBufferPerformance(): List<SizeThroughput> {
        val sizes = listOf(256, 1024) // KB

        return sizes.map { sizeKB ->
            val results = mutableListOf<Double>()
            Log.d(TAG, "开始 NCM ByteBuffer ${sizeKB}KB 测试")

            repeat(ITERATIONS) {
                val dataSize = sizeKB * 1024

                DirectBufferPool.acquireDirectBuffer()?.use { slot ->
                    val buffer = slot.buffer

                    // 直接在 ByteBuffer 中生成测试数据，避免 ByteArray -> ByteBuffer 的复制
                    // fillBufferWithRandomData 内部会检查容量并调用 flip()
                    fillBufferWithRandomData(buffer, dataSize)

                    // 每次测试都需要重新初始化RC4，因为它是有状态的
                    val key = ByteArray(256)
                    RC4Decrypt.ksa(key)

                    // 测量解密性能
                    val startTime = System.nanoTime()
                    RC4Decrypt.prgaDecryptByteBuffer(buffer, dataSize)
                    val endTime = System.nanoTime()

                    val durationSec = (endTime - startTime) / 1_000_000_000.0
                    val throughput = sizeKB / durationSec
                    Log.d(TAG, "NCM ByteBuffer ${sizeKB}KB 第${results.size + 1}次: ${String.format("%.2f", throughput)} KB/s")
                    results.add(throughput)
                }
            }

            val avgThroughput = results.average()
            val avgDurationMs = (results.size * sizeKB / avgThroughput * 1000).toLong()

            SizeThroughput(sizeKB, avgThroughput, avgDurationMs)
        }
    }

    /**
     * 测试NCM解密性能 (ByteArray模式)
     */
    private fun testNCMArrayPerformance(): List<SizeThroughput> {
        val sizes = listOf(256, 1024) // KB

        return sizes.map { sizeKB ->
            val results = mutableListOf<Double>()
            val dataSize = sizeKB * 1024
            Log.d(TAG, "开始 NCM ByteArray ${sizeKB}KB 测试")

            repeat(ITERATIONS) {
                // 每次迭代都需要创建新的数组，因为 RC4 解密会修改原数组
                val data = ByteArray(dataSize)
                fillByteArrayWithRandomData(data)

                // 每次测试都需要重新初始化RC4，因为它是有状态的
                val key = ByteArray(256)
                RC4Decrypt.ksa(key)

                // 测量解密性能
                val startTime = System.nanoTime()
                RC4Decrypt.prgaDecryptByteArray(data, dataSize)
                val endTime = System.nanoTime()

                val durationSec = (endTime - startTime) / 1_000_000_000.0
                val throughput = sizeKB / durationSec
                Log.d(TAG, "NCM ByteArray ${sizeKB}KB 第${results.size + 1}次: ${String.format("%.2f", throughput)} KB/s")
                results.add(throughput)
            }

            val avgThroughput = results.average()
            val avgDurationMs = (results.size * sizeKB / avgThroughput * 1000).toLong()

            SizeThroughput(sizeKB, avgThroughput, avgDurationMs)
        }
    }

    /**
     * 直接在 ByteBuffer 中填充随机数据
     * 遵循 DirectBufferPool 的模式：clear -> 写入 -> flip
     * 使用一次性填充以确保与 ByteArray 测试的公平性
     */
    private fun fillBufferWithRandomData(buffer: ByteBuffer, size: Int) {
        // 检查容量
        if (size > buffer.capacity()) {
            throw IllegalArgumentException("数据大小 ${size}Bytes 超过 buffer 容量 ${buffer.capacity()}Bytes")
        }

        // 遵循 DirectBufferPool.safeWrite 的模式
        buffer.clear()  // position=0, limit=capacity

        // 使用一次性填充，与 ByteArray 测试保持一致的开销
        val data = ByteArray(size)
        Random().nextBytes(data)
        buffer.put(data)

        buffer.flip()  // limit=position(写入的数据量), position=0
    }

    /**
     * 在 ByteArray 中填充随机数据
     */
    private fun fillByteArrayWithRandomData(data: ByteArray) {
        Random().nextBytes(data)
    }

    /**
     * 扩展函数：用于 DirectBufferPool.Slot 的 use 模式
     */
    private fun <T : DirectBufferPool.Slot> T.use(block: (T) -> Unit) {
        try {
            block(this)
        } finally {
            this.close()
        }
    }
}
