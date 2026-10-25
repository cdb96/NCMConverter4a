package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.QmcCipher
import com.cdb96.ncmconverter4a.jni.KGGDecrypt
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * The optimised QMC ciphers must stay byte-identical to the original
 * per-byte implementations kept in [ReferenceQmcRc4], [ReferenceQmcMap] and
 * [ReferenceQmcStatic].
 */
class QmcCipherReferenceTest {
    private val rc4Keys = listOf(
        qmcKey(301, 1),
        qmcKey(512, 2),
        qmcKey(768, 3),
        qmcKey(1024, 4),
        // Zero key bytes drive the segment skip through a division by zero.
        ByteArray(400) { if (it % 37 == 0) 0 else (it * 11 + 5).toByte() },
        // Long.MAX_VALUE % 512 == 511, exercising the final cached stream byte.
        ByteArray(512),
    )

    @Test
    fun rc4MatchesTheReferenceAtEverySegmentBoundary() {
        val offsets = longArrayOf(0, 1, 127, 128, 129, 5119, 5120, 5121, 10239, 327_680, 332_679, 1_000_003)
        val lengths = intArrayOf(0, 1, 127, 128, 129, 5119, 5120, 5121, 10241, 327_683)
        for (key in rc4Keys) {
            val cipher = QmcCipher.createCipher(key)
            for (offset in offsets) {
                for (length in lengths) {
                    val plain = ByteArray(length) { (it * 7 + offset.toInt()).toByte() }
                    val expected = plain.copyOf().also { ReferenceQmcRc4(key).decrypt(it, offset) }
                    val actual = plain.copyOf().also { cipher.decrypt(it, offset) }
                    assertContentEquals(expected, actual, "key=${key.size} offset=$offset length=$length")
                }
            }
        }
    }

    @Test
    fun streamingWithTheAlignedBufferMatchesOneShotAndOnlyTouchesTheGivenLength() {
        val key = qmcKey(768, 9)
        val plain = ByteArray(3 * QmcCipher.STREAM_BUFFER_SIZE + 777) { (it * 13 + 1).toByte() }
        val expected = plain.copyOf().also { ReferenceQmcRc4(key).decrypt(it, 0) }

        val cipher = QmcCipher.createCipher(key)
        val buffer = ByteArray(QmcCipher.STREAM_BUFFER_SIZE)
        val actual = ByteArray(plain.size)
        var position = 0
        while (position < plain.size) {
            val length = minOf(buffer.size, plain.size - position)
            plain.copyInto(buffer, 0, position, position + length)
            buffer.fill(0x5A, length)
            cipher.decrypt(buffer, position.toLong(), length)
            assertContentEquals(ByteArray(buffer.size - length) { 0x5A }, buffer.copyOfRange(length, buffer.size))
            buffer.copyInto(actual, position, 0, length)
            position += length
        }
        assertContentEquals(expected, actual)
    }

    @Test
    fun rc4SupportsOutOfOrderReadsAndOffsetsNearLongMaxValue() {
        val offsets = longArrayOf(
            327_683, 128, 0, 5120, 127, Long.MAX_VALUE - 8193, 5119, 128,
            Long.MAX_VALUE - 8193, 0,
        )
        for (key in rc4Keys) {
            val cipher = QmcCipher.createCipher(key)
            for (offset in offsets) {
                val length = 8193
                val plain = ByteArray(length) { (it * 7 + offset.toInt()).toByte() }
                val expected = plain.copyOf().also { ReferenceQmcRc4(key).decrypt(it, offset) }
                val actual = plain.copyOf(length + 17).also { it.fill(0x5A, length) }
                cipher.decrypt(actual, offset, length)
                assertContentEquals(expected, actual.copyOf(length), "key=${key.size} offset=$offset")
                assertContentEquals(ByteArray(17) { 0x5A }, actual.copyOfRange(length, actual.size))
            }
        }
    }

    @Test
    fun maskCiphersMatchTheReferenceAcrossThePeriodBoundary() {
        val offsets = longArrayOf(0, 1, 0x7FFE, 0x7FFF, 0x8000, 0xFFFD, 0xFFFE, 0xFFFF, 0x17FFC, 123_456_789)
        val lengths = intArrayOf(0, 1, 2, 3, 100, 70_000)
        val mapKeys = listOf(qmcKey(1, 4), qmcKey(7, 5), qmcKey(256, 6), qmcKey(300, 7))
        for (offset in offsets) {
            for (length in lengths) {
                val plain = ByteArray(length) { (it * 3 + 11).toByte() }
                for (key in mapKeys) {
                    val expected = plain.copyOf().also { ReferenceQmcMap(key).decrypt(it, offset) }
                    val actual = plain.copyOf().also { QmcCipher.createCipher(key).decrypt(it, offset) }
                    assertContentEquals(expected, actual, "map key=${key.size} offset=$offset length=$length")
                }
                val expected = plain.copyOf().also { ReferenceQmcStatic.decrypt(it, offset) }
                val actual = plain.copyOf().also { QmcCipher.createCipher(ByteArray(0)).decrypt(it, offset) }
                assertContentEquals(expected, actual, "static offset=$offset length=$length")
            }
        }
    }

    @Test
    fun rejectsInvalidRanges() {
        val data = ByteArray(16)
        for (cipher in listOf(
            QmcCipher.createCipher(qmcKey(512, 1)),
            QmcCipher.createCipher(qmcKey(16, 1)),
            QmcCipher.createCipher(ByteArray(0)),
        )) {
            assertFailsWith<IllegalArgumentException> { cipher.decrypt(data, -1) }
            assertFailsWith<IllegalArgumentException> { cipher.decrypt(data, 0, data.size + 1) }
            assertFailsWith<IllegalArgumentException> { cipher.decrypt(data, 0, -1) }
            assertFailsWith<IllegalArgumentException> { cipher.decrypt(data, Long.MAX_VALUE) }
        }
    }

    @Test
    fun arbitraryChunksMatchOneShotForAllModes() {
        val sizes = intArrayOf(1, 15, 16, 17, 63, 64, 65, 127, 128, 129, 5119, 5120, 5121, 32767, 32768)
        for (key in listOf(qmcKey(301, 3), ByteArray(512), qmcKey(7, 2), ByteArray(0))) {
            val cipher = QmcCipher.createCipher(key)
            val input = ByteArray(180_013) { (it * 13 + 7).toByte() }
            val expected = input.copyOf().also { referenceDecrypt(key, it, 0) }
            val actual = ByteArray(input.size)
            var position = 0
            var chunk = 0
            while (position < input.size) {
                val length = minOf(sizes[chunk++ % sizes.size], input.size - position)
                val buffer = input.copyOfRange(position, position + length).copyOf(length + 19)
                buffer.fill(0x5A, length)
                cipher.decrypt(buffer, position.toLong(), length)
                assertContentEquals(ByteArray(19) { 0x5A }, buffer.copyOfRange(length, buffer.size))
                buffer.copyInto(actual, position, 0, length)
                position += length
            }
            assertContentEquals(expected, actual, "key=${key.size}")
        }
    }

    @Test
    fun cipherSnapshotsTheKeyAndSupportsConcurrentInterleavedFiles() {
        val keys = listOf(qmcKey(768, 3), qmcKey(301, 7), ByteArray(512), qmcKey(300, 5), ByteArray(0))
        val ciphers = keys.map { key ->
            val mutableKey = key.copyOf()
            QmcCipher.createCipher(mutableKey).also { mutableKey.fill(0) }
        }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = (0 until 30).map { task -> Callable {
                val index = task % keys.size
                val offset = when (task % 3) {
                    0 -> Long.MAX_VALUE - 12_345
                    1 -> 127L
                    else -> 32767L
                }
                val expected = ByteArray(12_345) { (it * 7 + task).toByte() }
                val actual = expected.copyOf()
                referenceDecrypt(keys[index], expected, offset)
                ciphers[index].decrypt(actual, offset)
                assertContentEquals(expected, actual, "task=$task")
            } }
            executor.invokeAll(work).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun nativeBridgeRejectsMalformedStateAndAllowsEmptyRangesAtLongMax() {
        val data = ByteArray(16)
        assertFailsWith<IllegalArgumentException> { KGGDecrypt.decrypt(ByteArray(0), data, 0, 16) }
        assertFailsWith<IllegalArgumentException> { KGGDecrypt.decrypt(ByteArray(8), data, 0, 16) }
        val state = KGGDecrypt.prepare(qmcKey(512, 1))
        assertFailsWith<IllegalArgumentException> { KGGDecrypt.decrypt(state.copyOf(state.size - 1), data, 0, 16) }
        assertFailsWith<IllegalArgumentException> { KGGDecrypt.decrypt(state, state, 0, 0) }
        KGGDecrypt.decrypt(state, data, Long.MAX_VALUE, 0)
        assertContentEquals(ByteArray(16), data)
    }

    private fun referenceDecrypt(key: ByteArray, data: ByteArray, offset: Long) {
        when {
            key.size > 300 -> ReferenceQmcRc4(key).decrypt(data, offset)
            key.isNotEmpty() -> ReferenceQmcMap(key).decrypt(data, offset)
            else -> ReferenceQmcStatic.decrypt(data, offset)
        }
    }
}
