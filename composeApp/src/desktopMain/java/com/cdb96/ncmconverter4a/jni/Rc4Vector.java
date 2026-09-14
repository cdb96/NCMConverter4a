package com.cdb96.ncmconverter4a.jni;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public class Rc4Vector {
    private static final ThreadLocal<byte[]> KEY_STREAM =
            ThreadLocal.withInitial(() -> new byte[256]);
    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    public static void ksa(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length == 0) {
            throw new IllegalArgumentException("RC4 key must not be null or empty");
        }
        byte[] keyStreamBytes = KEY_STREAM.get();
        int keyLength = keyBytes.length;
        byte[] sBox = new byte[256];
        for (int i=0; i < 256; i++) {
            sBox[i] = (byte) i;
         }
        int j = 0;
        for (int i = 0; i < 256; ++i) {
            j = (j + sBox[i] + keyBytes[i % keyLength]) & 0xFF;
            byte temp = sBox[i];
            sBox[i] = sBox[j];
            sBox[j] = temp;
        }
        for (int k = 1; k < 256; ++k) {
            keyStreamBytes[k - 1] = sBox[ (sBox[k] + sBox [ ( sBox[k] + k ) & 0xff ] ) & 0xff ];
        }
        keyStreamBytes[255] = sBox[ (sBox[0] + sBox [ (sBox[0]) & 0xff ] ) & 0xff ];
    }

    public static void decrypt(byte[] data,int bytesRead){
        if (data == null) {
            throw new IllegalArgumentException("RC4 data must not be null");
        }
        if (bytesRead < 0 || bytesRead > data.length) {
            throw new IllegalArgumentException(
                    "bytesRead must be between 0 and data.length: " + bytesRead);
        }
        byte[] keyStreamBytes = KEY_STREAM.get();
        int i = 0;
        int vectorLength = SPECIES.length();
        while (i < bytesRead) {
            int keyOffset = i & 0xff;
            int remaining = bytesRead - i;
            int untilKeyWrap = 256 - keyOffset;
            if (remaining >= vectorLength && untilKeyWrap >= vectorLength) {
                ByteVector vData = ByteVector.fromArray(SPECIES, data, i);
                ByteVector vKey = ByteVector.fromArray(SPECIES, keyStreamBytes, keyOffset);
                ByteVector vResult = vData.lanewise(VectorOperators.XOR,vKey);
                vResult.intoArray(data,i);
                i += vectorLength;
            } else {
                data[i] ^= keyStreamBytes[keyOffset];
                i++;
            }
        }
    }
}
