package com.cdb96.ncmconverter4a.jni

actual object RC4Decrypt {
    init {
        System.loadLibrary("ncmc4a")
    }

    @JvmStatic actual external fun ksa(key: ByteArray)

    @JvmStatic @JvmName("prgaDecryptByteArray")
    actual external fun prgaDecrypt(cipherData: ByteArray, bytesRead: Int)
}
