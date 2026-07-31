package com.cdb96.ncmconverter4a.service

import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import com.cdb96.ncmconverter4a.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.TimeSource

class BenchmarkService {

    companion object {
        private const val TAG = "BenchmarkService"
        private const val ITERATIONS = 6
        private val log = Logger(TAG)
    }

    suspend fun runBenchmark(onProgress: (TestStatus) -> Unit): BenchmarkResult =
        withContext(Dispatchers.Default) {
            val startMark = TimeSource.Monotonic.markNow()
            onProgress(TestStatus.WARMING)
            warmup()

            onProgress(TestStatus.TESTING_KGM)
            val kgmResults = testKGMPerformance()

            onProgress(TestStatus.TESTING_NCM)
            val ncmResults = testNCMPerformance()

            onProgress(TestStatus.COMPLETED)

            val totalDuration = startMark.elapsedNow().inWholeMilliseconds
            BenchmarkResult(
                kgmResults = kgmResults,
                ncmResults = ncmResults,
                totalDuration = totalDuration
            )
        }

    private fun warmup() {
        try {
            KGMDecrypt.init(ByteArray(17))
            RC4Decrypt.ksa(ByteArray(256))

            val data = ByteArray(100 * 1024)
            Random.Default.nextBytes(data)
            RC4Decrypt.prgaDecrypt(data, data.size)

            val data2 = ByteArray(100 * 1024)
            Random.Default.nextBytes(data2)
            KGMDecrypt.decrypt(data2, 0, data2.size)

            log.d("预热完成")
        } catch (e: Exception) {
            log.e("预热失败: ${e.message}", e)
        }
    }

    private fun testKGMPerformance(): List<SizeThroughput> {
        val sizes = listOf(256, 1024)
        val key = ByteArray(17)
        val clock = TimeSource.Monotonic

        return sizes.map { sizeKB ->
            val results = mutableListOf<Double>()
            log.d("开始 KGM ${sizeKB}KB 测试")

            repeat(ITERATIONS) {
                val dataSize = sizeKB * 1024
                val data = ByteArray(dataSize)
                Random.Default.nextBytes(data)
                KGMDecrypt.init(key)

                val mark = clock.markNow()
                var pos = 0
                val chunkSize = 256 * 1024
                while (pos < dataSize) {
                    val bytesToProcess = minOf(chunkSize, dataSize - pos)
                    pos = KGMDecrypt.decrypt(data, pos, bytesToProcess)
                }
                val elapsed = mark.elapsedNow().inWholeNanoseconds

                if (pos != dataSize) {
                    log.e("未处理完所有数据！期望: $dataSize, 实际: $pos")
                }

                val durationSec = elapsed / 1_000_000_000.0
                val throughput = sizeKB / durationSec
                log.d("KGM ${sizeKB}KB 第${results.size + 1}次: ${fmt1(throughput)} KB/s")
                results.add(throughput)
            }

            val maxThroughput = results.maxOrNull() ?: 0.0
            val avgDurationMs = (results.size * sizeKB / maxThroughput * 1000).toLong()
            SizeThroughput(sizeKB, maxThroughput, avgDurationMs)
        }
    }

    private fun testNCMPerformance(): List<SizeThroughput> {
        val sizes = listOf(256, 1024)
        val clock = TimeSource.Monotonic

        return sizes.map { sizeKB ->
            val results = mutableListOf<Double>()
            log.d("开始 NCM ${sizeKB}KB 测试")

            repeat(ITERATIONS) {
                val dataSize = sizeKB * 1024
                val data = ByteArray(dataSize)
                Random.Default.nextBytes(data)
                RC4Decrypt.ksa(ByteArray(256))

                val mark = clock.markNow()
                RC4Decrypt.prgaDecrypt(data, dataSize)
                val elapsed = mark.elapsedNow().inWholeNanoseconds

                val durationSec = elapsed / 1_000_000_000.0
                val throughput = sizeKB / durationSec
                log.d("NCM ${sizeKB}KB 第${results.size + 1}次: ${fmt1(throughput)} KB/s")
                results.add(throughput)
            }

            val maxThroughput = results.maxOrNull() ?: 0.0
            val avgDurationMs = (results.size * sizeKB / maxThroughput * 1000).toLong()
            SizeThroughput(sizeKB, maxThroughput, avgDurationMs)
        }
    }

    /** 手动格式化保留 1 位小数（替代 String.format，commonMain 可用） */
    private fun fmt1(d: Double): String {
        val n = (d * 10).toLong()
        return "${n / 10}.${abs(n % 10)}"
    }
}
