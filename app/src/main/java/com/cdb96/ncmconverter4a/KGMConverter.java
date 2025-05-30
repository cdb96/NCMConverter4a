package com.cdb96.ncmconverter4a;

import com.cdb96.ncmconverter4a.JNIUtil.KGMDecrypt;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;


public class KGMConverter {
    private static final ThreadLocal<byte[]> ownKeyBytes = ThreadLocal.withInitial(() -> new byte[17]);
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

    public static void write(InputStream inputStream, OutputStream outputStream, String format) throws Exception {
        int bufferSize = 4 * 1024 * 1024;
        if (bufferSize > inputStream.available()) {
            bufferSize = inputStream.available();
        }

        byte formatIdentifier = 0;
        byte[] buffer = new byte[bufferSize];
        if (Objects.equals(format, "flac")) {
            formatIdentifier = 0x66;
        } else if (Objects.equals(format, "mp3")) {
            formatIdentifier = 0x49;
        }

        int pos = 0;
        KGMDecrypt.init(ownKeyBytes.get());
        int bytesRead;
        //消除读取格式带来的1字节偏差,这个buffer[0]第一次是乱填的
        buffer[0] = 0x25;
        inputStream.read(buffer,1,bufferSize-1);
        pos = KGMDecrypt.decrypt(buffer,pos,bufferSize);
        buffer[0] = formatIdentifier;
        outputStream.write(buffer, 0, bufferSize);
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            pos = KGMDecrypt.decrypt(buffer, pos, bytesRead);
            outputStream.write(buffer, 0, bytesRead);
        }
    }

    public static String detectFormat(InputStream inputStream) throws Exception {
        //KGM magic header实际长度为16字节,上面为了简化检测步骤弄成两个字节，这里补回来
        inputStream.skip(16 - 2);
        byte[] headerLengthBytes = new byte[4];

        inputStream.read(headerLengthBytes, 0, 4);
        inputStream.skip(8);
        inputStream.read(ownKeyBytes.get(), 0, 17);
        Objects.requireNonNull(ownKeyBytes.get())[16] = 0;

        int headerLength = LengthUtils.getLittleEndianInteger(headerLengthBytes);
        //减去之前读取的字节
        inputStream.skip(headerLength - 17 - 8 - 4 - 16);

        byte[] formatIdentifier = new byte[1];
        byte keyBytes = 0;
        byte MaskV2PreDef0 = (byte) 0xB8;
        inputStream.read(formatIdentifier, 0, 1);
        int med8 = Objects.requireNonNull(ownKeyBytes.get())[0] ^ formatIdentifier[0];
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
