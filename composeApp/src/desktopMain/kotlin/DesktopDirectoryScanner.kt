package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.service.ScanDirectory
import com.cdb96.ncmconverter4a.service.ScanDirectoryBrowser
import com.cdb96.ncmconverter4a.service.ScanFormat
import com.cdb96.ncmconverter4a.service.ScanNode
import com.cdb96.ncmconverter4a.service.defaultScanDirectories
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal class DesktopDirectoryBrowser : ScanDirectoryBrowser {
    override fun root(directory: ScanDirectory): ScanNode {
        val file = File(directory.path)
        require(file.isDirectory) { "目录不存在或不是文件夹" }
        return node(file)
    }

    override suspend fun children(directory: ScanNode): List<ScanNode> {
        val children = mutableListOf<ScanNode>()
        Files.newDirectoryStream(Path.of(directory.source)).use { stream ->
            for (path in stream) {
                currentCoroutineContext().ensureActive()
                if (Files.isRegularFile(path) || Files.isDirectory(path)) children.add(node(path.toFile()))
            }
        }
        return children
    }

    private fun node(file: File): ScanNode = ScanNode(
        id = file.canonicalPath, source = file.absolutePath, name = file.name,
        path = file.absolutePath, isDirectory = file.isDirectory,
        size = if (file.isFile) file.length() else null,
    )
}

internal class DesktopScanDirectoryPreference(
    private val file: Path = Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home"),
        "NCMConverter4a", "scan-directories.properties"),
) {
    fun load(): Map<ScanFormat, ScanDirectory> {
        val properties = runCatching { read() }.getOrDefault(Properties())
        return defaultScanDirectories(android = false).mapValues { (format, _) ->
            ScanDirectory(properties.getProperty(format.name, ""))
        }
    }

    fun save(format: ScanFormat, directory: ScanDirectory) {
        if (directory.configured) require(File(directory.path).isDirectory) { "所选目录不存在" }
        val properties = read().apply { setProperty(format.name, directory.path) }
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.newOutputStream(file).use { properties.store(it, "NCMConverter4a scan directories") }
    }

    private fun read(): Properties = Properties().apply {
        if (Files.exists(file)) Files.newInputStream(file).use { load(it) }
    }
}
