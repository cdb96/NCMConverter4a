package com.cdb96.ncmconverter4a.converter.kgg.root

import java.io.File
import java.io.IOException

/** Reads binary stdout without mixing in su/cat diagnostics or blocking a stderr pipe. */
internal class RootDatabaseReader(
    private val cacheDirectory: File,
    private val processBuilder: (List<String>) -> ProcessBuilder = { ProcessBuilder(it) },
) {
    companion object {
        const val DATABASE_PATH = "/data/data/com.kugou.android/files/mmkv/mggkey_multi_process"
        private const val DATABASE_RELATIVE_PATH = "com.kugou.android/files/mmkv/mggkey_multi_process"
        private val unsupportedOption = Regex("(?i)(unknown|unrecognized|invalid|illegal|unsupported) option")
    }

    fun read(): ByteArray {
        val command = databaseCommand()
        // Android app data isolation can hide other packages even from a root
        // shell that inherits our mount namespace. Magisk/KernelSU support -M.
        val global = execute(processBuilder(listOf("su", "-M", "-c", command)))
        val result = if (global.exitCode != 0 && unsupportedOption.containsMatchIn(
                global.detail + global.bytes.decodeToString(endIndex = minOf(1024, global.bytes.size))
            )) {
            // Older su implementations do not support mount-master. Retry only
            // an option error, not a denied root request or a failed cat.
            execute(processBuilder(listOf("su", "-c", command)))
        } else global
        return checkedBytes(result)
    }

    fun read(builder: ProcessBuilder): ByteArray = checkedBytes(execute(builder))

    private fun databaseCommand(): String {
        // cacheDir identifies our Android user (and adopted storage volume).
        // Probe paths in the root shell; File.exists() here sees the app sandbox.
        val paths = listOfNotNull(
            cacheDirectory.absoluteFile.parentFile?.parentFile?.resolve(DATABASE_RELATIVE_PATH)?.invariantSeparatorsPath,
            "/data/user/0/$DATABASE_RELATIVE_PATH",
            DATABASE_PATH,
        ).distinct().joinToString(" ", transform = ::shellQuote)
        return """
            for database in $paths /data/user/[0-9]*/$DATABASE_RELATIVE_PATH; do
                if [ -f "${'$'}database" ]; then
                    printf '读取数据库: %s\n' "${'$'}database" >&2
                    exec cat "${'$'}database"
                fi
            done
            printf '%s\n' 'Root shell 中未找到可见的酷狗 MMKV 数据库；已检查当前用户、主用户和其他用户目录，请检查酷狗数据位置及 Root 管理器的目录隔离设置' >&2
            exit 44
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun checkedBytes(result: ReadResult): ByteArray {
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "Root读取数据库失败 (exit=${result.exitCode})" +
                    if (result.detail.isEmpty()) "，请确认已授予Root权限" else ": ${result.detail}"
            )
        }
        check(result.bytes.size >= 8) {
            "Root读取到的MMKV数据库为空或不完整: " + result.detail.ifEmpty { DATABASE_PATH }
        }
        return result.bytes
    }

    private data class ReadResult(val bytes: ByteArray, val exitCode: Int, val detail: String)

    private fun execute(builder: ProcessBuilder): ReadResult {
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
                val detail = diagnostics.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    val length = reader.read(buffer)
                    if (length > 0) String(buffer, 0, length).trim() else ""
                }
                return ReadResult(bytes, exitCode, detail)
            } finally {
                process.destroy()
            }
        } finally {
            diagnostics.delete()
        }
    }
}
