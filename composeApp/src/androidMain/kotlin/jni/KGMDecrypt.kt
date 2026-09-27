package com.cdb96.ncmconverter4a.jni

actual object KGMDecrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun create(ownKeyBytes: ByteArray): Long
    @JvmStatic actual external fun decrypt(context: Long, cipherData: ByteArray, offset: Int, bytesRead: Int): Int
    @JvmStatic actual external fun destroy(context: Long)
}
