package com.cdb96.ncmconverter4a;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class LengthUtils {
    public static byte[] toSyncSafeIntegerBytes(int value) {
        byte[] syncSafe = new byte[4];
        syncSafe[0] = (byte) ((value >> 21) & 0x7F);
        syncSafe[1] = (byte) ((value >> 14) & 0x7F);
        syncSafe[2] = (byte) ((value >> 7) & 0x7F);
        syncSafe[3] = (byte) (value & 0x7F);
        return syncSafe;
    }

    public static int getLittleEndianInteger(byte[] bytes) throws Exception
    {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        return buffer.getInt();
    }

    public static int getBigEndianInteger(byte[] bytes) throws  Exception
    {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);

        return buffer.getInt();
    }

    public static byte[] toBigEndianInteger3Bytes(int value)
    {
        byte[] bigEndianInteger3Bytes = new byte[3];
        bigEndianInteger3Bytes[0] = (byte) ((value >> 16) & 0xff);
        bigEndianInteger3Bytes[1] = (byte) ((value >> 8) & 0xff);
        bigEndianInteger3Bytes[2] = (byte) ( value & 0xff);
        return bigEndianInteger3Bytes;
    }

    public static int getBigEndianInteger3bytes(byte[] bytes)
    {
        return ( (bytes[0] & 0xFF) << 16 ) | ( (bytes[1] & 0xFF) << 8 ) | ( bytes[2] & 0xFF );
    }

    public static int getSyncSafeInteger(byte[] bytes)
    {
        return ( (bytes[0] & 0x7f)<< 21 ) + ( (bytes[1] & 0x7f)<< 14 ) + ( (bytes[2] & 0x7f)<< 7 ) + (bytes[3] & 0x7f);
    }

    public static int findVorbisComment(byte[] flacBytes) throws Exception {
        byte[] blockSize = new byte[3];
        System.arraycopy(flacBytes,5,blockSize,0,3);
        int StreamInfoSize = getBigEndianInteger3bytes(blockSize);

        System.arraycopy(flacBytes,5 + StreamInfoSize + 4 ,blockSize,0,3);
        int seekTableSize = getBigEndianInteger3bytes(blockSize);
        return ( 5 + seekTableSize + 4 + StreamInfoSize + 4 );
    }

    public static int findLastMetaData(byte[] flacBytes) throws  Exception {
        int pivot = 4; //跳过FLAC
        while ( (flacBytes[pivot] & 0x80) == 0 ) {
            byte[] blockSizeBytes = new byte[3];
            System.arraycopy(flacBytes,pivot + 1,blockSizeBytes,0,3);
            pivot += getBigEndianInteger3bytes(blockSizeBytes) + 4;
        }
        return pivot;
    }
}