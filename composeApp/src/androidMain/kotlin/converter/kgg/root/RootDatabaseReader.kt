package com.cdb96.ncmconverter4a.converter.kgg.root

import java.io.File
import java.io.IOException

/** Reads binary stdout without mixing in su/cat diagnostics or blocking a stderr pipe. */
internal class RootDatabaseReader(private val cacheDirectory: File) {
    companion object {
        const val DATABASE_PATH = "/data/data/com.kugou.android/files/mmkv/mggkey_multi_process"
    }

    fun read(builder: ProcessBuilder = ProcessBuilder("su", "-c", "cat \"$DATABASE_PATH\"")): ByteArray {
        val diagnostics = File.createTempFile("kgg-root-", ".stderr", cacheDirectory)
        try {
            // A cache file also drains large diagnostics without a second worker.
            // Keep stdout binary even if a supplied builder previously merged stderr.
            builder.redirectErrorStream(false).redirectError(diagnostics)
            val process = try {
                builder.start()
            } catch (error: IOException) {
                throw IllegalStateException("无法启动 su，请确认设备已 Root 并安装 su", error)
            }
            try {
                process.outputStream.close()
                val bytes = process.inputStream.use { it.readBytes() }
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val detail = diagnostics.bufferedReader().use { reader ->
                        val buffer = CharArray(512)
                        val length = reader.read(buffer)
                        if (length > 0) String(buffer, 0, length).trim() else ""
                    }
                    throw IllegalStateException(
                        "Root读取数据库失败 (exit=$exitCode)，请确认已授予Root权限且酷狗数据库存在" +
                            if (detail.isEmpty()) "" else ": $detail"
                    )
                }
                check(bytes.size >= 8) { "Root读取到的MMKV数据库为空或不完整: $DATABASE_PATH" }
                return bytes
            } finally {
                process.destroy()
            }
        } finally {
            diagnostics.delete()
        }
    }
}
