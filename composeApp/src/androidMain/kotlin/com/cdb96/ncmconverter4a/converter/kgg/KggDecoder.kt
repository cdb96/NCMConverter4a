//ported from Unlock Music Project
package com.cdb96.ncmconverter4a.converter.kgg

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.InputStream

class KggDecoder(context: Context) {
    private val contentResolver = context.contentResolver

    fun decryptWithUri(audioFileUri: Uri, dbFileUri: Uri?, isRooted: Boolean){
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

        val cipher = getCipher(header.audioHash, dbFileUri, isRooted)
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

    fun getCipher(audioHash: String, dbFileUri: Uri?, isRooted: Boolean): QmcCipher.QmcStreamCipher {
        val key = if (isRooted) {
            getKeyAsRoot(audioHash)
        } else {
            val dbFileInputStream = contentResolver.openInputStream(dbFileUri!!)
                ?: throw IllegalStateException("无法打开mmkv数据库文件，Uri: $dbFileUri")
            getKey(dbFileInputStream, audioHash)
        }
        return QmcCipher.createCipher(key)
    }

    fun getKey(inputStream: InputStream,audioHash: String): ByteArray{
        val mmkvParser = MMKVParser(inputStream.readBytes())
        val eKeyBytes = mmkvParser.getBytes(audioHash) ?: throw IllegalStateException("ekey解析失败")
        val key = deriveKey(eKeyBytes)
        return key
    }

    private fun getKeyAsRoot(audioHash: String): ByteArray {
        val mmkvPath = "/data/data/com.kugou.android/files/mmkv/mggkey_multi_process"
        val processBuilder = ProcessBuilder("su", "-c", "cat \"$mmkvPath\"")
        processBuilder.redirectErrorStream(true)
        val process = processBuilder.start()
        val key = getKey(process.inputStream, audioHash)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Root获取密钥失败，请确认已授予Root权限")
        }
        return key
    }

    fun detectAudioFormat(audioFileInputStream: BufferedInputStream, cipher: QmcCipher.QmcStreamCipher?): String {
        val header = ByteArray(4)
        audioFileInputStream.read(header)
        cipher?.decrypt(header, 0)
        return when {
            header.startsWith("ID3".toByteArray()) -> "mp3"
            header.startsWith("fLaC".toByteArray()) -> "flac"
            header.startsWith("Ogg".toByteArray()) -> "ogg"
            else -> "null"
        }
    }

    fun outputMusic(
        fileName: String,
        cipher: QmcCipher.QmcStreamCipher?,
        audioFileInputStream: BufferedInputStream
    ){
        val audioFormat = detectAudioFormat(audioFileInputStream, cipher)
        val mimeType = when (audioFormat.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }
        val musicName = "$fileName.${audioFormat.lowercase()}"
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
                    cipher?.decrypt(buffer,offset)
                    outputStream.write(buffer, 0, bytesRead)
                    offset += bufferSize
                }
                outputStream.flush()
            }
        }
    }
}