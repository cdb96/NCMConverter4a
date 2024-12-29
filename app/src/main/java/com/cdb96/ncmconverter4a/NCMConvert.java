package com.cdb96.ncmconverter4a;

import android.os.Build;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.io.ByteArrayOutputStream;
class getResult {
    byte[] byteArrayValue;
    ArrayList<String> StringArrayValue;
    getResult(byte[] byteArrayValue, ArrayList<String> StringArrayValue) {
        this.byteArrayValue = byteArrayValue;
        this.StringArrayValue = StringArrayValue;
    }
}
class NCMConverter {
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    public static byte[] decrypt(byte[] key,byte[] encryptedBytes) throws Exception
    {
        SecretKeySpec secretKey = new SecretKeySpec(key,ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE,secretKey);
        return cipher.doFinal(encryptedBytes);
    }

    private static int getLength(byte[] bytes) throws Exception
    {
        int result = 0;
        for (int i = 0; i<4 ; i++){
            result |= (bytes[i] & 0xFF) << (i*8);
        };
        return result;
    }

    private static byte[] getRC4key(InputStream fileStream) throws Exception
    {
        fileStream.skip(10);
        byte[] CoreKey = {0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F, 0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57};
        byte[] bytes = new byte[4];
        fileStream.read(bytes, 0, 4);
        int keyLength = getLength(bytes);
        bytes = new byte[keyLength];
        fileStream.read(bytes,0,keyLength);
        for (int i = 0; i < keyLength; i++) {
            bytes[i] ^= 0x64;
        };
        bytes = decrypt(CoreKey,bytes);
        byte[] key = new byte[bytes.length - 17];
        System.arraycopy(bytes,17,key,0,key.length);
        return key;
    }

    private static byte[] getMetaData(InputStream fileStream) throws Exception
    {
        byte[] metaKey = {0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21, 0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28};
        byte[] rawMetaLength= new byte[4];
        fileStream.read(rawMetaLength,0,4);
        int metaLength = getLength(rawMetaLength);
        byte[] rawMetaBytes = new byte[metaLength];
        fileStream.read(rawMetaBytes,0,metaLength);
        for (int i = 0; i < metaLength; i++) {
            rawMetaBytes[i] ^= 0x63;
        }
        byte[] metaBytes = new byte[rawMetaBytes.length - 22];
        System.arraycopy(rawMetaBytes, 22, metaBytes, 0, metaBytes.length);
        metaBytes = Base64.getDecoder().decode(metaBytes);
        metaBytes = decrypt(metaKey,metaBytes);
        return metaBytes;
    }

    private static ArrayList<String> parseMetaData(String metaData)
    {
        ArrayList<String> musicInfo = new ArrayList<String>();
        int count = 0;
        for (int i = 0; i < metaData.length() - 1; i++) {
            if ( (count+2) % 2 == 0) {
                if (metaData.charAt(i) == '\"') {
                    int j = i + 1;
                    while (metaData.charAt(j) != '\"') {
                        j++;
                    }
                    musicInfo.add(metaData.substring(i + 1, j));
                    i = j;
                    count++;
                }
            } else {
                int j = ++i;
                if (metaData.charAt(i) == '[' || metaData.charAt(i) == '{') {
                    int leftBracketCount = 0;
                    while (metaData.charAt(j) == '[' || metaData.charAt(j) == '{') {
                        leftBracketCount++;
                        j++;
                        if (metaData.charAt(i) == '[') {
                            while (metaData.charAt(j) != ']') {
                                j++;
                            }
                        }
                        else if (metaData.charAt(i) == '{')
                            while (metaData.charAt(j) != '}') {
                                j++;
                            }
                    }
                    musicInfo.add(metaData.substring(i, j + leftBracketCount));
                }
                else {
                    do {
                        j++;
                    } while (metaData.charAt(j) != ',');
                    if (metaData.charAt(i) != '\"') {
                        musicInfo.add(metaData.substring(i, j));
                    } else {
                        musicInfo.add(metaData.substring(i + 1, j - 1));
                    }
                }
                i = j;
                count++;
            }
        }
        return musicInfo;
    }

    private static byte[] getCoverData(InputStream fileStream) throws Exception
    {
        fileStream.skip(5);

        byte[] bytes = new byte[4];
        fileStream.read(bytes,0,4);
        int coverFrameLength = getLength(bytes);

        fileStream.read(bytes,0,4);
        int image1Length = getLength(bytes);
        byte[] image1Data = new byte[image1Length];

        fileStream.read(image1Data,0, image1Length);
        fileStream.skip(coverFrameLength - image1Data.length);

        return image1Data;
    }

    private static byte[] outputMusic(InputStream fileStream, byte[] RC4Key) throws Exception
    {
        int fileLength = fileStream.available();
        byte[] sbox = RC4Util.ksa(RC4Key);
        byte[] fileData = new byte[fileLength];
        ByteArrayOutputStream musicDataByte = new ByteArrayOutputStream();
        //这里我也不知道为什么搞缓存区后就有杂音了，只能像下面这么搞
        fileStream.read(fileData,0,fileLength);
        RC4Util.prgaDecrypt(sbox,fileData);
        musicDataByte.write(fileData,0,fileLength);

        return musicDataByte.toByteArray();
    }

    public static getResult convert(InputStream fileStream) throws Exception
    {
        byte[] RC4Key = getRC4key(fileStream);
        byte[] metaBytes = getMetaData(fileStream);
        byte[] coverData = getCoverData(fileStream);

        String metaData = new String(metaBytes, StandardCharsets.UTF_8);
        ArrayList<String> musicInfo = parseMetaData(metaData);
        byte[] musicData = outputMusic(fileStream,RC4Key);
        return new getResult(musicData,musicInfo);
    }
}