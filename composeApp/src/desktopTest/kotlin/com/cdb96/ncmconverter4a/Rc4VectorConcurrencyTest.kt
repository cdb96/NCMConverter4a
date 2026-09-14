package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals

class Rc4VectorConcurrencyTest {
    @Test
    fun rc4GoldenVector() {
        val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val actual = ByteArray(32) { it.toByte() }
        val expected = byteArrayOf(
            0x6D, 0x84.toByte(), 0xE4.toByte(), 0x70, 0x92.toByte(), 0x1D,
            0xEE.toByte(), 0x82.toByte(), 0xA1.toByte(), 0x5E, 0x4D, 0x6C,
            0xB4.toByte(), 0xD0.toByte(), 0x0E, 0x5A, 0x0B, 0x00,
            0xC2.toByte(), 0xC0.toByte(), 0xF1.toByte(), 0xFE.toByte(), 0x2D,
            0xE9.toByte(), 0xB4.toByte(), 0x98.toByte(), 0xA1.toByte(),
            0x8F.toByte(), 0x87.toByte(), 0x42, 0x9F.toByte(), 0x82.toByte()
        )

        RC4Decrypt.ksa(key)
        RC4Decrypt.prgaDecrypt(actual, actual.size)

        assertContentEquals(expected, actual)
    }

    @Test
    fun concurrentKeysDoNotOverwriteEachOther() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map { worker ->
                executor.submit(Callable {
                    val key = ByteArray(17) { ((worker + 1) * 13 + it * 7).toByte() }
                    val input = ByteArray(513) { ((worker * 19 + it * 3) and 0xFF).toByte() }
                    val expected = input.copyOf()
                    referenceDecrypt(key, expected)

                    repeat(100) {
                        val actual = input.copyOf()
                        RC4Decrypt.ksa(key)
                        RC4Decrypt.prgaDecrypt(actual, actual.size)
                        assertContentEquals(expected, actual)
                    }
                })
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun vectorPathDoesNotCrossThe256ByteKeyStreamBoundary() {
        val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val expected = ByteArray(513) { ((it * 11 + 3) and 0xFF).toByte() }
        val actual = expected.copyOf()
        referenceDecrypt(key, expected)

        RC4Decrypt.ksa(key)
        RC4Decrypt.prgaDecrypt(actual, actual.size)

        assertContentEquals(expected, actual)
    }

    private fun referenceDecrypt(key: ByteArray, data: ByteArray) {
        val sBox = ByteArray(256) { it.toByte() }
        var j = 0
        for (i in 0 until 256) {
            j = (j + sBox[i] + key[i % key.size]) and 0xFF
            val temp = sBox[i]
            sBox[i] = sBox[j]
            sBox[j] = temp
        }

        val stream = ByteArray(256)
        for (k in 1 until 256) {
            stream[k - 1] = sBox[(sBox[k] + sBox[(sBox[k] + k) and 0xFF]) and 0xFF]
        }
        stream[255] = sBox[(sBox[0] + sBox[sBox[0].toInt() and 0xFF]) and 0xFF]

        for (i in data.indices) {
            data[i] = (data[i].toInt() xor stream[i and 0xFF].toInt()).toByte()
        }
    }
}
