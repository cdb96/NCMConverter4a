package com.cdb96.ncmconverter4a.jni

actual object RC4Decrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun create(key: ByteArray): Int

    @JvmStatic actual external fun decrypt(context: Int, cipherData: ByteArray, bytesRead: Int)
    @JvmStatic actual external fun destroy(context: Int)
}
