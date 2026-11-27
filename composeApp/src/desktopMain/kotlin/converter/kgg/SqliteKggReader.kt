package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.converter.startsWith
import com.cdb96.ncmconverter4a.platform.Logger
import com.cdb96.ncmconverter4a.util.LengthUtils.readIntBE

/**
 * Pure-Kotlin SQLite 数据库文件读取器 —— 无 JDBC、无原生依赖，解析 B-tree、
 * record 与溢出页链，读取 KGG 的 ShareFileItems 表并提取 EncryptionKeyId -> EncryptionKey。
 */
object SqliteKggReader {

    private val log = Logger("SqliteKggReader")
    private const val TABLE_NAME = "ShareFileItems"
    private const val PT_INTERIOR_TABLE = 0x05
    private const val PT_LEAF_TABLE = 0x0D
    private const val MAX_PAYLOAD = 1 shl 20  // 1 MiB
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray()
    private val INT_SIZES = intArrayOf(0, 1, 2, 3, 4, 6, 8)

    /** Parse [dbBytes] and return a Map of EncryptionKeyId -> EncryptionKey. */
    fun readKeyMapping(dbBytes: ByteArray): Map<String, String> {
        val db = parseDb(dbBytes)
        val schema = findTableSchema(db)
        val colId = schema.colId.let { if (it >= 0) it else 0 }
        val colKey = schema.colKey.let { if (it >= 0) it else 1 }

        val mapping = LinkedHashMap<String, String>()
        // Decode only the two required columns and consume each row immediately.
        traverseBTree(db, schema.rootPage, 0, HashSet(), setOf(colId, colKey)) row@{ row ->
            val id = row.getOrNull(colId)?.takeIf { it.isNotEmpty() } ?: return@row
            val key = row.getOrNull(colKey) ?: return@row
            mapping[id] = key
        }
        log.i("pageSize=${db.pageSize} mapping=${mapping.size} entries")
        return mapping
    }

    /**
     * 直接在解密后的数据库字节中按 audioHash 提取 ekey，绕过 B-tree 行解析。
     */
    fun extractEkey(dbBytes: ByteArray, audioHash: String): ByteArray? {
        val needle = audioHash.toByteArray(Charsets.UTF_8)
        val binNeedle = hexToBytes(audioHash)
        val pageSize = parsePageSizeLenient(dbBytes)

        val typedNeedle = ByteArray(1 + needle.size) { if (it == 0) 0x4D else needle[it - 1] }
        val passes = mutableListOf(
            MatchPattern(typedNeedle, hashOffset = 1, hashLen = needle.size),
            MatchPattern(needle, hashOffset = 0, hashLen = needle.size),
        )
        if (binNeedle != null) passes.add(MatchPattern(binNeedle, 0, binNeedle.size))

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

    private fun parsePageSizeLenient(data: ByteArray): Int {
        if (data.size < 18) return 0
        var ps = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        if (ps == 1) ps = 65536
        return if (ps in 512..65536 && (ps and (ps - 1)) == 0) ps else 0
    }

    private fun parseDb(data: ByteArray): Db {
        require(data.size >= 100) { "Not a valid SQLite file (too small)" }
        require(data.startsWith(SQLITE_MAGIC)) { "Not a valid SQLite file (bad magic)" }
        var pageSize = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        if (pageSize == 1) pageSize = 65536
        require(pageSize in 512..65536 && (pageSize and (pageSize - 1)) == 0) {
            "Invalid page size: $pageSize"
        }
        val usableSize = pageSize - (data[20].toInt() and 0xFF)
        require(usableSize >= 48) { "Invalid usable page size: $usableSize" }
        return Db(data, pageSize, usableSize)
    }

    private class TableSchema(val rootPage: Int, val colId: Int, val colKey: Int)

    private fun findTableSchema(db: Db): TableSchema {
        val masterRows = mutableListOf<List<String?>>()
        traverseBTree(db, pageNum = 1, headerOffset = 100, visited = HashSet()) { masterRows.add(it) }
        val tables = masterRows.filter { it.getOrNull(0) == "table" }.mapNotNull { it.getOrNull(1) }

        for (row in masterRows) {
            if (row.size < 5 || row[0] != "table") continue
            val name = row[1] ?: continue
            if (!name.equals(TABLE_NAME, ignoreCase = true)) continue
            val rootPage = row[3]?.toIntOrNull() ?: -1
            if (rootPage <= 0) break
            val createSql = row[4]
            val colId = createSql?.let { findColumnInSql(it, "EncryptionKeyId") } ?: -1
            val colKey = createSql?.let { findColumnInSql(it, "EncryptionKey") } ?: -1
            return TableSchema(rootPage, colId, colKey)
        }
        throw IllegalArgumentException("Table '$TABLE_NAME' not found in database. Available tables: $tables")
    }

    private fun findColumnInSql(createSql: String, colName: String): Int {
        val start = createSql.indexOf('(')
        val end = createSql.lastIndexOf(')')
        if (start < 0 || end <= start) return -1
        var recordIdx = 0
        for (col in splitColumnDefs(createSql.substring(start + 1, end))) {
            val trimmed = col.trim()
            if (trimmed.isEmpty() || isTableConstraint(trimmed)) continue
            val name = trimmed.split("\\s+".toRegex()).firstOrNull()
                ?.trim('"', '`', '[', ']') ?: continue
            if (name.equals(colName, ignoreCase = true)) return recordIdx
            recordIdx++
        }
        return -1
    }

    private fun splitColumnDefs(s: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in s) {
            when (c) {
                '(' -> { depth++; cur.append(c) }
                ')' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { result.add(cur.toString()); cur.setLength(0) } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        return result
    }

    private fun isTableConstraint(s: String): Boolean {
        val u = s.uppercase().trim()
        return u.startsWith("PRIMARY") || u.startsWith("FOREIGN") ||
            u.startsWith("UNIQUE") || u.startsWith("CHECK") || u.startsWith("CONSTRAINT")
    }

    private fun traverseBTree(
        db: Db, pageNum: Int, headerOffset: Int,
        visited: HashSet<Int>, columns: Set<Int>? = null,
        onRow: (List<String?>) -> Unit
    ) {
        if (!visited.add(pageNum)) return
        require(pageNum in 1..db.maxPage) {
            "Invalid page $pageNum (max=${db.maxPage}, fileSize=${db.data.size}, pageSize=${db.pageSize})"
        }
        val pageStart = db.pageOffset(pageNum)
        val pageEnd = minOf(pageStart + db.pageSize, db.data.size)
        val hdrBase = pageStart + headerOffset
        if (hdrBase + 8 > pageEnd) return
        val pageType = db.data[hdrBase].toInt() and 0xFF
        val numCells = db.u16(hdrBase + 3)
        var contentStart = db.u16(hdrBase + 5)
        if (contentStart == 0) contentStart = 65536

        val pageHeaderSize = when (pageType) {
            PT_LEAF_TABLE -> 8
            PT_INTERIOR_TABLE -> 12
            else -> throw IllegalArgumentException("Unknown page type 0x${pageType.toString(16)} at page $pageNum")
        }
        val cellPtrArrayStart = hdrBase + pageHeaderSize
        if (cellPtrArrayStart > pageEnd) return
        val safeCellCount = minOf(numCells, (pageEnd - cellPtrArrayStart) / 2)

        when (pageType) {
            PT_LEAF_TABLE -> {
                for (i in 0 until safeCellCount) {
                    val co = db.u16(cellPtrArrayStart + i * 2)
                    if (co < contentStart || co >= db.pageSize) continue
                    readCellRecord(db, pageStart + co, columns)?.let(onRow)
                }
            }
            PT_INTERIOR_TABLE -> {
                val children = mutableListOf<Int>()
                for (i in 0 until safeCellCount) {
                    val co = db.u16(cellPtrArrayStart + i * 2)
                    if (co < contentStart || co + 4 > db.pageSize) continue
                    val leftChild = db.u32(pageStart + co)
                    if (leftChild in 1..db.maxPage) children.add(leftChild)
                }
                val rightChild = db.u32(hdrBase + 8)
                if (rightChild in 1..db.maxPage) children.add(rightChild)
                for (child in children) {
                    traverseBTree(db, child, 0, visited, columns, onRow)
                }
            }
        }
    }

    private fun readCellRecord(db: Db, cellStart: Int, columns: Set<Int>?): List<String?>? {
        val data = db.data
        val usableSize = db.usableSize
        val maxLocal = usableSize - 35
        var pos = cellStart

        // KGG 表中声明的 payload_len 可能不可信，仅用其 varint 长度跳过并按 record header 反推真实大小。
        pos += db.varint(pos).bytes
        pos += db.varint(pos).bytes
        val recStart = pos
        if (recStart >= data.size) return null

        val (hdrLen, serialTypes) = parseRecordHeader(data, recStart, minOf(2000, data.size - recStart))
        val maxRecordSize = minOf(data.size, MAX_PAYLOAD).toLong()
        var totalSize = hdrLen.toLong()
        for (st in serialTypes) {
            val fieldSize = serialTypeSize(st)
            if (fieldSize < 0L || fieldSize > maxRecordSize - totalSize) return null
            totalSize += fieldSize
        }
        if (totalSize <= 0L) return null
        val recordSize = totalSize.toInt()

        val payload: ByteArray
        val payloadOffset: Int
        if (recordSize > maxLocal) {
            payload = readOverflowRange(data, db.pageSize, usableSize, recStart, localPayloadSize(usableSize, recordSize), 0, recordSize)
            payloadOffset = 0
        } else if (recStart + recordSize <= data.size) {
            payload = data
            payloadOffset = recStart
        } else {
            payload = ByteArray(recordSize).also { out ->
                val copyLen = maxOf(0, data.size - recStart)
                data.copyInto(out, 0, recStart, recStart + copyLen)
            }
            payloadOffset = 0
        }
        return decodeRecord(payload, payloadOffset, recordSize, hdrLen, serialTypes, columns)
    }

    private fun localPayloadSize(usableSize: Int, recordSize: Int): Int {
        val maxLocal = usableSize - 35
        if (recordSize <= maxLocal) return recordSize
        val minLocal = ((usableSize - 12) * 32 / 255) - 23
        val local = minLocal + (recordSize - minLocal) % (usableSize - 4)
        return if (local > maxLocal) minLocal else local
    }

    private fun readOverflowRange(
        data: ByteArray,
        pageSize: Int,
        usableSize: Int,
        recStart: Int,
        local: Int,
        rangeOffset: Int,
        rangeLen: Int,
    ): ByteArray {
        val out = ByteArray(rangeLen)
        var writePos = 0
        var remaining = rangeLen
        if (rangeOffset < local) {
            val localAvail = minOf(local - rangeOffset, maxOf(0, data.size - (recStart + rangeOffset)))
            val inPage = minOf(remaining, localAvail)
            if (inPage > 0) {
                data.copyInto(out, 0, recStart + rangeOffset, recStart + rangeOffset + inPage)
                writePos += inPage
                remaining -= inPage
            }
        }
        if (remaining <= 0) return out

        val localEnd = recStart + local
        var chainOffset = maxOf(0, rangeOffset - local)
        var overflowPage = if (localEnd + 4 <= data.size) readIntBE(data, localEnd) else 0
        val seen = HashSet<Int>()
        while (overflowPage > 0 && remaining > 0 && seen.add(overflowPage)) {
            val pageOffsetLong = (overflowPage.toLong() - 1L) * pageSize.toLong()
            if (pageOffsetLong < 0L || pageOffsetLong + 4L > data.size.toLong()) break
            val pageOffset = pageOffsetLong.toInt()
            val nextPage = readIntBE(data, pageOffset)
            val chunkStart = pageOffset + 4
            val chunkAvail = minOf(usableSize - 4, data.size - chunkStart)
            if (chunkAvail <= 0) break
            val skip = minOf(chainOffset, chunkAvail)
            chainOffset -= skip
            val copyable = chunkAvail - skip
            if (copyable > 0) {
                val copy = minOf(remaining, copyable)
                data.copyInto(out, writePos, chunkStart + skip, chunkStart + skip + copy)
                writePos += copy
                remaining -= copy
            }
            overflowPage = nextPage
        }
        return out
    }

    private fun readSerialTypes(data: ByteArray, start: Int, hdrEnd: Int): Pair<List<Long>, Boolean> {
        val serialTypes = mutableListOf<Long>()
        var pos = start
        while (pos < hdrEnd) {
            val st = Varint.from(data, pos)
            if (st.bytes == 0 || pos + st.bytes > hdrEnd) return serialTypes to false
            serialTypes.add(st.value)
            pos += st.bytes
        }
        return serialTypes to (pos == hdrEnd)
    }

    private fun parseRecordHeader(data: ByteArray, start: Int, maxLen: Int): Pair<Int, List<Long>> {
        val header = Varint.from(data, start)
        val hdrLen = header.value.toInt().coerceIn(0, maxLen)
        val (serialTypes, _) = readSerialTypes(data, start + header.bytes, start + hdrLen)
        return hdrLen to serialTypes
    }

    private fun decodeRecord(
        data: ByteArray,
        recordStart: Int,
        recordLen: Int,
        hdrLen: Int,
        serialTypes: List<Long>,
        columns: Set<Int>?,
    ): List<String?> {
        val bodyEnd = minOf(recordStart + recordLen, data.size)
        var bodyPos = recordStart + hdrLen
        val row = mutableListOf<String?>()

        for ((column, st) in serialTypes.withIndex()) {
            if (bodyPos > bodyEnd) break
            if (columns != null && column !in columns) {
                if (st == 10L || st == 11L) break
                val size = serialTypeSize(st)
                if (size < 0L || size > (bodyEnd - bodyPos).toLong()) break
                row.add(null)
                bodyPos += size.toInt()
                continue
            }
            when {
                st == 0L -> row.add(null)
                st == 8L -> row.add("0")
                st == 9L -> row.add("1")
                st in 1L..6L -> {
                    val size = INT_SIZES[st.toInt()]
                    if (bodyPos + size > bodyEnd) break
                    var value = 0L
                    for (i in 0 until size) {
                        value = (value shl 8) or (data[bodyPos + i].toLong() and 0xFF)
                    }
                    if (size > 0 && (data[bodyPos].toInt() and 0x80) != 0) {
                        value -= (1L shl (size * 8))
                    }
                    row.add(value.toString())
                    bodyPos += size
                }
                st == 7L -> {
                    if (bodyPos + 8 > bodyEnd) break
                    var bits = 0L
                    for (i in 0 until 8) {
                        bits = (bits shl 8) or (data[bodyPos + i].toLong() and 0xFF)
                    }
                    row.add(Double.fromBits(bits).toString())
                    bodyPos += 8
                }
                st >= 12L -> {
                    val lenLong = (st - 12L) / 2L
                    if (lenLong > (bodyEnd - bodyPos).toLong()) break
                    val len = lenLong.toInt()
                    row.add(String(data, bodyPos, len, Charsets.UTF_8))
                    bodyPos += len
                }
                else -> break
            }
        }
        return row
    }

    private fun serialTypeSize(st: Long): Long = when {
        st == 0L || st == 8L || st == 9L -> 0L
        st in 1L..6L -> INT_SIZES[st.toInt()].toLong()
        st == 7L -> 8L
        st >= 12L -> (st - 12L) / 2L
        else -> -1L
    }

    private class MatchPattern(
        val needle: ByteArray,
        val hashOffset: Int,
        val hashLen: Int,
    )

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[2 * i].digitToIntOrNull(16) ?: return null
            val lo = hex[2 * i + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun findEkeyInRecord(data: ByteArray, hashIdx: Int, hashLen: Int, pageSize: Int): ByteArray? {
        val minHdr = maxOf(0, hashIdx - 1024)
        for (hdrStart in (hashIdx - 1) downTo minHdr) {
            val hdrLen = data[hdrStart].toInt() and 0xFF
            if (hdrLen !in 2..126) continue
            val bodyStart = hdrStart + hdrLen
            if (bodyStart > hashIdx) continue
            val (serialTypes, exact) = readSerialTypes(data, hdrStart + 1, bodyStart)
            if (!exact) continue

            val hashSt = 13L + 2L * hashLen
            var bp = bodyStart.toLong()
            var totalSize = hdrLen.toLong()
            var foundHash = false
            var validSizes = true
            for (st in serialTypes) {
                val sz = serialTypeSize(st)
                if (sz < 0L || sz > MAX_PAYLOAD.toLong() - totalSize) {
                    validSizes = false
                    break
                }
                if (bp == hashIdx.toLong() && st == hashSt) foundHash = true
                bp += sz
                totalSize += sz
            }
            if (!validSizes || !foundHash) continue

            val recordSize = totalSize.toInt()
            bp = bodyStart.toLong()
            for (st in serialTypes) {
                val sz = serialTypeSize(st)
                if (sz == 1024L && bp + 1024L <= data.size.toLong()) {
                    return readColumn(data, bp.toInt(), 1024, hdrStart, recordSize, pageSize)
                }
                bp += sz
            }
            return null
        }
        return null
    }

    private fun readColumn(
        data: ByteArray, colStart: Int, colLen: Int,
        recStart: Int, recordSize: Int, pageSize: Int
    ): ByteArray {
        if (pageSize < 512) return data.copyOfRange(colStart, colStart + colLen)
        val usableSize = pageSize - (data[20].toInt() and 0xFF)
        val local = localPayloadSize(usableSize, recordSize)
        if (colStart + colLen <= recStart + local) {
            return data.copyOfRange(colStart, colStart + colLen)
        }
        return readOverflowRange(data, pageSize, usableSize, recStart, local, colStart - recStart, colLen)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private class Db(val data: ByteArray, val pageSize: Int, val usableSize: Int) {
        val maxPage: Int get() = data.size / pageSize
        fun pageOffset(pageNum: Int): Int = (pageNum - 1) * pageSize
        fun u16(offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        fun u32(offset: Int): Int = readIntBE(data, offset)
        fun varint(start: Int): Varint = Varint.from(data, start)
    }

    private class Varint(val value: Long, val bytes: Int) {
        companion object {
            fun from(data: ByteArray, start: Int): Varint {
                var value = 0L
                var pos = start
                for (i in 0 until 9) {
                    if (pos >= data.size) break
                    val b = data[pos].toLong() and 0xFF
                    pos++
                    if (i == 8) {
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
}
