package com.cdb96.ncmconverter4a.jni

expect object RC4Decrypt {
    fun create(key: ByteArray): Int
    fun decrypt(context: Int, cipherData: ByteArray, bytesRead: Int)
    fun destroy(context: Int)
}
