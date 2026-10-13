package com.cdb96.ncmconverter4a.jni

actual object KGMDecrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun create(ownKeyBytes: ByteArray): Int
    @JvmStatic actual external fun decrypt(context: Int, cipherData: ByteArray, offset: Int, bytesRead: Int): Int
    @JvmStatic actual external fun destroy(context: Int)
}
