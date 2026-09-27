package com.cdb96.ncmconverter4a.jni

actual object RC4Decrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun create(key: ByteArray): Long

    @JvmStatic actual external fun decrypt(context: Long, cipherData: ByteArray, bytesRead: Int)
    @JvmStatic actual external fun destroy(context: Long)
}
