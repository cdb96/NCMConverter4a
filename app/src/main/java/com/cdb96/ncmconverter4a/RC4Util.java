package com.cdb96.ncmconverter4a;

class RC4Util {
    public static byte[] ksa(byte[] key)
    {
        byte[] sBox = new byte[256];
        int j = 0;
        for (int i = 0; i < 256; i++)
        {
            sBox[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++)
        {
            j = (j + (sBox[i] & 0xFF) + (key[i % key.length] & 0xFF)) & 0xFF;
            byte temp = sBox[i];
            sBox[i] = sBox[j];
            sBox[j] = temp;
        }
        return sBox;
    }

    public static void prgaDecrypt(byte[] sBox, byte[] ciphertext)
    {
        for (int i = 1,j = 1; i < ciphertext.length; i++ )
        {
            j = i & 0xff;
            ciphertext[i-1] ^= sBox[(sBox[j] + sBox[(sBox[j]+j) & 0xff ]) & 0xff];
        }
    }
}