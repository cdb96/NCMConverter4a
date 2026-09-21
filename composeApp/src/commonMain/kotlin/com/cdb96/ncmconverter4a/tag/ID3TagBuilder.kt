package com.cdb96.ncmconverter4a.tag

import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.write
import com.cdb96.ncmconverter4a.util.LengthUtils.toSyncSafeIntegerBytes
import com.cdb96.ncmconverter4a.util.LengthUtils.writeIntBE
import com.cdb96.ncmconverter4a.util.LengthUtils.writeShortBE

class ID3TagBuilder {
    private val chunks = mutableListOf<ByteArray>()

    fun initDefaultTagHeader() {
        chunks.add(byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    }

    fun outputHeader(): ByteArray {
        val totalSize = prepareHeader()
        val headerBytes = ByteArray(totalSize).also { arr ->
            var offset = 0
            for (chunk in chunks) { chunk.copyInto(arr, offset); offset += chunk.size }
        }
        chunks.clear()
        return headerBytes
    }

    /** Writes frame headers and their bodies directly, without copying the cover. */
    fun writeTo(output: BinaryOutput) {
        prepareHeader()
        try {
            chunks.forEach { output.write(it) }
        } finally {
            chunks.clear()
        }
    }

    private fun prepareHeader(): Int {
        val totalSize = chunks.sumOf { it.size.toLong() }
        require(totalSize in 10L..0x0FFFFFFFL + 10) { "invalid ID3 tag size: $totalSize" }
        // The sync-safe tag size excludes the ten-byte ID3 header.
        toSyncSafeIntegerBytes(totalSize.toInt() - 10).copyInto(chunks.first(), 6)
        return totalSize.toInt()
    }

    private fun addFrame(id: String, vararg bodies: ByteArray) {
        val header = ByteArray(10)
        id.encodeToByteArray().copyInto(header)
        writeIntBE(header, 4, bodies.sumOf { it.size })
        writeShortBE(header, 8, 0)
        chunks.add(header)
        chunks.addAll(bodies)
    }

    fun addCover(coverData: ByteArray) {
        val mimeTypeBytes = "image/jpeg".encodeToByteArray()
        val descriptionBytes = "".encodeToByteArray()
        val textEncoding: Byte = 0x00  // ISO-8859-1
        val pictureType: Byte = 0x03

        var pos = 0
        val body = ByteArray(1 + mimeTypeBytes.size + 1 + descriptionBytes.size + 1 + 1)
        body[pos++] = textEncoding
        mimeTypeBytes.copyInto(body, pos); pos += mimeTypeBytes.size
        body[pos++] = 0  // MIME终止符
        body[pos++] = pictureType
        descriptionBytes.copyInto(body, pos); pos += descriptionBytes.size
        body[pos++] = 0  // 描述终止符
        addFrame("APIC", body, coverData)
    }

    // UTF-16LE 手动编码 (commonMain 无 toByteArray(Charset))
    private fun String.encodeUtf16LE(): ByteArray {
        val out = ByteArray(length * 2)
        for (i in indices) {
            val ch = this[i].code
            out[i * 2] = ch.toByte()
            out[i * 2 + 1] = (ch shr 8).toByte()
        }
        return out
    }

    fun addTIT2(title: String) {
        val titleBytes = title.encodeUtf16LE()
        var pos = 0
        val body = ByteArray(1 + 2 + titleBytes.size)
        body[pos++] = 0x01  // UTF-16 编码
        body[pos++] = 0xFF.toByte()  // BOM UTF-16LE
        body[pos++] = 0xFE.toByte()
        titleBytes.copyInto(body, pos)
        addFrame("TIT2", body)
    }

    fun addTPE1(artist: String) {
        val artistBytes = artist.encodeUtf16LE()
        var pos = 0
        val body = ByteArray(1 + 2 + artistBytes.size)
        body[pos++] = 0x01
        body[pos++] = 0xFF.toByte()
        body[pos++] = 0xFE.toByte()
        artistBytes.copyInto(body, pos)
        addFrame("TPE1", body)
    }

    fun addTALB(album: String) {
        val albumBytes = album.encodeUtf16LE()
        var pos = 0
        val body = ByteArray(1 + 2 + albumBytes.size)
        body[pos++] = 0x01
        body[pos++] = 0xFF.toByte()
        body[pos++] = 0xFE.toByte()
        albumBytes.copyInto(body, pos)
        addFrame("TALB", body)
    }
}
