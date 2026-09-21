package com.cdb96.ncmconverter4a.converter

import kotlin.test.Test
import kotlin.test.assertEquals

class EncryptedFormatTest {
    @Test
    fun detectsCompleteKnownHeaders() {
        assertEquals(
            EncryptedFormat.NCM,
            detectEncryptedFormat("CTENFDAM".encodeToByteArray())
        )
        assertEquals(
            EncryptedFormat.KGM,
            detectEncryptedFormat(
                byteArrayOf(
                    0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(),
                    0x86.toByte(), 0x02, 0x7F, 0x4B,
                    0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(),
                    0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14
                )
            )
        )
        assertEquals(EncryptedFormat.UNSUPPORTED, detectEncryptedFormat(byteArrayOf(0x7C, 0xD5.toByte())))
    }
}
