package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Concurrency coverage for the native RC4 core: the key stream lives in
 * thread-local native state, so parallel conversions must not disturb each other.
 */
class Rc4DecryptConcurrencyTest {

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
