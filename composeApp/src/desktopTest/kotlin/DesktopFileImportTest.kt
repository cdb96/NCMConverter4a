package com.cdb96.ncmconverter4a

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFileImportTest {
    @Test
    fun conversionBufferUsesOneMiBBudgetAcrossActiveWorkers() {
        assertEquals(1024 * 1024, desktopBufferSize(inputCount = 1, threadCount = 8))
        assertEquals(512 * 1024, desktopBufferSize(inputCount = 2, threadCount = 8))
        assertEquals(256 * 1024, desktopBufferSize(inputCount = 8, threadCount = 4))
        assertEquals(128 * 1024, desktopBufferSize(inputCount = 16, threadCount = 16))
    }

    @Test
    fun externalFileListFiltersDirectoriesAndDuplicates() {
        val directory = Files.createTempDirectory("ncm-drop-test").toFile()
        val file = File(directory, "song.ncm").apply { writeText("test") }
        try {
            val transfer = object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
                override fun getTransferData(flavor: DataFlavor): Any = listOf(file, file, directory, "invalid")
            }
            assertEquals(listOf(file), droppedFiles(transfer))
            assertTrue(droppedFiles(StringSelection("not a file drop")).isEmpty())
        } finally {
            file.delete()
            directory.delete()
        }
    }

}
