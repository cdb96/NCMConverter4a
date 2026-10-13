package com.cdb96.ncmconverter4a.service

import com.cdb96.ncmconverter4a.converter.EncryptedFormat
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.converter.detectEncryptedFormat
import com.cdb96.ncmconverter4a.converter.kgg.KggBatchKeys
import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.converter.kgg.QmcCipher
import com.cdb96.ncmconverter4a.converter.kgg.parseKgmHeader
import com.cdb96.ncmconverter4a.converter.kgg.startsWith
import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.readAtMost
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.io.skipFully
import com.cdb96.ncmconverter4a.platform.Logger
import com.cdb96.ncmconverter4a.util.FileNameUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Platform file access; conversion and batch orchestration are shared. */
internal interface ConversionFiles {
    fun fileName(source: String): String
    fun openInput(source: String): InputStream
    fun writeOutput(
        fileName: String,
        extension: String,
        mitigateConflicts: Boolean,
        write: (OutputStream) -> Unit,
    )
    fun loadKggDatabase(database: String?, rootMode: Boolean): KggKeyDatabase
    fun prepareOutput(mitigateConflicts: Boolean) {}
    fun bufferSize(inputCount: Int, threadCount: Int): Int = NCMConverter.AUDIO_BUFFER_SIZE
}

internal open class OutputExistsException(message: String) : IOException(message)

class FileConversionService internal constructor(private val files: ConversionFiles) {
    private val log = Logger("FileConversionService")

    suspend fun processFiles(
        sources: List<String>,
        threadCount: Int,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean,
        kggDatabase: String? = null,
        kggRootMode: Boolean = false,
        onProgress: suspend (processed: Int, total: Int, fileName: String) -> Unit,
    ): ConversionResult {
        val startTime = System.currentTimeMillis()
        val completedCount = AtomicInteger(0)
        val sourceNames = withContext(Dispatchers.IO) {
            files.prepareOutput(duplicateConflictMitigation)
            sources.map(files::fileName)
        }
        val workerCount = minOf(sources.size.coerceAtLeast(1), threadCount.coerceAtLeast(1))
        val dispatcher = Dispatchers.IO.limitedParallelism(workerCount)
        val bufferSize = files.bufferSize(sources.size, threadCount)
        val kggKeys = if (kggRootMode || kggDatabase != null) {
            KggBatchKeys { files.loadKggDatabase(kggDatabase, kggRootMode) }
        } else null

        val results = supervisorScope {
            sources.mapIndexed { index, source ->
                async(dispatcher) {
                    val fileName = sourceNames[index]
                    val result = try {
                        convertFile(source, fileName, rawWriteMode, duplicateConflictMitigation, bufferSize, kggKeys)
                        FileConversionResult(fileName = fileName, success = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (error !is OutputExistsException) {
                            log.e("处理文件时出错: ${error.message}", error)
                        }
                        FileConversionResult(
                            fileName = fileName,
                            success = false,
                            error = error.message ?: error::class.simpleName,
                            outputAlreadyExists = error is OutputExistsException,
                        )
                    }

                    onProgress(completedCount.incrementAndGet(), sources.size, fileName)
                    result
                }
            }.awaitAll()
        }

        return ConversionResult.from(
            results = results,
            durationMillis = System.currentTimeMillis() - startTime,
            sourceNames = sourceNames,
        )
    }

    // Keep the native context lifecycle within this synchronous worker call.
    // Coroutine progress updates can suspend only after conversion has finished.
    private fun convertFile(
        source: String,
        fileName: String,
        rawWriteMode: Boolean,
        mitigateConflicts: Boolean,
        bufferSize: Int,
        kggKeys: KggBatchKeys?,
    ) {
        BufferedInputStream(files.openInput(source)).use { input ->
            input.mark(24)
            val header = ByteArray(24)
            val size = BinaryInput(input::read).readAtMost(header)
            input.reset()
            when (detectEncryptedFormat(header.copyOf(size))) {
                EncryptedFormat.NCM -> convertNcm(input, rawWriteMode, mitigateConflicts, bufferSize)
                EncryptedFormat.KGM -> convertKgm(input, fileName, mitigateConflicts, bufferSize)
                EncryptedFormat.KGG -> {
                    require(kggKeys != null) { "请先在设置中选择 KGG 数据库" }
                    convertKgg(input, fileName, mitigateConflicts, kggKeys)
                }
                EncryptedFormat.UNSUPPORTED ->
                    throw IllegalArgumentException("不支持的加密文件格式: $fileName")
            }
        }
    }

    private fun convertNcm(
        input: InputStream,
        rawWriteMode: Boolean,
        mitigateConflicts: Boolean,
        bufferSize: Int,
    ) {
        val binaryInput = BinaryInput(input::read)
        val info = NCMConverter.readHeader(binaryInput, includeCover = !rawWriteMode)
        files.writeOutput("${info.musicArtists} - ${info.musicName}", info.format, mitigateConflicts) { output ->
            NCMConverter.writeAudio(
                input = binaryInput,
                output = BinaryOutput(output::write),
                info = info,
                rawWriteMode = rawWriteMode,
                bufferSize = bufferSize,
            )
        }
    }

    private fun convertKgm(
        input: InputStream,
        fileName: String,
        mitigateConflicts: Boolean,
        bufferSize: Int,
    ) {
        val binaryInput = BinaryInput(input::read)
        val header = ByteArray(KGMConverter.HEADER_LENGTH)
        binaryInput.readFully(header)
        val ownKeyBytes = KGMConverter.getOwnKeyBytes(header)
        val firstChunk = ByteArray(bufferSize)
        val firstSize = binaryInput.readChunk(firstChunk)
        require(firstSize > 0) { "KGM audio payload is empty" }
        val format = KGMConverter.detectFormat(firstChunk[0], ownKeyBytes)
        require(format.isNotEmpty()) { "无法识别 KGM 音频格式" }

        files.writeOutput(FileNameUtils.removeLastExtension(fileName), format, mitigateConflicts) { output ->
            KGMConverter.decrypt(
                ownKeyBytes = ownKeyBytes,
                firstChunk = firstChunk,
                firstSize = firstSize,
                bufferSize = bufferSize,
                read = binaryInput::readChunk,
                write = { buffer, count -> output.write(buffer, 0, count) },
            )
        }
    }

    private fun convertKgg(
        input: BufferedInputStream,
        fileName: String,
        mitigateConflicts: Boolean,
        keys: KggBatchKeys,
    ) {
        val binaryInput = BinaryInput(input::read)
        val headerBytes = ByteArray(KGMConverter.HEADER_LENGTH)
        binaryInput.readFully(headerBytes)
        val header = parseKgmHeader(headerBytes)
        require(header.cryptoVersion == 5u) { "不是 KGG 文件" }
        val cipher = QmcCipher.createCipher(keys.getKey(header.audioHash))
        binaryInput.skipFully(header.audioOffset.toLong() - headerBytes.size)

        input.mark(8)
        val audioHeader = ByteArray(4)
        binaryInput.readFully(audioHeader)
        cipher.decrypt(audioHeader, 0L)
        input.reset()
        val format = when {
            audioHeader.startsWith("ID3".encodeToByteArray()) -> "mp3"
            audioHeader.startsWith("fLaC".encodeToByteArray()) -> "flac"
            audioHeader.startsWith("Ogg".encodeToByteArray()) -> "ogg"
            (audioHeader[0].toInt() and 0xFF) == 0xFF &&
                (audioHeader[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> throw IllegalArgumentException("无法识别 KGG 音频格式")
        }

        files.writeOutput(FileNameUtils.removeLastExtension(fileName), format, mitigateConflicts) { output ->
            val buffer = ByteArray(QmcCipher.STREAM_BUFFER_SIZE)
            var offset = 0L
            while (true) {
                val count = binaryInput.readChunk(buffer)
                if (count < 0) break
                cipher.decrypt(buffer, offset, count)
                output.write(buffer, 0, count)
                offset += count
            }
        }
    }
}
