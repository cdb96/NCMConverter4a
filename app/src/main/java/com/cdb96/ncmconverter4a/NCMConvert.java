package com.cdb96.ncmconverter4a;

import com.cdb96.ncmconverter4a.JNIUtil.RC4Decrypt;
import android.util.Log;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;

class ConvertResult {
    byte[] RC4key;
    byte[] coverData;
    ArrayList<String> musicInfoStringArrayValue;
    boolean rawWriteMode;
    ConvertResult(byte[] RC4key,byte[] coverData,boolean rawWriteMode, ArrayList<String> musicInfoStringArrayValue) {
        this.RC4key = RC4key;
        this.coverData = coverData;
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
        if (keyLength < 1024) { //防止因导入错误的文件而崩溃
            bytes = new byte[keyLength];
        } else {
            throw new Exception();
        }
        fileStream.read(bytes, 0, keyLength);
        for (int i = 0; i < keyLength; i++) {
            bytes[i] ^= 0x64;
        }
        bytes = decrypt(CoreKey, bytes);
        byte[] key = new byte[bytes.length - 17];
        System.arraycopy(bytes, 17, key, 0, key.length);
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
        ArrayList<String> musicInfo = new ArrayList<>();
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

    public static byte[] expandByteArray(byte[] original, int newSize) {
        byte[] newArray = new byte[original.length + newSize];
        System.arraycopy(original, 0, newArray, 0, original.length);
        return newArray;
    }
    public static void modifyHeader(InputStream fileStream, OutputStream fileOutputStream, byte[] sBox, ArrayList<String> musicInfo, byte[] coverData,int bufferSize) throws Exception {
        byte[] preFetchHeader = new byte[bufferSize];
        fileStream.read(preFetchHeader, 0, bufferSize);
        RC4Decrypt.prgaDecrypt(sBox, preFetchHeader, preFetchHeader.length);

        String musicName = musicInfo.get( musicInfo.indexOf("musicName") + 1);
        String musicArtist = musicInfo.get( musicInfo.indexOf("artist") + 1);
        String musicAlbum = musicInfo.get( musicInfo.indexOf("album") + 1);
        musicArtist = combineArtistsString(musicArtist);

        if (preFetchHeader[0] == 0x49) {
            byte[] ID3LengthBytes = new byte[4];
            System.arraycopy(preFetchHeader, 6, ID3LengthBytes, 0, 4);
            int ID3Length = LengthUtils.getSyncSafeInteger(ID3LengthBytes);

            if (ID3Length > bufferSize) {
                int expandSizeFactor = (ID3Length + bufferSize - 1) / bufferSize;
                int expandSize = expandSizeFactor * bufferSize;
                preFetchHeader  = expandByteArray(preFetchHeader, expandSize);
                byte[] temp = new byte[expandSize - bufferSize];
                System.arraycopy(temp, 0, preFetchHeader, bufferSize, expandSize - bufferSize);
            }


            ID3HeaderGen ID3Header = new ID3HeaderGen();
            ID3Header.initDefaultTagHeader();
            ID3Header.addTIT2(musicName);
            ID3Header.addTPE1(musicArtist);
            ID3Header.addTALB(musicAlbum);
            ID3Header.addCover(coverData);

            byte[] mp3Header = ID3Header.outputHeader();
            fileOutputStream.write(mp3Header);
            fileOutputStream.write(preFetchHeader,0, preFetchHeader.length - ID3Length);

        } else if (preFetchHeader[0] == 0x66) {
            while (!LengthUtils.hasLastBlock(preFetchHeader)) {
                byte[] temp = new byte[bufferSize];
                fileStream.read(temp, 0, bufferSize);
                RC4Decrypt.prgaDecrypt(sBox,temp,temp.length);
                preFetchHeader = expandByteArray(preFetchHeader, bufferSize);
                System.arraycopy(temp, 0, preFetchHeader, preFetchHeader.length - bufferSize, temp.length);
            };

            byte[] blockSizeBytes = new byte[3];
            int vorbisCommentBegin = LengthUtils.findVorbisComment(preFetchHeader);
            fileOutputStream.write(preFetchHeader,0,vorbisCommentBegin);
            System.arraycopy(preFetchHeader,vorbisCommentBegin + 1, blockSizeBytes,0,3);

            byte[] vendorLengthBytes = new byte[4];
            System.arraycopy(preFetchHeader,vorbisCommentBegin + 4, vendorLengthBytes,0,4);
            int vendorLength = LengthUtils.getLittleEndianInteger(vendorLengthBytes);
            byte[] vendorBytes = new byte[vendorLength];
            System.arraycopy(preFetchHeader,vorbisCommentBegin + 8, vendorBytes,0,vendorLength);
            int vorbisCommentSize = LengthUtils.getBigEndianInteger3bytes(blockSizeBytes);
            byte[] vorbisCommentBlock = FLACHeaderGen.vorbisCommentBlockGen(musicName,musicArtist,musicAlbum,vendorBytes);
            fileOutputStream.write(vorbisCommentBlock);

            int vorbisCommentEnd = vorbisCommentSize + vorbisCommentBegin + 4; //加上块头的4个字节
            int pictureBlockBegin = LengthUtils.findLastBlock(preFetchHeader);
            fileOutputStream.write(preFetchHeader,vorbisCommentEnd, pictureBlockBegin - vorbisCommentEnd);
            byte[] pictureBlock = FLACHeaderGen.pictureBlockGen(coverData);

            fileOutputStream.write(pictureBlock);
            fileOutputStream.write(preFetchHeader,pictureBlockBegin,preFetchHeader.length - pictureBlockBegin);
        }
    }
    public static void outputMusic(OutputStream outputFileStream,InputStream fileStream, byte[] RC4Key, byte[] coverData, boolean rawWriteMode, ArrayList<String> musicInfo) throws Exception {
        int bufferSize = 4 * 1024 * 1024;
        if (fileStream.available() < bufferSize) {
            bufferSize = fileStream.available();
        }
        byte[] sbox = RC4Decrypt.ksa(RC4Key);
        byte[] buffer = new byte[bufferSize];
        if (!rawWriteMode) {
            modifyHeader(fileStream, outputFileStream, sbox, musicInfo, coverData,bufferSize);
        }
        int bytesRead;
        while ((bytesRead = fileStream.read(buffer)) != -1) {
            long startTime = System.nanoTime();
            RC4Decrypt.prgaDecrypt(sbox, buffer, bytesRead);
            long duration = System.nanoTime() - startTime;
            Log.d("Performance", "耗时: " + duration / 1_000_000 + "ms");
            outputFileStream.write(buffer, 0, bytesRead);
        }
    }
    private static String combineArtistsString(String artistsString) {
        String[] artistsStringArray = artistsString.replaceAll("[\\[\\]\"]","").split(",");
        StringBuilder combinedArtistsString = new StringBuilder();
        for (int i = 0; i < artistsStringArray.length; i += 2){
            combinedArtistsString.append(artistsStringArray[i]).append("/");
        }
        return combinedArtistsString.substring(0,combinedArtistsString.length() - 1);
    }
    public static ConvertResult convert(InputStream fileStream,boolean rawWriteMode) throws Exception
    {
        byte[] RC4Key = getRC4key(fileStream);
        byte[] metaBytes = getMetaData(fileStream);
        byte[] coverData = getCoverData(fileStream);

        String metaData = new String(metaBytes, StandardCharsets.UTF_8);
        ArrayList<String> musicInfo = parseMetaData(metaData);
        return new ConvertResult(RC4Key,coverData,rawWriteMode,musicInfo);
    }
}