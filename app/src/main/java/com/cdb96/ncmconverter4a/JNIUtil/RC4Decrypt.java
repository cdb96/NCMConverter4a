package com.cdb96.ncmconverter4a.JNIUtil;
public class RC4Decrypt {
    static {
        System.loadLibrary("ncmc4a");
    }

    public static native void ksa(byte[] key);
    public static native void prgaDecrypt(byte[] cipherData, int bytesRead);
}
