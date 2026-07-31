package com.cdb96.ncmconverter4a.util

object LengthUtils {

    // ── 通用字节读写（替代 ByteBuffer）────────────────────────────────

    fun readIntBE(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8) or
        (data[offset + 3].toInt() and 0xFF)

    fun readIntLE(data: ByteArray, offset: Int): Int =
        getLittleEndianInteger(data.copyOfRange(offset, offset + 4))

    fun readIntLEInline(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)

    fun writeIntBE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 24).toByte()
        data[offset + 1] = (value shr 16).toByte()
        data[offset + 2] = (value shr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    fun writeIntLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
        data[offset + 2] = (value shr 16).toByte()
        data[offset + 3] = (value shr 24).toByte()
    }

    fun writeShortBE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    // ── 原方法 ──────────────────────────────────────────────────────

    fun toSyncSafeIntegerBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte()
        )
    }

    fun getLittleEndianInteger(bytes: ByteArray): Int {
        return (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
    }

    fun toBigEndianBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    fun toBigEndianInteger3Bytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    fun getBigEndianInteger3bytes(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 16) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                (bytes[2].toInt() and 0xFF)
    }

    fun getSyncSafeInteger(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0x7f) shl 21) +
                ((bytes[1].toInt() and 0x7f) shl 14) +
                ((bytes[2].toInt() and 0x7f) shl 7) +
                (bytes[3].toInt() and 0x7f)
    }

    fun findVorbisComment(flacBytes: ByteArray): Int {
        var pivot = 4 // 跳过FLAC字段
        while ((flacBytes[pivot].toInt() and 0x04) == 0) {
            val blockSizeBytes = flacBytes.copyOfRange(pivot + 1, pivot + 4)
            pivot += getBigEndianInteger3bytes(blockSizeBytes) + 4
        }
        return pivot
    }

    fun findLastBlock(flacBytes: ByteArray): Int {
        var pivot = 4 // 跳过FLAC字段
        while ((flacBytes[pivot].toInt() and 0x80) == 0) {
            val blockSize = ((flacBytes[pivot + 1].toInt() and 0xFF) shl 16
                    or ((flacBytes[pivot + 2].toInt() and 0xFF) shl 8)
                    or (flacBytes[pivot + 3].toInt() and 0xFF))
            pivot += blockSize + 4
        }
        return pivot
    }

    fun hasLastBlock(flacBytes: ByteArray): Boolean {
        var pivot = 4
        while ((flacBytes[pivot].toInt() and 0x80) == 0) {
            val blockSize = ((flacBytes[pivot + 1].toInt() and 0xFF) shl 16
                    or ((flacBytes[pivot + 2].toInt() and 0xFF) shl 8)
                    or (flacBytes[pivot + 3].toInt() and 0xFF))
            pivot += blockSize + 4
            if (pivot >= flacBytes.size) {
                return false
            }
        }
        val lastBlockSize = ((flacBytes[pivot + 1].toInt() and 0xFF) shl 16
                or ((flacBytes[pivot + 2].toInt() and 0xFF) shl 8)
                or (flacBytes[pivot + 3].toInt() and 0xFF))
        return pivot + lastBlockSize + 4 < flacBytes.size
    }
}
