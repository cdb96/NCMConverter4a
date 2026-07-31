package com.cdb96.ncmconverter4a.jni

/**
 * 桌面端 KGM 解密：委托给 Java + Vector API 实现（KgmVector）。
 *
 * Kotlin 2.4.10 无法直接解析 jdk.incubator.vector，故 SIMD 循环用 Java 封装。
 * 算法与 C++ NEON 版本逐字节一致。
 */
actual object KGMDecrypt {
    actual fun init(ownKeyBytes: ByteArray) = KgmVector.init(ownKeyBytes)
    actual fun decrypt(cipherData: ByteArray, offset: Int, bytesRead: Int): Int =
        KgmVector.decrypt(cipherData, offset, bytesRead)
}
