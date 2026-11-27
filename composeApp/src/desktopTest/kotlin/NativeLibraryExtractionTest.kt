package com.cdb96.ncmconverter4a

import com.cdb96.ncmconverter4a.converter.kgg.SqliteKggReader
import com.cdb96.ncmconverter4a.jni.NativeLibrary
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeLibraryExtractionTest {
    @Test
    fun extractUsesPrivateDirectoryOnPosixAndRejectsUnsafeTargetOnWindows() {
        val root = Files.createTempDirectory("ncm-native-extract-test")
        try {
            val payload = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 1, 2, 3, 4)
            val supportsPosix = root.fileSystem.supportedFileAttributeViews().contains("posix")

            if (supportsPosix) {
                val first = NativeLibrary.extract(ByteArrayInputStream(payload), "ncmc4a/linux-x86_64/ncmc4a.so", root)
                val second = NativeLibrary.extract(ByteArrayInputStream(payload), "ncmc4a/linux-x86_64/ncmc4a.so", root)
                assertContentEquals(payload, first.readBytes())
                assertContentEquals(payload, second.readBytes())
                assertNotEquals(first.parentFile.toPath(), second.parentFile.toPath())
                assertNotEquals(root.resolve("ncmc4a-native"), first.parentFile.toPath())
                val expectedPerms = PosixFilePermissions.fromString("rwx------")
                assertEquals(expectedPerms, Files.getPosixFilePermissions(first.parentFile.toPath()))
                assertEquals(expectedPerms, Files.getPosixFilePermissions(first.toPath()))
            } else {
                val extracted = NativeLibrary.extract(ByteArrayInputStream(payload), "ncmc4a/windows-x86_64/ncmc4a.dll", root)
                assertTrue(extracted.isFile)
                assertContentEquals(payload, extracted.readBytes())

                val poisonedRoot = Files.createTempDirectory(root, "poisoned")
                Files.writeString(poisonedRoot.resolve("ncmc4a-native"), "not-a-directory")
                assertFailsWith<IOException> {
                    NativeLibrary.extract(ByteArrayInputStream(payload), "ncmc4a/windows-x86_64/ncmc4a.dll", poisonedRoot)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sqliteReaderRejectsOverflowingSerialTypesAndCorruptCellCounts() {
        val db = ByteArray(1024)
        "SQLite format 3\u0000".encodeToByteArray().copyInto(db, 0)
        // Page size = 1024 (0x0400)
        db[16] = 0x04
        db[17] = 0x00
        // Page 1 leaf table header at offset 100
        db[100] = 0x0D
        // Claim 0xFFFF cells (exceeds page size)
        db[103] = 0xFF.toByte()
        db[104] = 0xFF.toByte()
        // Content start = 500
        db[105] = 0x01
        db[106] = 0xF4.toByte()
        // Cell 0 points to offset 500
        db[108] = 0x01
        db[109] = 0xF4.toByte()
        // Cell at 500: payload_len=10, rowid=1, hdrLen=10, 9-byte huge serialType varint (overflows Int)
        var p = 500
        db[p++] = 10
        db[p++] = 1
        db[p++] = 10
        repeat(8) { db[p++] = 0xFF.toByte() }
        db[p] = 0x7F

        // Must fail cleanly with IllegalArgumentException (table not found), never AIOOBE/OOM.
        assertFailsWith<IllegalArgumentException> {
            SqliteKggReader.readKeyMapping(db)
        }
    }
}
