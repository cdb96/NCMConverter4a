package com.cdb96.ncmconverter4a.JNIUtil;

import java.nio.ByteBuffer;

public class KGMDecrypt {
    static {
        System.loadLibrary("ncmc4a");
    }

    public static native int decrypt(byte[] cipherDataBytes,int offset,int bytesRead);
    public static native void init(byte[] ownKeyBytes);
}
