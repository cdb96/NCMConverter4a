package com.cdb96.ncmconverter4a.jni

internal actual object KGGDecrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun prepare(key: ByteArray): ByteArray
    @JvmStatic actual external fun decrypt(state: ByteArray, data: ByteArray, offset: Long, length: Int)
}
