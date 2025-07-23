package com.cdb96.ncmconverter4a.converter;

import static com.cdb96.ncmconverter4a.util.DirectBufferPool.safeWrite;

import com.cdb96.ncmconverter4a.jni.KGMDecrypt;
import com.cdb96.ncmconverter4a.util.DirectBufferPool;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class KGMConverter {
    public static boolean KGMDetect(InputStream inputStream) throws Exception {
        final byte[] truncatedKGMMagicHeader = {0x7c, (byte) 0xd5};
        byte[] fileHeader = new byte[2];
        inputStream.read(fileHeader, 0, 2);
        if (Arrays.equals(fileHeader, truncatedKGMMagicHeader)) {
            System.out.println("KGM file detected");
            return true;
        } else {
            System.out.println("KGM file not detected");
            return false;
        }
    }

    public static void write(FileChannel inputChannel, FileChannel outputChannel, byte[] ownKeyBytes) throws Exception {
        KGMDecrypt.init(ownKeyBytes);

        inputChannel.position(1024);

        try (DirectBufferPool.Slot bufferSlot = DirectBufferPool.acquireDirectBuffer()){
            ByteBuffer buffer = bufferSlot.buffer;
            int bytesRead;
            int pos = 0;
            while ((bytesRead = inputChannel.read(buffer)) != -1) {
                pos = KGMDecrypt.decrypt(buffer, pos, bytesRead);
                safeWrite(outputChannel, buffer);
            }
        }
    }

    public static byte[] getOwnKeyBytes(FileInputStream inputStream) throws Exception {
        final int HEADER_LENGTH = 1024;
        byte[] ownKeyBytes = new byte[17];

        //KGM magic header实际长度为16字节,上面为了简化检测步骤弄成两个字节，这里16-2补回来
        inputStream.skip(16 - 2 + 8 + 4);
        inputStream.read(ownKeyBytes);
        ownKeyBytes[16] = 0;
        //减去之前读取的字节
        inputStream.skip(HEADER_LENGTH - 17 - 8 - 4 - 16);

        return ownKeyBytes;
    }
    public static String detectFormat(FileInputStream inputStream,byte[] ownKeyBytes) throws Exception {
        byte[] formatIdentifier = new byte[1];
        byte keyBytes = 0;
        byte MaskV2PreDef0 = (byte) 0xB8;
        inputStream.read(formatIdentifier);
        int med8 = ownKeyBytes[0] ^ formatIdentifier[0];
        med8 ^= (med8 & 0xf) << 4;
        int msk8 = keyBytes ^ MaskV2PreDef0;
        msk8 ^= (msk8 & 0xf) << 4;
        formatIdentifier[0] = (byte) (med8 ^ msk8);

        if (formatIdentifier[0] == 0x66) {
            return "flac";
        } else if (formatIdentifier[0] == 0x49) {
            return "mp3";
        }
        return "";
    }
}
