package com.cdb96.ncmconverter4a.service

import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.converter.kgg.MMKVParser
import com.cdb96.ncmconverter4a.converter.kgg.root.RootDatabaseReader
import com.cdb96.ncmconverter4a.util.FileNameUtils
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val ANDROID_BUFFER_BUDGET = 4 * 1024 * 1024
private const val MIN_ANDROID_BUFFER_SIZE = 512 * 1024
private const val MAX_ANDROID_BUFFER_SIZE = 1024 * 1024
private const val MEDIASTORE_QUERY_BATCH_SIZE = 200
private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

internal fun androidBufferSize(inputCount: Int, threadCount: Int): Int =
    boundedWorkerBufferSize(
        inputCount = inputCount,
        threadCount = threadCount,
        budgetBytes = ANDROID_BUFFER_BUDGET,
        minBytes = MIN_ANDROID_BUFFER_SIZE,
        maxBytes = MAX_ANDROID_BUFFER_SIZE,
    )

fun createFileConversionService(context: Context): FileConversionService =
    FileConversionService(AndroidConversionFiles(context))

private class AndroidConversionFiles(private val context: Context) : ConversionFiles {
    private val mediaRelativePath = "${Environment.DIRECTORY_MUSIC}/NCMConverter4A/"
    private val rootDatabaseReader by lazy { RootDatabaseReader(context.cacheDir) }
    private val mediaClientRef = AtomicReference<ContentProviderClient?>(null)

    // Reserve a sequence while holding the per-basename CHM compute lock.
    private val seqTable = ConcurrentHashMap<String, MutableSet<Int>>()

    override fun fileName(source: String): String =
        DocumentFile.fromSingleUri(context, Uri.parse(source))?.name ?: "未知文件"

    override fun beginBatch(mitigateConflicts: Boolean) {
        val client = runCatching {
            context.contentResolver.acquireContentProviderClient(MediaStore.AUTHORITY)
        }.getOrNull()
        mediaClientRef.getAndSet(client)?.close()
        if (mitigateConflicts) scanExistingFiles()
    }

    override fun endBatch() {
        mediaClientRef.getAndSet(null)?.close()
    }

    private inline fun <T> withMediaClient(
        clientOp: (ContentProviderClient) -> T,
        fallbackOp: () -> T,
    ): T {
        val client = mediaClientRef.get()
        if (client != null) {
            try {
                return clientOp(client)
            } catch (_: RemoteException) {
                // Fall back to ContentResolver if the cached client binder died.
            }
        }
        return fallbackOp()
    }

    override fun resolveFileNames(
        sources: List<String>,
        knownFileNames: Map<String, String>,
    ): Map<String, String> {
        val resolved = HashMap<String, String>(maxOf(sources.size, knownFileNames.size))
        resolved.putAll(knownFileNames)

        val mediaGroups = LinkedHashMap<Uri, MutableList<Pair<String, String>>>()
        for (source in sources) {
            if (source in resolved) continue
            val uri = runCatching { Uri.parse(source) }.getOrNull() ?: continue
            if (uri.scheme != "content") continue

            if (uri.authority == EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                val name = docId
                    ?.takeIf { ':' in it }
                    ?.substringAfter(':')
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                if (name != null) {
                    resolved[source] = name
                    continue
                }
            }

            if (uri.authority == MediaStore.AUTHORITY && uri.query == null) {
                val id = uri.lastPathSegment
                if (id != null && id.toLongOrNull() != null) {
                    val collection = Uri.parse(source.substringBeforeLast('/'))
                    mediaGroups.getOrPut(collection) { mutableListOf() }.add(id to source)
                }
            }
        }

        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        for ((collectionUri, entries) in mediaGroups) {
            for (chunk in entries.chunked(MEDIASTORE_QUERY_BATCH_SIZE)) {
                val idToSource = HashMap<String, String>(chunk.size)
                for ((id, source) in chunk) {
                    idToSource[id] = source
                }
                val placeholders = Array(chunk.size) { "?" }.joinToString(",")
                val selection = "${MediaStore.MediaColumns._ID} IN ($placeholders)"
                val selectionArgs = chunk.map { it.first }.toTypedArray()
                runCatching {
                    withMediaClient(
                        { it.query(collectionUri, projection, selection, selectionArgs, null) },
                        { context.contentResolver.query(collectionUri, projection, selection, selectionArgs, null) },
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        if (idCol >= 0 && nameCol >= 0) {
                            while (cursor.moveToNext()) {
                                val rowId = cursor.getString(idCol) ?: continue
                                val displayName = cursor.getString(nameCol) ?: continue
                                val source = idToSource[rowId] ?: continue
                                resolved[source] = displayName
                            }
                        }
                    }
                }
            }
        }

        return resolved
    }

    override fun openInput(source: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(source))
            ?: throw IllegalStateException("无法打开输入文件: $source")

    override fun openInputWithSize(source: String): OpenedInput {
        val uri = Uri.parse(source)
        val pfd = if (uri.authority == MediaStore.AUTHORITY) {
            withMediaClient(
                { it.openFile(uri, "r") },
                { context.contentResolver.openFileDescriptor(uri, "r") },
            )
        } else {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        }
        if (pfd != null) {
            return OpenedInput(ParcelFileDescriptor.AutoCloseInputStream(pfd), pfd.statSize)
        }
        return OpenedInput(openInput(source), -1L)
    }

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

    override fun bufferSize(inputCount: Int, threadCount: Int): Int =
        androidBufferSize(inputCount, threadCount)

    override fun publishParallelism(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(4, 8)

    override fun writeOutput(
        fileName: String,
        extension: String,
        mitigateConflicts: Boolean,
        write: (OutputStream) -> Unit,
    ): PendingOutput? {
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

        // On Android 11+ (API 30+), MediaProvider FUSE triggers an asynchronous
        // background scan on FD close(), avoiding a blocking IS_PENDING=0 IPC.
        val usePendingFlag = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, mediaRelativePath)
            if (usePendingFlag) {
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val outputUri = withMediaClient(
            { it.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) },
            { context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) },
        ) ?: throw IllegalStateException("无法创建输出文件: $displayName")

        try {
            val pfd = withMediaClient(
                { it.openFile(outputUri, "w") },
                { context.contentResolver.openFileDescriptor(outputUri, "w") },
            ) ?: throw IllegalStateException("无法打开输出文件: $displayName")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use(write)
        } catch (error: Throwable) {
            runCatching { deleteOutputUri(outputUri) }
            throw error
        }

        return if (usePendingFlag) AndroidPendingOutput(outputUri, displayName) else null
    }

    private fun deleteOutputUri(uri: Uri) {
        withMediaClient(
            { it.delete(uri, null, null) },
            { context.contentResolver.delete(uri, null, null) },
        )
    }

    private inner class AndroidPendingOutput(
        private val outputUri: Uri,
        private val displayName: String,
    ) : PendingOutput {
        private val finished = AtomicBoolean(false)

        override fun publish() {
            if (!finished.compareAndSet(false, true)) return
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                val published = withMediaClient(
                    { it.update(outputUri, values, null, null) },
                    { context.contentResolver.update(outputUri, values, null, null) },
                )
                check(published == 1) { "无法发布输出文件: $displayName" }
            } catch (error: Throwable) {
                runCatching { deleteOutputUri(outputUri) }
                throw error
            }
        }

        override fun discard() {
            if (!finished.compareAndSet(false, true)) return
            runCatching { deleteOutputUri(outputUri) }
        }
    }

    private fun scanExistingFiles() {
        seqTable.clear()
        val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(mediaRelativePath)
        withMediaClient(
            { it.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null) },
            { context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null) },
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
