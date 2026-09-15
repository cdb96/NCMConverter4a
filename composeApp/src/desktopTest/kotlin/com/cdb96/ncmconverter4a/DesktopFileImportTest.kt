package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.ui.screens.KggUiState
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFileImportTest {
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

    @Test
    fun importsAudioAndDatabaseWithoutDiscardingExistingSelection() {
        val audio = File("song.KGG")
        val database = File("mggkey_multi_process")
        val state = KggUiState(dbFileName = "previous.db").importKggFiles(listOf(audio))
        assertEquals("previous.db", state.dbFileName)
        assertEquals(audio.absolutePath, state.audioFileName)
        val updated = state.importKggFiles(listOf(database))
        assertEquals(database.absolutePath, updated.dbFileName)
        assertEquals(audio.absolutePath, updated.audioFileName)
        assertFalse(updated.resultIsError)
    }

    @Test
    fun rejectsAmbiguousDropsAndIgnoresDropsWhileBusy() {
        val state = KggUiState(audioFileName = "existing.kgg", dbFileName = "existing.db")
        for (files in listOf(
            listOf(File("a.kgg"), File("b.kgg")),
            listOf(File("a.db"), File("b.mmkv")),
            listOf(File("a.kgg"), File("other.ncm")),
        )) {
            val rejected = state.importKggFiles(files)
            assertTrue(rejected.resultIsError)
            assertEquals(state.audioFileName, rejected.audioFileName)
            assertEquals(state.dbFileName, rejected.dbFileName)
        }
        val busy = state.copy(isProcessing = true)
        assertEquals(busy, busy.importKggFiles(listOf(File("new.kgg"))))
    }
}
