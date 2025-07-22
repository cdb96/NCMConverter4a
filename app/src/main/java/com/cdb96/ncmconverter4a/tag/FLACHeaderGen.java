package com.cdb96.ncmconverter4a.tag;
import static java.nio.charset.StandardCharsets.UTF_8;
import com.cdb96.ncmconverter4a.util.LengthUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FLACHeaderGen
{
    public static byte[] pictureBlockGen(byte[] coverData) {
        byte[] mimeTypeBytes = "image/jpeg".getBytes(UTF_8);
        byte[] descriptionBytes = "".getBytes(UTF_8);

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

        return pictureBlock.array();
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

        return vorbisCommentBlock.array();
    }
}