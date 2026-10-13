package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * KGM decrypts a stream chunk by chunk: the native cursor is threaded through the
 * return value while each call operates on its own buffer, which is how the
 * converters drive it. Chunked decryption must therefore match decrypting the
 * whole payload in one call, for every chunk size the converters use.
 */
class KgmDecryptTest {

    private val payload = ByteArray(262_144 + 4096) { (it * 7 + 11).toByte() }
    private val key = ByteArray(17) { (it * 31 + 5).toByte() }

    private fun decryptWhole(): ByteArray {
        val data = payload.copyOf()
        KGMDecrypt.init(key)
        KGMDecrypt.decrypt(data, 0, data.size)
        return data
    }

    private fun decryptChunked(chunkSize: Int): Pair<ByteArray, Int> {
        KGMDecrypt.init(key)
        val output = ByteArray(payload.size)
        var offset = 0
        while (offset < payload.size) {
            val size = minOf(chunkSize, payload.size - offset)
            val chunk = payload.copyOfRange(offset, offset + size)
            offset = KGMDecrypt.decrypt(chunk, offset, size)
            chunk.copyInto(output, offset - size)
        }
        return output to offset
    }

    @Test
    fun rejectsUnalignedOffsetsWithoutChangingData() {
        val expected = payload.copyOf(16)
        val actual = expected.copyOf()
        KGMDecrypt.init(key)
        for (offset in listOf(1, 15, 271)) {
            assertFailsWith<IllegalArgumentException> {
                KGMDecrypt.decrypt(actual, offset, actual.size)
            }
            assertContentEquals(expected, actual)
        }
    }

    @Test
    fun productionChunkSizeMatchesWholeBuffer() {
        val (chunked, end) = decryptChunked(262_144)

        assertEquals(payload.size, end)
        assertContentEquals(decryptWhole(), chunked)
    }

    @Test
    fun smallerAlignedChunksMatchWholeBuffer() {
        val (chunked, end) = decryptChunked(4096)

        assertEquals(payload.size, end)
        assertContentEquals(decryptWhole(), chunked)
    }

    @Test
    fun converterReusesMatchingFirstBufferAndPreservesTheShortFinalChunk() {
        val source = payload.copyOf(payload.size + 7)
        val expected = source.copyOf()
        KGMDecrypt.init(key)
        KGMDecrypt.decrypt(expected, 0, expected.size)

        for (firstBufferSize in listOf(256, 4096)) {
            val input = ByteArrayInputStream(source)
            val firstChunk = ByteArray(firstBufferSize)
            val firstSize = BinaryInput(input::read).readChunk(firstChunk)
            val output = ByteArrayOutputStream()
            KGMConverter.decrypt(
                ownKeyBytes = key,
                firstChunk = firstChunk,
                firstSize = firstSize,
                bufferSize = 4096,
                read = { buffer ->
                    assertEquals(4096, buffer.size)
                    if (firstBufferSize == 4096) assertSame(firstChunk, buffer)
                    BinaryInput(input::read).readChunk(buffer)
                },
                write = { buffer, length -> output.write(buffer, 0, length) }
            )
            assertContentEquals(expected, output.toByteArray())
        }
    }

    @Test
    fun concurrentKeysRemainIndependent() {
        val vectors = (0 until 8).map { worker ->
            val workerKey = ByteArray(17) { ((worker + 1) * 13 + it * 7).toByte() }
            val expected = payload.copyOf()
            KGMDecrypt.init(workerKey)
            KGMDecrypt.decrypt(expected, 0, expected.size)
            workerKey to expected
        }
        val executor = Executors.newFixedThreadPool(8)
        val barrier = CyclicBarrier(8)
        try {
            vectors.map { (workerKey, expected) ->
                executor.submit(Callable {
                    KGMDecrypt.init(workerKey)
                    barrier.await()
                    val actual = payload.copyOf()
                    KGMDecrypt.decrypt(actual, 0, actual.size)
                    assertContentEquals(expected, actual)
                })
            }.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }
}
