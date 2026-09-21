package com.cdb96.ncmconverter4a.tag

import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.write
import com.cdb96.ncmconverter4a.util.LengthUtils.toBigEndianInteger3Bytes
import com.cdb96.ncmconverter4a.util.LengthUtils.writeIntBE
import com.cdb96.ncmconverter4a.util.LengthUtils.writeIntLE

object FLACMetadataGenerator {
    private const val MAX_BLOCK_SIZE = 0xFFFFFF

    /** Emits the small picture header followed by the original cover array. */
    fun writePictureBlock(output: BinaryOutput, coverData: ByteArray, isLast: Boolean) {
        val mimeTypeBytes = "image/jpeg".encodeToByteArray()
        val headerSize = 4 + 4 + 4 + mimeTypeBytes.size + 4 + 4 * 4 + 4
        val bodyHeaderSize = headerSize - 4
        require(coverData.size <= MAX_BLOCK_SIZE - bodyHeaderSize) {
            "FLAC picture block is too large: ${coverData.size}"
        }

        val header = ByteArray(headerSize)
        header[0] = (6 or if (isLast) 0x80 else 0).toByte()
        toBigEndianInteger3Bytes(bodyHeaderSize + coverData.size).copyInto(header, 1)
        writeIntBE(header, 4, 3) // front cover
        writeIntBE(header, 8, mimeTypeBytes.size)
        mimeTypeBytes.copyInto(header, 12)
        // Empty description and unspecified dimensions remain zero.
        writeIntBE(header, header.size - 4, coverData.size)

        output.write(header)
        output.write(coverData)
    }

    /** [writeVendor] copies exactly [vendorLength] bytes from the original block. */
    fun writeVorbisCommentBlock(
        output: BinaryOutput,
        title: String,
        artist: String,
        album: String,
        vendorLength: Int,
        writeVendor: (BinaryOutput) -> Unit
    ) {
        val comments = arrayOf(
            "ARTIST=$artist".encodeToByteArray(),
            "TITLE=$title".encodeToByteArray(),
            "ALBUM=$album".encodeToByteArray()
        )
        val blockSize = 8L + vendorLength + comments.sumOf { 4L + it.size }
        require(vendorLength >= 0 && blockSize <= MAX_BLOCK_SIZE) {
            "FLAC Vorbis comment block is too large: $blockSize"
        }

        val header = ByteArray(8)
        header[0] = 4 // a picture block will follow, so this is never the last block
        toBigEndianInteger3Bytes(blockSize.toInt()).copyInto(header, 1)
        writeIntLE(header, 4, vendorLength)
        output.write(header)
        writeVendor(output)

        val lengthBytes = ByteArray(4)
        writeIntLE(lengthBytes, 0, comments.size)
        output.write(lengthBytes)
        for (comment in comments) {
            writeIntLE(lengthBytes, 0, comment.size)
            output.write(lengthBytes)
            output.write(comment)
        }
    }
}
