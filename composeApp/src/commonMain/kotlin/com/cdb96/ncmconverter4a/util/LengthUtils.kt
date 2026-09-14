package com.cdb96.ncmconverter4a.util

object LengthUtils {

    fun readIntBE(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= data.size - 4) { "invalid big-endian integer offset" }
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    fun readIntLE(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= data.size - 4) { "invalid little-endian integer offset" }
        return readIntLEInline(data, offset)
    }

    fun readIntLEInline(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= data.size - 4) { "invalid little-endian integer offset" }
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun writeIntBE(data: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset <= data.size - 4) { "invalid big-endian integer offset" }
        data[offset] = (value shr 24).toByte()
        data[offset + 1] = (value shr 16).toByte()
        data[offset + 2] = (value shr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    fun writeIntLE(data: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset <= data.size - 4) { "invalid little-endian integer offset" }
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
        data[offset + 2] = (value shr 16).toByte()
        data[offset + 3] = (value shr 24).toByte()
    }

    fun writeShortBE(data: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset <= data.size - 2) { "invalid big-endian short offset" }
        data[offset] = (value shr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    fun toSyncSafeIntegerBytes(value: Int): ByteArray {
        require(value >= 0 && value <= 0x0FFFFFFF) { "invalid sync-safe integer: $value" }
        return byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte()
        )
    }

    fun getLittleEndianInteger(bytes: ByteArray): Int {
        require(bytes.size >= 4) { "need four bytes for a little-endian integer" }
        return readIntLEInline(bytes, 0)
    }

    fun toBigEndianBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )

    fun toBigEndianInteger3Bytes(value: Int): ByteArray {
        require(value in 0..0xFFFFFF) { "invalid three-byte integer: $value" }
        return byteArrayOf(
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    fun getBigEndianInteger3bytes(bytes: ByteArray): Int {
        require(bytes.size >= 3) { "need three bytes for a big-endian integer" }
        return ((bytes[0].toInt() and 0xFF) shl 16) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            (bytes[2].toInt() and 0xFF)
    }

    fun getSyncSafeInteger(bytes: ByteArray): Int {
        require(bytes.size >= 4) { "need four bytes for a sync-safe integer" }
        require(bytes.take(4).all { (it.toInt() and 0x80) == 0 }) {
            "invalid sync-safe integer"
        }
        return ((bytes[0].toInt() and 0x7F) shl 21) +
            ((bytes[1].toInt() and 0x7F) shl 14) +
            ((bytes[2].toInt() and 0x7F) shl 7) +
            (bytes[3].toInt() and 0x7F)
    }

    fun findVorbisComment(flacBytes: ByteArray): Int {
        require(flacBytes.size >= 8) { "truncated FLAC metadata" }
        var pivot = 4
        while (true) {
            require(pivot + 4 <= flacBytes.size) { "truncated FLAC metadata block header" }
            if ((flacBytes[pivot].toInt() and 0x7F) == 4) return pivot
            val blockSize = getBigEndianInteger3bytes(flacBytes.copyOfRange(pivot + 1, pivot + 4))
            val blockEnd = pivot + blockSize + 4
            require(blockEnd > pivot && blockEnd <= flacBytes.size) {
                "invalid FLAC metadata block length"
            }
            pivot = blockEnd
        }
    }

    fun findLastBlock(flacBytes: ByteArray): Int {
        require(flacBytes.size >= 8) { "truncated FLAC metadata" }
        var pivot = 4
        while (true) {
            require(pivot + 4 <= flacBytes.size) { "truncated FLAC metadata block header" }
            if ((flacBytes[pivot].toInt() and 0x80) != 0) return pivot
            val blockSize = getBigEndianInteger3bytes(flacBytes.copyOfRange(pivot + 1, pivot + 4))
            val blockEnd = pivot + blockSize + 4
            require(blockEnd > pivot && blockEnd <= flacBytes.size) {
                "invalid FLAC metadata block length"
            }
            pivot = blockEnd
        }
    }

    fun hasLastBlock(flacBytes: ByteArray): Boolean {
        if (flacBytes.size < 8) return false
        var pivot = 4
        while (true) {
            if (pivot + 4 > flacBytes.size) return false
            val blockSize = getBigEndianInteger3bytes(flacBytes.copyOfRange(pivot + 1, pivot + 4))
            val blockEnd = pivot + blockSize + 4
            if (blockEnd <= pivot || blockEnd > flacBytes.size) return false
            if ((flacBytes[pivot].toInt() and 0x80) != 0) return true
            pivot = blockEnd
        }
    }
}
