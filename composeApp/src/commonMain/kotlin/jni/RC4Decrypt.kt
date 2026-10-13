package com.cdb96.ncmconverter4a.jni

expect object RC4Decrypt {
    fun init(key: ByteArray)
    fun decrypt(cipherData: ByteArray, bytesRead: Int)
}
