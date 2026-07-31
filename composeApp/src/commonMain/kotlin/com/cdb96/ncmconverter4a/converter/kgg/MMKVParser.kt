package com.cdb96.ncmconverter4a.converter.kgg

class MMKVParser(private val data: ByteArray) {
    private var pos = 0

    fun getBytes(queryKey: String): ByteArray? {
        pos += 8
        while (true) {
            if (pos >= data.size) return null
            val keyLen = (data[pos++].toInt() and 0xFF)
            if (pos + keyLen > data.size) return null
            val keyBytes = data.copyOfRange(pos, pos + keyLen); pos += keyLen

            // tag(2) + length(2)
            pos += 2  // skip tag
            val valueLenBytes = data.copyOfRange(pos, pos + 2); pos += 2
            val valueLen = valueLenBytes.readVarint32()
            val value = data.copyOfRange(pos, pos + valueLen); pos += valueLen

            if (queryKey == keyBytes.decodeToString()) {
                return value
            }
        }
    }

    private fun ByteArray.readVarint32(): Int {
        var result = 0
        var shift = 0
        var i = 0
        var byte: Int
        do {
            byte = this[i].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            shift += 7
            i++
        } while (byte and 0x80 != 0)
        return result
    }
}
