package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.converter.NcmFileInfo
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.InputStreamBinaryInput
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NcmId3MetadataTest {
    @Test
    fun writesValidFramesWithoutCopyingCoverOrLosingAudioAfterOldTag() {
        val key = byteArrayOf(1, 3, 5, 7)
        val cover = ByteArray(8193) { (it * 13).toByte() }
        val audio = ByteArray(1027) { (it * 29 + 11).toByte() }
        // The 769-byte old tag crosses three 256-byte decrypt buffers.
        val oldHeader = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 6, 1)
        val encrypted = oldHeader + ByteArray(769) + audio
        RC4Decrypt.ksa(key)
        RC4Decrypt.prgaDecrypt(encrypted, encrypted.size)
        val result = ByteArrayOutputStream()
        var coverWrites = 0

        NCMConverter.writeAudio(
            input = InputStreamBinaryInput(ByteArrayInputStream(encrypted)),
            output = object : BinaryOutput {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (buffer === cover) {
                        assertEquals(0, offset)
                        assertEquals(cover.size, length)
                        coverWrites++
                    } else {
                        assertTrue(buffer.size <= 256, "only the cover itself may exceed the buffer size")
                    }
                    result.write(buffer, offset, length)
                }
            },
            info = NcmFileInfo(key, cover, "歌曲𝄞", "专辑", "歌手", "mp3"),
            rawWriteMode = false,
            bufferSize = 256
        )

        val bytes = result.toByteArray()
        val tagSize = (6..9).fold(0) { size, index ->
            assertEquals(0, bytes[index].toInt() and 0x80)
            (size shl 7) or (bytes[index].toInt() and 0x7F)
        }
        assertEquals(bytes.size - audio.size - 10, tagSize)
        assertEquals(1, coverWrites)
        val frames = linkedMapOf<String, ByteArray>()
        var position = 10
        while (position < 10 + tagSize) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes, position + 4, 4).int
            assertEquals(0, bytes[position + 8].toInt())
            assertEquals(0, bytes[position + 9].toInt())
            frames[id] = bytes.copyOfRange(position + 10, position + 10 + length)
            position += 10 + length
        }
        assertEquals(10 + tagSize, position)
        assertEquals(listOf("TIT2", "TPE1", "TALB", "APIC"), frames.keys.toList())
        for ((id, text) in mapOf("TIT2" to "歌曲𝄞", "TPE1" to "歌手", "TALB" to "专辑")) {
            val body = frames.getValue(id)
            assertEquals(1, body[0].toInt())
            assertEquals(text, String(body, 1, body.size - 1, Charsets.UTF_16))
        }
        val picture = frames.getValue("APIC")
        assertContentEquals(
            byteArrayOf(0) + "image/jpeg".encodeToByteArray() + byteArrayOf(0, 3, 0),
            picture.copyOfRange(0, 14)
        )
        assertContentEquals(cover, picture.copyOfRange(14, picture.size))
        assertContentEquals(audio, bytes.copyOfRange(position, bytes.size))
    }

    @Test
    fun omitsApicWithoutCoverAndSkipsAnId3v24Footer() {
        val key = byteArrayOf(2, 4, 6)
        val audio = byteArrayOf(0x7F, 0x11, 0x22, 0x33)
        // ID3v2.4, footer flag (0x10) set, 300-byte body followed by a 10-byte footer.
        val oldHeader = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0x10, 0, 0, 2, 0x2C)
        val footer = byteArrayOf(0x33, 0x44, 0x49, 4, 0, 0x10, 0, 0, 2, 0x2C)
        val encrypted = oldHeader + ByteArray(300) + footer + audio
        RC4Decrypt.ksa(key)
        RC4Decrypt.prgaDecrypt(encrypted, encrypted.size)

        val bytes = convert(encrypted, key, cover = byteArrayOf())

        val frames = parseFrames(bytes)
        assertEquals(listOf("TIT2", "TPE1", "TALB"), frames.keys.toList())
        assertContentEquals(audio, bytes.copyOfRange(10 + tagSize(bytes), bytes.size))
    }

    @Test
    fun labelsPngCoversWithThePngMimeType() {
        val key = byteArrayOf(9, 9, 9)
        val pngCover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 7, 7)
        val encrypted = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0) + byteArrayOf(1, 2, 3)
        RC4Decrypt.ksa(key)
        RC4Decrypt.prgaDecrypt(encrypted, encrypted.size)

        val picture = parseFrames(convert(encrypted, key, pngCover)).getValue("APIC")

        assertContentEquals(
            byteArrayOf(0) + "image/png".encodeToByteArray() + byteArrayOf(0, 3, 0) + pngCover,
            picture
        )
    }

    private fun convert(encrypted: ByteArray, key: ByteArray, cover: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        NCMConverter.writeAudio(
            input = InputStreamBinaryInput(ByteArrayInputStream(encrypted)),
            output = object : BinaryOutput {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    result.write(buffer, offset, length)
                }
            },
            info = NcmFileInfo(key, cover, "歌曲", "专辑", "歌手", "mp3"),
            rawWriteMode = false,
            bufferSize = 256
        )
        return result.toByteArray()
    }

    private fun tagSize(bytes: ByteArray): Int = (6..9).fold(0) { size, index ->
        assertEquals(0, bytes[index].toInt() and 0x80)
        (size shl 7) or (bytes[index].toInt() and 0x7F)
    }

    private fun parseFrames(bytes: ByteArray): Map<String, ByteArray> {
        val frames = linkedMapOf<String, ByteArray>()
        val end = 10 + tagSize(bytes)
        var position = 10
        while (position < end) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes, position + 4, 4).int
            frames[id] = bytes.copyOfRange(position + 10, position + 10 + length)
            position += 10 + length
        }
        assertEquals(end, position)
        return frames
    }
}
