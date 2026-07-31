package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.EncryptedFormat
import com.cdb96.ncmconverter4a.converter.KGMConverter
import com.cdb96.ncmconverter4a.converter.NCMConverter
import com.cdb96.ncmconverter4a.jni.RC4Decrypt
import com.cdb96.ncmconverter4a.platform.Logger
import com.cdb96.ncmconverter4a.service.ConversionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DesktopConversionFacade {
    private val log = Logger("DesktopConversion")
    private val outputBase = File(System.getProperty("user.home"), "Music/NCMConverter4A")

    suspend fun processFiles(
        filePaths: List<String>,
        threadCount: Int,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean,
        onProgress: (processed: Int, total: Int, fileName: String) -> Unit
    ): ConversionResult {
        outputBase.mkdirs()

        val startTime = System.currentTimeMillis()
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val completedCount = AtomicInteger(0)
        val successfulFiles = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()
        val totalFiles = filePaths.size

        val dispatcher = Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()

        val fileNameMap = filePaths.associateWith { File(it).name }

        try {
            supervisorScope {
                val jobs = filePaths.map { path ->
                    launch(dispatcher) {
                        val fileName = fileNameMap[path] ?: "未知文件"
                        try {
                            val success = convertFile(path, rawWriteMode, duplicateConflictMitigation)
                            synchronized(successfulFiles) {
                                if (success) {
                                    successfulFiles.add(fileName)
                                    successCount.incrementAndGet()
                                } else {
                                    failedFiles.add(fileName)
                                    failureCount.incrementAndGet()
                                }
                            }
                        } catch (e: Exception) {
                            log.e("处理文件时出错: ${e.message}", e)
                            synchronized(failedFiles) { failedFiles.add(fileName) }
                            failureCount.incrementAndGet()
                        }
                        val completed = completedCount.incrementAndGet()
                        onProgress(completed, totalFiles, fileName)
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
                successfulFileNames = successfulFiles.toList(),
                failedFileNames = failedFiles.toList(),
            )
        } finally {
            dispatcher.close()
        }
    }

    private suspend fun convertFile(
        path: String,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext false

        FileInputStream(file).use { fis ->
            // Read first 2 bytes to detect format
            val header = ByteArray(2)
            fis.read(header)
            val truncatedKGMMagicHeader = byteArrayOf(0x7c, 0xd5.toByte())
            val isKGM = header.contentEquals(truncatedKGMMagicHeader)

            if (isKGM) {
                convertKGM(fis, file.nameWithoutExtension, duplicateConflictMitigation)
            } else {
                convertNCM(fis, rawWriteMode, duplicateConflictMitigation)
            }
        }
    }

    private fun convertNCM(
        inputStream: FileInputStream,
        rawWriteMode: Boolean,
        duplicateConflictMitigation: Boolean
    ): Boolean {
        return try {
            val fileData = ByteArrayOutputStream().use { out ->
                val buf = ByteArray(8192)
                var n: Int
                while (inputStream.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                out.toByteArray()
            }
            val (info, dataOffset) = NCMConverter.convert(fileData)
            val artists = info.musicArtists.replace(Regex("[/\\\\]"), ",")
            val fileName = "$artists - ${info.musicName}"
            val format = info.format

            val outputFile = resolveOutput(fileName, format, duplicateConflictMitigation)
            FileOutputStream(outputFile).use { fos ->
                RC4Decrypt.ksa(info.RC4key)
                var remaining = fileData.copyOfRange(dataOffset, fileData.size)
                if (!rawWriteMode) {
                    val result = NCMConverter.modifyHeader(remaining, info, info.coverData, 512 * 1024)
                    fos.write(result.headerBytes)
                    remaining = result.remainingData
                }
                val buf = ByteArray(256 * 1024)
                var rpos = 0
                var bytesRead: Int
                while (rpos < remaining.size) {
                    bytesRead = minOf(buf.size, remaining.size - rpos)
                    remaining.copyInto(buf, 0, rpos, rpos + bytesRead)
                    RC4Decrypt.prgaDecrypt(buf, bytesRead)
                    fos.write(buf, 0, bytesRead)
                    rpos += bytesRead
                }
            }
            true
        } catch (e: Exception) {
            log.e("NCM文件处理失败: ${e.message}", e)
            false
        }
    }

    private fun convertKGM(
        inputStream: FileInputStream,
        fileName: String,
        duplicateConflictMitigation: Boolean
    ): Boolean {
        return try {
            val header = ByteArray(1022)
            inputStream.read(header, 0, 1022)
            val ownKeyBytes = KGMConverter.getOwnKeyBytes(header)

            val first = ByteArray(256 * 1024)
            var bytesRead = inputStream.read(first)
            val musicFormat = KGMConverter.detectFormat(first[0], ownKeyBytes)
            val cleanName = fileName.replace(Regex("(.kgm)|(.flac)", RegexOption.IGNORE_CASE), "")

            val outputFile = resolveOutput(cleanName, musicFormat, duplicateConflictMitigation)
            FileOutputStream(outputFile).use { fos ->
                KGMConverter.decrypt(
                    ownKeyBytes, first, bytesRead, 256 * 1024,
                    read = { inputStream.read(it) },
                    write = { buf, n -> fos.write(buf, 0, n) }
                )
            }
            true
        } catch (e: Exception) {
            log.e("KGM文件处理失败: ${e.message}", e)
            false
        }
    }

    private fun resolveOutput(name: String, format: String, mitigate: Boolean): File {
        val ext = format.lowercase()
        val candidate = if (mitigate) {
            var count = 0
            var f: File
            do {
                val suffix = if (count > 0) " ($count)" else ""
                f = File(outputBase, "$name$suffix.$ext")
                count++
            } while (f.exists())
            f
        } else {
            File(outputBase, "$name.$ext")
        }
        return candidate
    }
}
