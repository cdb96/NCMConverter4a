package com.cdb96.ncmconverter4a.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

actual object AESDecrypt {
    actual fun decryptEcbPkcs5(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }
}
