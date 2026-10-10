package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.service.ConversionFiles
import com.cdb96.ncmconverter4a.service.FileConversionService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private const val DESKTOP_BUFFER_BUDGET = 1024 * 1024
private const val MIN_DESKTOP_BUFFER_SIZE = 128 * 1024

internal fun desktopBufferSize(inputCount: Int, threadCount: Int): Int {
    val workers = minOf(inputCount.coerceAtLeast(1), threadCount.coerceAtLeast(1))
    val size = maxOf(MIN_DESKTOP_BUFFER_SIZE, DESKTOP_BUFFER_BUDGET / workers)
    return size - size % 256
}

fun createFileConversionService(outputDirectory: File = defaultDesktopOutputDirectory()): FileConversionService =
    FileConversionService(DesktopConversionFiles(outputDirectory))

private class DesktopConversionFiles(outputDirectory: File) : ConversionFiles {
    private val outputAllocator = DesktopOutputAllocator(outputDirectory)

    override fun fileName(source: String): String = File(source).name

    override fun openInput(source: String): InputStream {
        val file = File(source)
        require(file.isFile) { "输入文件不存在: $source" }
        return file.inputStream()
    }

    override fun writeOutput(
        fileName: String,
        extension: String,
        mitigateConflicts: Boolean,
        write: (OutputStream) -> Unit,
    ) {
        outputAllocator.withUniqueOutput(fileName, extension, mitigateConflicts, write)
    }

    override fun loadKggDatabase(database: String?, rootMode: Boolean): KggKeyDatabase =
        loadDesktopKggDatabase(requireNotNull(database) { "请先在设置中选择 KGG 数据库" })

    override fun bufferSize(inputCount: Int, threadCount: Int): Int = desktopBufferSize(inputCount, threadCount)
}
