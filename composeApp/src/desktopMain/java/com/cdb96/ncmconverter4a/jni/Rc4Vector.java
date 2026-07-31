package com.cdb96.ncmconverter4a.jni;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public class Rc4Vector {
    public static byte[] keyStreamBytes = new byte[256];
    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    public static void ksa(byte[] keyBytes) {
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
        int i = 0;
        for (; i + SPECIES.length() <= bytesRead; i += SPECIES.length()) {
            ByteVector vData = ByteVector.fromArray(SPECIES, data, i);
            ByteVector vKey = ByteVector.fromArray(SPECIES, keyStreamBytes, i & 0xff);
            ByteVector vResult = vData.lanewise(VectorOperators.XOR,vKey);
            vResult.intoArray(data,i);
        }
        for (; i < bytesRead; i++) {
            int j = i & 0xff;
            data[i] ^= keyStreamBytes[j];
        }
    }
}
