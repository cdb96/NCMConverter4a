//ported from Unlock Music Project
package com.cdb96.ncmconverter4a.converter.kgg

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.io.skipFully
import com.cdb96.ncmconverter4a.util.FileNameUtils
import java.io.BufferedInputStream
import java.io.InputStream

class KggDecoder(context: Context) {
    private val contentResolver = context.contentResolver

    fun decryptWithUri(audioFileUri: Uri, dbFileUri: Uri?, isRooted: Boolean) {
        val rawStream = contentResolver.openInputStream(audioFileUri)
            ?: throw IllegalStateException("无法打开音频文件，Uri: $audioFileUri")
        rawStream.use { raw ->
            BufferedInputStream(raw, 256 * 1024).use { audioStream ->
                val headerChunk = ByteArray(KGMConverter.HEADER_LENGTH)
                audioStream.readFully(headerChunk)
                val header = parseKgmHeader(headerChunk)
                require(header.cryptoVersion == 5u) { "不是KGG文件" }

                val cipher = getCipher(header.audioHash, dbFileUri, isRooted)
                val musicName = getFileName(audioFileUri)
                outputMusic(musicName, header.audioOffset.toLong(), cipher, audioStream)
            }
        }
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

    fun getCipher(
        audioHash: String,
        dbFileUri: Uri?,
        isRooted: Boolean
    ): QmcCipher.QmcStreamCipher {
        val key = if (isRooted) {
            getKeyAsRoot(audioHash)
        } else {
            val uri = dbFileUri ?: throw IllegalStateException("请先选择mmkv数据库文件")
            val dbInput = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开mmkv数据库文件，Uri: $uri")
            dbInput.use { getKey(it, audioHash) }
        }
        return QmcCipher.createCipher(key)
    }

    fun getKey(inputStream: InputStream, audioHash: String): ByteArray {
        val mmkvParser = MMKVParser(inputStream.readBytes())
        val eKeyBytes = mmkvParser.getBytes(audioHash) ?: throw IllegalStateException("ekey解析失败")
        return deriveKey(eKeyBytes)
    }

    private fun getKeyAsRoot(audioHash: String): ByteArray {
        val mmkvPath = "/data/data/com.kugou.android/files/mmkv/mggkey_multi_process"
        val processBuilder = ProcessBuilder("su", "-c", "cat \"$mmkvPath\"")
        processBuilder.redirectErrorStream(true)
        val process = processBuilder.start()
        val key = process.inputStream.use { getKey(it, audioHash) }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Root获取密钥失败，请确认已授予Root权限")
        }
        return key
    }

    private fun detectAudioFormat(
        audioFileInputStream: BufferedInputStream,
        cipher: QmcCipher.QmcStreamCipher
    ): String {
        audioFileInputStream.mark(8)
        val header = ByteArray(4)
        audioFileInputStream.readFully(header)
        cipher.decrypt(header, 0L)
        audioFileInputStream.reset()
        return when {
            header.startsWith("ID3".encodeToByteArray()) -> "mp3"
            header.startsWith("fLaC".encodeToByteArray()) -> "flac"
            header.startsWith("Ogg".encodeToByteArray()) -> "ogg"
            header.size >= 2 &&
                (header[0].toInt() and 0xFF) == 0xFF &&
                (header[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> throw IllegalArgumentException("无法识别 KGG 音频格式")
        }
    }

    private fun outputMusic(
        fileName: String,
        audioOffset: Long,
        cipher: QmcCipher.QmcStreamCipher,
        audioFileInputStream: BufferedInputStream
    ) {
        require(audioOffset >= KGMConverter.HEADER_LENGTH) {
            "KGG audio offset is before the header: $audioOffset"
        }
        audioFileInputStream.skipFully(audioOffset - KGMConverter.HEADER_LENGTH)
        val audioFormat = detectAudioFormat(audioFileInputStream, cipher)
        val mimeType = when (audioFormat) {
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val safeName = FileNameUtils.sanitizeFileName(
            FileNameUtils.removeLastExtension(fileName)
        )
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeName.$audioFormat")
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A")
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建输出文件")
        try {
            val output = contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("无法打开输出文件")
            output.use {
                val buffer = ByteArray(8192)
                var streamOffset = 0L
                while (true) {
                    val bytesRead = audioFileInputStream.readChunk(buffer)
                    if (bytesRead < 0) break
                    cipher.decrypt(buffer, streamOffset)
                    it.write(buffer, 0, bytesRead)
                    streamOffset += bytesRead.toLong()
                }
            }
        } catch (error: Throwable) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}
