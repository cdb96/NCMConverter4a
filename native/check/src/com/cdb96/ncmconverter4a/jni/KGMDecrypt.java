package com.cdb96.ncmconverter4a.jni;

public final class KGMDecrypt {
    public static native void init(byte[] ownKeyBytes);
    public static native int decrypt(byte[] cipherData, int offset, int bytesRead);
}
