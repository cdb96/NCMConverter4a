package com.cdb96.ncmconverter4a.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class BinaryIoTest {
    @Test
    fun readFullyHandlesShortReads() {
        val expected = ByteArray(1024) { (it and 0xFF).toByte() }
        val input = PartialInput(expected, intArrayOf(1, 7, 31, 511))
        val actual = ByteArray(expected.size)

        input.readFully(actual)

        assertContentEquals(expected, actual)
    }

    @Test
    fun readFullyPreservesBytesOutsideTheRangeAndRejectsTruncation() {
        val input = PartialInput(byteArrayOf(1, 2, 3), intArrayOf(1, 2))
        val actual = ByteArray(7) { 9 }
        input.readFully(actual, 2, 3)
        assertContentEquals(byteArrayOf(9, 9, 1, 2, 3, 9, 9), actual)

        assertFailsWith<BinaryInputException> {
            PartialInput(byteArrayOf(1, 2), intArrayOf(1)).readFully(ByteArray(3))
        }
    }

    @Test
    fun readFullyStillValidatesRangesAndReaderResults() {
        val buffer = ByteArray(4)
        for ((offset, length) in listOf(-1 to 1, 0 to -1, 3 to 2)) {
            assertFailsWith<IllegalArgumentException> {
                BinaryInput { _, _, _ -> -1 }.readFully(buffer, offset, length)
            }
        }
        for (result in listOf(0, buffer.size + 1)) {
            assertFailsWith<BinaryInputException> {
                BinaryInput { _, _, _ -> result }.readFully(buffer)
            }
        }
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
