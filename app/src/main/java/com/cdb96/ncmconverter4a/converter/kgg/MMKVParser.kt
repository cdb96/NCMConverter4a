package com.cdb96.ncmconverter4a.converter.kgg

import java.io.InputStream

class MMKVParser(private val input: InputStream) {

    fun getBytes(queryKey: String): ByteArray? {
        val header = ByteArray(8)
        if (input.read(header) != 8) return null

        while (true) {
            val keyLen = input.read()
            val keyBytes = ByteArray(keyLen)
            input.read(keyBytes)

            //tag(2) + length(2)
            //tag我没搞清楚是干啥的，但是好像不处理也不影响就不管它了
            input.skip(2)
            val valueLenBytes = ByteArray(2)
            input.read(valueLenBytes)
            val valueLen = valueLenBytes.readVarint32()
            val value = ByteArray(valueLen)
            input.read(value)

            if (queryKey == keyBytes.toString(Charsets.UTF_8)) {
                return value
            }
        }
    }

    fun ByteArray.readVarint32(): Int {
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