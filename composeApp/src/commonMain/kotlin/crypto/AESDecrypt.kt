package com.cdb96.ncmconverter4a.crypto

expect object AESDecrypt {
    fun decryptEcbPkcs5(key: ByteArray, data: ByteArray): ByteArray
}
