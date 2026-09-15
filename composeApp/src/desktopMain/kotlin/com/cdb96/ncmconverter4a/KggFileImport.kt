package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.ui.screens.KggUiState
import java.io.File

internal fun KggUiState.importKggFiles(files: List<File>): KggUiState {
    if (isProcessing || files.isEmpty()) return this
    val audio = files.filter { it.extension.equals("kgg", ignoreCase = true) }
    val databases = files.filter { it.extension.lowercase() in setOf("db", "mmkv", "") }
    if (audio.size > 1 || databases.size > 1 || audio.size + databases.size != files.size) {
        return copy(resultIsError = true, decryptResult = "请一次拖入一个 KGG 音频和 / 或一个 DB、MMKV 数据库文件。")
    }
    return copy(
        audioFileName = audio.singleOrNull()?.absolutePath ?: audioFileName,
        dbFileName = databases.singleOrNull()?.absolutePath ?: dbFileName,
        audioDisplayName = audio.singleOrNull()?.name ?: audioDisplayName,
        dbDisplayName = databases.singleOrNull()?.name ?: dbDisplayName,
        decryptResult = null,
        resultIsError = false,
    )
}
