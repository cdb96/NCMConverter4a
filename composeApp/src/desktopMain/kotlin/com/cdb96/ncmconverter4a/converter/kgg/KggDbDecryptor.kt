package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.util.LengthUtils.writeIntLE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.stream.IntStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

//ported from Unlock Music Project
//桌面端数据库解密：AES-CBC 解密 SQLite 加密页 → 提取 ekey 映射
object KggDbDecryptor {

    const val PAGE_SIZE = 1024
    val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()
    val DEFAULT_MASTER_KEY = byteArrayOf(
        0x1D.toByte(), 0x61.toByte(), 0x31.toByte(), 0x45.toByte(),
        0xB2.toByte(), 0x47.toByte(), 0xBF.toByte(), 0x7F.toByte(),
        0x3D.toByte(), 0x18.toByte(), 0x96.toByte(), 0x72.toByte(),
        0x14.toByte(), 0x4F.toByte(), 0xE4.toByte(), 0xBF.toByte(),
        0x00, 0x00, 0x00, 0x00,
        0x73, 0x41, 0x6C, 0x54
    )

    private fun deriveIvSeed(seed: Int): Int {
        val seedL = seed.toLong() and 0xFFFFFFFFL
        val left = (seedL * 0x9EF4L) and 0xFFFFFFFFL
        val right = ((seedL / 0xCE26L) * 0x7FFFFF07L) and 0xFFFFFFFFL
        val value = (left - right) and 0xFFFFFFFFL
        return if ((value and 0x80000000L) == 0L) value.toInt()
        else ((value + 0x7FFFFF07L) and 0xFFFFFFFFL).toInt()
    }

    /**
     * Per-thread cipher state. Every page derives its own key and IV from the
     * page number, so pages are independent and the scratch buffers below are
     * the only state a worker needs.
     */
    private class PageDecryptor {
        private val digest = MessageDigest.getInstance("MD5")
        private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        private val keyMaterial = DEFAULT_MASTER_KEY.copyOf()
        private val ivSeed = ByteArray(16)
        private val padded = ByteArray(PAGE_SIZE)
        private val output = ByteArray(PAGE_SIZE)

        /**
         * Decrypts `bytes[offset, offset + length)` in place as page [page].
         * A partial page (only the file's tail) is zero-padded to a full page
         * first, exactly like the streaming implementation did.
         */
        fun decrypt(bytes: ByteArray, offset: Int, length: Int, page: Int) {
            writeIntLE(keyMaterial, 0x10, page)
            val key = digest.digest(keyMaterial)

            var seed = (page + 1).toLong() and 0xFFFFFFFFL
            for (index in 0 until 4) {
                seed = deriveIvSeed(seed.toInt()).toLong() and 0xFFFFFFFFL
                writeIntLE(ivSeed, index * 4, seed.toInt())
            }
            val iv = digest.digest(ivSeed)

            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            // Decrypting into a separate array keeps the JCE from copying the
            // input first; the result is then moved back over the cipher text.
            if (length % 16 == 0) {
                cipher.doFinal(bytes, offset, length, output, 0)
            } else {
                padded.fill(0)
                System.arraycopy(bytes, offset, padded, 0, length)
                cipher.doFinal(padded, 0, PAGE_SIZE, output, 0)
            }
            System.arraycopy(output, 0, bytes, offset, length)
        }
    }

    private val pageDecryptor = ThreadLocal.withInitial { PageDecryptor() }

    private fun validateFirstPageHeader(header: ByteArray): Boolean {
        val o10 = ByteBuffer.wrap(header, 0x10, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val o14 = ByteBuffer.wrap(header, 0x14, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val v6 = ((o10 and 0xff) shl 8) or ((o10 and 0xff00) shl 16)
        return o14 == 0x20204000 && (v6 - 0x200) <= 0xFE00 && ((v6 - 1) and v6) == 0
    }

    /**
     * Page 1 keeps its bytes 0x10..0x18 in clear as a validation header, with
     * the corresponding cipher bytes parked at 0x08; the rest of the page is
     * encrypted from 0x10 on.
     */
    private fun decryptFirstPage(bytes: ByteArray): Boolean {
        if (!validateFirstPageHeader(bytes)) return false
        val expectedHeader = bytes.copyOfRange(0x10, 0x18)
        System.arraycopy(bytes, 0x08, bytes, 0x10, 8)
        pageDecryptor.get().decrypt(bytes, 0x10, PAGE_SIZE - 0x10, page = 1)
        if (!bytes.copyOfRange(0x10, 0x18).contentEquals(expectedHeader)) return false
        System.arraycopy(SQLITE_HEADER, 0, bytes, 0, SQLITE_HEADER.size)
        return true
    }

    private fun startsWithSqliteHeader(buffer: ByteArray): Boolean =
        buffer.size >= SQLITE_HEADER.size &&
            SQLITE_HEADER.indices.all { buffer[it] == SQLITE_HEADER[it] }

    /** 判断是否为 SQLite 数据库 (已解密或 KGG 加密格式), 否则视为 MMKV */
    fun isSqliteDatabase(buffer: ByteArray): Boolean {
        if (startsWithSqliteHeader(buffer)) return true
        if (buffer.size < 0x18) return false
        return validateFirstPageHeader(buffer)
    }

    /**
     * Decrypts an encrypted KGG database in place and returns [bytes]; a plain
     * SQLite file is returned untouched. The pages after the first are
     * independent, so they are decrypted in parallel.
     */
    fun decryptDatabase(bytes: ByteArray): ByteArray {
        require(bytes.size >= PAGE_SIZE) { "数据库文件太小或读取失败" }
        if (startsWithSqliteHeader(bytes)) return bytes
        require(decryptFirstPage(bytes)) { "第一页解密失败，可能是无效的数据库文件" }

        val pageCount = (bytes.size + PAGE_SIZE - 1) / PAGE_SIZE
        IntStream.rangeClosed(2, pageCount).parallel().forEach { page ->
            val offset = (page - 1) * PAGE_SIZE
            pageDecryptor.get().decrypt(bytes, offset, minOf(PAGE_SIZE, bytes.size - offset), page)
        }
        return bytes
    }

    fun extractKeyMapping(buffer: ByteArray): Map<String, String> {
        return SqliteKggReader.readKeyMapping(buffer)
    }

    /** 直接按 audioHash 在解密后的数据库字节中提取 ekey (绕过行解析). */
    fun extractEkey(buffer: ByteArray, audioHash: String): ByteArray? {
        return SqliteKggReader.extractEkey(buffer, audioHash)
    }
}
