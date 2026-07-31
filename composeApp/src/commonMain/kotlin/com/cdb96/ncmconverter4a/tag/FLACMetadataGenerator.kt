package com.cdb96.ncmconverter4a.tag

import com.cdb96.ncmconverter4a.util.LengthUtils.toBigEndianInteger3Bytes
import com.cdb96.ncmconverter4a.util.LengthUtils.writeIntBE
import com.cdb96.ncmconverter4a.util.LengthUtils.writeIntLE

object FLACMetadataGenerator {

    fun pictureBlockGen(coverData: ByteArray): ByteArray {
        val mimeTypeBytes = "image/jpeg".encodeToByteArray()
        val descriptionBytes = "".encodeToByteArray()

        val blockSize = 4 + 4 + mimeTypeBytes.size + 4 + descriptionBytes.size +
            4 + 4 + 4 + 4 + 4 + coverData.size
        val totalSize = blockSize + 4
        var pos = 0
        val buf = ByteArray(totalSize)

        // 块类型 + size (big-endian 3 bytes)
        buf[pos++] = 0x06.toByte()
        val sizeBytes = toBigEndianInteger3Bytes(blockSize)
        sizeBytes.copyInto(buf, pos); pos += 3

        // 图片类型
        writeIntBE(buf, pos, 3); pos += 4

        // MIME type
        writeIntBE(buf, pos, mimeTypeBytes.size); pos += 4
        mimeTypeBytes.copyInto(buf, pos); pos += mimeTypeBytes.size

        // Description
        writeIntBE(buf, pos, descriptionBytes.size); pos += 4
        descriptionBytes.copyInto(buf, pos); pos += descriptionBytes.size

        // 宽/高/颜色深度/索引颜色
        writeIntBE(buf, pos, 0); pos += 4
        writeIntBE(buf, pos, 0); pos += 4
        writeIntBE(buf, pos, 0); pos += 4
        writeIntBE(buf, pos, 0); pos += 4

        // 封面数据
        writeIntBE(buf, pos, coverData.size); pos += 4
        coverData.copyInto(buf, pos)

        return buf
    }

    fun vorbisCommentBlockGen(
        title: String, artist: String, album: String, vendorBytes: ByteArray
    ): ByteArray {
        val titleBytes = "TITLE=$title".encodeToByteArray()
        val artistBytes = "ARTIST=$artist".encodeToByteArray()
        val albumBytes = "ALBUM=$album".encodeToByteArray()
        val blockSize = 4 + vendorBytes.size + 4 + 4 + artistBytes.size +
            4 + titleBytes.size + 4 + albumBytes.size
        val totalSize = blockSize + 4
        var pos = 0
        val buf = ByteArray(totalSize)

        // 块类型 + size
        buf[pos++] = 0x04.toByte()
        val sizeBytes = toBigEndianInteger3Bytes(blockSize)
        sizeBytes.copyInto(buf, pos); pos += 3

        // Vendor (little-endian length + data)
        writeIntLE(buf, pos, vendorBytes.size); pos += 4
        vendorBytes.copyInto(buf, pos); pos += vendorBytes.size

        // 注释数
        writeIntLE(buf, pos, 3); pos += 4

        // Artist
        writeIntLE(buf, pos, artistBytes.size); pos += 4
        artistBytes.copyInto(buf, pos); pos += artistBytes.size

        // Title
        writeIntLE(buf, pos, titleBytes.size); pos += 4
        titleBytes.copyInto(buf, pos); pos += titleBytes.size

        // Album
        writeIntLE(buf, pos, albumBytes.size); pos += 4
        albumBytes.copyInto(buf, pos)

        return buf
    }
}
