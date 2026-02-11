package com.cdb96.ncmconverter4a.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import com.cdb96.ncmconverter4a.util.DirectBufferPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern


class FileConversionService(private val context: Context) {
    companion object {
        private const val TAG = "FileConversionService"
        private val EXTENSION_REGEX = Pattern.compile("(.kgm)|(.flac)", Pattern.CASE_INSENSITIVE)
    }

    suspend fun processFiles(
        uris: List<Uri>,
        threadCount: Int,
        rawWriteMode: Boolean,
        fileCoroutineDispatcher: CoroutineDispatcher,
        onProgress: (processed: Int, total: Int, fileName: String) -> Unit
    ): ConversionResult {
        DirectBufferPool.updateSlotBuffer(minOf(uris.size, threadCount))

        val startTime = System.currentTimeMillis()
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val totalFiles = uris.size
        val successfulFiles = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()

        try {
            // 获取所有文件名
            val fileNameMap = uris.associateWith { uri ->
                withContext(Dispatchers.IO) {
                    uri.getFileName(context) ?: "未知文件"
                }
            }
            // 并行处理所有文件
            supervisorScope {
                val jobs = uris.map { uri ->
                    launch (fileCoroutineDispatcher) {
                        val fileName = fileNameMap[uri] ?: "未知文件"
                        try {
                            val success = routeEncryptedFile(uri, rawWriteMode, fileName)
                            if (success) {
                                successfulFiles.add(fileName)
                                successCount.incrementAndGet()
                            } else {
                                failedFiles.add(fileName)
                                failureCount.incrementAndGet()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "处理文件时出错: ${e.message}", e)
                            failedFiles.add(fileName)
                            failureCount.incrementAndGet()
                        }
                        val completed = completedCount.incrementAndGet()
                        launch(Dispatchers.Main) {
                            onProgress(completed, totalFiles, fileName)
                        }
                    }
                }
                jobs.joinAll()
            }
            val allFileNames = fileNameMap.values.joinToString(", ")
            val duration = System.currentTimeMillis() - startTime
            return ConversionResult(
                successCount = successCount.get(),
                failureCount = failureCount.get(),
                durationMillis = duration,
                allFileNames = allFileNames,
                successfulFileNames = successfulFiles,
                failedFileNames = failedFiles,
            )
        } catch (e: Exception) {
            Log.e(TAG, "批量转换过程中发生错误: ${e.message}", e)
            throw e
        }
    }

    private suspend fun routeEncryptedFile(
        uri: Uri,
        rawWriteMode: Boolean,
        fileName: String?
    ): Boolean = withContext(Dispatchers.IO) {
        withFileInputStream(uri) { fis ->
            val format = detectEncryptedFormat(fis)
            Log.i(TAG,"使用${format}解密器")
            when (format) {
                EncryptedFormat.KGM -> processKGMFile(uri, fis, fileName)
                EncryptedFormat.NCM -> processNCMFile(rawWriteMode, fis)
            }
        }
    }

    private suspend fun processNCMFile(
        rawWriteMode: Boolean,
        inputStream: FileInputStream
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            val preFetchChunkSize = 512 * 1024
            val ncmFileInfo = NCMConverter.convert(inputStream)
            val fileName = getMusicInfoData(ncmFileInfo.musicInfoStringArrayValue, "musicName")
            val format = getMusicInfoData(ncmFileInfo.musicInfoStringArrayValue, "format")

            withFileOutputStream(format, fileName) { fileOutputStream ->
                RC4Decrypt.ksa(ncmFileInfo.RC4key)
                if (!rawWriteMode) {
                    NCMConverter.modifyHeader(
                        inputStream, fileOutputStream, ncmFileInfo.musicInfoStringArrayValue,
                        ncmFileInfo.coverData, preFetchChunkSize
                    )
                }
                NCMConverter.outputMusic(
                    fileOutputStream.channel,
                    inputStream.channel,
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "NCM文件处理失败: ${e.message}", e)
            false
        }
    }

    private suspend fun processKGMFile(
        uri: Uri,
        inputStream: FileInputStream,
        fileName: String?
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            // 获取音乐格式
            val ownKeyBytes = KGMConverter.getOwnKeyBytes(inputStream)
            val musicFormat = KGMConverter.detectFormat(inputStream, ownKeyBytes)

            if (musicFormat.isNullOrBlank()) false

            // 获取文件名并处理
            var processedFileName = fileName ?: uri.getFileName(context) ?: "null"
            processedFileName = EXTENSION_REGEX.matcher(processedFileName).replaceAll("")

            // 创建输出流并转换
            withFileOutputStream(musicFormat, processedFileName) { fileOutputStream ->
                KGMConverter.write(inputStream.channel, fileOutputStream.channel, ownKeyBytes)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "KGM文件处理失败: ${e.message}", e)
            false
        }
    }

    private suspend fun withFileInputStream(
        uri: Uri,
        block: suspend (FileInputStream) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            FileInputStream(pfd?.fileDescriptor).use { fis ->
                block(fis)
            }
        }
    }

    private suspend fun withFileOutputStream(
        format: String,
        fileName: String,
        block: (FileOutputStream) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val mimeType = when (format.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            else -> "audio/mpeg"
        }

        val musicName = "$fileName.${format.lowercase()}"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, musicName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A")
        }

        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fileOutputStream ->
                    block(fileOutputStream)
                    return@withContext true
                }
            }
        }
        return@withContext false
    }

    private fun getMusicInfoData(arrayList: ArrayList<String>, key: String): String {
        return arrayList.indexOf(key).takeIf { it != -1 }?.let { index ->
            arrayList.getOrNull(index + 1)
        } ?: "unknown"
    }

    private fun Uri.getFileName(context: Context): String? {
        return DocumentFile.fromSingleUri(context, this)?.name
    }

    private fun detectEncryptedFormat(inputStream: InputStream): EncryptedFormat {
        val truncatedKGMMagicHeader = byteArrayOf(0x7c, 0xd5.toByte())
        val fileHeader = ByteArray(2)
        inputStream.read(fileHeader, 0, 2)
        //这里应该检测NCM的头来判断是否为NCM格式的，不过我懒得搞了，反正就两个格式
        return if (fileHeader.contentEquals(truncatedKGMMagicHeader)) EncryptedFormat.KGM else EncryptedFormat.NCM
    }
}
enum class EncryptedFormat { KGM, NCM }
data class ConversionResult(
    val successCount: Int,
    val failureCount: Int,
    val durationMillis: Long,
    val allFileNames: String,
    val successfulFileNames: List<String>,
    val failedFileNames: List<String>
    )