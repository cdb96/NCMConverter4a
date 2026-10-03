package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.converter.kgg.KggBatchKeys
import com.cdb96.ncmconverter4a.converter.kgg.QmcCipher
import com.cdb96.ncmconverter4a.converter.kgg.parseKgmHeader
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.io.skipFully
import com.cdb96.ncmconverter4a.util.FileNameUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/** Desktop implementation of KGG decryption using bounded stream buffers. */
class DesktopKggDecrypt(outputDirectory: File = defaultDesktopOutputDirectory()) {
    private val outputAllocator = DesktopOutputAllocator(outputDirectory)

    fun decrypt(audioFilePath: String, dbFilePath: String?, mitigateConflicts: Boolean = true) {
        val keys = KggBatchKeys {
            val path = dbFilePath ?: error("桌面版本需要选择DB文件")
            loadDesktopKggDatabase(path)
        }
        decrypt(audioFilePath, mitigateConflicts, keys)
    }

    internal fun decrypt(audioFilePath: String, mitigateConflicts: Boolean, keys: KggBatchKeys) {
        val audioFile = File(audioFilePath)
        require(audioFile.isFile) { "音频文件不存在: $audioFilePath" }

        BufferedInputStream(FileInputStream(audioFile)).use { audioStream ->
            val headerChunk = ByteArray(1024)
            BinaryInput(audioStream::read).readFully(headerChunk)
            val header = parseKgmHeader(headerChunk)
            require(header.cryptoVersion == 5u) {
                "不是KGG文件 (cryptoVersion=${header.cryptoVersion})"
            }

            val key = keys.getKey(header.audioHash)

            val cipher = QmcCipher.createCipher(key)
            val audioOffset = header.audioOffset.toLong()
            require(audioOffset >= headerChunk.size) {
                "KGG audio offset is before the header: $audioOffset"
            }
            BinaryInput(audioStream::read).skipFully(audioOffset - headerChunk.size)

            val audioFormat = detectAudioFormat(audioStream, cipher)
            // audioFile.name is the real source file name (e.g. song.kgg): strip
            // its extension before it becomes the output basename.
            val outputBaseName = FileNameUtils.removeLastExtension(audioFile.name)

            outputAllocator.withUniqueOutput(
                requestedName = outputBaseName,
                extension = audioFormat,
                mitigateConflicts = mitigateConflicts
            ) { outputStream ->
                val buffer = ByteArray(QmcCipher.STREAM_BUFFER_SIZE)
                var streamOffset = 0L
                while (true) {
                    val bytesRead = BinaryInput(audioStream::read).readChunk(buffer)
                    if (bytesRead < 0) break
                    cipher.decrypt(buffer, streamOffset, bytesRead)
                    outputStream.write(buffer, 0, bytesRead)
                    streamOffset += bytesRead.toLong()
                }
            }
        }
    }

    private fun detectAudioFormat(
        audioStream: BufferedInputStream,
        cipher: QmcCipher.QmcStreamCipher
    ): String {
        audioStream.mark(8)
        val header = ByteArray(4)
        BinaryInput(audioStream::read).readFully(header)
        cipher.decrypt(header, 0L)
        audioStream.reset()
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

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}
