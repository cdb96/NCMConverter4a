package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.io.InputStreamBinaryInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFailsWith

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

    private fun parse(bytes: ByteArray) {
        NCMConverter.readHeader(InputStreamBinaryInput(ByteArrayInputStream(bytes)))
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

    private fun validHeader(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ncmPrefixWithValidKey())
        val metadataJson = "{\"musicName\":\"Song\",\"album\":\"Album\",\"artist\":\"[[\\\"Artist\\\",0]]\",\"format\":\"mp3\"}"
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
