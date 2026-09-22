package com.cdb96.ncmconverter4a

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KggBatchRoutingTest {
    @Test
    fun missingDatabaseIsReportedPerFileWithoutAbortingBatch() = runBlocking {
        val directory = Files.createTempDirectory("kgg-batch").toFile()
        val kgg = File(directory, "renamed.mp3")
        val other = File(directory, "invalid.ncm")
        try {
            kgg.writeBytes(byteArrayOf(
                0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(),
                0x86.toByte(), 0x02, 0x7F, 0x4B,
                0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(),
                0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14,
            ).copyOf(1024).apply { this[20] = 5 })
            other.writeText("invalid")
            val progress = java.util.concurrent.atomic.AtomicInteger()
            val result = DesktopConversionFacade().processFiles(
                listOf(kgg.absolutePath, other.absolutePath), 2, false, false,
            ) { _, _, _ -> progress.incrementAndGet() }
            assertEquals(2, result.failureCount)
            assertEquals(2, progress.get())
            assertTrue(result.failedFiles.first { it.fileName == kgg.name }.error.orEmpty().contains("设置"))
        } finally {
            kgg.delete()
            other.delete()
            directory.delete()
        }
    }
}
