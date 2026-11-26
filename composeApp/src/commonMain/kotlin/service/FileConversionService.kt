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
import com.cdb96.ncmconverter4a.io.BinaryInputException
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.ConversionBufferPool
import com.cdb96.ncmconverter4a.io.PassThroughBufferedOutputStream
import com.cdb96.ncmconverter4a.io.PrefetchInputStream
import com.cdb96.ncmconverter4a.io.WorkerBuffers
import com.cdb96.ncmconverter4a.io.readAtMost
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.platform.Logger
import com.cdb96.ncmconverter4a.util.FileNameUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Handle for deferred output publication (e.g. MediaStore IS_PENDING=0). */
internal interface PendingOutput {
    fun publish()
    fun discard()
}

internal class OpenedInput(
    val stream: InputStream,
    val knownSize: Long = -1L,
) : Closeable by stream

/** Platform file access; conversion and batch orchestration are shared. */
internal interface ConversionFiles {
    fun fileName(source: String): String
    fun resolveFileNames(sources: List<String>, knownFileNames: Map<String, String>): Map<String, String> =
        knownFileNames
    fun openInput(source: String): InputStream
    fun openInputWithSize(source: String): OpenedInput = OpenedInput(openInput(source))
    fun writeOutput(
        fileName: String,
        extension: String,
        mitigateConflicts: Boolean,
        write: (OutputStream) -> Unit,
    ): PendingOutput?
    fun loadKggDatabase(database: String?, rootMode: Boolean): KggKeyDatabase
    fun prepareOutput(mitigateConflicts: Boolean) {}
    fun beginBatch(mitigateConflicts: Boolean) {
        prepareOutput(mitigateConflicts)
    }
    fun endBatch() {}
    fun bufferSize(inputCount: Int, threadCount: Int): Int = NCMConverter.AUDIO_BUFFER_SIZE
    fun publishParallelism(): Int = 2
}

internal open class OutputExistsException(message: String) : IOException(message)

/** Computes a 256-byte-aligned per-worker buffer size within [budgetBytes]. */
internal fun boundedWorkerBufferSize(
    inputCount: Int,
    threadCount: Int,
    budgetBytes: Int,
    minBytes: Int,
    maxBytes: Int = Int.MAX_VALUE,
): Int {
    val workers = minOf(inputCount.coerceAtLeast(1), threadCount.coerceAtLeast(1))
    val size = (budgetBytes / workers).coerceIn(minBytes, maxBytes)
    return size - size % 256
}

class FileConversionService internal constructor(private val files: ConversionFiles) {
    private val log = Logger("FileConversionService")

    suspend fun processFiles(
        sources: List<String>,
        threadCount: Int,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean,
        kggDatabase: String? = null,
        kggRootMode: Boolean = false,
        knownFileNames: Map<String, String> = emptyMap(),
        onProgress: suspend (processed: Int, total: Int, fileName: String) -> Unit,
    ): ConversionResult {
        val startTime = System.currentTimeMillis()
        val completedCount = AtomicInteger(0)
        val fileNameNs = AtomicLong(0L)
        val workerCount = minOf(sources.size.coerceAtLeast(1), threadCount.coerceAtLeast(1))
        val dispatcher = Dispatchers.IO.limitedParallelism(workerCount)
        val publishDispatcher = Dispatchers.IO.limitedParallelism(files.publishParallelism().coerceAtLeast(1))
        val bufferSize = files.bufferSize(sources.size, threadCount)
        val bufferPool = ConversionBufferPool(bufferSize)
        val resolvedNames = Array(sources.size) { "" }
        val convertNs = AtomicLong(0L)
        val publishNs = AtomicLong(0L)

        val results = try {
            val preResolvedNames = withContext(Dispatchers.IO) {
                files.beginBatch(duplicateConflictMitigation)
                val t0 = System.nanoTime()
                val resolved = files.resolveFileNames(sources, knownFileNames)
                fileNameNs.addAndGet(System.nanoTime() - t0)
                resolved
            }
            val kggKeys = if (kggRootMode || kggDatabase != null) {
                KggBatchKeys { files.loadKggDatabase(kggDatabase, kggRootMode) }
            } else null

            supervisorScope {
                val conversionTasks: List<Deferred<Deferred<FileConversionResult>>> = sources.mapIndexed { index, source ->
                    async(dispatcher) {
                        val fileName = preResolvedNames[source] ?: run {
                            val fileNameStart = System.nanoTime()
                            try {
                                files.fileName(source)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                "未知文件"
                            } finally {
                                fileNameNs.addAndGet(System.nanoTime() - fileNameStart)
                            }
                        }
                        resolvedNames[index] = fileName

                        val convertStart = System.nanoTime()
                        val pending = try {
                            val buffers = bufferPool.acquire()
                            try {
                                convertFile(
                                    source = source,
                                    fileName = fileName,
                                    rawWriteMode = rawWriteMode,
                                    mitigateConflicts = duplicateConflictMitigation,
                                    bufferSize = bufferSize,
                                    buffers = buffers,
                                    kggKeys = kggKeys,
                                )
                            } finally {
                                bufferPool.release(buffers)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (error !is OutputExistsException) {
                                log.e("处理文件时出错: ${error.message}", error)
                            }
                            onProgress(completedCount.incrementAndGet(), sources.size, fileName)
                            return@async CompletableDeferred(
                                FileConversionResult(
                                    fileName = fileName,
                                    success = false,
                                    error = error.message ?: error::class.simpleName,
                                    outputAlreadyExists = error is OutputExistsException,
                                )
                            )
                        } finally {
                            convertNs.addAndGet(System.nanoTime() - convertStart)
                        }

                        if (pending == null) {
                            onProgress(completedCount.incrementAndGet(), sources.size, fileName)
                            CompletableDeferred(FileConversionResult(fileName = fileName, success = true))
                        } else {
                            val publishDeferred = async(publishDispatcher) {
                                val publishStart = System.nanoTime()
                                val result = try {
                                    pending.publish()
                                    FileConversionResult(fileName = fileName, success = true)
                                } catch (cancelled: CancellationException) {
                                    pending.discard()
                                    throw cancelled
                                } catch (error: Exception) {
                                    pending.discard()
                                    log.e("发布文件时出错: ${error.message}", error)
                                    FileConversionResult(
                                        fileName = fileName,
                                        success = false,
                                        error = error.message ?: error::class.simpleName,
                                        outputAlreadyExists = error is OutputExistsException,
                                    )
                                } finally {
                                    publishNs.addAndGet(System.nanoTime() - publishStart)
                                }
                                onProgress(completedCount.incrementAndGet(), sources.size, fileName)
                                result
                            }
                            publishDeferred.invokeOnCompletion { cause ->
                                if (cause != null) pending.discard()
                            }
                            publishDeferred
                        }
                    }
                }
                conversionTasks.awaitAll().awaitAll()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                files.endBatch()
            }
        }

        val wallMs = System.currentTimeMillis() - startTime
        log.d(
            "Batch finished: files=${sources.size}, workers=$workerCount, buf=${bufferSize / 1024}KB, " +
                "wall=${wallMs}ms, sum(name=${fileNameNs.get() / 1_000_000}ms, " +
                "convert=${convertNs.get() / 1_000_000}ms, publish=${publishNs.get() / 1_000_000}ms)"
        )

        return ConversionResult.from(
            results = results,
            durationMillis = wallMs,
            sourceNames = resolvedNames.toList(),
        )
    }

    // Keep thread-local initialization and decryption in one synchronous worker call.
    private fun convertFile(
        source: String,
        fileName: String,
        rawWriteMode: Boolean,
        mitigateConflicts: Boolean,
        bufferSize: Int,
        buffers: WorkerBuffers,
        kggKeys: KggBatchKeys?,
    ): PendingOutput? {
        val opened = files.openInputWithSize(source)
        if (opened.knownSize in 1L..buffers.smallInputBuffer.size.toLong()) {
            val expected = opened.knownSize.toInt()
            var total = 0
            opened.use { input ->
                val stream = input.stream
                while (total < expected) {
                    val read = stream.read(buffers.smallInputBuffer, total, expected - total)
                    if (read < 0) break
                    if (read == 0) throw BinaryInputException("input returned zero bytes")
                    total += read
                }
            }
            return PrefetchInputStream.fromBuffer(buffers.smallInputBuffer, total).use { input ->
                convertOpenedStream(input, fileName, rawWriteMode, mitigateConflicts, bufferSize, buffers, kggKeys)
            }
        }

        return PrefetchInputStream(opened.stream, buffers.inputPrefetch).use { input ->
            convertOpenedStream(input, fileName, rawWriteMode, mitigateConflicts, bufferSize, buffers, kggKeys)
        }
    }

    private fun convertOpenedStream(
        input: PrefetchInputStream,
        fileName: String,
        rawWriteMode: Boolean,
        mitigateConflicts: Boolean,
        bufferSize: Int,
        buffers: WorkerBuffers,
        kggKeys: KggBatchKeys?,
    ): PendingOutput? {
        val size = input.peek(buffers.headerPeek)
        return when (detectEncryptedFormat(buffers.headerPeek.copyOf(size))) {
            EncryptedFormat.NCM -> convertNcm(input, rawWriteMode, mitigateConflicts, bufferSize, buffers)
            EncryptedFormat.KGM -> convertKgm(input, fileName, mitigateConflicts, bufferSize, buffers)
            EncryptedFormat.KGG -> {
                require(kggKeys != null) { "请先在设置中选择 KGG 数据库" }
                convertKgg(input, fileName, mitigateConflicts, buffers, kggKeys)
            }
            EncryptedFormat.UNSUPPORTED ->
                throw IllegalArgumentException("不支持的加密文件格式: $fileName")
        }
    }

    private fun writeCoalesced(
        fileName: String,
        extension: String,
        mitigateConflicts: Boolean,
        buffers: WorkerBuffers,
        block: (OutputStream) -> Unit,
    ): PendingOutput? =
        files.writeOutput(fileName, extension, mitigateConflicts) { rawOutput ->
            val buffered = PassThroughBufferedOutputStream(rawOutput, buffers.outputScratch)
            block(buffered)
            buffered.flush()
        }

    private fun convertNcm(
        input: InputStream,
        rawWriteMode: Boolean,
        mitigateConflicts: Boolean,
        bufferSize: Int,
        buffers: WorkerBuffers,
    ): PendingOutput? {
        val binaryInput = BinaryInput(input::read)
        val info = NCMConverter.readHeader(binaryInput, includeCover = !rawWriteMode)
        return writeCoalesced("${info.musicArtists} - ${info.musicName}", info.format, mitigateConflicts, buffers) { output ->
            NCMConverter.writeAudio(
                input = binaryInput,
                output = BinaryOutput(output::write),
                info = info,
                rawWriteMode = rawWriteMode,
                bufferSize = bufferSize,
                buffer = buffers.audioBuffer,
            )
        }
    }

    private fun convertKgm(
        input: InputStream,
        fileName: String,
        mitigateConflicts: Boolean,
        bufferSize: Int,
        buffers: WorkerBuffers,
    ): PendingOutput? {
        val binaryInput = BinaryInput(input::read)
        val header = ByteArray(KGMConverter.HEADER_LENGTH)
        binaryInput.readFully(header)
        val ownKeyBytes = KGMConverter.getOwnKeyBytes(header)
        val firstChunk = buffers.audioBuffer
        val firstSize = binaryInput.readChunk(firstChunk)
        require(firstSize > 0) { "KGM audio payload is empty" }
        val format = KGMConverter.detectFormat(firstChunk[0], ownKeyBytes)
        require(format.isNotEmpty()) { "无法识别 KGM 音频格式" }

        return writeCoalesced(FileNameUtils.removeLastExtension(fileName), format, mitigateConflicts, buffers) { output ->
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
        input: PrefetchInputStream,
        fileName: String,
        mitigateConflicts: Boolean,
        buffers: WorkerBuffers,
        keys: KggBatchKeys,
    ): PendingOutput? {
        val binaryInput = BinaryInput(input::read)
        val headerBytes = ByteArray(KGMConverter.HEADER_LENGTH)
        binaryInput.readFully(headerBytes)
        val header = parseKgmHeader(headerBytes)
        require(header.cryptoVersion == 5u) { "不是 KGG 文件" }
        val cipher = QmcCipher.createCipher(keys.getKey(header.audioHash))
        input.skipFully(header.audioOffset.toLong() - headerBytes.size)

        val audioHeader = ByteArray(4)
        if (input.peek(audioHeader) != audioHeader.size) {
            throw BinaryInputException("unexpected end of input while reading ${audioHeader.size} bytes")
        }
        cipher.decrypt(audioHeader, 0L)
        val format = when {
            audioHeader.startsWith("ID3".encodeToByteArray()) -> "mp3"
            audioHeader.startsWith("fLaC".encodeToByteArray()) -> "flac"
            audioHeader.startsWith("Ogg".encodeToByteArray()) -> "ogg"
            (audioHeader[0].toInt() and 0xFF) == 0xFF &&
                (audioHeader[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> throw IllegalArgumentException("无法识别 KGG 音频格式")
        }

        return writeCoalesced(FileNameUtils.removeLastExtension(fileName), format, mitigateConflicts, buffers) { output ->
            val usePooled = buffers.audioBuffer.size >= QmcCipher.RC4_SEGMENT_SIZE
            val buffer = if (usePooled) buffers.audioBuffer else ByteArray(QmcCipher.STREAM_BUFFER_SIZE)
            val chunkLimit = if (usePooled) {
                (buffer.size / QmcCipher.RC4_SEGMENT_SIZE) * QmcCipher.RC4_SEGMENT_SIZE
            } else {
                buffer.size
            }
            var offset = 0L
            while (true) {
                val count = binaryInput.readAtMost(buffer, 0, chunkLimit)
                if (count == 0) break
                cipher.decrypt(buffer, offset, count)
                output.write(buffer, 0, count)
                offset += count
            }
        }
    }
}
