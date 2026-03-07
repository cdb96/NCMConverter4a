package com.cdb96.ncmconverter4a.converter.kgg

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

//ported from Unlock Music Project
//给pc用的，暂且用不上
class KggDbDecryptor {

    companion object {

        const val PAGE_SIZE = 1024
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()
        val DEFAULT_MASTER_KEY = byteArrayOf(
            0x1D.toByte(), 0x61.toByte(), 0x31.toByte(), 0x45.toByte(),
            0xB2.toByte(), 0x47.toByte(), 0xBF.toByte(), 0x7F.toByte(),
            0x3D.toByte(), 0x18.toByte(), 0x96.toByte(), 0x72.toByte(),
            0x14.toByte(), 0x4F.toByte(), 0xE4.toByte(), 0xBF.toByte(),
            0x00, 0x00, 0x00, 0x00, // page number placeholder
            0x73, 0x41, 0x6C, 0x54  // "sAlT"
        )
    }

    private fun deriveIvSeed(seed: Int): Int {
        // 使用 Long 模拟 32 位无符号整数运算
        val seedL = seed.toLong() and 0xFFFFFFFFL

        // 左边部分：seed * 0x9EF4
        val left = (seedL * 0x9EF4L) and 0xFFFFFFFFL

        // 右边部分： * 0x7FFFFF07
        val right = ((seedL / 0xCE26L) * 0x7FFFFF07L) and 0xFFFFFFFFL

        // 计算差值
        val value = (left - right) and 0xFFFFFFFFL

        // 如果最高位为 0，直接返回
        // 如果最高位为 1，加上 0x7FFFFF07
        return if ((value and 0x80000000L) == 0L) {
            value.toInt()
        } else {
            ((value + 0x7FFFFF07L) and 0xFFFFFFFFL).toInt()
        }
    }

    private fun derivePageIv(page: Int): ByteArray {
        // 页码 + 1
        var p = (page + 1).toLong() and 0xFFFFFFFFL

        // 创建 16 字节的 IV 种子缓冲区
        val iv = ByteArray(16)
        val buffer = ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN)

        // 循环 4 次，每次生成 4 字节
        for (i in 0 until 4) {
            p = deriveIvSeed(p.toInt()).toLong() and 0xFFFFFFFFL
            buffer.putInt(p.toInt())
        }

        // MD5 哈希得到最终 IV
        return md5(iv)
    }

    private fun derivePageKey(page: Int): ByteArray {
        // 复制主密钥模板
        val masterKey = DEFAULT_MASTER_KEY.copyOf()

        // 将页码写入偏移 0x10 位置（小端序）
        val pageBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(page)
            .array()
        System.arraycopy(pageBytes, 0, masterKey, 0x10, 4)

        // MD5 哈希得到最终密钥
        return md5(masterKey)
    }

    private fun md5(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(input)
    }

    private fun aes128CbcDecryptNoPadding(buffer: ByteArray, key: ByteArray, iv: ByteArray) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        // 原地解密
        val decrypted = cipher.doFinal(buffer)
        System.arraycopy(decrypted, 0, buffer, 0, buffer.size)
    }

    private fun decryptPage(buffer: ByteArray, page: Int) {
        // 派生密钥和 IV
        val key = derivePageKey(page)
        val iv = derivePageIv(page)

        // AES-128-CBC 解密
        aes128CbcDecryptNoPadding(buffer, key, iv)
    }

    private fun validateFirstPageHeader(header: ByteArray): Boolean {
        // 读取偏移 0x10 和 0x14 的 32 位值
        val o10 = ByteBuffer.wrap(header, 0x10, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int
        val o14 = ByteBuffer.wrap(header, 0x14, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int

        // 计算验证值
        val v6 = ((o10 and 0xff) shl 8) or ((o10 and 0xff00) shl 16)

        // 验证条件：
        // 1. o14 必须等于 0x20204000
        // 2. (v6 - 0x200) 必须在 0 到 0xFE00 之间
        // 3. (v6 - 1) & v6 必须为 0（检查是否为 2 的幂）
        val cond1 = o14 == 0x20204000
        val cond2 = (v6 - 0x200) <= 0xFE00
        val cond3 = ((v6 - 1) and v6) == 0

        return cond1 && cond2 && cond3
    }

    private fun decryptPage1(buffer: ByteArray): Boolean {
        // 1. 验证第一页头部格式
        if (!validateFirstPageHeader(buffer)) {
            return false
        }

        // 2. 备份期望的头部，交换密文块
        //    将 buffer[0x08:0x10] 复制到 buffer[0x10:0x18]
        val expectedHeader = buffer.copyOfRange(0x10, 0x18)
        System.arraycopy(buffer, 0x08, buffer, 0x10, 8)

        // 3. 解密 buffer[0x10:] 部分（跳过前 16 字节）
        val slice = buffer.copyOfRange(0x10, buffer.size)
        decryptPage(slice, 1)
        System.arraycopy(slice, 0, buffer, 0x10, slice.size)

        // 4. 验证解密结果
        if (!buffer.copyOfRange(0x10, 0x18).contentEquals(expectedHeader)) {
            return false
        }

        // 5. 恢复 SQLite 文件头
        System.arraycopy(SQLITE_HEADER, 0, buffer, 0, SQLITE_HEADER.size)

        return true
    }

    fun decryptDatabase(input: InputStream): ByteArray {
        // 创建输出流用于存储解密结果
        val output = ByteArrayOutputStream()

        // 页缓冲区
        val buffer = ByteArray(PAGE_SIZE)

        // ========== 1. 读取并处理第一页 ==========
        var bytesRead = input.read(buffer)
        if (bytesRead != PAGE_SIZE) {
            throw IllegalArgumentException("数据库文件太小或读取失败")
        }

        // 检查是否已解密（已有 SQLite 头）
        if (buffer.copyOfRange(0, SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)) {
            // 已解密，直接复制剩余内容
            output.write(buffer, 0, bytesRead)
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            return output.toByteArray()
        }

        // 解密第一页（特殊处理）
        if (!decryptPage1(buffer)) {
            throw IllegalArgumentException("第一页解密失败，可能是无效的数据库文件")
        }
        output.write(buffer, 0, PAGE_SIZE)

        // ========== 2. 逐页读取并解密后续页 ==========
        var pageNumber = 2
        while (true) {
            bytesRead = input.read(buffer)
            if (bytesRead == -1) break

            if (bytesRead == PAGE_SIZE) {
                // 完整页，直接解密
                decryptPage(buffer, pageNumber)
                output.write(buffer, 0, PAGE_SIZE)
            } else {
                // 不完整页（可能是文件末尾的填充）
                // 先填充到完整页大小，解密后再截取原始长度
                val padded = buffer.copyOf(PAGE_SIZE)
                decryptPage(padded, pageNumber)
                output.write(padded, 0, bytesRead)
            }
            pageNumber++
        }

        return output.toByteArray()
    }

    fun extractKeyMapping(buffer: ByteArray): Map<String, String> {
        val tempFile = File.createTempFile("sqlite_import", ".db")
        try {
            tempFile.writeBytes(buffer)

            // 打开临时数据库
            SQLiteDatabase.openDatabase(
                tempFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                val cursor = db.rawQuery(
                    """
                SELECT EncryptionKeyId, EncryptionKey 
                FROM ShareFileItems 
                WHERE EncryptionKey != '' AND EncryptionKey IS NOT NULL
                """.trimIndent(),
                    null
                )

                return cursor.use {
                    buildMap {
                        val keyIdIndex = it.getColumnIndexOrThrow("EncryptionKeyId")
                        val keyIndex = it.getColumnIndexOrThrow("EncryptionKey")

                        while (it.moveToNext()) {
                            val keyId = it.getString(keyIdIndex)
                            val key = it.getString(keyIndex)
                            if (!keyId.isNullOrEmpty() && !key.isNullOrEmpty()) {
                                put(keyId, key)
                            }
                        }
                    }
                }
            }
        } finally {
            // 确保临时文件被删除
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}