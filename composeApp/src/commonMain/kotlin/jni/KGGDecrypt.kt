package com.cdb96.ncmconverter4a.jni

internal expect object KGGDecrypt {
    fun prepare(key: ByteArray): ByteArray
    fun decrypt(state: ByteArray, data: ByteArray, offset: Long, length: Int)
}
