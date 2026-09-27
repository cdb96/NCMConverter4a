package com.cdb96.ncmconverter4a.jni;

public final class KGMDecrypt {
    public static native long create(byte[] ownKeyBytes);
    public static native int decrypt(long context, byte[] cipherData, int offset, int bytesRead);
    public static native void destroy(long context);
}
