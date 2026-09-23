package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.platform.Logger

/**
 * Pure-Kotlin SQLite 数据库文件读取器 —— 无 JDBC、无任何原生依赖, 直接按
 * https://www.sqlite.org/fileformat2.html 解析文件格式 (B-tree 遍历、record 解码、
 * varint、溢出页链), 读取 KGG 的 ShareFileItems 表, 提取 EncryptionKeyId -> EncryptionKey 映射。
 *
 * 表的 CREATE SQL (KGG): CREATE TABLE ShareFileItems (
 *   EncryptionKeyId TEXT,
 *   EncryptionKey TEXT,
 *   ... )
 *
 * KGG 库的两个已知特性 (经验发现, 重写时已保留):
 * 1. **部分记录声明的 payload_len 值不可信** (可能远大于真实大小)。因此 leaf cell 的
 *    payload_len varint 只用于定位 record 起始位, record 真实大小须用 record header
 *    (headerSize + 序列类型) 反推; 物理布局 (是否溢出、溢出指针位置) 也由真实大小决定 ——
 *    SQLite 写入时的布局本就由真实 payload 大小计算, 与声明的 payload_len 无关。
 * 2. **文本数据 (CREATE SQL / ekey) 以 BLOB 序列类型存储**, 解码时按 UTF-8 文本处理。
 */
object SqliteKggReader {

    private val log = Logger("SqliteKggReader")

    private const val TABLE_NAME = "ShareFileItems"

    // B-tree 页类型
    private const val PT_INTERIOR_TABLE = 0x05
    private const val PT_LEAF_TABLE = 0x0D

    /** 单条 record payload 上限, 超过视为损坏丢弃, 防止损坏的巨型 varint 导致堆溢出. */
    private const val MAX_PAYLOAD = 1 shl 20  // 1 MiB

    /** 序列类型 1–6 对应的字节数 (索引即类型号). */
    private val INT_SIZES = intArrayOf(0, 1, 2, 3, 4, 6, 8)

    // ── 入口 ─────────────────────────────────────────────────────────────

    /** Parse [dbBytes] and return a Map of EncryptionKeyId -> EncryptionKey. */
    fun readKeyMapping(dbBytes: ByteArray): Map<String, String> {
        val db = parseDb(dbBytes)
        log.i("pageSize=${db.pageSize} usableSize=${db.usableSize} fileSize=${dbBytes.size}")

        val dump = DebugDump()
        val schema = findTableSchema(db, dump)

        // 兜底: KGG ShareFileItems 中 EncryptionKeyId 恒在 EncryptionKey 之前,
        // CREATE SQL 解析失败时按列序 0/1 处理
        val colId = schema.colId.let { if (it >= 0) it else 0 }
        val colKey = schema.colKey.let { if (it >= 0) it else 1 }

        dump.dumpCells = true
        val rows = collectRows(db, pageNum = schema.rootPage, headerOffset = 0, dump = dump)
        log.i("data rows: ${rows.size} (colId=$colId colKey=$colKey)")
        rows.take(3).forEachIndexed { i, row ->
            log.d("  row[$i] cols=${row.size}: ${row.mapIndexed { ci, v -> "[$ci]=${v?.take(48)}" }}")
        }

        val mapping = LinkedHashMap<String, String>()
        for (row in rows) {
            val id = row.getOrNull(colId)?.takeIf { it.isNotEmpty() } ?: continue
            val key = row.getOrNull(colKey) ?: continue
            mapping[id] = key
        }
        log.i("mapping: ${mapping.size} entries")
        mapping.entries.take(5).forEach {
            log.d("  '${it.key}' -> '${it.value.take(64)}'")
        }
        return mapping
    }

    /**
     * 直接在解密后的数据库字节中按 audioHash 提取 ekey, 绕过 B-tree 行解析.
     *
     * audioHash (32 字符 hex) 作为 TEXT(32) 值存储在某个 record 的 body 里; 该 record 内
     * 还含一个 1024 字节的 EncryptionKey 列. 做法: 在原始字节中定位 audioHash, 向前回溯找到
     * record 头 (以 headerSize varint 起始), 校验 audioHash 确为某列 TEXT 值, 再返回同 record
     * 内 1024 字节列的内容.
     */
    fun extractEkey(dbBytes: ByteArray, audioHash: String): ByteArray? {
        val needle = audioHash.toByteArray(Charsets.UTF_8)
        val binNeedle = hexToBytes(audioHash)
        // 宽松解析页大小: 不校验 magic, 损坏文件直接按字节扫描; 页大小仅用于
        // 跨页列 (溢出) 的读取判断, 非法时降级为同页连续拷贝
        val pageSize = parsePageSizeLenient(dbBytes)

        // 匹配模式按命中率从高到低:
        //   1) typed: 序列类型 0x4D (即 TEXT(32)) + audioHash 字符串 — hash 是 record 的
        //      末列时 (0x4D 恰为 header 末字节) 直接相邻, 命中率最高
        //   2) string: 32 字符字符串 (可能嵌在路径里, 命中率低)
        //   3) binary: 16 字节二进制 MD5
        val typedNeedle = ByteArray(1 + needle.size) { if (it == 0) 0x4D else needle[it - 1] }
        val passes = mutableListOf(
            MatchPattern(typedNeedle, hashOffset = 1, hashLen = needle.size, label = "typed(0x4D+hash)"),
            MatchPattern(needle, hashOffset = 0, hashLen = needle.size, label = "string"),
        )
        if (binNeedle != null) passes.add(MatchPattern(binNeedle, 0, binNeedle.size, "binary"))

        // 统计并记录各模式的出现次数
        for (p in passes) {
            log.i("${p.label} occurrences: ${countOccurrences(dbBytes, p.needle)}")
            val first = indexOf(dbBytes, p.needle, 0)
            if (first >= 0) {
                val s = maxOf(0, first - 48)
                val e = minOf(dbBytes.size, first + p.needle.size + 48)
                log.d("first ${p.label} at $first, context hex: ${dbBytes.copyOfRange(s, e).joinToString(" ") { hexByte(it) }}")
            }
        }

        // 依次扫描各模式, 首个校验通过的 record 即返回
        for (p in passes) {
            var from = 0
            while (true) {
                val idx = indexOf(dbBytes, p.needle, from)
                if (idx < 0) break
                val ekey = findEkeyInRecord(dbBytes, idx + p.hashOffset, p.hashLen, pageSize)
                if (ekey != null) return ekey
                from = idx + 1
            }
        }
        return null
    }

    /** 宽松解析页大小 (无 require, 非法返回 0); 仅 extractEkey 用于跨页列判断. */
    private fun parsePageSizeLenient(data: ByteArray): Int {
        if (data.size < 18) return 0
        var ps = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        if (ps == 1) ps = 65536
        if (ps !in 512..65536 || (ps and (ps - 1)) != 0) return 0
        return ps
    }

    // ── 文件头 ──────────────────────────────────────────────────────────

    private fun parseDb(data: ByteArray): Db {
        require(data.size >= 100) { "Not a valid SQLite file (too small)" }
        // SQLite magic: "SQLite format 3\0" (16 字节, 末尾是 0x00)
        val magic = data.copyOfRange(0, 16)
        require(magic.contentEquals("SQLite format 3\u0000".toByteArray())) {
            "Not a valid SQLite file (bad magic)"
        }
        var pageSize = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        // SQLite 规范: 值 1 表示 65536
        if (pageSize == 1) pageSize = 65536
        require(pageSize in 512..65536 && (pageSize and (pageSize - 1)) == 0) {
            "Invalid page size: $pageSize"
        }
        // 数据库头偏移 20 处 1 字节为每页末尾保留区大小, 通常为 0
        val usableSize = pageSize - (data[20].toInt() and 0xFF)
        return Db(data, pageSize, usableSize)
    }

    // ── Schema 解析 ──────────────────────────────────────────────────────

    /** ShareFileItems 表信息: 根页 + 目标列在存储记录中的 0-based 位置. */
    private class TableSchema(val rootPage: Int, val colId: Int, val colKey: Int)

    /** 在 sqlite_master 中查找 ShareFileItems 表, 返回其根页与列位置. */
    private fun findTableSchema(db: Db, dump: DebugDump): TableSchema {
        // 第 1 页前 100 字节是数据库头, 其 B-tree 页头 (及 sqlite_master 行) 从偏移 100 开始
        val masterRows = collectRows(db, pageNum = 1, headerOffset = 100, dump = dump)
        val tables = masterRows.filter { it.getOrNull(0) == "table" }.mapNotNull { it.getOrNull(1) }
        log.i("sqlite_master: ${masterRows.size} rows, tables=$tables")

        var schema: TableSchema? = null
        for (row in masterRows) {
            if (row.size < 5) continue
            if (row[0] != "table") continue
            val name = row[1] ?: continue
            if (!name.equals(TABLE_NAME, ignoreCase = true)) continue
            val rootPage = row[3]?.toIntOrNull() ?: -1
            val createSql = row[4]
            val colId = createSql?.let { findColumnInSql(it, "EncryptionKeyId") } ?: -1
            val colKey = createSql?.let { findColumnInSql(it, "EncryptionKey") } ?: -1
            log.i("found table '$name': rootPage=$rootPage colId=$colId colKey=$colKey")
            log.d("CREATE SQL: $createSql")
            schema = TableSchema(rootPage, colId, colKey)
            break
        }
        require(schema != null && schema.rootPage > 0) {
            "Table '$TABLE_NAME' not found in database. Available tables: $tables"
        }
        return schema
    }

    /**
     * 返回 [colName] 在记录中的 0-based 位置.
     *
     * 表中定义的每一列在存储记录里都占据一个连续槽位 —— 包括 INTEGER PRIMARY KEY
     * 列: 它是 rowid 别名, 在记录里以 NULL(serial type 0)存储, 但**仍占一个位置**.
     * 表级约束(PRIMARY KEY (...)/FOREIGN KEY/UNIQUE/CHECK/CONSTRAINT)不算列, 跳过.
     */
    private fun findColumnInSql(createSql: String, colName: String): Int {
        val start = createSql.indexOf('(')
        val end = createSql.lastIndexOf(')')
        if (start < 0 || end <= start) return -1
        val cols = splitColumnDefs(createSql.substring(start + 1, end))
        var recordIdx = 0
        for (col in cols) {
            val trimmed = col.trim()
            if (trimmed.isEmpty()) continue
            if (isTableConstraint(trimmed)) continue
            val name = trimmed.split("\\s+".toRegex()).firstOrNull()
                ?.trim('"', '`', '[', ']') ?: continue
            if (name.equals(colName, ignoreCase = true)) return recordIdx
            recordIdx++
        }
        return -1
    }

    /** 按顶层逗号拆分列定义(忽略括号内的逗号, 如 PRIMARY KEY (a, b)). */
    private fun splitColumnDefs(s: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in s) {
            when (c) {
                '(' -> { depth++; cur.append(c) }
                ')' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { result.add(cur.toString()); cur.setLength(0) }
                else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        return result
    }

    private fun isTableConstraint(s: String): Boolean {
        val u = s.uppercase().trim()
        return u.startsWith("PRIMARY") || u.startsWith("FOREIGN") ||
            u.startsWith("UNIQUE") || u.startsWith("CHECK") ||
            u.startsWith("CONSTRAINT")
    }

    // ── B-tree 遍历 ──────────────────────────────────────────────────────

    private fun collectRows(
        db: Db, pageNum: Int, headerOffset: Int, dump: DebugDump
    ): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        traverseBTree(db, pageNum, headerOffset, dump, HashSet()) { rows.add(it) }
        return rows
    }

    private fun traverseBTree(
        db: Db, pageNum: Int, headerOffset: Int,
        dump: DebugDump, visited: HashSet<Int>, onRow: (List<String?>) -> Unit
    ) {
        if (!visited.add(pageNum)) return  // 防止环路导致无限递归
        require(pageNum in 1..db.maxPage) {
            "Invalid page $pageNum (max=${db.maxPage}, fileSize=${db.data.size}, pageSize=${db.pageSize})"
        }
        val hdrBase = db.pageOffset(pageNum) + headerOffset

        val pageType = db.data[hdrBase].toInt() and 0xFF
        val numCells = db.u16(hdrBase + 3)

        // contentStart == 0 在 SQLite 规范中表示 65536
        var contentStart = db.u16(hdrBase + 5)
        if (contentStart == 0) contentStart = 65536

        if (dump.nextPage()) {
            log.d("page $pageNum: type=0x${pageType.toString(16)} numCells=$numCells contentStart=$contentStart headerOffset=$headerOffset")
        }

        val pageHeaderSize = when (pageType) {
            PT_LEAF_TABLE -> 8
            PT_INTERIOR_TABLE -> 12
            else -> throw IllegalArgumentException(
                "Unknown page type 0x${pageType.toString(16)} at page $pageNum"
            )
        }
        // cell pointer array 紧跟页头之后, 每项 2 字节大端, 值为相对页起始的偏移
        val cellPtrArrayStart = hdrBase + pageHeaderSize

        when (pageType) {
            PT_LEAF_TABLE -> {
                for (i in 0 until numCells) {
                    val co = db.u16(cellPtrArrayStart + i * 2)
                    if (co < contentStart || co >= db.pageSize) continue
                    val record = readCellRecord(db, db.pageOffset(pageNum) + co, dump)
                    if (record != null) onRow(record)
                }
            }
            PT_INTERIOR_TABLE -> {
                val children = mutableListOf<Int>()
                for (i in 0 until numCells) {
                    val co = db.u16(cellPtrArrayStart + i * 2)
                    if (co < contentStart || co + 4 > db.pageSize) continue
                    // interior table cell: [4 字节左子页][varint rowid 键]
                    val leftChild = db.u32(db.pageOffset(pageNum) + co)
                    if (leftChild in 1..db.maxPage) children.add(leftChild)
                }
                // 最右子页指针在页头偏移 8
                val rightChild = db.u32(hdrBase + 8)
                if (rightChild in 1..db.maxPage) children.add(rightChild)
                for (child in children) {
                    traverseBTree(db, child, 0, dump, visited, onRow)
                }
            }
        }
    }

    // ── Record 解码 ─────────────────────────────────────────────────────

    private fun readCellRecord(db: Db, cellStart: Int, dump: DebugDump): List<String?>? {
        val data = db.data
        val usableSize = db.usableSize
        val maxLocal = usableSize - 35
        var pos = cellStart

        // Table leaf cell: [payload_len varint][rowid varint][payload (=record)]
        // ★ KGG 库中 payload_len 的**值**不可信 (可能远大于 record 实际大小), 只使用:
        //   1) varint 字节长度 → 正确偏移到 record 起始位
        //   2) record header → 反推真实 record 大小 (recordSize = hdrLen + Σ 序列类型长度)
        // 物理布局 (是否溢出、溢出指针位置) 由真实 recordSize 决定 —— SQLite 写入时的
        // 布局本就由真实 payload 大小计算, 与声明的 payload_len 无关。
        val payloadLen = db.varint(pos)
        pos += payloadLen.bytes
        val declaredSize = payloadLen.value.toInt()
        val rowId = db.varint(pos)
        pos += rowId.bytes
        val recStart = pos

        // 在原始数据上直接解析 record 头, 算出真正的 record 大小
        val (hdrLen, serialTypes) = parseRecordHeader(data, recStart, minOf(2000, data.size - recStart))
        val bodySize = serialTypes.sumOf { serialTypeSize(it).toLong() }
        val recordSize = (hdrLen.toLong() + bodySize).toInt()
        if (recordSize <= 0 || recordSize > minOf(db.data.size, MAX_PAYLOAD)) return null

        val dumpCell = dump.nextCell()
        val payload = if (recordSize > maxLocal) {
            // 溢出: local 部分留在本页, 其余在溢出页链上; local 按真实 recordSize 计算
            val minLocal = ((usableSize - 12) * 32 / 255) - 23
            var local = minLocal + (recordSize - minLocal) % (usableSize - 4)
            if (local > maxLocal) local = minLocal
            if (dumpCell) {
                val rawN = minOf(10, data.size - cellStart)
                log.d("DATA#${dump.cellCount} cellStart=$cellStart recStart=$recStart recordSize=$recordSize declared=$declaredSize local=$local overflow=true")
                log.d("  rawCell[cellStart..+$rawN] hex: ${data.copyOfRange(cellStart, cellStart + rawN).joinToString(" ") { hexByte(it) }}")
            }
            readPayloadWithOverflow(db, recStart, recordSize, local, dumpCell)
        } else {
            if (dumpCell) {
                val rawN = minOf(10, data.size - cellStart)
                log.d("DATA#${dump.cellCount} cellStart=$cellStart recStart=$recStart recordSize=$recordSize declared=$declaredSize overflow=false")
                log.d("  rawCell[cellStart..+$rawN] hex: ${data.copyOfRange(cellStart, cellStart + rawN).joinToString(" ") { hexByte(it) }}")
            }
            val copyLen = minOf(recordSize, maxOf(0, data.size - recStart))
            ByteArray(recordSize).also { out -> data.copyInto(out, 0, recStart, recStart + copyLen) }
        }
        val record = decodeRecord(payload, 0, recordSize)
        if (dumpCell) {
            val n = minOf(128, payload.size)
            log.d("  payload[0..$n] hex: ${payload.copyOfRange(0, n).joinToString(" ") { hexByte(it) }}")
            log.d("  decoded cols=${record.size}: ${record.mapIndexed { i, v -> "[$i]=${v?.take(40)}" }}")
        }
        return record
    }

    /**
     * 读取溢出 cell payload: 先从叶子页复制 local 字节, 再从溢出页链读取剩余.
     * [local] 由真实 recordSize 决定 (物理溢出指针位置); [totalSize] 由 record header 决定.
     */
    private fun readPayloadWithOverflow(db: Db, payloadStart: Int, totalSize: Int, local: Int, dumpChain: Boolean): ByteArray {
        val data = db.data
        val usableSize = db.usableSize
        val payload = ByteArray(totalSize)

        // 拷贝叶子页上的局部 payload
        val localCopy = minOf(local, maxOf(0, data.size - payloadStart))
        if (localCopy > 0) data.copyInto(payload, 0, payloadStart, payloadStart + localCopy)
        if (localCopy >= totalSize) return payload

        // 紧跟局部 payload 之后是 4 字节大端的溢出页指针
        val overflowPtrPos = payloadStart + local
        if (dumpChain) log.d("  chain: local=$local totalSize=$totalSize overflowPtrPos=$overflowPtrPos")
        var overflowPage = if (overflowPtrPos + 4 <= data.size) db.u32(overflowPtrPos) else 0
        var remaining = totalSize - local
        var writePos = local
        val seen = HashSet<Int>()
        while (overflowPage > 0 && remaining > 0 && seen.add(overflowPage)) {
            val pageOffset = db.pageOffset(overflowPage)
            if (pageOffset < 0 || pageOffset + 4 > data.size) break
            val nextPage = db.u32(pageOffset)
            val chunkSize = minOf(remaining, usableSize - 4)
            val avail = data.size - (pageOffset + 4)
            if (avail <= 0) break
            val copy = minOf(chunkSize, avail)
            if (dumpChain) log.d("    overflowPage=$overflowPage pageOffset=$pageOffset nextPage=$nextPage chunk=$copy")
            data.copyInto(payload, writePos, pageOffset + 4, pageOffset + 4 + copy)
            writePos += copy
            remaining -= copy
            overflowPage = nextPage
        }
        return payload
    }

    /**
     * 解析 record 头: 返回 (headerSize 值, 序列类型列表).
     * 序列类型须恰好填满头部 ([maxLen] 为头部长度上限, 越界视为损坏停止).
     */
    private fun parseRecordHeader(data: ByteArray, start: Int, maxLen: Int): Pair<Int, List<Long>> {
        val header = Varint.from(data, start)
        val hdrLen = header.value.toInt().coerceIn(0, maxLen)
        val serialTypes = mutableListOf<Long>()
        var pos = start + header.bytes
        val hdrEnd = start + hdrLen
        while (pos < hdrEnd) {
            val st = Varint.from(data, pos)
            if (st.bytes == 0 || pos + st.bytes > hdrEnd) break
            serialTypes.add(st.value)
            pos += st.bytes
        }
        return hdrLen to serialTypes
    }

    private fun decodeRecord(data: ByteArray, recordStart: Int, recordLen: Int): List<String?> {
        val (hdrLen, serialTypes) = parseRecordHeader(data, recordStart, recordLen)
        val bodyStart = recordStart + hdrLen
        val bodyEnd = minOf(recordStart + recordLen, data.size)
        var bodyPos = bodyStart
        val row = mutableListOf<String?>()

        for (st in serialTypes) {
            if (bodyPos > bodyEnd) break
            when {
                // NULL
                st == 0L -> row.add(null)

                // type 8 -> 整数 0, type 9 -> 整数 1 (body 中不占字节)
                st == 8L -> row.add("0")
                st == 9L -> row.add("1")

                // 1–6: 有符号整数, 分别占 1/2/3/4/6/8 字节
                st in 1L..6L -> {
                    val size = INT_SIZES[st.toInt()]            // st ∈ 1..6 -> 索引 1..6 ✓
                    if (bodyPos + size > bodyEnd) break
                    var value = 0L
                    for (i in 0 until size) {
                        value = (value shl 8) or (data[bodyPos + i].toLong() and 0xFF)
                    }
                    // 符号扩展: 最高位为 1 则为负数
                    if (size > 0 && (data[bodyPos].toInt() and 0x80) != 0) {
                        value -= (1L shl (size * 8))
                    }
                    row.add(value.toString())
                    bodyPos += size
                }

                // 7: IEEE 754 双精度浮点, 8 字节
                st == 7L -> {
                    if (bodyPos + 8 > bodyEnd) break
                    var bits = 0L
                    for (i in 0 until 8) {
                        bits = (bits shl 8) or (data[bodyPos + i].toLong() and 0xFF)
                    }
                    row.add(Double.fromBits(bits).toString())   // 纯 Kotlin, 无 java.lang 依赖
                    bodyPos += 8
                }

                // ≥12 偶数: BLOB, 长度 = (N-12)/2
                // KGG 库将文本(CREATE SQL / ekey)以 BLOB 序列类型存储, 内容实为 UTF-8 文本, 故按文本解码
                st >= 12L && st % 2L == 0L -> {
                    val len = ((st - 12) / 2).toInt()
                    if (bodyPos + len > bodyEnd) break
                    row.add(String(data, bodyPos, len, Charsets.UTF_8))
                    bodyPos += len
                }

                // ≥13 奇数: TEXT, 长度 = (N-13)/2
                st >= 13L && st % 2L == 1L -> {
                    val len = ((st - 13) / 2).toInt()
                    if (bodyPos + len > bodyEnd) break
                    val text = String(data, bodyPos, len, Charsets.UTF_8)
                    row.add(text)
                    bodyPos += len
                }

                else -> break  // 未知类型 (10/11 reserved), 停止
            }
        }
        return row
    }

    private fun serialTypeSize(st: Long): Int = when {
        st == 0L || st == 8L || st == 9L -> 0
        st in 1L..6L -> INT_SIZES[st.toInt()]
        st == 7L -> 8
        st >= 12L && st % 2L == 0L -> ((st - 12) / 2).toInt()    // BLOB
        st >= 13L -> ((st - 13) / 2).toInt()                     // TEXT
        else -> 0
    }

    // ── 原始字节扫描 (extractEkey 辅助) ──────────────────────────────────

    /** [audioHash] 在原始字节中的匹配模式: needle 字节 + needle 内 hash 值的起始偏移/长度. */
    private class MatchPattern(
        val needle: ByteArray,
        val hashOffset: Int,
        val hashLen: Int,
        val label: String,
    )

    private fun countOccurrences(data: ByteArray, needle: ByteArray): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = indexOf(data, needle, from)
            if (i < 0) break
            count++
            from = i + 1
        }
        return count
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            // ★ 旧实现误用 hex[i]/hex[i+1] (索引重叠), 每对字节错位一位,
            //   导致 binary 匹配阶段的 needle 永远构建错误; 已修正为 hex[2i]/hex[2i+1]
            val hi = hex[2 * i].digitToIntOrNull(16) ?: return null
            val lo = hex[2 * i + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun findEkeyInRecord(data: ByteArray, hashIdx: Int, hashLen: Int, pageSize: Int): ByteArray? {
        // audioHash 值在 [hashIdx, hashIdx+hashLen). 向前找 record 头.
        val minHdr = maxOf(0, hashIdx - 1024)
        for (hdrStart in (hashIdx - 1) downTo minHdr) {
            val hdrLen = data[hdrStart].toInt() and 0xFF
            if (hdrLen < 2 || hdrLen > 126) continue            // 1 字节 headerSize, 合理范围
            val bodyStart = hdrStart + hdrLen
            if (bodyStart > hashIdx) continue                    // body 必须在 audioHash 之前开始
            val serialTypes = mutableListOf<Long>()
            var p = hdrStart + 1
            val hdrEnd = hdrStart + hdrLen
            var ok = true
            while (p < hdrEnd) {
                val st = Varint.from(data, p)
                if (st.bytes == 0 || p + st.bytes > hdrEnd) { ok = false; break }
                serialTypes.add(st.value)
                p += st.bytes
            }
            if (!ok || p != hdrEnd) continue                     // 序列类型须恰好填满头
            val hashSt = 13L + 2L * hashLen                      // TEXT(hashLen) 的序列类型
            var bp = bodyStart
            var foundHash = false
            for (st in serialTypes) {
                if (bp == hashIdx && st == hashSt) foundHash = true
                bp += serialTypeSize(st)
            }
            if (!foundHash) continue                             // audioHash 不是此 record 的列值
            // 找到 record. 返回 1024 字节列 (EncryptionKey)
            // ★ 列可能跨页 (溢出): 用 SQLite 溢出布局公式计算 local, 跨页时跟随溢出页链,
            //   而不是直接按同页连续字节拷贝 (旧实现跨页时读到的是下一页内容, ekey 尾部错误)
            val recordSize = hdrLen + serialTypes.sumOf { serialTypeSize(it) }
            bp = bodyStart
            for (st in serialTypes) {
                val sz = serialTypeSize(st)
                if (sz == 1024 && bp + 1024 <= data.size) {
                    return readColumn(data, bp, 1024, hdrStart, recordSize, pageSize)
                }
                bp += sz
            }
            return null                                          // 此 record 无 1024 字节列
        }
        return null
    }

    /**
     * 读取 record 中从 [colStart] 起 [colLen] 字节的列, 支持跨页 (溢出链) 场景.
     *
     * record 的数据源: 页内 [recStart, recStart+local) 连续存放, 之后是 4 字节大端
     * 溢出页指针, 溢出页链每页 [4 字节 next 指针][usableSize-4 字节数据] 按序续接.
     * [pageSize] 为 0 (非法头) 时降级为同页连续拷贝.
     */
    private fun readColumn(
        data: ByteArray, colStart: Int, colLen: Int,
        recStart: Int, recordSize: Int, pageSize: Int
    ): ByteArray {
        val usableSize = if (pageSize >= 512) pageSize - (data[20].toInt() and 0xFF) else 0
        val maxLocal = usableSize - 35
        if (pageSize < 512 || recordSize <= maxLocal) {
            // 无溢出: 整个 record 在本页连续存放
            return data.copyOfRange(colStart, colStart + colLen)
        }
        val minLocal = ((usableSize - 12) * 32 / 255) - 23
        var local = minLocal + (recordSize - minLocal) % (usableSize - 4)
        if (local > maxLocal) local = minLocal
        val localEnd = recStart + local
        if (colStart + colLen <= localEnd) {
            // 列完整落在页内 local 区域内
            return data.copyOfRange(colStart, colStart + colLen)
        }

        val out = ByteArray(colLen)
        // 页内部分
        val inPage = minOf(colLen, maxOf(0, localEnd - colStart))
        if (inPage > 0) data.copyInto(out, 0, colStart, colStart + inPage)
        // 溢出链部分: 链流从 localEnd 起, 先跳过 [localEnd, colStart) 再取列尾
        var chainOffset = maxOf(0, colStart - localEnd)
        var toRead = colLen - inPage
        var writePos = inPage
        var overflowPage = if (localEnd + 4 <= data.size) u32(data, localEnd) else 0
        val seen = HashSet<Int>()
        while (overflowPage > 0 && toRead > 0 && seen.add(overflowPage)) {
            val pageOffset = (overflowPage - 1) * pageSize
            if (pageOffset + 4 > data.size) break
            val nextPage = u32(data, pageOffset)
            val chunkStart = pageOffset + 4
            val chunkAvail = minOf(usableSize - 4, data.size - chunkStart)
            if (chunkAvail <= 0) break
            val skip = minOf(chainOffset, chunkAvail)
            chainOffset -= skip
            val copyable = chunkAvail - skip
            if (copyable > 0) {
                val n = minOf(toRead, copyable)
                data.copyInto(out, writePos, chunkStart + skip, chunkStart + skip + n)
                writePos += n
                toRead -= n
            }
            overflowPage = nextPage
        }
        return out
    }

    /** 字节转两位小写 hex (commonMain 无 String.format, 用于 dump 日志). */
    private fun hexByte(b: Byte): String = b.toUByte().toString(16).padStart(2, '0')

    /** 4 字节大端读取 (无边界检查, 调用方须保证 offset+4 <= size). */
    private fun u32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // ── 基础类型与内部结构 ───────────────────────────────────────────────

    /** 解析后的数据库句柄: 原始字节 + 页大小 + 可用页大小 (页大小 - 末尾保留区). */
    private class Db(val data: ByteArray, val pageSize: Int, val usableSize: Int) {
        /** 文件包含的最大页号. */
        val maxPage: Int get() = data.size / pageSize

        /** 页 [pageNum] (1-based) 在文件中的起始偏移. */
        fun pageOffset(pageNum: Int): Int = (pageNum - 1) * pageSize

        fun u16(offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

        fun u32(offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

        fun varint(start: Int): Varint = Varint.from(data, start)
    }

    /** SQLite varint 读取结果: 值 + 消耗的字节数. */
    private class Varint(val value: Long, val bytes: Int) {
        companion object {
            /**
             * SQLite varint: 每字节低 7 bit 为数据 (第 9 字节用全部 8 bit),
             * 最高位为续传标志.
             *
             * ★ 高位优先 (大端): 第一个字节是**最高位**的 7 bit, 逐字节向低位累加.
             *   旧实现误作低位优先 (第一个字节是最低位), 导致所有多字节 varint
             *   读反 —— 这是旧代码 "declared payload_len 不可信" 等一串 hack 的
             *   真正根源 (eg. [0x90, 0x0D] = 2061 = TEXT(1024), 旧实现读作
             *   1680 → ekey 被截断到 833 字节). 已按 SQLite 规范修正.
             */
            fun from(data: ByteArray, start: Int): Varint {
                var value = 0L
                var pos = start
                for (i in 0 until 9) {
                    if (pos >= data.size) break
                    val b = data[pos].toLong() and 0xFF
                    pos++
                    if (i == 8) {
                        // 第 9 字节: 全部 8 bit 有效
                        value = (value shl 8) or b
                        break
                    }
                    value = (value shl 7) or (b and 0x7F)
                    if ((b and 0x80L) == 0L) break
                }
                return Varint(value, pos - start)
            }
        }
    }

    /** 调试信息收集器 (单次解析的局部状态, 不再污染对象). */
    private class DebugDump {
        /** 是否转储数据 cell 的详情 (仅数据表遍历时开启, sqlite_master 不转储). */
        var dumpCells = false
        var pageCount = 0
        var cellCount = 0

        /** 前 12 个遍历过的页记录日志. */
        fun nextPage(): Boolean = if (pageCount < 12) { pageCount++; true } else false

        /** 前 3 个有效 cell 记录日志 (须在有效性校验后调用, 与旧版一致). */
        fun nextCell(): Boolean = if (dumpCells && cellCount < 3) { cellCount++; true } else false
    }
}
