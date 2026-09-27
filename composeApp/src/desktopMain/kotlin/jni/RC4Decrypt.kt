package com.cdb96.ncmconverter4a.jni

/**
 * Desktop RC4 bridge.
 *
 * The algorithm is the shared C++ core; only the bridge differs per platform,
 * so Android and Desktop run the same implementation.
 */
actual object RC4Decrypt {
    init {
        NativeLibrary.load()
    }

    @JvmStatic
    actual external fun create(key: ByteArray): Long

    @JvmStatic
    actual external fun decrypt(context: Long, cipherData: ByteArray, bytesRead: Int)

    @JvmStatic
    actual external fun destroy(context: Long)
}
