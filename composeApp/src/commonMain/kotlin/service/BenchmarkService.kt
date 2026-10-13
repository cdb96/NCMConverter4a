package com.cdb96.ncmconverter4a.service

import com.cdb96.ncmconverter4a.converter.kgg.QmcCipher
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
        private const val ITERATIONS = 6
        private val SIZES_KB = listOf(256, 1024)
        private val log = Logger("BenchmarkService")
    }

    suspend fun runBenchmark(onProgress: suspend (TestStatus) -> Unit): BenchmarkResult =
        withContext(Dispatchers.Default) {
            val startMark = TimeSource.Monotonic.markNow()
            onProgress(TestStatus.WARMING)
            warmup()

            onProgress(TestStatus.TESTING_KGM)
            val kgmResults = testNativePerformance("KGM", ByteArray(17), KGMDecrypt::init) { data ->
                var offset = 0
                while (offset < data.size) {
                    offset = KGMDecrypt.decrypt(data, offset, minOf(256 * 1024, data.size - offset))
                }
            }

            onProgress(TestStatus.TESTING_NCM)
            val ncmResults = testNativePerformance("NCM", ByteArray(256), RC4Decrypt::init) { data ->
                RC4Decrypt.decrypt(data, data.size)
            }

            onProgress(TestStatus.TESTING_KGG)
            val kggRc4Results = testKGGPerformance("RC4", kggBenchmarkKey(512))
            val kggMapResults = testKGGPerformance("Map", kggBenchmarkKey(256))

            onProgress(TestStatus.COMPLETED)

            val totalDuration = startMark.elapsedNow().inWholeMilliseconds
            BenchmarkResult(
                kgmResults = kgmResults,
                ncmResults = ncmResults,
                kggRc4Results = kggRc4Results,
                kggMapResults = kggMapResults,
                totalDuration = totalDuration
            )
        }

    private fun warmup() {
        val data = Random.Default.nextBytes(ByteArray(100 * 1024))
        RC4Decrypt.init(ByteArray(256))
        RC4Decrypt.decrypt(data, data.size)
        KGMDecrypt.init(ByteArray(17))
        KGMDecrypt.decrypt(data, 0, data.size)
        val buffer = Random.Default.nextBytes(ByteArray(QmcCipher.STREAM_BUFFER_SIZE))
        for (keySize in listOf(512, 256)) {
            val cipher = QmcCipher.createCipher(kggBenchmarkKey(keySize))
            repeat(3) { chunk ->
                cipher.decrypt(buffer, chunk.toLong() * buffer.size, buffer.size)
            }
            cipher.decrypt(buffer, 3L * buffer.size, 777)
        }
        log.d("预热完成")
    }

    private fun testNativePerformance(
        mode: String,
        key: ByteArray,
        init: (ByteArray) -> Unit,
        decrypt: (ByteArray) -> Unit,
    ): List<SizeThroughput> = testPerformance(mode) { dataSize ->
        val data = Random.Default.nextBytes(ByteArray(dataSize))
        init(key)
        val mark = TimeSource.Monotonic.markNow()
        decrypt(data)
        mark.elapsedNow().inWholeNanoseconds
    }

    private fun testKGGPerformance(mode: String, key: ByteArray): List<SizeThroughput> =
        testPerformance("KGG $mode") { dataSize ->
            // Include initialization in the timing; prepare input beforehand.
            val chunks = List((dataSize + QmcCipher.STREAM_BUFFER_SIZE - 1) / QmcCipher.STREAM_BUFFER_SIZE) { index ->
                val size = minOf(QmcCipher.STREAM_BUFFER_SIZE, dataSize - index * QmcCipher.STREAM_BUFFER_SIZE)
                Random.Default.nextBytes(ByteArray(size))
            }
            val mark = TimeSource.Monotonic.markNow()
            val cipher = QmcCipher.createCipher(key)
            var offset = 0L
            for (chunk in chunks) {
                cipher.decrypt(chunk, offset, chunk.size)
                offset += chunk.size
            }
            mark.elapsedNow().inWholeNanoseconds
        }

    private fun testPerformance(mode: String, measure: (Int) -> Long): List<SizeThroughput> =
        SIZES_KB.map { sizeKB ->
            var maxThroughput = 0.0
            var totalElapsedNs = 0L
            log.d("开始 $mode ${sizeKB}KB 测试")
            repeat(ITERATIONS) { iteration ->
                val elapsedNs = measure(sizeKB * 1024).coerceAtLeast(1)
                totalElapsedNs += elapsedNs
                val throughput = sizeKB / (elapsedNs / 1_000_000_000.0)
                maxThroughput = maxOf(maxThroughput, throughput)
                log.d("$mode ${sizeKB}KB 第${iteration + 1}次: ${fmt1(throughput)} KB/s")
            }

            SizeThroughput(sizeKB, maxThroughput, totalElapsedNs / 1_000_000)
        }

    /** Deterministic, nonzero key bytes exercise both QMC key-length branches. */
    private fun kggBenchmarkKey(size: Int): ByteArray =
        ByteArray(size) { ((it * 73 + 19) % 255 + 1).toByte() }

    /** 手动格式化保留 1 位小数（替代 String.format，commonMain 可用） */
    private fun fmt1(d: Double): String {
        val n = (d * 10).toLong()
        return "${n / 10}.${abs(n % 10)}"
    }
}
