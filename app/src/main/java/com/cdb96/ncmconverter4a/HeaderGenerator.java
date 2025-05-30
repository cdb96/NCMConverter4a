package com.cdb96.ncmconverter4a;
import static java.nio.charset.StandardCharsets.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class ID3HeaderGen
{
    private final ByteArrayOutputStream ID3Header = new ByteArrayOutputStream();
    public void initDefaultTagHeader() throws IOException {
        byte[] defaultHeader = {0x49,0x44,0x33,0x04,0x00,0x00,0x00,0x00,0x00,0x00} ;
        ID3Header.write(defaultHeader);
    }

    public byte[] outputHeader() {
        byte[] sizeBytes = LengthUtils.toSyncSafeIntegerBytes(ID3Header.size());
        byte[] ID3HeaderBytes = ID3Header.toByteArray();
        System.arraycopy(sizeBytes,0,ID3HeaderBytes,6,4);
        ID3Header.reset();
        return ID3HeaderBytes;
    }

    public void addCover(byte[] coverData) throws IOException {
        byte[] mimeTypeBytes = "image/jpeg".getBytes();
        byte[] descriptionBytes = "NCMC4A".getBytes();
        byte textEncoding = 0x00;
        byte pictureType = 0x06;

        int frameSize = 1 + mimeTypeBytes.length + 1 + descriptionBytes.length + 1 + 1 + coverData.length;
        byte[] frameSizeBytes;
        frameSizeBytes = LengthUtils.toSyncSafeIntegerBytes(frameSize);
        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        //header
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("APIC".getBytes()); // 帧ID
        frame.put( frameSizeBytes ); // 帧大小
        frame.putShort((short) 0);

        //body
        frame.put(textEncoding); // 文本编码
        frame.put(mimeTypeBytes); // MIME类型
        frame.put((byte) 0); // MIME类型结束符
        frame.put(pictureType); // 图片类型
        frame.put(descriptionBytes); // 描述
        frame.put((byte) 0); // 描述结束符
        frame.put(coverData); // 图片数据

        ID3Header.write(frame.array());
    }
    public void addTIT2(String title) throws Exception {
        byte[] titleBytes = title.getBytes(UTF_8);
        int frameSize = 1 + titleBytes.length;
        byte[] frameSizeBytes;

        frameSizeBytes = LengthUtils.toSyncSafeIntegerBytes(frameSize);
        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        //header
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("TIT2".getBytes());
        frame.put( frameSizeBytes );
        frame.putShort((short) 0);

        //body
        frame.put((byte) 0x03);
        frame.put(titleBytes);

        ID3Header.write(frame.array());
    }

    public void addTPE1(String artist) throws Exception {
        byte[] artistBytes = artist.getBytes(UTF_8);
        int frameSize = 1 + artistBytes.length;
        byte[] frameSizeBytes;

        frameSizeBytes = LengthUtils.toSyncSafeIntegerBytes(frameSize);
        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("TPE1".getBytes());
        frame.put( frameSizeBytes );
        frame.putShort((short) 0);

        frame.put((byte) 0x03);
        frame.put(artistBytes);

        ID3Header.write(frame.array());
    }

    public void addTALB(String album) throws Exception {
        byte[] albumBytes = album.getBytes(UTF_8);
        int frameSize = 1 + albumBytes.length;
        byte[] frameSizeBytes;

        frameSizeBytes = LengthUtils.toSyncSafeIntegerBytes(frameSize);
        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("TALB".getBytes());
        frame.put( frameSizeBytes );
        frame.putShort((short) 0);

        frame.put((byte) 0x03);
        frame.put(albumBytes);

        ID3Header.write(frame.array());
    }
}

class FLACHeaderGen
{
    public static byte[] pictureBlockGen(byte[] coverData) {
        byte[] mimeTypeBytes = "image/jpeg".getBytes(UTF_8);
        byte[] descriptionBytes = "NCMC4A".getBytes(UTF_8);

        int blockSize = 4 + 4 + mimeTypeBytes.length + 4 + descriptionBytes.length + 4 + 4 + 4 + 4 + 4 + coverData.length;
        ByteBuffer pictureBlock = ByteBuffer.allocate(blockSize + 4); //块的大小加上块头的大小
        byte[] blockSizeBytes = LengthUtils.toBigEndianInteger3Bytes( blockSize );
        pictureBlock.order(ByteOrder.BIG_ENDIAN);

        pictureBlock.put( (byte) (0x06) );  // 插入块类型6(PICTURE:0x06,最后一个块:0x86)
        pictureBlock.put(blockSizeBytes); // 插入块长度字节

        pictureBlock.putInt(3); // 插入图片类型(封面:3) 第一个4
        pictureBlock.putInt(mimeTypeBytes.length); // 第二个4
        pictureBlock.put(mimeTypeBytes); // MIME类型

        pictureBlock.putInt(descriptionBytes.length); // 第三个4
        pictureBlock.put(descriptionBytes); // 描述

        pictureBlock.putInt(0); // 宽度 第四个4
        pictureBlock.putInt(0); // 高度 第五个4
        pictureBlock.putInt(0); // 颜色深度 第六个4
        pictureBlock.putInt(0); // 索引颜色数 第七个4

        pictureBlock.putInt(coverData.length); // 封面长度 第八个4
        pictureBlock.put(coverData); // 封面数据

        byte[] result = new byte[pictureBlock.position()];
        pictureBlock.flip();
        pictureBlock.get(result);
        return result;
    }
    public static byte[] vorbisCommentBlockGen(String title, String artist,String album, byte[] vendorBytes) {
        byte[] titleBytes = ("TITLE=" + title).getBytes(UTF_8);
        byte[] artistBytes = ("ARTIST=" + artist).getBytes(UTF_8);
        byte[] albumBytes = ("ALBUM=" + album).getBytes(UTF_8);
        int blockSize = 4 + vendorBytes.length + 4 + 4 + artistBytes.length + 4 + titleBytes.length + 4 + albumBytes.length;
        ByteBuffer vorbisCommentBlock = ByteBuffer.allocate(blockSize + 4);

        byte[] blockSizeBytes = LengthUtils.toBigEndianInteger3Bytes( blockSize );
        vorbisCommentBlock.order(ByteOrder.BIG_ENDIAN);
        vorbisCommentBlock.put( (byte) (0x04) );
        vorbisCommentBlock.put(blockSizeBytes);

        vorbisCommentBlock.order(ByteOrder.LITTLE_ENDIAN);
        vorbisCommentBlock.putInt(vendorBytes.length); // 第一个4
        vorbisCommentBlock.put(vendorBytes);

        vorbisCommentBlock.putInt(3); // 插入注释数(3) 第二个4
        vorbisCommentBlock.putInt(artistBytes.length); // 第三个4
        vorbisCommentBlock.put(artistBytes);
        vorbisCommentBlock.putInt(titleBytes.length); // 第四个4
        vorbisCommentBlock.put(titleBytes);
        vorbisCommentBlock.putInt(albumBytes.length); // 第五个4
        vorbisCommentBlock.put(albumBytes);

        byte[] result = new byte[vorbisCommentBlock.position()];
        vorbisCommentBlock.flip();
        vorbisCommentBlock.get(result);
        return result;
    }
}