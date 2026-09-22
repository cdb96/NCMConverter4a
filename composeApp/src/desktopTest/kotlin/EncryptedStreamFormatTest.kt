package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.EncryptedFormat
import com.cdb96.ncmconverter4a.io.detectEncryptedFormat
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EncryptedStreamFormatTest {
    @Test
    fun detectionPreservesInputIncludingShortAndEmptyFiles() {
        val kggHeader = byteArrayOf(
            0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(),
            0x86.toByte(), 0x02, 0x7F, 0x4B,
            0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(),
            0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14,
        ).copyOf(32).apply { this[20] = 5 }
        val cases = listOf(
            kggHeader to EncryptedFormat.KGG,
            kggHeader.copyOf().apply { this[21] = 1 } to EncryptedFormat.KGM,
            "CTENFDAMpayload".encodeToByteArray() to EncryptedFormat.NCM,
            byteArrayOf(
                0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(),
                0x86.toByte(), 0x02, 0x7F, 0x4B,
                0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(),
                0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14, 0x01
            ) to EncryptedFormat.KGM,
            "CTEN".encodeToByteArray() to EncryptedFormat.UNSUPPORTED,
            ByteArray(0) to EncryptedFormat.UNSUPPORTED,
        )
        for ((bytes, expected) in cases) {
            ByteArrayInputStream(bytes).buffered().use { input ->
                assertEquals(expected, input.detectEncryptedFormat())
                assertContentEquals(bytes, input.readBytes())
            }
        }
    }
}
