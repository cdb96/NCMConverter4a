package com.cdb96.ncmconverter4a.util

object LengthUtils {

    fun readIntBE(data: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= data.size - 4) { "invalid big-endian integer offset" }
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
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

    fun toBigEndianInteger3Bytes(value: Int): ByteArray {
        require(value in 0..0xFFFFFF) { "invalid three-byte integer: $value" }
        return byteArrayOf(
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
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

}
