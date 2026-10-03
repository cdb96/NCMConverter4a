package com.cdb96.ncmconverter4a.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.TimeSource

enum class ScanFormat {
    NCM, KGG, KGM;

    companion object {
        fun fromFileName(name: String): ScanFormat? =
            entries.firstOrNull { name.substringAfterLast('.', "").equals(it.name, ignoreCase = true) }
    }
}

data class ScanDirectory(val path: String = "", val treeUri: String? = null) {
    val configured: Boolean get() = path.isNotBlank() || treeUri != null
}

const val ANDROID_NCM_SCAN_DIRECTORY = "/sdcard/Download/netease/cloudmusic/Music"

fun defaultScanDirectories(android: Boolean): Map<ScanFormat, ScanDirectory> =
    ScanFormat.entries.associateWith { format ->
        ScanDirectory(if (android && format == ScanFormat.NCM) ANDROID_NCM_SCAN_DIRECTORY else "")
    }

data class ScannedAudioFile(
    val id: String,
    val source: String,
    val name: String,
    val directory: String,
    val format: ScanFormat,
    val size: Long? = null,
)

data class ScanNode(
    val id: String,
    val source: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long? = null,
)

interface ScanDirectoryBrowser {
    fun root(directory: ScanDirectory): ScanNode
    suspend fun children(directory: ScanNode): List<ScanNode>
}

data class DirectoryScanResult(val files: List<ScannedAudioFile>, val warnings: List<String>)

/** Traverse each configured source once, even when several formats use it. */
class DirectoryScanService(private val browser: ScanDirectoryBrowser) {
    suspend fun scan(
        directories: Map<ScanFormat, ScanDirectory>,
        recursive: Boolean,
        onProgress: suspend (directories: Int, files: Int) -> Unit = { _, _ -> },
    ): DirectoryScanResult {
        val files = LinkedHashMap<String, ScannedAudioFile>()
        val warnings = mutableListOf<String>()
        var visitedCount = 0
        var reportedCount = 0
        var progressTime = TimeSource.Monotonic.markNow()
        val groups = directories.entries.filter { it.value.configured }
            .groupBy { it.value.treeUri ?: it.value.path }
        for (group in groups.values) {
            currentCoroutineContext().ensureActive()
            val formats = group.map { it.key }.toSet()
            val directory = group.first().value
            val root = try {
                browser.root(directory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                warnings.add("${directory.path}: ${error.message ?: "无法打开目录"}")
                continue
            }
            val pending = mutableListOf(root)
            val visited = HashSet<String>()
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val current = pending.removeAt(pending.lastIndex)
                if (!visited.add(current.id)) continue
                val children = try {
                    browser.children(current)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (warnings.size < 20) warnings.add("${current.path}: ${error.message ?: "无法读取目录"}")
                    emptyList()
                }
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    if (child.isDirectory) {
                        if (recursive) pending.add(child)
                    } else {
                        val format = ScanFormat.fromFileName(child.name) ?: continue
                        if (format in formats) {
                            files[child.id] = ScannedAudioFile(child.id, child.source, child.name, current.path, format, child.size)
                        }
                    }
                }
                visitedCount++
                if (visitedCount == 1 || progressTime.elapsedNow().inWholeMilliseconds >= 100) {
                    onProgress(visitedCount, files.size)
                    reportedCount = visitedCount
                    progressTime = TimeSource.Monotonic.markNow()
                }
            }
        }
        if (reportedCount != visitedCount) onProgress(visitedCount, files.size)
        return DirectoryScanResult(
            files.values.sortedWith(compareBy<ScannedAudioFile> { it.format.ordinal }
                .thenBy { it.name.lowercase() }.thenBy { it.directory }),
            warnings.distinct(),
        )
    }
}
