package com.cdb96.ncmconverter4a.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AndroidDirectoryBrowser(context: Context) : ScanDirectoryBrowser {
    private val resolver = context.contentResolver

    override fun root(directory: ScanDirectory): ScanNode {
        val tree = Uri.parse(directory.treeUri ?: error("请先授权此扫描目录"))
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return ScanNode("${tree.authority}:$documentId", document.toString(), directory.path.substringAfterLast('/'),
            directory.path, isDirectory = true)
    }

    override suspend fun children(directory: ScanNode): List<ScanNode> {
        val document = Uri.parse(directory.source)
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(document, DocumentsContract.getDocumentId(document))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val children = mutableListOf<ScanNode>()
        val cursor = resolver.query(query, columns, null, null, null) ?: error("目录无法读取，请重新授权")
        cursor.use {
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = it.getString(0)
                val name = it.getString(1) ?: continue
                val source = DocumentsContract.buildDocumentUriUsingTree(document, id)
                children.add(ScanNode("${document.authority}:$id", source.toString(), name,
                    "${directory.path.trimEnd('/')}/$name", it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (it.isNull(3)) null else it.getLong(3)))
            }
        }
        return children
    }
}

class AndroidScanDirectoryPreference(context: Context) {
    private val preferences = context.getSharedPreferences("scan_directories", Context.MODE_PRIVATE)

    fun load(): Map<ScanFormat, ScanDirectory> = defaultScanDirectories(android = true).mapValues { (format, default) ->
        ScanDirectory(preferences.getString("${format.name}_path", default.path).orEmpty(),
            preferences.getString("${format.name}_uri", null))
    }

    fun save(format: ScanFormat, directory: ScanDirectory) {
        preferences.edit().putString("${format.name}_path", directory.path)
            .putString("${format.name}_uri", directory.treeUri).apply()
    }
}
