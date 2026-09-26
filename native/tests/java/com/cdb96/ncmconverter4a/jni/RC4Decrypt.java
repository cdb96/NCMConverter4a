package com.cdb96.ncmconverter4a.jni;

/** Mirrors the Kotlin `actual object` declarations so the JNI symbol names
 *  under test are exactly the ones the Android/Desktop build uses. */
public final class RC4Decrypt {
    public static native void ksa(byte[] key);
    public static native void prgaDecryptByteArray(byte[] cipherData, int bytesRead);
}
