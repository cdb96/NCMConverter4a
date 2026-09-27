package com.cdb96.ncmconverter4a.jni

expect object KGMDecrypt {
    fun create(ownKeyBytes: ByteArray): Long
    fun decrypt(context: Long, cipherData: ByteArray, offset: Int, bytesRead: Int): Int
    fun destroy(context: Long)
}
