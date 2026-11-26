package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.service.ConversionFiles
import com.cdb96.ncmconverter4a.service.FileConversionService
import com.cdb96.ncmconverter4a.service.OpenedInput
import com.cdb96.ncmconverter4a.service.PendingOutput
import com.cdb96.ncmconverter4a.service.boundedWorkerBufferSize
import java.io.File
import java.io.OutputStream

private const val DESKTOP_BUFFER_BUDGET = 1024 * 1024
private const val MIN_DESKTOP_BUFFER_SIZE = 128 * 1024

internal fun desktopBufferSize(inputCount: Int, threadCount: Int): Int =
    boundedWorkerBufferSize(
        inputCount = inputCount,
        threadCount = threadCount,
        budgetBytes = DESKTOP_BUFFER_BUDGET,
        minBytes = MIN_DESKTOP_BUFFER_SIZE,
    )

fun createFileConversionService(outputDirectory: File = defaultDesktopOutputDirectory()): FileConversionService =
    FileConversionService(DesktopConversionFiles(outputDirectory))

private class DesktopConversionFiles(outputDirectory: File) : ConversionFiles {
    private val outputAllocator = DesktopOutputAllocator(outputDirectory)

    override fun fileName(source: String): String = File(source).name

    override fun openInputWithSize(source: String): OpenedInput {
        val file = File(source)
        require(file.isFile) { "输入文件不存在: $source" }
        return OpenedInput(file.inputStream(), file.length())
    }

    override fun writeOutput(
        fileName: String,
        extension: String,
        mitigateConflicts: Boolean,
        write: (OutputStream) -> Unit,
    ): PendingOutput? {
        outputAllocator.withUniqueOutput(fileName, extension, mitigateConflicts, write)
        return null
    }

    override fun loadKggDatabase(database: String?, rootMode: Boolean): KggKeyDatabase =
        loadDesktopKggDatabase(requireNotNull(database) { "请先在设置中选择 KGG 数据库" })

    override fun bufferSize(inputCount: Int, threadCount: Int): Int = desktopBufferSize(inputCount, threadCount)
}
