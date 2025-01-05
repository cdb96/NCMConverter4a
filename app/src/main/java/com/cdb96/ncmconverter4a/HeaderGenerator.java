package com.cdb96.ncmconverter4a;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

class ID3HeaderGen
{
    private static final ByteArrayOutputStream ID3Header = new ByteArrayOutputStream();
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
        ByteBuffer header = ByteBuffer.allocate(10);
        header.order(ByteOrder.BIG_ENDIAN);
        header.put("APIC".getBytes()); // 帧ID
        header.put( frameSizeBytes ); // 帧大小
        header.putShort((short) 0);

        ByteBuffer body = ByteBuffer.allocate(frameSize);
        body.put(textEncoding); // 文本编码
        body.put(mimeTypeBytes); // MIME类型
        body.put((byte) 0); // MIME类型结束符
        body.put(pictureType); // 图片类型
        body.put(descriptionBytes); // 描述
        body.put((byte) 0); // 描述结束符
        body.put(coverData); // 图片数据

        byte[] APICFrame = new byte[header.position() + body.position()];
        System.arraycopy(header.array(),0,APICFrame,0,header.position());
        System.arraycopy(body.array(),0,APICFrame,header.position(),body.position());
        ID3Header.write(APICFrame);
    }
}
class FLACHeaderGen
{
    public static byte[] pictureBlockGen(byte[] coverData) {
        byte[] mimeTypeBytes = "image/jpeg".getBytes(StandardCharsets.UTF_8);
        byte[] descriptionBytes = "NCMC4A".getBytes(StandardCharsets.UTF_8);

        int blockSize = 4 + 4 + mimeTypeBytes.length + 4 + descriptionBytes.length + 4 + 4 + 4 + 4 + 4 + coverData.length;
        ByteBuffer pictureBlock = ByteBuffer.allocate(blockSize + 4);
        byte[] blockSizeBytes = LengthUtils.toBigEndianInteger3Bytes( blockSize );
        pictureBlock.order(ByteOrder.BIG_ENDIAN);

        pictureBlock.put( (byte) (0x06) );  // 插入块类型6(PICTURE:0x06,最后一个块:0x86)
        pictureBlock.put(blockSizeBytes); // 插入块长度字节
        pictureBlock.putInt(3); // 插入图片类型(封面:3)

        pictureBlock.putInt(mimeTypeBytes.length);
        pictureBlock.put(mimeTypeBytes); // MIME类型

        pictureBlock.putInt(descriptionBytes.length);
        pictureBlock.put(descriptionBytes); // 描述

        pictureBlock.putInt(0); // 宽度
        pictureBlock.putInt(0); // 高度
        pictureBlock.putInt(0); // 颜色深度
        pictureBlock.putInt(0); // 索引颜色数

        pictureBlock.putInt(coverData.length); // 封面长度
        pictureBlock.put(coverData); // 封面数据

        byte[] result = new byte[pictureBlock.position()];
        pictureBlock.flip();
        pictureBlock.get(result);
        return result;

    }
}