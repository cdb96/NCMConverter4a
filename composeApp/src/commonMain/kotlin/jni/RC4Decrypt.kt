package com.cdb96.ncmconverter4a.jni

expect object RC4Decrypt {
    fun ksa(key: ByteArray)
    fun prgaDecrypt(cipherData: ByteArray, bytesRead: Int)
}
