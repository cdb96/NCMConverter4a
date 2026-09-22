package com.cdb96.ncmconverter4a.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cdb96.ncmconverter4a.converter.EncryptedFormat
import com.cdb96.ncmconverter4a.converter.kgg.KggDecoder
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.converter.detectEncryptedFormat
import com.cdb96.ncmconverter4a.io.readAtMost
import com.cdb96.ncmconverter4a.io.BinaryInput
import com.cdb96.ncmconverter4a.io.BinaryOutput
import com.cdb96.ncmconverter4a.io.readChunk
import com.cdb96.ncmconverter4a.io.readFully
import com.cdb96.ncmconverter4a.util.FileNameUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FileConversionService(private val context: Context) {
    companion object {
        private const val TAG = "FileConversionService"
        private val MEDIA_RELATIVE_PATH = "${Environment.DIRECTORY_MUSIC}/NCMConverter4A/"
    }

    // A sequence is reserved while holding the per-basename CHM compute lock.
    private val seqTable = ConcurrentHashMap<String, MutableSet<Int>>()

    suspend fun processFiles(
        uris: List<Uri>,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean = false,
        fileCoroutineDispatcher: CoroutineDispatcher,
        kggDatabase: Uri? = null,
        kggRootMode: Boolean = false,
        onProgress: suspend (processed: Int, total: Int, fileName: String) -> Unit
    ): ConversionResult {
        val startTime = System.currentTimeMillis()
        val completedCount = AtomicInteger(0)
        val totalFiles = uris.size

        if (duplicateConflictMitigation) {
            withContext(Dispatchers.IO) { scanExistingFiles() }
        }
        val fileNameMap = uris.associateWith { uri ->
            withContext(Dispatchers.IO) { uri.getFileName(context) ?: "未知文件" }
        }

        val results = supervisorScope {
            uris.map { uri ->
                async(fileCoroutineDispatcher) {
                    val fileName = fileNameMap[uri] ?: "未知文件"
                    val result = try {
                        val success = routeEncryptedFile(
                            uri,
                            rawWriteMode,
                            duplicateConflictMitigation,
                            fileName,
                            kggDatabase,
                            kggRootMode,
                        )
                        FileConversionResult(
                            fileName = fileName,
                            success = success,
                            error = if (success) null else "转换失败"
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG, "处理文件时出错: ${error.message}", error)
                        FileConversionResult(
                            fileName = fileName,
                            success = false,
                            error = error.message ?: error::class.simpleName
                        )
                    }

                    val completed = completedCount.incrementAndGet()
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(completed, totalFiles, fileName)
                    }
                    result
                }
            }.awaitAll()
        }

        return ConversionResult.from(
            results = results,
            durationMillis = System.currentTimeMillis() - startTime,
            sourceNames = fileNameMap.values,
        )
    }

    private suspend fun routeEncryptedFile(
        uri: Uri,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean,
        fileName: String,
        kggDatabase: Uri?,
        kggRootMode: Boolean,
    ): Boolean = withFileInputStream(uri) { input ->
        input.mark(24)
        val header = ByteArray(24)
        val size = BinaryInput(input::read).readAtMost(header)
        input.reset()
        val format = detectEncryptedFormat(header.copyOf(size))
        Log.i(TAG, "使用${format}解密器")
        when (format) {
            EncryptedFormat.KGG -> {
                check(kggRootMode || kggDatabase != null) { "请先在设置中选择 KGG 数据库" }
                KggDecoder(context).decryptToOutput(input, kggDatabase, kggRootMode) { format, write ->
                    withFileOutputStream(format, FileNameUtils.removeLastExtension(fileName), duplicateConflictMitigation, write)
                }
            }
            EncryptedFormat.KGM -> processKGMFile(
                input,
                fileName,
                duplicateConflictMitigation
            )
            EncryptedFormat.NCM -> processNCMFile(
                input,
                rawWriteMode,
                duplicateConflictMitigation
            )
            EncryptedFormat.UNSUPPORTED ->
                throw IllegalArgumentException("不支持的加密文件格式: $fileName")
        }
    }

    private suspend fun processNCMFile(
        inputStream: InputStream,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean
    ): Boolean {
        return try {
            val binaryInput = BinaryInput(inputStream::read)
            val info = NCMConverter.readHeader(binaryInput, includeCover = !rawWriteMode)
            val fileName = "${info.musicArtists} - ${info.musicName}"

            withFileOutputStream(info.format, fileName, duplicateConflictMitigation) { output ->
                NCMConverter.writeAudio(
                    input = binaryInput,
                    output = BinaryOutput(output::write),
                    info = info,
                    rawWriteMode = rawWriteMode
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "NCM文件处理失败: ${error.message}", error)
            false
        }
    }

    private suspend fun processKGMFile(
        inputStream: InputStream,
        fileName: String,
        duplicateConflictMitigation: Boolean
    ): Boolean {
        return try {
            val header = ByteArray(KGMConverter.HEADER_LENGTH)
            BinaryInput(inputStream::read).readFully(header)
            val ownKeyBytes = KGMConverter.getOwnKeyBytes(header)

            val firstChunk = ByteArray(NCMConverter.AUDIO_BUFFER_SIZE)
            val firstSize = BinaryInput(inputStream::read).readChunk(firstChunk)
            require(firstSize > 0) { "KGM audio payload is empty" }
            val musicFormat = KGMConverter.detectFormat(firstChunk[0], ownKeyBytes)
            require(musicFormat.isNotEmpty()) { "无法识别 KGM 音频格式" }

            // fileName is the real source file name (e.g. song.kgm): strip its
            // extension before it becomes the output basename.
            val outputBaseName = FileNameUtils.removeLastExtension(fileName)
            withFileOutputStream(
                musicFormat,
                outputBaseName,
                duplicateConflictMitigation
            ) { output ->
                KGMConverter.decrypt(
                    ownKeyBytes = ownKeyBytes,
                    firstChunk = firstChunk,
                    firstSize = firstSize,
                    bufferSize = NCMConverter.AUDIO_BUFFER_SIZE,
                    read = { buffer -> BinaryInput(inputStream::read).readChunk(buffer) },
                    write = { buffer, bytesToWrite -> output.write(buffer, 0, bytesToWrite) }
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "KGM文件处理失败: ${error.message}", error)
            false
        }
    }

    private suspend fun withFileInputStream(
        uri: Uri,
        block: suspend (BufferedInputStream) -> Boolean
    ): Boolean {
        val rawInput = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开输入文件: $uri")
        return rawInput.use { raw ->
            BufferedInputStream(raw).use { input ->
                block(input)
            }
        }
    }

    private suspend fun withFileOutputStream(
        format: String,
        fileName: String,
        duplicateConflictMitigation: Boolean,
        block: (OutputStream) -> Unit
    ): Boolean {
        val extension = FileNameUtils.normalizeExtension(format)
        val displayName = if (duplicateConflictMitigation) {
            assignSeq(fileName, extension)
        } else {
            FileNameUtils.outputFileName(fileName, extension)
        }
        val mimeType = when (extension) {
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, MEDIA_RELATIVE_PATH)
        }
        val outputUri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return false

        return try {
            val output = context.contentResolver.openOutputStream(outputUri, "w")
                ?: run {
                    context.contentResolver.delete(outputUri, null, null)
                    return false
                }
            output.use(block)
            true
        } catch (error: Throwable) {
            context.contentResolver.delete(outputUri, null, null)
            throw error
        }
    }

    private fun scanExistingFiles() {
        seqTable.clear()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(MEDIA_RELATIVE_PATH),
            null
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val (base, sequence) = extractSeq(cursor.getString(column))
                seqTable.getOrPut(base) { mutableSetOf() }.add(sequence)
            }
        }
    }

    private fun extractSeq(fullName: String): Pair<String, Int> {
        val dot = fullName.lastIndexOf('.')
        val nameEnd = if (dot > 0) dot else fullName.length
        val extension = if (dot > 0) fullName.substring(dot) else ""
        if (nameEnd <= 0 || fullName.getOrNull(nameEnd - 1) != ')') {
            return fullName to 0
        }

        val close = nameEnd - 1
        val open = fullName.lastIndexOf('(', close - 1)
        if (open < 0 || open == close - 1) return fullName to 0
        val sequence = fullName.substring(open + 1, close).toIntOrNull()
            ?: return fullName to 0
        val baseEnd = if (open > 0 && fullName[open - 1] == ' ') open - 1 else open
        return fullName.substring(0, baseEnd) + extension to sequence
    }

    private fun assignSeq(fileName: String, extension: String): String {
        val baseName = FileNameUtils.outputFileName(fileName, extension)
        var assigned = -1
        seqTable.compute(baseName) { _, existing ->
            val sequences = existing ?: mutableSetOf()
            var candidate = 0
            while (candidate in sequences) candidate++
            sequences.add(candidate)
            assigned = candidate
            sequences
        }
        return FileNameUtils.outputFileName(fileName, extension, assigned)
    }

    private fun Uri.getFileName(context: Context): String? =
        DocumentFile.fromSingleUri(context, this)?.name
}
