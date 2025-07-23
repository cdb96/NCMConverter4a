package com.cdb96.ncmconverter4a.util;

public class LengthUtils {

    public static byte[] toSyncSafeIntegerBytes(int value) {
        return new byte[] {
                (byte) ((value >> 21) & 0x7F),
                (byte) ((value >> 14) & 0x7F),
                (byte) ((value >> 7) & 0x7F),
                (byte) (value & 0x7F)
        };
    }

    public static int getLittleEndianInteger(byte[] bytes) {
        return (bytes[0] & 0xFF) |
                ((bytes[1] & 0xFF) << 8) |
                ((bytes[2] & 0xFF) << 16) |
                ((bytes[3] & 0xFF) << 24);
    }

    public static byte[] toBigEndianBytes(int value) {
        return new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    public static byte[] toBigEndianInteger3Bytes(int value) {
        return new byte[] {
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    public static int getBigEndianInteger3bytes(byte[] bytes)
    {
        return ( (bytes[0] & 0xFF) << 16 ) | ( (bytes[1] & 0xFF) << 8 ) | ( bytes[2] & 0xFF );
    }

    public static int getSyncSafeInteger(byte[] bytes)
    {
        return ( (bytes[0] & 0x7f)<< 21 ) + ( (bytes[1] & 0x7f)<< 14 ) + ( (bytes[2] & 0x7f)<< 7 ) + (bytes[3] & 0x7f);
    }

    public static int findVorbisComment(byte[] flacBytes) {
        int pivot = 4; //跳过FLAC字段
        while ( (flacBytes[pivot] & 0x04) == 0 ) {
            byte[] blockSizeBytes = new byte[3];
            System.arraycopy(flacBytes,pivot + 1,blockSizeBytes,0,3);
            pivot += getBigEndianInteger3bytes(blockSizeBytes) + 4;
        }
        return pivot;
    }

    public static int findLastBlock(byte[] flacBytes) {
        int pivot = 4; //跳过FLAC字段
        while ( (flacBytes[pivot] & 0x80) == 0 ) {
            int blockSize = ((flacBytes[pivot + 1] & 0xFF) << 16
                    | (flacBytes[pivot + 2] & 0xFF) << 8
                    | (flacBytes[pivot + 3] & 0xFF));
            pivot += blockSize + 4;
        }
        return pivot;
    }

    public static boolean hasLastBlock(byte[] flacBytes) {
        int pivot = 4;
        while ( (flacBytes[pivot] & 0x80) == 0 ) {
            int blockSize = ((flacBytes[pivot + 1] & 0xFF) << 16
                    | (flacBytes[pivot + 2] & 0xFF) << 8
                    | (flacBytes[pivot + 3] & 0xFF));
            pivot += blockSize + 4;
            if (pivot >= flacBytes.length) {
                return false;
            }
        }
        int lastBlockSize = ((flacBytes[pivot + 1] & 0xFF) << 16
                | (flacBytes[pivot + 2] & 0xFF) << 8
                | (flacBytes[pivot + 3] & 0xFF));
        return pivot + lastBlockSize + 4 < flacBytes.length;
    }
}