package com.cdb96.ncmconverter4a.converter

import com.cdb96.ncmconverter4a.converter.kgg.QmcCipher
import kotlin.test.Test
import kotlin.test.assertContentEquals

class QmcCipherPartialBufferTest {
    @Test
    fun partialBuffersProduceTheSameQmcStreamAsOneBuffer() {
        val key = ByteArray(512) { ((it * 17) % 251 + 1).toByte() }
        val original = ByteArray(40_000) { ((it * 31 + 7) and 0xFF).toByte() }

        val oneShot = original.copyOf()
        QmcCipher.createCipher(key).decrypt(oneShot, 0L)

        val partial = original.copyOf()
        val cipher = QmcCipher.createCipher(key)
        val chunks = intArrayOf(1, 7, 31, 511, 4093, 8192, 3, 1024)
        var position = 0
        var chunkIndex = 0
        while (position < partial.size) {
            val length = minOf(chunks[chunkIndex % chunks.size], partial.size - position)
            val chunk = partial.copyOfRange(position, position + length)
            cipher.decrypt(chunk, position.toLong())
            chunk.copyInto(partial, position)
            position += length
            chunkIndex++
        }

        assertContentEquals(oneShot, partial)
    }
}
