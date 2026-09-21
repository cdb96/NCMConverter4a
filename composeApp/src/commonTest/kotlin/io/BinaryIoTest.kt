package com.cdb96.ncmconverter4a.io

import kotlin.test.Test
import kotlin.test.assertContentEquals

class BinaryIoTest {
    @Test
    fun readFullyHandlesShortReads() {
        val expected = ByteArray(1024) { (it and 0xFF).toByte() }
        val input = PartialInput(expected, intArrayOf(1, 7, 31, 511))
        val actual = ByteArray(expected.size)

        input.readFully(actual)

        assertContentEquals(expected, actual)
    }

    private class PartialInput(
        private val source: ByteArray,
        private val chunks: IntArray
    ) : BinaryInput {
        private var position = 0
        private var chunkIndex = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == source.size) return -1
            val requested = chunks[chunkIndex++ % chunks.size]
            val count = minOf(requested, length, source.size - position)
            source.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
