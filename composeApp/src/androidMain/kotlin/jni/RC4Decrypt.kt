package com.cdb96.ncmconverter4a.jni

actual object RC4Decrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun init(key: ByteArray)
    @JvmStatic actual external fun decrypt(cipherData: ByteArray, bytesRead: Int)
}
