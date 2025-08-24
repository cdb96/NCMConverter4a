package com.cdb96.ncmconverter4a.converter;

import static com.cdb96.ncmconverter4a.util.DirectBufferPool.safeWrite;

import com.cdb96.ncmconverter4a.tag.ID3TagBuilder;
import com.cdb96.ncmconverter4a.tag.FLACMetadataGenerator;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import com.cdb96.ncmconverter4a.util.DirectBufferPool;
import com.cdb96.ncmconverter4a.util.LengthUtils;
import com.cdb96.ncmconverter4a.util.SimpleJsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.StringJoiner;

public class NCMConverter {
    public record ncmFileInfo(byte[] RC4key, byte[] coverData, ArrayList<String> musicInfoStringArrayValue) {}

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    public static byte[] decrypt(byte[] key, byte[] encryptedBytes) throws Exception
    {
        SecretKeySpec secretKey = new SecretKeySpec(key,ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE,secretKey);
        return cipher.doFinal(encryptedBytes);
    }

    private static byte[] getRC4key(InputStream fileStream) throws Exception
    {
        //这里-2是因为之前检测KGM文件的时候已经读取两个字节了
        fileStream.skip(10 - 2);
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

    public static byte[] expandByteArray(byte[] original, int addtionalBytes) {
        if (original.length + addtionalBytes > 24 * 1024 * 1024) {
            throw new OutOfMemoryError("扩展长度过长");
        }
        byte[] newArray = new byte[original.length + addtionalBytes];
        System.arraycopy(original, 0, newArray, 0, original.length);
        return newArray;
    }

    public static void modifyHeader(FileInputStream fileStream, FileOutputStream fileOutputStream, ArrayList<String> musicInfo, byte[] coverData,int bufferSize) throws Exception {
        byte[] preFetchChunk = new byte[bufferSize];
        fileStream.read(preFetchChunk, 0, bufferSize);
        RC4Decrypt.prgaDecryptByteArray(preFetchChunk, preFetchChunk.length);

        String musicName = musicInfo.get( musicInfo.indexOf("musicName") + 1);
        String musicArtist = musicInfo.get( musicInfo.indexOf("artist") + 1);
        String musicAlbum = musicInfo.get( musicInfo.indexOf("album") + 1);
        musicArtist = combineArtistsString(musicArtist);

        if (preFetchChunk[0] == 0x49) {
            byte[] ID3LengthBytes = new byte[4];
            System.arraycopy(preFetchChunk, 6, ID3LengthBytes, 0, 4);
            int ID3Length = LengthUtils.getSyncSafeInteger(ID3LengthBytes);

            if (ID3Length > bufferSize) {
                int expandSizeFactor = (ID3Length + bufferSize - 1) / bufferSize;
                int expandSize = expandSizeFactor * bufferSize;
                preFetchChunk = expandByteArray(preFetchChunk, expandSize - bufferSize);
                byte[] temp = new byte[expandSize - bufferSize];
                int bytesRead = fileStream.read(temp,  0, expandSize - bufferSize);
                RC4Decrypt.prgaDecryptByteArray(temp, bytesRead);
                System.arraycopy(temp, 0, preFetchChunk, bufferSize, bytesRead);
            }

            ID3TagBuilder ID3Header = new ID3TagBuilder();
            ID3Header.initDefaultTagHeader();
            ID3Header.addTIT2(musicName);
            ID3Header.addTPE1(musicArtist);
            ID3Header.addTALB(musicAlbum);
            ID3Header.addCover(coverData);

            byte[] mp3Header = ID3Header.outputHeader();
            fileOutputStream.write(mp3Header);
            fileOutputStream.write(preFetchChunk,ID3Length, preFetchChunk.length - ID3Length);

        } else if (preFetchChunk[0] == 0x66) {
            while (!LengthUtils.hasLastBlock(preFetchChunk)) {
                byte[] temp = new byte[bufferSize];
                fileStream.read(temp, 0, bufferSize);
                RC4Decrypt.prgaDecryptByteArray(temp,temp.length);
                preFetchChunk = expandByteArray(preFetchChunk, bufferSize);
                System.arraycopy(temp, 0, preFetchChunk, preFetchChunk.length - bufferSize, temp.length);
            }

            byte[] blockSizeBytes = new byte[3];
            int vorbisCommentBegin = LengthUtils.findVorbisComment(preFetchChunk);
            fileOutputStream.write(preFetchChunk,0,vorbisCommentBegin); //写0表示不是最后一个块
            System.arraycopy(preFetchChunk,vorbisCommentBegin + 1, blockSizeBytes,0,3);

            byte[] vendorLengthBytes = new byte[4];
            System.arraycopy(preFetchChunk,vorbisCommentBegin + 4, vendorLengthBytes,0,4); // +4跳过块头
            int vendorLength = LengthUtils.getLittleEndianInteger(vendorLengthBytes);
            byte[] vendorBytes = new byte[vendorLength];
            System.arraycopy(preFetchChunk,vorbisCommentBegin + 8, vendorBytes,0,vendorLength); // +8跳过块头和vendor长度字段
            int vorbisCommentSize = LengthUtils.getBigEndianInteger3bytes(blockSizeBytes);
            byte[] vorbisCommentBlock = FLACMetadataGenerator.vorbisCommentBlockGen(musicName,musicArtist,musicAlbum,vendorBytes);
            fileOutputStream.write(vorbisCommentBlock);

            int vorbisCommentEnd = vorbisCommentSize + vorbisCommentBegin + 4; //加上块头的4个字节,因为size不包含块头的4个字节
            int pictureBlockBegin = LengthUtils.findLastBlock(preFetchChunk);
            fileOutputStream.write(preFetchChunk,vorbisCommentEnd, pictureBlockBegin - vorbisCommentEnd);
            byte[] pictureBlock = FLACMetadataGenerator.pictureBlockGen(coverData);

            fileOutputStream.write(pictureBlock);
            fileOutputStream.write(preFetchChunk,pictureBlockBegin, preFetchChunk.length - pictureBlockBegin);
        }
    }

    public static void outputMusic(FileChannel outputChannel, FileChannel inputChannel) throws Exception {
        try (DirectBufferPool.Slot bufferSlot = DirectBufferPool.acquireDirectBuffer()){
            ByteBuffer buffer = bufferSlot.buffer;
            int bytesRead;
            while ((bytesRead = inputChannel.read(buffer)) != -1) {
                RC4Decrypt.prgaDecryptByteBuffer(buffer, bytesRead);
                safeWrite(outputChannel, buffer);
            }
        }
    }

    private static String combineArtistsString(String artistsString) {
        String[] artistsStringArray = artistsString.replaceAll("[\\\\\\[\\]\"]", "").split(",");
        StringJoiner joiner = new StringJoiner("/");
        for (int i = 0; i < artistsStringArray.length; i += 2){
            String trimmedArtist = artistsStringArray[i].trim();
            joiner.add(trimmedArtist);
        }
        return joiner.toString();
    }

    public static ncmFileInfo convert(InputStream fileStream) throws Exception
    {
        byte[] RC4Key = getRC4key(fileStream);
        byte[] metaBytes = getMetaData(fileStream);
        byte[] coverData = getCoverData(fileStream);

        String metaData = new String(metaBytes, StandardCharsets.UTF_8);
        ArrayList<String> musicInfo = SimpleJsonParser.parse(metaData);
        return new ncmFileInfo(RC4Key, coverData, musicInfo);
    }
}