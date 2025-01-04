package com.cdb96.ncmconverter4a;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.io.ByteArrayOutputStream;

class ConvertResult {
    byte[] musicDataByteArray;
    ArrayList<String> musicInfoStringArrayValue;
    ConvertResult(byte[] musicDataByteArray, ArrayList<String> musicInfoStringArrayValue) {
        this.musicDataByteArray = musicDataByteArray;
        this.musicInfoStringArrayValue = musicInfoStringArrayValue;
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
    private static byte[] getRC4key(InputStream fileStream) throws Exception
    {
        fileStream.skip(10);
        byte[] CoreKey = {0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F, 0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57};
        byte[] bytes = new byte[4];
        fileStream.read(bytes, 0, 4);
        int keyLength = LengthUtils.getLittleEndianInteger(bytes);
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
        int metaLength = LengthUtils.getLittleEndianInteger(rawMetaLength);
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

    private static ArrayList<String> parseMetaData(String metaData) {
        ArrayList<String> musicInfo = new ArrayList<String>();
        for (int i = 0,j; i < metaData.length() - 1; i++) {
            if (metaData.charAt(i) == '\"') {
                j = i + 1;
                while (metaData.charAt(j) != '\"' && j < metaData.length() - 1) {
                    j++;
                }
                musicInfo.add(metaData.substring(i + 1, j));
                i = j + 2;
                j = i ;
                if (metaData.charAt(i) == '[' || metaData.charAt(i) == '{') {
                    int leftBracketCount = 1;
                    int rightBracketCount = 0;
                    j++;
                    while (leftBracketCount != rightBracketCount && j < metaData.length() -1 ) {
                        if ( metaData.charAt(j) == '[' || metaData.charAt(j) == '{') {
                            leftBracketCount++;
                        } else if (metaData.charAt(j) == ']' || metaData.charAt(j) == '}') {
                            rightBracketCount++;
                        }
                        j++;
                    }
                    musicInfo.add(metaData.substring(i,j));
                } else {
                    while (metaData.charAt(j) != ',' && j < metaData.length() - 1) {
                        j++;
                    }
                    if (metaData.charAt(i) == '\"'){
                        musicInfo.add(metaData.substring(i + 1,j - 1));
                    } else {
                        musicInfo.add(metaData.substring(i,j));
                    }
                }
                i = j ;
            }
        }
        return musicInfo;
    }

    private static byte[] getCoverData(InputStream fileStream) throws Exception
    {
        fileStream.skip(5);
        byte[] bytes = new byte[4];

        fileStream.read(bytes,0,4);
        int coverFrameLength = LengthUtils.getLittleEndianInteger(bytes);

        fileStream.read(bytes,0,4);
        int image1Length = LengthUtils.getLittleEndianInteger(bytes);

        byte[] image1Data = new byte[image1Length];
        fileStream.read(image1Data,0, image1Length);
        fileStream.skip(coverFrameLength - image1Data.length);

        return image1Data;
    }
    private static byte[] outputMusic(InputStream fileStream, byte[] RC4Key,byte[] coverData,boolean rawWriteMode) throws Exception {
        int fileLength = fileStream.available();
        byte[] sbox = RC4Util.ksa(RC4Key);
        byte[] fileData = new byte[fileLength];
        ByteArrayOutputStream musicDataByte = new ByteArrayOutputStream();
        fileStream.read(fileData, 0, fileLength);
        RC4Util.prgaDecrypt(sbox, fileData);

        byte[] ID3LengthBytes = new byte[4];
        System.arraycopy(fileData, 6, ID3LengthBytes, 0, 4);
        int ID3Length = LengthUtils.getSyncSafeInteger(ID3LengthBytes);
        if (!rawWriteMode) {
            if (fileData[0] == 0x49) {
                ID3HeaderGen ID3Header = new ID3HeaderGen();
                ID3Header.initDefaultTagHeader();
                ID3Header.addCover(coverData);
                byte[] mp3Header = ID3Header.outputHeader();
                musicDataByte.write(mp3Header);
                musicDataByte.write(fileData, ID3Length, fileLength - ID3Length);
            } else if (fileData[0] == 0x66) {
                byte[] blockSizeBytes = new byte[3];
                int vorbisCommentBegin = LengthUtils.findVorbisComment(fileData);
                System.arraycopy(fileData,vorbisCommentBegin, blockSizeBytes,0,3);
                int vorbisCommentSize = LengthUtils.getBigEndianInteger3bytes(blockSizeBytes);

                int pictureBlockBegin = LengthUtils.findLastMetaData(fileData);
                musicDataByte.write(fileData,0,pictureBlockBegin);
                byte[] pictureBlock = FLACHeaderGen.pictureBlockGen(coverData);

                musicDataByte.write(pictureBlock);
                musicDataByte.write(fileData, pictureBlockBegin, fileLength - pictureBlockBegin);
            }
        } else {
            musicDataByte.write(fileData);
        }
        return musicDataByte.toByteArray();
    }

    public static ConvertResult convert(InputStream fileStream,boolean rawWriteMode) throws Exception
    {
        byte[] RC4Key = getRC4key(fileStream);
        byte[] metaBytes = getMetaData(fileStream);
        byte[] coverData = getCoverData(fileStream);

        String metaData = new String(metaBytes, StandardCharsets.UTF_8);
        ArrayList<String> musicInfo = parseMetaData(metaData);
        byte[] musicData = outputMusic(fileStream,RC4Key,coverData,rawWriteMode);
        return new ConvertResult(musicData,musicInfo);
    }
}