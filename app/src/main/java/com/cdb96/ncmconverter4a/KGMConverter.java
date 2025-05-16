package com.cdb96.ncmconverter4a;

import com.cdb96.ncmconverter4a.JNIUtil.KGMDecrypt;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;


public class KGMConverter {
    static byte[] ownKeyBytes = new byte[17];
    public static boolean KGMDetect(InputStream inputStream) throws Exception {
        final byte[] KGMMagicHeader = {0x7c, (byte) 0xd5, 0x32, (byte) 0xeb, (byte) 0x86, 0x02, 0x7f, 0x4b, (byte) 0xa8, (byte) 0xaf, (byte) 0xa6, (byte) 0x8e, 0x0f, (byte) 0xff, (byte) 0x99, 0x14};
        byte[] fileHeader = new byte[16];
        inputStream.read(fileHeader, 0, 16);
        if (Arrays.equals(fileHeader, KGMMagicHeader)) {
            System.out.println("KGM file detected");
            return true;
        } else {
            System.out.println("KGM file not detected");
            return false;
        }
    }

    public static void write(InputStream inputStream, OutputStream outputStream, String format, int initOffset) throws Exception {
        int bufferSize = 8 * 1024 * 1024;
        if (bufferSize > inputStream.available()) {
            bufferSize = inputStream.available();
        }

        byte[] buffer = new byte[bufferSize];
        if (Objects.equals(format, "flac")) {
            outputStream.write(0x66);
        } else if (Objects.equals(format, "mp3")) {
            outputStream.write(0x49);
        }

        int pos = initOffset;
        KGMDecrypt.init(ownKeyBytes);
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            pos = KGMDecrypt.decrypt(buffer, pos, bytesRead);
            outputStream.write(buffer, 0, bytesRead);
        }
    }

    public static String detectFormat(InputStream inputStream) throws Exception {
        byte[] headerLengthBytes = new byte[4];

        inputStream.read(headerLengthBytes, 0, 4);
        inputStream.skip(8);
        inputStream.read(ownKeyBytes, 0, 17);
        ownKeyBytes[16] = 0;

        int headerLength = LengthUtils.getLittleEndianInteger(headerLengthBytes);
        inputStream.skip(headerLength - 17 - 8 - 4 - 16);

        byte[] formatIdentifier = new byte[1];
        byte keyBytes = 0;
        byte MaskV2PreDef0 = (byte) 0xB8;
        inputStream.read(formatIdentifier, 0, 1);
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
