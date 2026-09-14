package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
