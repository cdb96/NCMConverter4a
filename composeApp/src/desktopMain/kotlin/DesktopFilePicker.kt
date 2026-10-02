package com.cdb96.ncmconverter4a

import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File

/**
 * Desktop file picker using Swing JFileChooser.
 * Swing components must be created and shown on EDT.
 */
object DesktopFilePicker {
    fun pickDirectory(currentDirectory: String): String? {
        check(SwingUtilities.isEventDispatchThread()) {
            "DesktopFilePicker.pickDirectory must be called on the Swing EDT"
        }
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            isAcceptAllFileFilterUsed = false
            dialogTitle = "选择输出文件夹"
            approveButtonText = "选择文件夹"
            File(currentDirectory).takeIf { it.isDirectory }?.let {
                this.currentDirectory = it
            }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.absolutePath
        } else null
    }

    fun pickFiles(multiSelect: Boolean = true, database: Boolean = false): List<String> {
        check(SwingUtilities.isEventDispatchThread()) {
            "DesktopFilePicker.pickFiles must be called on the Swing EDT"
        }
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = multiSelect
            dialogTitle = if (database) "选择酷狗 DB / MMKV 数据库" else "选择 NCM / KGM / KGG 文件"
            if (!database) fileFilter = FileNameExtensionFilter(
                "加密音频文件 (*.ncm, *.kgm, *.kgg, *.flac, *.mp3, *.m4a)",
                "ncm", "kgm", "kgg", "flac", "mp3", "m4a"
            )
        }
        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) {
            if (multiSelect) {
                chooser.selectedFiles.map { it.absolutePath }
            } else {
                listOf(chooser.selectedFile.absolutePath)
            }
        } else {
            emptyList()
        }
    }
}
