package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.EncryptedFormat
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.io.detectEncryptedFormat
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

private const val DESKTOP_BUFFER_BUDGET = 1024 * 1024
private const val MIN_DESKTOP_BUFFER_SIZE = 128 * 1024

internal fun desktopBufferSize(inputCount: Int, threadCount: Int): Int {
    val workers = minOf(inputCount.coerceAtLeast(1), threadCount.coerceAtLeast(1))
    val size = maxOf(MIN_DESKTOP_BUFFER_SIZE, DESKTOP_BUFFER_BUDGET / workers)
    return size - size % 256
}

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
        val workerCount = minOf(filePaths.size.coerceAtLeast(1), threadCount.coerceAtLeast(1))
        val bufferSize = desktopBufferSize(filePaths.size, threadCount)
        val dispatcher = Executors.newFixedThreadPool(workerCount).asCoroutineDispatcher()

        try {
            val results = supervisorScope {
                filePaths.mapIndexed { index, path ->
                    async(dispatcher) {
                        val fileName = sourceNames[index]
                        val result = try {
                            convertFile(path, rawWriteMode, duplicateConflictMitigation, bufferSize)
                            FileConversionResult(fileName = fileName, success = true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: DesktopOutputExistsException) {
                            FileConversionResult(
                                fileName = fileName,
                                success = false,
                                error = error.message,
                                outputAlreadyExists = true,
                            )
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

            return ConversionResult.from(
                results = results,
                durationMillis = System.currentTimeMillis() - startTime,
                sourceNames = sourceNames,
            )
        } finally {
            dispatcher.close()
            releaseIdleDesktopHeap()
        }
    }

    private fun convertFile(
        path: String,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean,
        bufferSize: Int,
    ) {
        val file = File(path)
        require(file.isFile) { "输入文件不存在: $path" }

        BufferedInputStream(FileInputStream(file)).use { input ->
            val format = input.detectEncryptedFormat()
            when (format) {
                EncryptedFormat.KGM -> convertKGM(
                    input,
                    file.name,
                    duplicateConflictMitigation,
                    bufferSize,
                )
                EncryptedFormat.NCM -> convertNCM(
                    input,
                    rawWriteMode,
                    duplicateConflictMitigation,
                    bufferSize,
                )
                EncryptedFormat.UNSUPPORTED ->
                    throw IllegalArgumentException("不支持的加密文件格式: ${file.name}")
            }
        }
    }

    private fun convertNCM(
        input: BufferedInputStream,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean,
        bufferSize: Int,
    ) {
        val binaryInput = InputStreamBinaryInput(input)
        val info = NCMConverter.readHeader(binaryInput, includeCover = !rawWriteMode)
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
                rawWriteMode = rawWriteMode,
                bufferSize = bufferSize,
            )
        }
    }

    private fun convertKGM(
        input: BufferedInputStream,
        sourceName: String,
        duplicateConflictMitigation: Boolean,
        bufferSize: Int,
    ) {
        val header = ByteArray(KGMConverter.HEADER_LENGTH)
        input.readFully(header)
        val ownKeyBytes = KGMConverter.getOwnKeyBytes(header)

        val firstChunk = ByteArray(bufferSize)
        val firstSize = input.readChunk(firstChunk)
        require(firstSize > 0) { "KGM audio payload is empty" }
        val musicFormat = KGMConverter.detectFormat(firstChunk[0], ownKeyBytes)
        require(musicFormat.isNotEmpty()) { "无法识别 KGM 音频格式" }

        // sourceName is the real source file name (e.g. song.kgm): strip its
        // extension before it becomes the output basename.
        val outputBaseName = FileNameUtils.removeLastExtension(sourceName)
        outputAllocator.withUniqueOutput(
            requestedName = outputBaseName,
            extension = musicFormat,
            mitigateConflicts = duplicateConflictMitigation
        ) { output ->
            KGMConverter.decrypt(
                ownKeyBytes = ownKeyBytes,
                firstChunk = firstChunk,
                firstSize = firstSize,
                bufferSize = bufferSize,
                read = { buffer -> input.readChunk(buffer) },
                write = { buffer, bytesToWrite -> output.write(buffer, 0, bytesToWrite) }
            )
        }
    }
}
