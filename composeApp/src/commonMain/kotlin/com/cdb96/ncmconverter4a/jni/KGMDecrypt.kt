package com.cdb96.ncmconverter4a.jni

expect object KGMDecrypt {
    fun init(ownKeyBytes: ByteArray)
    fun decrypt(cipherData: ByteArray, offset: Int, bytesRead: Int): Int
}
