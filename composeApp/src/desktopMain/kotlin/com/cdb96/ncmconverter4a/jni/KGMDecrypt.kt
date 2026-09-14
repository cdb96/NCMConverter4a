package com.cdb96.ncmconverter4a.jni

/**
 * Desktop KGM bridge.
 *
 * The algorithm is the shared C++ core; only the bridge differs per platform,
 * so Android and Desktop run the same implementation.
 */
actual object KGMDecrypt {
    init {
        NativeLibrary.load()
    }

    @JvmStatic
    actual external fun init(ownKeyBytes: ByteArray)

    @JvmStatic
    actual external fun decrypt(cipherData: ByteArray, offset: Int, bytesRead: Int): Int
}
