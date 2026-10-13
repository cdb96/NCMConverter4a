package com.cdb96.ncmconverter4a.jni

expect object KGMDecrypt {
    fun create(ownKeyBytes: ByteArray): Int
    fun decrypt(context: Int, cipherData: ByteArray, offset: Int, bytesRead: Int): Int
    fun destroy(context: Int)
}
