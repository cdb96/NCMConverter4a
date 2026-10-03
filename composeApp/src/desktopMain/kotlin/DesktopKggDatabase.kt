package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.KggDbDecryptor
import com.cdb96.ncmconverter4a.converter.kgg.KggKeyDatabase
import com.cdb96.ncmconverter4a.converter.kgg.MMKVParser
import com.cdb96.ncmconverter4a.platform.Logger
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

internal fun loadDesktopKggDatabase(path: String): KggKeyDatabase {
    val file = File(path)
    require(file.isFile) { "数据库文件不存在: $path" }
    val bytes = file.readBytes()
    val sqlite = KggDbDecryptor.isSqliteDatabase(bytes)
    Logger("DesktopKggDatabase").i("dbFile=${file.name} size=${bytes.size} isSqliteDatabase=$sqlite")
    if (!sqlite) {
        val parser = MMKVParser(bytes)
        return KggKeyDatabase(parser::getBytes)
    }

    val decrypted = KggDbDecryptor.decryptDatabase(bytes)
    val mapping = KggDbDecryptor.extractKeyMapping(decrypted)
    // Keep the decrypted snapshot for the compatibility fallback, and cache
    // both hits and misses so duplicate hashes do not scan it repeatedly.
    val fallback = ConcurrentHashMap<String, Optional<ByteArray>>()
    return KggKeyDatabase { hash ->
        mapping[hash]?.encodeToByteArray() ?: fallback.computeIfAbsent(hash) {
            Optional.ofNullable(KggDbDecryptor.extractEkey(decrypted, it))
        }.orElse(null)?.copyOf()
    }
}
