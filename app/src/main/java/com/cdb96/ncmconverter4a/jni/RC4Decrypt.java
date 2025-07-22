package com.cdb96.ncmconverter4a.jni;

import java.nio.ByteBuffer;

public class RC4Decrypt {
    static {
        System.loadLibrary("ncmc4a");
    }

    public static native void ksa(byte[] key);
    public static native void prgaDecryptByteBuffer(ByteBuffer cipherData, int bytesRead);
    public static native void prgaDecryptByteArray(byte[] cipherData, int bytesRead);
}
