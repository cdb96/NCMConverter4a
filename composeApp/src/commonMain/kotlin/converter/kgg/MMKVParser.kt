package com.cdb96.ncmconverter4a.converter.kgg

class MMKVParser(private val data: ByteArray) {
    private var position = 0

    fun getBytes(queryKey: String): ByteArray? {
        val wantedKey = queryKey.encodeToByteArray()
        if (data.size < 8) return null
        position = 8
        while (position < data.size) {
            val keyLength = data[position++].toInt() and 0xFF
            if (position > data.size - keyLength) return null
            val keyStart = position
            position += keyLength

            // tag(2) + encoded value length
            if (position > data.size - 2) return null
            position += 2
            val valueLengthResult = readVarint32(position) ?: return null
            val valueLength = valueLengthResult.first
            position = valueLengthResult.second
            if (valueLength < 0 || position > data.size - valueLength) return null

            // Only the matching value is copied out; every other entry is skipped in place.
            if (keyLength == wantedKey.size && regionEquals(keyStart, wantedKey)) {
                return data.copyOfRange(position, position + valueLength)
            }
            position += valueLength
        }
        return null
    }

    private fun regionEquals(start: Int, expected: ByteArray): Boolean {
        for (index in expected.indices) {
            if (data[start + index] != expected[index]) return false
        }
        return true
    }

    private fun readVarint32(start: Int): Pair<Int, Int>? {
        var cursor = start
        var result = 0
        var shift = 0
        repeat(5) {
            if (cursor >= data.size) return null
            val byte = data[cursor++].toInt() and 0xFF
            if (shift == 28 && byte > 0x0F) return null
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result to cursor
            shift += 7
        }
        return null
    }
}
