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

    /** Writes frame headers and their bodies directly, without copying the cover. */
    fun writeTo(output: BinaryOutput) {
        prepareHeader()
        try {
            chunks.forEach { output.write(it) }
        } finally {
            chunks.clear()
        }
    }

    private fun prepareHeader() {
        val totalSize = chunks.sumOf { it.size.toLong() }
        require(totalSize in 10L..0x0FFFFFFFL + 10) { "invalid ID3 tag size: $totalSize" }
        // The sync-safe tag size excludes the ten-byte ID3 header.
        toSyncSafeIntegerBytes(totalSize.toInt() - 10).copyInto(chunks.first(), 6)
    }

    private fun addFrame(id: String, vararg bodies: ByteArray) {
        val header = ByteArray(10)
        id.encodeToByteArray().copyInto(header)
        writeIntBE(header, 4, bodies.sumOf { it.size })
        writeShortBE(header, 8, 0)
        chunks.add(header)
        chunks.addAll(bodies)
    }

    /** Adds an APIC frame; an NCM file without cover art gets no frame at all. */
    fun addCover(coverData: ByteArray) {
        if (coverData.isEmpty()) return
        val mimeTypeBytes = CoverImage.mimeType(coverData).encodeToByteArray()
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

    private fun addTextFrame(id: String, text: String) {
        val textBytes = text.encodeUtf16LE()
        val body = ByteArray(3 + textBytes.size)
        body[0] = 0x01  // UTF-16 编码
        body[1] = 0xFF.toByte()  // BOM UTF-16LE
        body[2] = 0xFE.toByte()
        textBytes.copyInto(body, 3)
        addFrame(id, body)
    }

    fun addTIT2(title: String) = addTextFrame("TIT2", title)

    fun addTPE1(artist: String) = addTextFrame("TPE1", artist)

    fun addTALB(album: String) = addTextFrame("TALB", album)
}
