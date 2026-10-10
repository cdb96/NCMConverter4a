package com.cdb96.ncmconverter4a.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.converter.kgg.MMKVParser
import com.cdb96.ncmconverter4a.converter.kgg.root.RootDatabaseReader
import com.cdb96.ncmconverter4a.util.FileNameUtils
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

fun createFileConversionService(context: Context): FileConversionService =
    FileConversionService(AndroidConversionFiles(context))

private class AndroidConversionFiles(private val context: Context) : ConversionFiles {
    private val mediaRelativePath = "${Environment.DIRECTORY_MUSIC}/NCMConverter4A/"
    private val rootDatabaseReader by lazy { RootDatabaseReader(context.cacheDir) }

    // Reserve a sequence while holding the per-basename CHM compute lock.
    private val seqTable = ConcurrentHashMap<String, MutableSet<Int>>()

    override fun fileName(source: String): String =
        DocumentFile.fromSingleUri(context, Uri.parse(source))?.name ?: "未知文件"

    override fun openInput(source: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(source))
            ?: throw IllegalStateException("无法打开输入文件: $source")

    override fun loadKggDatabase(database: String?, rootMode: Boolean): KggKeyDatabase {
        val bytes = if (rootMode) {
            rootDatabaseReader.read()
        } else {
            val uri = Uri.parse(requireNotNull(database) { "请先在设置中选择 KGG 数据库" })
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开 mmkv 数据库文件: $uri")
            input.use { it.readBytes() }
        }
        val parser = MMKVParser(bytes)
        return KggKeyDatabase(parser::getBytes)
    }

    override fun prepareOutput(mitigateConflicts: Boolean) {
        if (mitigateConflicts) scanExistingFiles()
    }

    override fun writeOutput(
        fileName: String,
        extension: String,
        mitigateConflicts: Boolean,
        write: (OutputStream) -> Unit,
    ) {
        val normalizedExtension = FileNameUtils.normalizeExtension(extension)
        val displayName = if (mitigateConflicts) {
            assignSeq(fileName, normalizedExtension)
        } else {
            FileNameUtils.outputFileName(fileName, normalizedExtension)
        }
        val mimeType = when (normalizedExtension) {
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            else -> "audio/mpeg"
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, mediaRelativePath)
        }
        val outputUri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("无法创建输出文件: $displayName")

        try {
            val output = context.contentResolver.openOutputStream(outputUri, "w")
                ?: throw IllegalStateException("无法打开输出文件: $displayName")
            output.use(write)
        } catch (error: Throwable) {
            context.contentResolver.delete(outputUri, null, null)
            throw error
        }
    }

    private fun scanExistingFiles() {
        seqTable.clear()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(mediaRelativePath),
            null
        )?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val (base, sequence) = extractSeq(cursor.getString(column))
                seqTable.getOrPut(base) { mutableSetOf() }.add(sequence)
            }
        }
    }

    private fun extractSeq(fullName: String): Pair<String, Int> {
        val dot = fullName.lastIndexOf('.')
        val nameEnd = if (dot > 0) dot else fullName.length
        val extension = if (dot > 0) fullName.substring(dot) else ""
        if (nameEnd <= 0 || fullName.getOrNull(nameEnd - 1) != ')') {
            return fullName to 0
        }

        val close = nameEnd - 1
        val open = fullName.lastIndexOf('(', close - 1)
        if (open < 0 || open == close - 1) return fullName to 0
        val sequence = fullName.substring(open + 1, close).toIntOrNull()
            ?: return fullName to 0
        val baseEnd = if (open > 0 && fullName[open - 1] == ' ') open - 1 else open
        return fullName.substring(0, baseEnd) + extension to sequence
    }

    private fun assignSeq(fileName: String, extension: String): String {
        val baseName = FileNameUtils.outputFileName(fileName, extension)
        var assigned = -1
        seqTable.compute(baseName) { _, existing ->
            val sequences = existing ?: mutableSetOf()
            var candidate = 0
            while (candidate in sequences) candidate++
            sequences.add(candidate)
            assigned = candidate
            sequences
        }
        return FileNameUtils.outputFileName(fileName, extension, assigned)
    }
}
