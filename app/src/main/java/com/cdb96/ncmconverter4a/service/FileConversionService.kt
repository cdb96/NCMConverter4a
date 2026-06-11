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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern


class FileConversionService(private val context: Context) {
    companion object {
        private const val TAG = "FileConversionService"
        private val EXTENSION_REGEX = Pattern.compile("(.kgm)|(.flac)", Pattern.CASE_INSENSITIVE)
    }

    // 第一步：扫描出来的表 — 基本名 → 已占用序号集合
    private val seqTable = ConcurrentHashMap<String, MutableSet<Int>>()

    suspend fun processFiles(
        uris: List<Uri>,
        threadCount: Int,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean = false,
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
            if (duplicateConflictMitigation) {
                withContext(Dispatchers.IO) { scanExistingFiles() }
            }
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
                            val success = routeEncryptedFile(uri, rawWriteMode, duplicateConflictMitigation, fileName)
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
        duplicateConflictMitigation: Boolean,
        fileName: String?
    ): Boolean = withContext(Dispatchers.IO) {
        withFileInputStream(uri) { fis ->
            val format = detectEncryptedFormat(fis)
            Log.i(TAG,"使用${format}解密器")
            when (format) {
                EncryptedFormat.KGM -> processKGMFile(uri, fis, fileName, duplicateConflictMitigation)
                EncryptedFormat.NCM -> processNCMFile(rawWriteMode, fis, duplicateConflictMitigation)
            }
        }
    }

    private suspend fun processNCMFile(
        rawWriteMode: Boolean,
        inputStream: FileInputStream,
        duplicateConflictMitigation: Boolean = false
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            val preFetchChunkSize = 512 * 1024
            val ncmFileInfo = NCMConverter.convert(inputStream)
            val fileName = "${ncmFileInfo.musicArtists().replace(Regex("[/\\\\]"), ",")} - ${ncmFileInfo.musicName()}"
            val format = ncmFileInfo.format()

            withFileOutputStream(format, fileName, duplicateConflictMitigation) { fileOutputStream ->
                RC4Decrypt.ksa(ncmFileInfo.RC4key)
                if (!rawWriteMode) {
                    NCMConverter.modifyHeader(
                        inputStream, fileOutputStream, ncmFileInfo,
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
        fileName: String?,
        duplicateConflictMitigation: Boolean = false
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            // 获取音乐格式
            val ownKeyBytes = KGMConverter.getOwnKeyBytes(inputStream)
            val musicFormat = KGMConverter.detectFormat(inputStream, ownKeyBytes)

            // 获取文件名并处理
            var processedFileName = fileName ?: uri.getFileName(context) ?: "null"
            processedFileName = EXTENSION_REGEX.matcher(processedFileName).replaceAll("")

            // 创建输出流并转换
            withFileOutputStream(musicFormat, processedFileName, duplicateConflictMitigation) { fileOutputStream ->
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
        duplicateConflictMitigation: Boolean = false,
        block: (FileOutputStream) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val mimeType = when (format.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            else -> "audio/mpeg"
        }

        val extension = format.lowercase()
        val musicName = if (duplicateConflictMitigation) {
            assignSeq(fileName, extension)
        } else {
            "$fileName.$extension"
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, musicName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NCMConverter4A/")
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

    //第一步：扫描目录，提取序号，建表
    private fun scanExistingFiles() {
        seqTable.clear()
        val relativePath = "${Environment.DIRECTORY_MUSIC}/NCMConverter4A/"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val (base, seq) = extractSeq(cursor.getString(col))
                seqTable.getOrPut(base) { mutableSetOf() }.add(seq)
            }
        }
    }

    private fun extractSeq(fullName: String): Pair<String, Int> {
        var dotIdx = fullName.length - 1
        while (dotIdx >= 0 && fullName[dotIdx] != '.') {
            dotIdx--
        }
        val hasExt = dotIdx > 0
        val nameEnd = if (hasExt) dotIdx else fullName.length
        val ext = if (hasExt) fullName.substring(dotIdx) else ""
        if (nameEnd <= 0) return Pair(fullName, 0)

        val lastCharIdx = nameEnd - 1
        if (fullName[lastCharIdx] == ')') {
            var p = lastCharIdx - 1
            // 指针向左扫描，直到遇到非数字
            while (p >= 0 && fullName[p].isDigit()) {
                p--
            }
            if (p >= 0 && fullName[p] == '(' && p < lastCharIdx - 1) {
                val seq = fullName.substring(p + 1, lastCharIdx).toIntOrNull() ?: 0
                //p-1是因为系统给文件加序号的时候序号前都会带个空格
                val base = fullName.substring(0, p - 1)
                return Pair(base + ext, seq)
            }
        }
        return Pair(fullName, 0)
    }

    //查表 → 从 0 开始分配序号
    private fun assignSeq(fileName: String, extension: String): String {
        val (baseMusicName, _) = extractSeq("$fileName.$extension")
        var assigned = -1
        seqTable.compute(baseMusicName) { _, set ->
            val s = set ?: mutableSetOf()
            var n = 0
            while (n in s) n++
            s.add(n)
            assigned = n
            s
        }
        val dot = baseMusicName.lastIndexOf('.')
        val baseWithoutExt = baseMusicName.substring(0, dot)
        return if (assigned > 0) {
            "$baseWithoutExt ($assigned).$extension"
        } else {
            baseMusicName
        }
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