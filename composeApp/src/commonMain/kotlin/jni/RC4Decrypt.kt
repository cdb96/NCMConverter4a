package com.cdb96.ncmconverter4a.jni

expect object RC4Decrypt {
    fun create(key: ByteArray): Long
    fun decrypt(context: Long, cipherData: ByteArray, bytesRead: Int)
    fun destroy(context: Long)
}
