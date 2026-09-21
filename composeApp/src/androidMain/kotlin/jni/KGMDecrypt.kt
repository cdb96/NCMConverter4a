package com.cdb96.ncmconverter4a.jni

actual object KGMDecrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun init(ownKeyBytes: ByteArray)
    @JvmStatic actual external fun decrypt(cipherData: ByteArray, offset: Int, bytesRead: Int): Int
}
