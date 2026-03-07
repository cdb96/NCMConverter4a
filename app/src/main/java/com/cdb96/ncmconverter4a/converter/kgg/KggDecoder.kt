//ported from Unlock Music Project
package com.cdb96.ncmconverter4a.converter.kgg

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.BufferedInputStream

data class KggHeader(
    val magicHeader: ByteArray,      // 16字节: 魔数
    val audioOffset: UInt,           // 4字节: 音频数据偏移
    val cryptoVersion: UInt,         // 4字节: 加密版本
    val cryptoSlot: UInt,            // 4字节: 密钥槽位
    val cryptoTestData: ByteArray,   // 16字节: 测试数据
    val cryptoKey: ByteArray,        // 16字节: 密钥
    var audioHash: String = ""       // V5: 音频哈希标识
)

fun parseKgmHeader(data: ByteArray): KggHeader {
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    // 读取基础头部
    val magicHeader = ByteArray(16)
    buffer.get(magicHeader)

    val audioOffset = buffer.int.toUInt()
    val cryptoVersion = buffer.int.toUInt()
    val cryptoSlot = buffer.int.toUInt()

    val cryptoTestData = ByteArray(16)
    buffer.get(cryptoTestData)

    val cryptoKey = ByteArray(16)
    buffer.get(cryptoKey)

    val header = KggHeader(
        magicHeader = magicHeader,
        audioOffset = audioOffset,
        cryptoVersion = cryptoVersion,
        cryptoSlot = cryptoSlot,
        cryptoTestData = cryptoTestData,
        cryptoKey = cryptoKey
    )

    // V5 版本额外读取 AudioHash
    if (cryptoVersion == 5u) {
        buffer.position(buffer.position() + 8)  // 跳过 8 字节
        val audioHashLen = buffer.int.toUInt().toInt()
        val audioHashBuffer = ByteArray(audioHashLen)
        buffer.get(audioHashBuffer)
        header.audioHash = String(audioHashBuffer)
    }

    return header
}

class KggDecoder(context: Context) {
    private val contentResolver = context.contentResolver

    fun decryptWithUri(audioFileUri: Uri,dbFileUri: Uri){
        val rawStream: InputStream = contentResolver.openInputStream(audioFileUri) ?: throw IllegalStateException("无法打开音频文件，Uri: $audioFileUri")
        val audioFileInputStream = BufferedInputStream(rawStream).apply {
            mark(1024 * 1024)
        }

        val headerChunk = ByteArray(1024)
        audioFileInputStream.read(headerChunk)
        val header: KggHeader = parseKgmHeader(headerChunk)
        if (header.cryptoVersion != 5u) {
            throw IllegalStateException("不是KGG文件")
        }

        val dbFileInputStream: InputStream = contentResolver.openInputStream(dbFileUri) ?: throw IllegalStateException("无法打开mmkv数据库文件，Uri: $dbFileUri")
        val cipher = getCipher(dbFileInputStream,header.audioHash)
        val musicName = getFileName(audioFileUri)
        outputMusic(musicName,cipher,audioFileInputStream)
    }

    private fun getFileName(uri: Uri): String {
        var name = "未知文件"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun getCipher(dbFileInputStream: InputStream,audioHash: String): QmcCipher.QmcStreamCipher {
        val mmkvParser = MMKVParser(dbFileInputStream)
        val eKeyBytes =
            mmkvParser.getBytes(audioHash) ?: throw IllegalStateException("ekey解析失败")

        /*
        val kggDbDecryptor = KggDbDecryptor()
        val eKey = kggDbDecryptor.extractKeyMapping(dbByteArray)[KggHeader.audioHash] ?: throw IllegalStateException("未找到对应的eKey")
        val eKeyBytes = eKey.toByteArray(Charsets.UTF_8)
        */

        val key = deriveKey(eKeyBytes)
        val cipher = QmcCipher.createCipher(key)
        return cipher
    }

    fun getFormat(audioFileInputStream: BufferedInputStream,cipher: QmcCipher.QmcStreamCipher): String {
        val header = ByteArray(4)
        audioFileInputStream.read(header)
        cipher.decrypt(header, 0)
        return when {
            header.startsWith("ID3".toByteArray()) -> "mp3"
            header.startsWith("fLaC".toByteArray()) -> "flac"
            header.startsWith("Ogg".toByteArray()) -> "ogg"
            else -> "null"
        }
    }

    fun outputMusic(
        fileName: String,
        cipher: QmcCipher.QmcStreamCipher,
        audioFileInputStream: BufferedInputStream
    ){
        val format = getFormat(audioFileInputStream,cipher)
        val mimeType = when (format.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val musicName = "$fileName.${format.lowercase()}"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, musicName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A")
        }
        audioFileInputStream.reset()
        audioFileInputStream.skip(1024)
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let{
            contentResolver.openOutputStream(uri, "w")?.use { outputStream ->
                val bufferSize = 8192
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                var offset = 0

                // 从输入源读取并写入
                while (audioFileInputStream.read(buffer).also { bytesRead = it } != -1) {
                    cipher.decrypt(buffer,offset)
                    outputStream.write(buffer, 0, bytesRead)
                    offset += bufferSize
                }
                outputStream.flush()
            }
        }
    }
}