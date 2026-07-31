package com.cdb96.ncmconverter4a

import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop file picker using Swing JFileChooser.
 * Must be called from a non-EDT (non-Compose) thread to avoid blocking the coroutine dispatcher.
 */
object DesktopFilePicker {
    fun pickFiles(multiSelect: Boolean = true): List<String> {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = multiSelect
            dialogTitle = "选择 NCM / KGM / KGG 文件"
            fileFilter = FileNameExtensionFilter(
                "加密音频文件 (*.ncm, *.kgm, *.kgg, *.flac, *.mp3)",
                "ncm", "kgm", "kgg", "flac", "mp3"
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
