package com.cdb96.ncmconverter4a;
public class RC4Jni {
    static {
        System.loadLibrary("RC4Decrypt");
    }

    public static native byte[] ksa(byte[] key);
    public static native void prgaDecrypt(byte[] sBox, byte[] cipherData, int bytesRead);
}
