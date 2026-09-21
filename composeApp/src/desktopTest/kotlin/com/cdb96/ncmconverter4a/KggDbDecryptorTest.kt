package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.KggDbDecryptor
import com.cdb96.ncmconverter4a.converter.kgg.MMKVParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KggDbDecryptorTest {
    @Test
    fun decryptsEveryPageInPlaceIncludingAnAlignedTrailingPartialPage() {
        val plain = ReferenceKggDb.plaintextDatabase(300 * 1024 + 512, seed = 1)
        val encrypted = ReferenceKggDb.encryptDatabase(plain)
        assertTrue(KggDbDecryptor.isSqliteDatabase(encrypted))

        val decrypted = KggDbDecryptor.decryptDatabase(encrypted)

        assertSame(encrypted, decrypted)
        assertContentEquals(plain, decrypted)
    }

    @Test
    fun unalignedTrailingPageKeepsItsWholeBlocks() {
        val plain = ReferenceKggDb.plaintextDatabase(4 * 1024 + 300, seed = 2)

        val decrypted = KggDbDecryptor.decryptDatabase(ReferenceKggDb.encryptDatabase(plain))

        // The final partial block is decrypted against zero padding, as before;
        // every whole block of the tail page is exact.
        val exact = 4 * 1024 + 288
        assertEquals(plain.size, decrypted.size)
        assertContentEquals(plain.copyOf(exact), decrypted.copyOf(exact))
    }

    @Test
    fun plainDatabasesPassThroughAndDamagedFirstPagesAreRejected() {
        val plain = ReferenceKggDb.plaintextDatabase(2048, seed = 3)
        val copy = plain.copyOf()
        assertSame(copy, KggDbDecryptor.decryptDatabase(copy))
        assertContentEquals(plain, copy)

        assertFailsWith<IllegalArgumentException> { KggDbDecryptor.decryptDatabase(ByteArray(1023)) }
        val damaged = ReferenceKggDb.encryptDatabase(ReferenceKggDb.plaintextDatabase(2048, seed = 4))
        damaged[0x09] = (damaged[0x09] + 1).toByte()
        assertFailsWith<IllegalArgumentException> { KggDbDecryptor.decryptDatabase(damaged) }
    }

    @Test
    fun mmkvLookupCopiesOnlyTheMatchingValue() {
        val entries = listOf("aaaa" to ByteArray(300) { 1 }, "hash" to byteArrayOf(9, 8, 7), "zz" to ByteArray(5))
        val file = ByteArray(8) + entries.flatMap { (key, value) ->
            val keyBytes = key.encodeToByteArray()
            listOf(keyBytes.size.toByte()) + keyBytes.toList() + listOf(0x0A, 0) +
                encodeVarint(value.size) + value.toList()
        }.toByteArray()

        assertContentEquals(byteArrayOf(9, 8, 7), MMKVParser(file).getBytes("hash"))
        assertContentEquals(ByteArray(5), MMKVParser(file).getBytes("zz"))
        assertNull(MMKVParser(file).getBytes("has"))
        assertNull(MMKVParser(file).getBytes("hashh"))
        assertNull(MMKVParser(file.copyOf(file.size - 1)).getBytes("zz"))
    }

    private fun encodeVarint(value: Int): List<Byte> {
        val out = mutableListOf<Byte>()
        var remaining = value
        while (remaining >= 0x80) {
            out += ((remaining and 0x7F) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        out += remaining.toByte()
        return out
    }
}
