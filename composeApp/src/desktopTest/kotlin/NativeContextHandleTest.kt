package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeContextHandleTest {
    private data class Decoder(
        val create: (ByteArray) -> Int,
        val decrypt: (Int, ByteArray) -> Unit,
        val destroy: (Int) -> Unit,
    )

    private val decoders = listOf(
        Decoder(RC4Decrypt::create, { handle, data -> RC4Decrypt.decrypt(handle, data, data.size) }, RC4Decrypt::destroy),
        Decoder(KGMDecrypt::create, { handle, data -> KGMDecrypt.decrypt(handle, data, 0, data.size) }, KGMDecrypt::destroy),
    )
    private val key = ByteArray(17) { (it * 31 + 5).toByte() }
    private val payload = ByteArray(4096) { (it * 7 + 11).toByte() }

    @Test
    fun invalidIntegerHandlesAreRejectedWithoutChangingData() {
        for (decoder in decoders) {
            for (handle in listOf(0, -1, Int.MAX_VALUE)) {
                val data = payload.copyOf()
                assertFailsWith<IllegalArgumentException> { decoder.decrypt(handle, data) }
                assertContentEquals(payload, data)
                assertFailsWith<IllegalArgumentException> { decoder.decrypt(handle, byteArrayOf()) }
            }
        }
    }

    @Test
    fun destroyedHandlesCannotAddressNewContexts() {
        for (decoder in decoders) {
            val first = decoder.create(key)
            assertTrue(first > 0)
            val expected = payload.copyOf()
            try {
                decoder.decrypt(first, expected)
            } finally {
                decoder.destroy(first)
            }
            decoder.destroy(first)

            val second = decoder.create(key)
            try {
                assertTrue(second > 0)
                assertNotEquals(first, second)
                val unchanged = payload.copyOf()
                assertFailsWith<IllegalArgumentException> { decoder.decrypt(first, unchanged) }
                assertContentEquals(payload, unchanged)
                val actual = payload.copyOf()
                decoder.decrypt(second, actual)
                assertContentEquals(expected, actual)
            } finally {
                decoder.destroy(second)
            }
        }
    }

    @Test
    fun concurrentContextCreationAndReleaseKeepKeysIndependent() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            for (decoder in decoders) {
                val vectors = (0 until 8).map { worker ->
                    val workerKey = ByteArray(17) { ((worker + 1) * 13 + it * 7).toByte() }
                    val expected = payload.copyOf()
                    val reference = decoder.create(workerKey)
                    try {
                        decoder.decrypt(reference, expected)
                    } finally {
                        decoder.destroy(reference)
                    }
                    workerKey to expected
                }
                val futures = vectors.map { (workerKey, expected) ->
                    executor.submit(Callable {
                        repeat(100) {
                            val handle = decoder.create(workerKey)
                            try {
                                val actual = payload.copyOf()
                                decoder.decrypt(handle, actual)
                                assertContentEquals(expected, actual)
                            } finally {
                                decoder.destroy(handle)
                            }
                        }
                    })
                }
                futures.forEach { it.get() }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun anotherWorkerCannotUseOrReleaseTheOwningWorkersContext() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (decoder in decoders) {
                val handle = decoder.create(key)
                try {
                    executor.submit(Callable {
                        val ownHandle = decoder.create(key)
                        try {
                            assertNotEquals(handle, ownHandle)
                            val data = payload.copyOf()
                            assertFailsWith<IllegalArgumentException> { decoder.decrypt(handle, data) }
                            assertContentEquals(payload, data)
                            decoder.destroy(handle)
                            decoder.decrypt(ownHandle, data)
                            data
                        } finally {
                            decoder.destroy(ownHandle)
                        }
                    }).get().let { expected ->
                        val actual = payload.copyOf()
                        decoder.decrypt(handle, actual)
                        assertContentEquals(expected, actual)
                    }
                } finally {
                    decoder.destroy(handle)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
