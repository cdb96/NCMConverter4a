//ported from Unlock Music Project
package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.io.BinaryInput
import android.content.Context
import android.net.Uri
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.kgg.root.RootDatabaseReader
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.io.skipFully
import java.io.BufferedInputStream
import java.io.InputStream

class KggDecoder(context: Context) {
    private val contentResolver = context.contentResolver
    private val rootDatabaseReader by lazy { RootDatabaseReader(context.cacheDir) }

    suspend fun decryptToOutput(
        input: BufferedInputStream,
        dbFileUri: Uri?,
        isRooted: Boolean,
        output: suspend (String, (java.io.OutputStream) -> Unit) -> Boolean,
    ): Boolean = decryptToOutput(input, createBatchKeys(dbFileUri, isRooted), output)

    internal suspend fun decryptToOutput(
        input: BufferedInputStream,
        keys: KggBatchKeys,
        output: suspend (String, (java.io.OutputStream) -> Unit) -> Boolean,
    ): Boolean {
        val bytes = ByteArray(KGMConverter.HEADER_LENGTH)
        BinaryInput(input::read).readFully(bytes)
        val header = parseKgmHeader(bytes)
        require(header.cryptoVersion == 5u) { "不是 KGG 文件" }
        val cipher = QmcCipher.createCipher(keys.getKey(header.audioHash))
        BinaryInput(input::read).skipFully(header.audioOffset.toLong() - bytes.size)
        val format = detectAudioFormat(input, cipher)
        return output(format) { stream ->
            val buffer = ByteArray(QmcCipher.STREAM_BUFFER_SIZE)
            var offset = 0L
            while (true) {
                val count = BinaryInput(input::read).readChunk(buffer)
                if (count < 0) break
                cipher.decrypt(buffer, offset, count)
                stream.write(buffer, 0, count)
                offset += count
            }
        }
    }

    fun getCipher(
        audioHash: String,
        dbFileUri: Uri?,
        isRooted: Boolean
    ): QmcCipher.QmcStreamCipher = QmcCipher.createCipher(createBatchKeys(dbFileUri, isRooted).getKey(audioHash))

    internal fun createBatchKeys(dbFileUri: Uri?, isRooted: Boolean): KggBatchKeys = KggBatchKeys {
        val bytes = if (isRooted) {
            rootDatabaseReader.read()
        } else {
            val uri = dbFileUri ?: throw IllegalStateException("请先在设置中选择 KGG 数据库")
            val dbInput = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开mmkv数据库文件，Uri: $uri")
            dbInput.use { it.readBytes() }
        }
        val parser = MMKVParser(bytes)
        KggKeyDatabase(parser::getBytes)
    }

    fun getKey(inputStream: InputStream, audioHash: String): ByteArray {
        val mmkvParser = MMKVParser(inputStream.readBytes())
        val eKeyBytes = mmkvParser.getBytes(audioHash) ?: throw IllegalStateException("ekey解析失败")
        return deriveKey(eKeyBytes)
    }

    private fun detectAudioFormat(
        audioFileInputStream: BufferedInputStream,
        cipher: QmcCipher.QmcStreamCipher
    ): String {
        audioFileInputStream.mark(8)
        val header = ByteArray(4)
        BinaryInput(audioFileInputStream::read).readFully(header)
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

}
