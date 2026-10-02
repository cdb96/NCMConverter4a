package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.kgg.deriveKey
import com.cdb96.ncmconverter4a.jni.KGMDecrypt
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopOutputDirectoryTest {
    private val magic = byteArrayOf(
        0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(), 0x86.toByte(), 0x02, 0x7F, 0x4B,
        0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(), 0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14,
    )

    @Test
    fun mixedBatchWritesNcmKgmAndKggToSelectedUnicodeDirectory() = runBlocking {
        val root = Files.createTempDirectory("output-directory").toFile()
        try {
            val destination = File(root, "中文 输出/新建文件夹")
            val audio = "ID3".toByteArray() + ByteArray(4096) { it.toByte() }
            val ncm = File(root, "input.ncm").apply { writeBytes(ncmFile(audio)) }

            val kgmHeader = magic.copyOf(1024).apply { this[20] = 3 }
            val kgmKey = KGMConverter.getOwnKeyBytes(kgmHeader)
            val kgmPayload = ByteArray(4099) { it.toByte() }.apply {
                this[0] = (0..255).first { KGMConverter.detectFormat(it.toByte(), kgmKey) == "mp3" }.toByte()
            }
            val expectedKgm = kgmPayload.copyOf()
            val kgmContext = KGMDecrypt.create(kgmKey)
            try {
                KGMDecrypt.decrypt(kgmContext, expectedKgm, 0, expectedKgm.size)
            } finally {
                KGMDecrypt.destroy(kgmContext)
            }
            val kgm = File(root, "kgm-song.kgm").apply { writeBytes(kgmHeader + kgmPayload) }

            // A fixed Tencent TEA ekey whose derived QMC map key is bytes 1..14.
            val ekey = "AQIDBAUGBwijvfhds1lCBVcHv7WluvwG".toByteArray()
            val key = ByteArray(14) { (it + 1).toByte() }
            assertContentEquals(key, deriveKey(ekey))
            val hash = "test-audio-hash"
            val encryptedKgg = audio.copyOf().also { ReferenceQmcMap(key).decrypt(it, 0) }
            val kggHeader = magic.copyOf(1024).apply {
                littleEndian(1024).copyInto(this, 16)
                this[20] = 5
                littleEndian(hash.length).copyInto(this, 68)
                hash.toByteArray().copyInto(this, 72)
            }
            val kgg = File(root, "kgg-song.kgg").apply { writeBytes(kggHeader + encryptedKgg) }
            val database = File(root, "keys.mmkv").apply {
                writeBytes(ByteArray(8) + byteArrayOf(hash.length.toByte()) + hash.toByteArray() +
                    byteArrayOf(0x0A, 0, ekey.size.toByte()) + ekey)
            }

            val result = DesktopConversionFacade(destination).processFiles(
                listOf(ncm.path, kgm.path, kgg.path), 3, true, false, database.path,
            ) { _, _, _ -> }

            assertEquals(3, result.successCount, result.failedFiles.toString())
            assertEquals(setOf("Artist - Song.mp3", "kgm-song.mp3", "kgg-song.mp3"),
                destination.listFiles().orEmpty().map { it.name }.toSet())
            assertContentEquals(audio, File(destination, "Artist - Song.mp3").readBytes())
            assertContentEquals(expectedKgm, File(destination, "kgm-song.mp3").readBytes())
            assertContentEquals(audio, File(destination, "kgg-song.mp3").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun switchingDestinationKeepsConflictHandlingWithinSelectedFolder() = runBlocking {
        val root = Files.createTempDirectory("output-switch").toFile()
        try {
            val first = File(root, "first")
            val second = File(root, "second")
            val audio = "ID3audio".toByteArray()
            val input = File(root, "song.ncm").apply { writeBytes(ncmFile(audio)) }
            suspend fun convert(destination: File, mitigate: Boolean = false) =
                DesktopConversionFacade(destination).processFiles(listOf(input.path), 1, true, mitigate) { _, _, _ -> }

            assertEquals(1, convert(first).successCount)
            assertEquals(1, convert(second).successCount)
            val conflict = convert(second)
            assertTrue(conflict.failedFiles.single().outputAlreadyExists)
            assertEquals(1, convert(second, mitigate = true).successCount)
            assertEquals(listOf("Artist - Song.mp3"), first.listFiles().orEmpty().map { it.name })
            assertContentEquals(audio, File(second, "Artist - Song (1).mp3").readBytes())
            assertContentEquals(audio, File(second, "Artist - Song.mp3").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun selectionPersistsAcrossPreferenceInstancesAndRejectsFiles() {
        val root = Files.createTempDirectory("output-preference").toFile()
        try {
            val config = File(root, "settings/conversion.properties").toPath()
            val preference = DesktopOutputDirectoryPreference(config)
            assertEquals(defaultDesktopOutputDirectory(), preference.load())
            val directory = File(root, "音乐 输出").apply { mkdirs() }
            preference.save(directory)
            assertEquals(directory, DesktopOutputDirectoryPreference(config).load())
            val file = File(root, "not-a-folder").apply { writeText("content") }
            assertFailsWith<IllegalArgumentException> { preference.save(file) }
            assertEquals(directory, preference.load())
            Files.writeString(config, "outputDirectory=\n")
            assertEquals(defaultDesktopOutputDirectory(), preference.load())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ncmFile(audio: ByteArray): ByteArray {
        val key = ByteArray(15) { (it + 1).toByte() }
        val encryptedKey = aes("hzHRAmso5kInbaxW", "neteasecloudmusic".toByteArray().copyOf(17) + key)
            .map { (it.toInt() xor 0x64).toByte() }.toByteArray()
        val metadata = """{"musicName":"Song","album":"Album","artist":[["Artist",0]],"format":"mp3"}"""
        val encoded = Base64.getEncoder().encode(aes("#14ljk_!\\]&0U<'(", metadata.toByteArray()))
        val encryptedMetadata = (ByteArray(22) + encoded).map { (it.toInt() xor 0x63).toByte() }.toByteArray()
        val payload = audio.copyOf()
        val context = RC4Decrypt.create(key)
        try {
            RC4Decrypt.decrypt(context, payload, payload.size)
        } finally {
            RC4Decrypt.destroy(context)
        }
        return ByteArrayOutputStream().apply {
            write("CTENFDAM".toByteArray() + ByteArray(2))
            write(littleEndian(encryptedKey.size))
            write(encryptedKey)
            write(littleEndian(encryptedMetadata.size))
            write(encryptedMetadata)
            write(ByteArray(13))
            write(payload)
        }.toByteArray()
    }

    private fun aes(key: String, data: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        doFinal(data)
    }

    private fun littleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
