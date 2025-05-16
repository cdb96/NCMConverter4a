package com.cdb96.ncmconverter4a.JNIUtil;
public class RC4Decrypt {
    static {
        System.loadLibrary("ncmc4a");
    }

    public static native byte[] ksa(byte[] key);
    public static native void prgaDecrypt(byte[] sBox, byte[] cipherData, int bytesRead);
}
