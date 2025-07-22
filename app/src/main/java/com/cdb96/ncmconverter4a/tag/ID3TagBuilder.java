package com.cdb96.ncmconverter4a.tag;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import com.cdb96.ncmconverter4a.util.LengthUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ID3TagBuilder
{
    private final ByteArrayOutputStream ID3Header = new ByteArrayOutputStream();

    public void initDefaultTagHeader() throws IOException {
        byte[] defaultHeader = {0x49,0x44,0x33,0x03,0x00,0x00,0x00,0x00,0x00,0x00} ;
        ID3Header.write(defaultHeader);
    }

    public byte[] outputHeader() {
        // ID3v2.3 使用同步安全整数表示总大小
        byte[] sizeBytes = LengthUtils.toSyncSafeIntegerBytes(ID3Header.size());
        byte[] ID3HeaderBytes = ID3Header.toByteArray();
        System.arraycopy(sizeBytes,0,ID3HeaderBytes,6,4);
        ID3Header.reset();
        return ID3HeaderBytes;
    }

    public void addCover(byte[] coverData) throws IOException {
        byte[] mimeTypeBytes = "image/jpeg".getBytes();
        byte[] descriptionBytes = "".getBytes();
        byte textEncoding = 0x00; // ISO-8859-1
        byte pictureType = 0x03;

        int frameSize = 1 + mimeTypeBytes.length + 1 + descriptionBytes.length + 1 + 1 + coverData.length;
        // ID3v2.3 帧大小使用普通32位整数，不是同步安全整数
        byte[] frameSizeBytes = LengthUtils.toBigEndianBytes(frameSize);

        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        //header
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("APIC".getBytes()); // 帧ID
        frame.put(frameSizeBytes); // 帧大小 (普通整数)
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
        byte[] titleBytes = title.getBytes(UTF_16LE);
        int frameSize = 1 + 2 + titleBytes.length; // BOM加2

        // ID3v2.3 帧大小使用普通32位整数
        byte[] frameSizeBytes = LengthUtils.toBigEndianBytes(frameSize);

        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        //header
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("TIT2".getBytes());
        frame.put(frameSizeBytes);
        frame.putShort((short) 0);

        //body
        frame.put((byte) 0x01); // UTF-16 编码
        frame.put((byte) 0xFF);  // BOM for UTF-16LE
        frame.put((byte) 0xFE);
        frame.put(titleBytes);

        ID3Header.write(frame.array());
    }

    public void addTPE1(String artist) throws Exception {
        byte[] artistBytes = artist.getBytes(UTF_16LE);
        int frameSize = 1 + 2 + artistBytes.length; // BOM加2

        byte[] frameSizeBytes = LengthUtils.toBigEndianBytes(frameSize);

        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("TPE1".getBytes());
        frame.put(frameSizeBytes);
        frame.putShort((short) 0);

        frame.put((byte) 0x01); // UTF-16 编码
        frame.put((byte) 0xFF);  // BOM for UTF-16LE
        frame.put((byte) 0xFE);
        frame.put(artistBytes);

        ID3Header.write(frame.array());
    }

    public void addTALB(String album) throws Exception {
        byte[] albumBytes = album.getBytes(UTF_16LE);
        int frameSize = 1 + 2 + albumBytes.length; // BOM加2

        byte[] frameSizeBytes = LengthUtils.toBigEndianBytes(frameSize);

        ByteBuffer frame = ByteBuffer.allocate(10 + frameSize);
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.put("TALB".getBytes());
        frame.put(frameSizeBytes);
        frame.putShort((short) 0);

        frame.put((byte) 0x01);
        frame.put((byte) 0xFF);
        frame.put((byte) 0xFE);
        frame.put(albumBytes);

        ID3Header.write(frame.array());
    }
}
