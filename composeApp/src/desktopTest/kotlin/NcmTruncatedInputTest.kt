package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.io.readFully
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NcmTruncatedInputTest {
    @Test
    fun rejectsIncompleteMagicAndOversizedKey() {
        assertFailsWith<IllegalArgumentException> {
            parse(byteArrayOf('C'.code.toByte(), 'T'.code.toByte()))
        }
        assertFailsWith<IllegalArgumentException> {
            parse(ncmPrefixWithValidKey().also { writeLittleEndian(it, 10, 4097) })
        }
    }

    @Test
    fun rejectsMetadataLengthPastTheInput() {
        val bytes = ncmPrefixWithValidKey()
        val output = ByteArrayOutputStream().apply {
            write(bytes)
            writeLittleEndian(this, 17 * 1024 * 1024)
        }
        assertFailsWith<IllegalArgumentException> { parse(output.toByteArray()) }
    }

    @Test
    fun rejectsCoverLengthPastTheInput() {
        val output = ByteArrayOutputStream().apply {
            write(validHeader())
            write(ByteArray(5))
            writeLittleEndian(this, 64 * 1024 * 1024 + 1)
        }
        assertFailsWith<IllegalArgumentException> { parse(output.toByteArray()) }
    }

    @Test
    fun skippingLargeCoverUsesSmallBuffersAndLeavesInputAtAudio() {
        val imageLength = 2 * 1024 * 1024
        val coverLength = imageLength + 17
        val prefix = coverHeader(coverLength, imageLength)
        val audio = byteArrayOf(0x12, 0x34, 0x56)
        val audioStart = prefix.size + coverLength
        val total = audioStart + audio.size
        val input = object : BinaryInput {
            var position = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(buffer.size <= 8192, "unused cover must not be allocated")
                if (position == total) return -1
                val count = minOf(length, total - position, 1021)
                repeat(count) { index ->
                    buffer[offset + index] = when {
                        position < prefix.size -> prefix[position]
                        position < audioStart -> 0x5A.toByte()
                        else -> audio[position - audioStart]
                    }
                    position++
                }
                return count
            }
        }

        val info = NCMConverter.readHeader(input, includeCover = false)

        assertTrue(info.coverData.isEmpty())
        assertEquals("Song", info.musicName)
        assertEquals("Album", info.musicAlbum)
        assertEquals("Artist", info.musicArtists)
        assertEquals("mp3", info.format)
        assertEquals(audioStart, input.position)
        val actualAudio = ByteArray(audio.size)
        input.readFully(actualAudio)
        assertContentEquals(audio, actualAudio)
    }

    @Test
    fun coverIsRetainedByDefaultWhilePaddingIsSkipped() {
        val cover = byteArrayOf(9, 8, 7, 6)
        val audio = byteArrayOf(1, 2, 3)
        val stream = ByteArrayInputStream(coverHeader(9, cover.size) + cover + ByteArray(5) + audio)
        val input = BinaryInput(stream::read)

        assertContentEquals(cover, NCMConverter.readHeader(input).coverData)
        assertContentEquals(audio, stream.readBytes())
    }

    @Test
    fun metadataValuesThatEqualKeyNamesDoNotShadowTheKeys() {
        val header = validHeader(
            """{"musicName":"format","album":"artist","artist":[["album",0]],"format":"flac"}"""
        )
        val stream = ByteArrayInputStream(header + ByteArray(5) + ByteArray(8))

        val info = NCMConverter.readHeader(BinaryInput(stream::read))

        assertEquals("format", info.musicName)
        assertEquals("artist", info.musicAlbum)
        assertEquals("album", info.musicArtists)
        assertEquals("flac", info.format)
    }

    @Test
    fun skippedCoverStillRejectsInvalidLengthsAndTruncatedImageOrPadding() {
        val invalidCovers = listOf(
            coverHeader(4, 5) + ByteArray(4),
            coverHeader(4, -1) + ByteArray(4),
            coverHeader(4, 4) + ByteArray(3),
            coverHeader(8, 4) + ByteArray(6),
            coverHeader(64 * 1024 * 1024 + 1, 0)
        )
        for (bytes in invalidCovers) {
            for (includeCover in listOf(true, false)) {
                assertFailsWith<IllegalArgumentException> {
                    NCMConverter.readHeader(
                        BinaryInput(ByteArrayInputStream(bytes)::read), includeCover
                    )
                }
            }
        }
    }

    private fun coverHeader(coverLength: Int, imageLength: Int): ByteArray =
        ByteArrayOutputStream().apply {
            write(validHeader())
            write(ByteArray(5))
            writeLittleEndian(this, coverLength)
            writeLittleEndian(this, imageLength)
        }.toByteArray()

    private fun parse(bytes: ByteArray) {
        NCMConverter.readHeader(BinaryInput(ByteArrayInputStream(bytes)::read))
    }

    private fun ncmPrefixWithValidKey(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("CTENFDAM".toByteArray(StandardCharsets.US_ASCII))
        output.write(byteArrayOf(0, 0))
        val key = ByteArray(15) { (it + 1).toByte() }
        val keyPlain = ByteArray(17 + key.size)
        "neteasecloudmusic".toByteArray(StandardCharsets.US_ASCII).copyInto(keyPlain)
        key.copyInto(keyPlain, 17)
        val encryptedKey = aesEncrypt(
            byteArrayOf(
                0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
                0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
            ),
            keyPlain
        )
        writeLittleEndian(output, encryptedKey.size)
        output.write(encryptedKey.map { (it.toInt() xor 0x64).toByte() }.toByteArray())
        return output.toByteArray()
    }

    private fun validHeader(
        metadataJson: String =
            """{"musicName":"Song","album":"Album","artist":[["Artist",0]],"format":"mp3"}"""
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ncmPrefixWithValidKey())
        val encryptedMetadata = aesEncrypt(
            byteArrayOf(
                0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
                0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
            ),
            metadataJson.toByteArray(StandardCharsets.UTF_8)
        )
        val encoded = java.util.Base64.getEncoder().encode(encryptedMetadata)
        val raw = ByteArray(22 + encoded.size) { 0 }
        encoded.copyInto(raw, 22)
        raw.indices.forEach { raw[it] = (raw[it].toInt() xor 0x63).toByte() }
        writeLittleEndian(output, raw.size)
        output.write(raw)
        return output.toByteArray()
    }

    private fun aesEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    private fun writeLittleEndian(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun writeLittleEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
