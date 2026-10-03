package com.cdb96.ncmconverter4a.converter.kgg

class MMKVParser(private val data: ByteArray) {
    private class ValueRange(val start: Int, val length: Int)

    // Retain only offsets into the snapshot; values are copied on demand.
    private val index by lazy { buildIndex() }

    fun getBytes(queryKey: String): ByteArray? {
        val range = index[queryKey] ?: return null
        return data.copyOfRange(range.start, range.start + range.length)
    }

    private fun buildIndex(): Map<String, ValueRange> {
        val entries = HashMap<String, ValueRange>()
        var position = 8
        while (position < data.size) {
            val keyLength = data[position++].toInt() and 0xFF
            if (position > data.size - keyLength) break
            val keyStart = position
            position += keyLength

            // tag(2) + encoded value length
            if (position > data.size - 2) break
            position += 2
            val valueLengthResult = readVarint32(position) ?: break
            val valueLength = valueLengthResult.first
            position = valueLengthResult.second
            if (valueLength < 0 || position > data.size - valueLength) break

            val key = data.decodeToString(keyStart, keyStart + keyLength)
            val encodedKey = key.encodeToByteArray()
            // Preserve byte-exact lookup and the previous first-match behavior.
            // Invalid UTF-8 must not shadow a later valid key.
            if (encodedKey.size == keyLength && regionEquals(keyStart, encodedKey) && key !in entries) {
                entries[key] = ValueRange(position, valueLength)
            }
            position += valueLength
        }
        return entries
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
