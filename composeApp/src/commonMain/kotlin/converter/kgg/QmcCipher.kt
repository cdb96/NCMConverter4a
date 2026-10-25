package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.jni.KGGDecrypt

/** KGG/QMC audio ciphers backed by the shared C++ SIMD core. */
object QmcCipher {
    const val RC4_SEGMENT_SIZE = 5120
    const val STREAM_BUFFER_SIZE = 64 * RC4_SEGMENT_SIZE

    fun createCipher(key: ByteArray): QmcStreamCipher = NativeCipher(KGGDecrypt.prepare(key))

    interface QmcStreamCipher {
        /** Decrypts `data[0, length)` in place at its absolute stream [offset]. */
        fun decrypt(data: ByteArray, offset: Long = 0L, length: Int = data.size)
    }

    // A GC-owned, immutable snapshot of the native key schedule. Each cipher
    // owns its state, so interleaved files, worker changes and concurrent reads
    // need neither native handles nor a thread-local initialization contract.
    private class NativeCipher(private val state: ByteArray) : QmcStreamCipher {
        override fun decrypt(data: ByteArray, offset: Long, length: Int) {
            KGGDecrypt.decrypt(state, data, offset, length)
        }
    }
}
