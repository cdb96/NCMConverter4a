package com.cdb96.ncmconverter4a;

import java.nio.ByteBuffer;

public class RC4Jni {
    static {
        System.loadLibrary("RC4Decrypt");
    }

    public static native byte[] ksa(byte[] key);
    public static native byte[] prgaDecrypt(byte[] sBox, byte[] cipherData);
}
