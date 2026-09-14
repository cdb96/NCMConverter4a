package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.EncryptedFormat
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.converter.detectEncryptedFormat
import com.cdb96.ncmconverter4a.io.InputStreamBinaryInput
import com.cdb96.ncmconverter4a.io.OutputStreamBinaryOutput
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.platform.Logger
import com.cdb96.ncmconverter4a.service.ConversionResult
import com.cdb96.ncmconverter4a.service.FileConversionResult
import com.cdb96.ncmconverter4a.util.FileNameUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DesktopConversionFacade {
    private val log = Logger("DesktopConversion")
    private val outputAllocator = DesktopOutputAllocator(
        File(System.getProperty("user.home"), "Music/NCMConverter4A")
    )

    suspend fun processFiles(
        filePaths: List<String>,
        threadCount: Int,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean,
        onProgress: suspend (processed: Int, total: Int, fileName: String) -> Unit
    ): ConversionResult {
        val startTime = System.currentTimeMillis()
        val completedCount = AtomicInteger(0)
        val sourceNames = filePaths.map { File(it).name }
        val dispatcher = Executors.newFixedThreadPool(threadCount.coerceAtLeast(1)).asCoroutineDispatcher()

        try {
            val results = supervisorScope {
                filePaths.mapIndexed { index, path ->
                    async(dispatcher) {
                        val fileName = sourceNames[index]
                        val result = try {
                            convertFile(path, rawWriteMode, duplicateConflictMitigation)
                            FileConversionResult(fileName = fileName, success = true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            log.e("处理文件时出错: ${error.message}", error)
                            FileConversionResult(
                                fileName = fileName,
                                success = false,
                                error = error.message ?: error::class.simpleName
                            )
                        }

                        val completed = completedCount.incrementAndGet()
                        onProgress(completed, filePaths.size, fileName)
                        result
                    }
                }.awaitAll()
            }

            val duration = System.currentTimeMillis() - startTime
            return ConversionResult(
                successCount = results.count { it.success },
                failureCount = results.count { !it.success },
                durationMillis = duration,
                allFileNames = sourceNames.joinToString(", "),
                successfulFileNames = results.filter { it.success }.map { it.fileName },
                failedFileNames = results.filterNot { it.success }.map { it.fileName },
            )
        } finally {
            dispatcher.close()
        }
    }

    private fun convertFile(
        path: String,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean
    ) {
        val file = File(path)
        require(file.isFile) { "输入文件不存在: $path" }

        BufferedInputStream(FileInputStream(file), NCMConverter.AUDIO_BUFFER_SIZE).use { input ->
            val format = detectFormat(input)
            when (format) {
                EncryptedFormat.KGM -> convertKGM(
                    input,
                    file.name,
                    duplicateConflictMitigation
                )
                EncryptedFormat.NCM -> convertNCM(
                    input,
                    rawWriteMode,
                    duplicateConflictMitigation
                )
                EncryptedFormat.UNSUPPORTED ->
                    throw IllegalArgumentException("不支持的加密文件格式: ${file.name}")
            }
        }
    }

    private fun detectFormat(input: BufferedInputStream): EncryptedFormat {
        input.mark(32)
        val header = ByteArray(16)
        val bytesRead = input.readChunk(header)
        input.reset()
        return detectEncryptedFormat(header.copyOf(if (bytesRead < 0) 0 else bytesRead))
    }

    private fun convertNCM(
        input: BufferedInputStream,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean
    ) {
        val binaryInput = InputStreamBinaryInput(input)
        val info = NCMConverter.readHeader(binaryInput)
        val requestedName = "${info.musicArtists} - ${info.musicName}"

        outputAllocator.withUniqueOutput(
            requestedName = requestedName,
            extension = info.format,
            mitigateConflicts = duplicateConflictMitigation
        ) { output ->
            NCMConverter.writeAudio(
                input = binaryInput,
                output = OutputStreamBinaryOutput(output),
                info = info,
                rawWriteMode = rawWriteMode
            )
        }
    }

    private fun convertKGM(
        input: BufferedInputStream,
        sourceName: String,
        duplicateConflictMitigation: Boolean
    ) {
        val header = ByteArray(KGMConverter.HEADER_LENGTH)
        input.readFully(header)
        val ownKeyBytes = KGMConverter.getOwnKeyBytes(header)

        val firstChunk = ByteArray(NCMConverter.AUDIO_BUFFER_SIZE)
        val firstSize = input.readChunk(firstChunk)
        require(firstSize > 0) { "KGM audio payload is empty" }
        val musicFormat = KGMConverter.detectFormat(firstChunk[0], ownKeyBytes)
        require(musicFormat.isNotEmpty()) { "无法识别 KGM 音频格式" }

        val requestedName = FileNameUtils.removeLastExtension(sourceName)
        outputAllocator.withUniqueOutput(
            requestedName = requestedName,
            extension = musicFormat,
            mitigateConflicts = duplicateConflictMitigation
        ) { output ->
            KGMConverter.decrypt(
                ownKeyBytes = ownKeyBytes,
                firstChunk = firstChunk,
                firstSize = firstSize,
                bufferSize = NCMConverter.AUDIO_BUFFER_SIZE,
                read = { buffer -> input.readChunk(buffer) },
                write = { buffer, bytesToWrite -> output.write(buffer, 0, bytesToWrite) }
            )
        }
    }
}
