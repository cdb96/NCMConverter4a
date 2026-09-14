package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.jni.KgmVector
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KgmVectorTest {
    @Test
    fun decryptsConsistentlyAcrossLargeInputChunks() {
        val key = ByteArray(17) { (it * 13 + 7).toByte() }
        val encrypted = ByteArray(600_000) { (it * 31 + 11).toByte() }

        KgmVector.init(key)
        val wholeFile = encrypted.copyOf()
        KgmVector.decrypt(wholeFile, 0, wholeFile.size)

        KgmVector.init(key)
        val chunkedFile = ByteArray(encrypted.size)
        var offset = 0
        while (offset < encrypted.size) {
            val size = minOf(262_144, encrypted.size - offset)
            val chunk = encrypted.copyOfRange(offset, offset + size)
            val returnedOffset = KgmVector.decrypt(chunk, offset, size)
            assertEquals(offset + size, returnedOffset)
            chunk.copyInto(chunkedFile, offset)
            offset += size
        }

        assertContentEquals(wholeFile, chunkedFile)
    }
}
