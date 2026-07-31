package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.KggDbDecryptor
import com.cdb96.ncmconverter4a.converter.kgg.MMKVParser
import com.cdb96.ncmconverter4a.converter.kgg.QmcCipher
import com.cdb96.ncmconverter4a.converter.kgg.deriveKey
import com.cdb96.ncmconverter4a.converter.kgg.parseKgmHeader
import com.cdb96.ncmconverter4a.platform.Logger
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Desktop implementation of KGG decryption.
 * Reads from files instead of Android ContentResolver.
 */
class DesktopKggDecrypt {
    private val log = Logger("DesktopKggDecrypt")

    fun decrypt(audioFilePath: String, dbFilePath: String?) {
        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) throw IllegalStateException("音频文件不存在: $audioFilePath")

        val audioStream = BufferedInputStream(FileInputStream(audioFile)).apply {
            mark(1024 * 1024)
        }

        val headerChunk = ByteArray(1024)
        audioStream.read(headerChunk)
        val header = parseKgmHeader(headerChunk)
        if (header.cryptoVersion != 5u) {
            throw IllegalStateException("不是KGG文件 (cryptoVersion=${header.cryptoVersion})")
        }

        val key = if (dbFilePath != null) {
            val dbFile = File(dbFilePath)
            if (!dbFile.exists()) throw IllegalStateException("数据库文件不存在: $dbFilePath")
            getKeyFromFile(dbFile, header.audioHash)
        } else {
            throw IllegalStateException("桌面版本需要选择DB文件")
        }

        val cipher = QmcCipher.createCipher(key)
        val audioFormat = detectAudioFormat(audioStream, cipher)
        val outputDir = File(System.getProperty("user.home"), "Music/NCMConverter4A")
        outputDir.mkdirs()

        val outputName = "${audioFile.nameWithoutExtension}.$audioFormat"
        val outputFile = File(outputDir, outputName)

        audioStream.reset()
        audioStream.skip(1024)

        FileOutputStream(outputFile).use { outputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var offset = 0
            while (audioStream.read(buffer).also { bytesRead = it } != -1) {
                cipher.decrypt(buffer, offset)
                outputStream.write(buffer, 0, bytesRead)
                offset += 8192
            }
            outputStream.flush()
        }
    }

    private fun getKeyFromFile(dbFile: File, audioHash: String): ByteArray {
        val dbBytes = dbFile.readBytes()
        val isSqlite = KggDbDecryptor.isSqliteDatabase(dbBytes)
        log.i("dbFile=${dbFile.name} size=${dbBytes.size} isSqliteDatabase=$isSqlite")
        val eKeyBytes = if (isSqlite) {
            val decrypted = KggDbDecryptor.decryptDatabase(ByteArrayInputStream(dbBytes))
            // 主路径: 用修复后的行解析器(现已正确忽略错误 payload_len, 用 header 算 record 大小)
            val mapping = KggDbDecryptor.extractKeyMapping(decrypted)
            log.i("map size=${mapping.size}, audioHash in map: ${mapping.containsKey(audioHash)}")
            if (mapping.containsKey(audioHash)) {
                log.d("map keys sample: ${mapping.keys.take(5)}")
                mapping[audioHash]?.encodeToByteArray()
            } else {
                // 兜底: 直接在解密字节中搜索 — 处理 schema 列名对不上的情况
                log.i("audioHash not in map, trying raw search...")
                KggDbDecryptor.extractEkey(decrypted, audioHash)
            }
        } else {
            MMKVParser(dbBytes).getBytes(audioHash)
        } ?: throw IllegalStateException("ekey解析失败: hash=$audioHash")
        return deriveKey(eKeyBytes)
    }

    private fun detectAudioFormat(
        audioStream: BufferedInputStream,
        cipher: QmcCipher.QmcStreamCipher
    ): String {
        val header = ByteArray(4)
        audioStream.read(header)
        cipher.decrypt(header, 0)
        return when {
            header.startsWith("ID3".toByteArray()) -> "mp3"
            header.startsWith("fLaC".toByteArray()) -> "flac"
            header.startsWith("Ogg".toByteArray()) -> "ogg"
            else -> "mp3"
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (this.size < prefix.size) return false
    for (i in prefix.indices) {
        if (this[i] != prefix[i]) return false
    }
    return true
}
