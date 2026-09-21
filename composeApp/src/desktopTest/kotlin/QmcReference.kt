package com.cdb96.ncmconverter4a

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.tan

/**
 * Byte-for-byte copies of the original, unoptimised QMC and KGG database
 * routines. They are the reference the optimised implementations are pinned
 * against, so they must stay verbatim.
 */
internal class ReferenceQmcRc4(private val key: ByteArray) {
    private val box: ByteArray
    private val n: Int = key.size
    private var hash: UInt = 1u

    init {
        box = ByteArray(n)
        for (i in 0 until n) box[i] = i.toByte()
        var j = 0
        for (i in 0 until n) {
            j = (j + (box[i].toInt() and 0xFF) + (key[i % n].toInt() and 0xFF)) % n
            val temp = box[i]
            box[i] = box[j]
            box[j] = temp
        }
        hash = 1u
        for (i in 0 until n) {
            val v = (key[i].toInt() and 0xFF).toUInt()
            if (v == 0u) continue
            val nextHash = hash * v
            if (nextHash == 0u || nextHash <= hash) break
            hash = nextHash
        }
    }

    fun decrypt(data: ByteArray, offset: Long) {
        var currentOffset = offset
        var toProcess = data.size
        var processed = 0

        fun markProcess(p: Int): Boolean {
            currentOffset += p
            toProcess -= p
            processed += p
            return toProcess == 0
        }

        if (currentOffset < 128L) {
            var blockSize = toProcess
            val firstSegmentRemaining = (128L - currentOffset).toInt()
            if (blockSize > firstSegmentRemaining) blockSize = firstSegmentRemaining
            encFirstSegment(data, 0, blockSize, currentOffset)
            if (markProcess(blockSize)) return
        }
        if (currentOffset % 5120L != 0L) {
            var blockSize = toProcess
            val segmentRemaining = (5120L - currentOffset % 5120L).toInt()
            if (blockSize > segmentRemaining) blockSize = segmentRemaining
            encASegment(data, processed, blockSize, currentOffset)
            if (markProcess(blockSize)) return
        }
        while (toProcess > 5120) {
            encASegment(data, processed, 5120, currentOffset)
            markProcess(5120)
        }
        if (toProcess > 0) encASegment(data, processed, toProcess, currentOffset)
    }

    private fun encFirstSegment(buf: ByteArray, start: Int, length: Int, offset: Long) {
        for (i in 0 until length) {
            buf[start + i] = (buf[start + i].toInt() xor key[getSegmentSkip(offset + i.toLong())].toInt()).toByte()
        }
    }

    private fun encASegment(buf: ByteArray, start: Int, length: Int, offset: Long) {
        val box = this.box.copyOf()
        var j = 0
        var k = 0
        val skipLen = (offset % 5120L).toInt() + getSegmentSkip(offset / 5120L)
        for (i in -skipLen until length) {
            j = (j + 1) % n
            k = ((box[j].toInt() and 0xFF) + k) % n
            val temp = box[j]
            box[j] = box[k]
            box[k] = temp
            if (i >= 0) {
                val xorValue = box[((box[j].toInt() and 0xFF) + (box[k].toInt() and 0xFF)) % n]
                buf[start + i] = (buf[start + i].toInt() xor xorValue.toInt()).toByte()
            }
        }
    }

    private fun getSegmentSkip(id: Long): Int {
        val seed = key[(id % n.toLong()).toInt()].toInt() and 0xFF
        val idx = (hash.toDouble() / ((id + 1L) * seed).toDouble() * 100.0).toLong()
        return abs(idx % n.toLong()).toInt()
    }
}

internal class ReferenceQmcMap(private val key: ByteArray) {
    private val size = key.size

    private fun getMask(offset: Long): Byte {
        var o = offset
        if (o > 0x7FFF) o %= 0x7FFFL
        val idx = ((o * o + 71214L) % size.toLong()).toInt()
        return rotate(key[idx], (idx and 0x7).toByte())
    }

    private fun rotate(value: Byte, bits: Byte): Byte {
        val rotate = ((bits + 4) % 8)
        val left = (value.toInt() and 0xFF) shl rotate
        val right = (value.toInt() and 0xFF) ushr rotate
        return ((left or right) and 0xFF).toByte()
    }

    fun decrypt(data: ByteArray, offset: Long) {
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor getMask(offset + i).toInt()).toByte()
        }
    }
}

internal object ReferenceQmcStatic {
    private val box = byteArrayOf(
        0x77.toByte(), 0x48, 0x32, 0x73, 0xDE.toByte(), 0xF2.toByte(), 0xC0.toByte(), 0xC8.toByte(),
        0x95.toByte(), 0xEC.toByte(), 0x30, 0xB2.toByte(), 0x51, 0xC3.toByte(), 0xE1.toByte(), 0xA0.toByte(),
        0x9E.toByte(), 0xE6.toByte(), 0x9D.toByte(), 0xCF.toByte(), 0xFA.toByte(), 0x7F, 0x14, 0xD1.toByte(),
        0xCE.toByte(), 0xB8.toByte(), 0xDC.toByte(), 0xC3.toByte(), 0x4A, 0x67, 0x93.toByte(), 0xD6.toByte(),
        0x28, 0xC2.toByte(), 0x91.toByte(), 0x70, 0xCA.toByte(), 0x8D.toByte(), 0xA2.toByte(), 0xA4.toByte(),
        0xF0.toByte(), 0x08, 0x61, 0x90.toByte(), 0x7E, 0x6F, 0xA2.toByte(), 0xE0.toByte(),
        0xEB.toByte(), 0xAE.toByte(), 0x3E, 0xB6.toByte(), 0x67, 0xC7.toByte(), 0x92.toByte(), 0xF4.toByte(),
        0x91.toByte(), 0xB5.toByte(), 0xF6.toByte(), 0x6C, 0x5E, 0x84.toByte(), 0x40, 0xF7.toByte(),
        0xF3.toByte(), 0x1B, 0x02, 0x7F, 0xD5.toByte(), 0xAB.toByte(), 0x41, 0x89.toByte(),
        0x28, 0xF4.toByte(), 0x25, 0xCC.toByte(), 0x52, 0x11, 0xAD.toByte(), 0x43,
        0x68, 0xA6.toByte(), 0x41, 0x8B.toByte(), 0x84.toByte(), 0xB5.toByte(), 0xFF.toByte(), 0x2C,
        0x92.toByte(), 0x4A, 0x26, 0xD8.toByte(), 0x47, 0x6A, 0x7C, 0x95.toByte(),
        0x61, 0xCC.toByte(), 0xE6.toByte(), 0xCB.toByte(), 0xBB.toByte(), 0x3F, 0x47, 0x58,
        0x89.toByte(), 0x75, 0xC3.toByte(), 0x75, 0xA1.toByte(), 0xD9.toByte(), 0xAF.toByte(), 0xCC.toByte(),
        0x08, 0x73, 0x17, 0xDC.toByte(), 0xAA.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0x16,
        0x41, 0xD8.toByte(), 0xA2.toByte(), 0x06, 0xC6.toByte(), 0x8B.toByte(), 0xFC.toByte(), 0x66,
        0x34, 0x9F.toByte(), 0xCF.toByte(), 0x18, 0x23, 0xA0.toByte(), 0x0A, 0x74,
        0xE7.toByte(), 0x2B, 0x27, 0x70, 0x92.toByte(), 0xE9.toByte(), 0xAF.toByte(), 0x37,
        0xE6.toByte(), 0x8C.toByte(), 0xA7.toByte(), 0xBC.toByte(), 0x62, 0x65, 0x9C.toByte(), 0xC2.toByte(),
        0x08, 0xC9.toByte(), 0x88.toByte(), 0xB3.toByte(), 0xF3.toByte(), 0x43, 0xAC.toByte(), 0x74,
        0x2C, 0x0F, 0xD4.toByte(), 0xAF.toByte(), 0xA1.toByte(), 0xC3.toByte(), 0x01, 0x64,
        0x95.toByte(), 0x4E, 0x48, 0x9F.toByte(), 0xF4.toByte(), 0x35, 0x78, 0x95.toByte(),
        0x7A, 0x39, 0xD6.toByte(), 0x6A, 0xA0.toByte(), 0x6D, 0x40, 0xE8.toByte(),
        0x4F, 0xA8.toByte(), 0xEF.toByte(), 0x11, 0x1D, 0xF3.toByte(), 0x1B, 0x3F,
        0x3F, 0x07, 0xDD.toByte(), 0x6F, 0x5B, 0x19, 0x30, 0x19,
        0xFB.toByte(), 0xEF.toByte(), 0x0E, 0x37, 0xF0.toByte(), 0x0E, 0xCD.toByte(), 0x16,
        0x49, 0xFE.toByte(), 0x53, 0x47, 0x13, 0x1A, 0xBD.toByte(), 0xA4.toByte(),
        0xF1.toByte(), 0x40, 0x19, 0x60, 0x0E, 0xED.toByte(), 0x68, 0x09,
        0x06, 0x5F, 0x4D, 0xCF.toByte(), 0x3D, 0x1A, 0xFE.toByte(), 0x20,
        0x77, 0xE4.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xF9.toByte(), 0xA4.toByte(), 0x2B, 0x76,
        0x1C, 0x71, 0xDB.toByte(), 0x00, 0xBC.toByte(), 0xFD.toByte(), 0x0C, 0x6C,
        0xA5.toByte(), 0x47, 0xF7.toByte(), 0xF6.toByte(), 0x00, 0x79, 0x4A, 0x11,
    )

    private fun getMask(offset: Long): Int {
        var off = offset
        if (off > 0x7FFF) off %= 0x7FFFL
        val idx = ((off * off + 27L) and 0xFFL).toInt()
        return box[idx].toInt() and 0xFF
    }

    fun decrypt(data: ByteArray, offset: Long) {
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor getMask(offset + i)).toByte()
        }
    }
}

/** Original key/IV derivation of the KGG SQLite page cipher plus the matching encryptor. */
internal object ReferenceKggDb {
    const val PAGE_SIZE = 1024
    val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()
    private val masterKey = byteArrayOf(
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

    fun pageIv(page: Int): ByteArray {
        var p = (page + 1).toLong() and 0xFFFFFFFFL
        val iv = ByteArray(16)
        val buf = ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) {
            p = deriveIvSeed(p.toInt()).toLong() and 0xFFFFFFFFL
            buf.putInt(p.toInt())
        }
        return MessageDigest.getInstance("MD5").digest(iv)
    }

    fun pageKey(page: Int): ByteArray {
        val key = masterKey.copyOf()
        val pageBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(page).array()
        System.arraycopy(pageBytes, 0, key, 0x10, 4)
        return MessageDigest.getInstance("MD5").digest(key)
    }

    private fun encryptPage(plain: ByteArray, page: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pageKey(page), "AES"), IvParameterSpec(pageIv(page)))
        return cipher.doFinal(plain)
    }

    /** A plaintext SQLite image with a valid first-page header and pseudo-random pages. */
    fun plaintextDatabase(totalSize: Int, seed: Int): ByteArray {
        require(totalSize >= PAGE_SIZE)
        val plain = ByteArray(totalSize) { ((it * 31 + seed) * 7 + (it ushr 8)).toByte() }
        SQLITE_HEADER.copyInto(plain)
        // page size 1024, write/read version 1, reserved 0, payload fractions 64/32/32
        byteArrayOf(0x04, 0x00, 1, 1, 0, 0x40, 0x20, 0x20).copyInto(plain, 16)
        return plain
    }

    /**
     * Encrypts a plaintext image into the KGG layout: page 1 keeps its bytes
     * 0x10..0x18 in clear as the validation header and parks the first eight
     * cipher bytes at 0x08; every later page is encrypted whole, a trailing
     * partial page after zero padding.
     */
    fun encryptDatabase(plain: ByteArray): ByteArray {
        val out = plain.copyOf()
        val firstPage = encryptPage(plain.copyOfRange(0x10, PAGE_SIZE), 1)
        out.fill(0, 0, 8)
        System.arraycopy(firstPage, 0, out, 0x08, 8)
        System.arraycopy(plain, 0x10, out, 0x10, 8)
        System.arraycopy(firstPage, 8, out, 0x18, firstPage.size - 8)

        var page = 2
        var offset = PAGE_SIZE
        while (offset < plain.size) {
            val length = minOf(PAGE_SIZE, plain.size - offset)
            val encrypted = encryptPage(plain.copyOfRange(offset, offset + length).copyOf(PAGE_SIZE), page)
            System.arraycopy(encrypted, 0, out, offset, length)
            offset += PAGE_SIZE
            page++
        }
        return out
    }
}

/** Deterministic key material of the shape [deriveKey] produces (keeps the tests self-contained). */
internal fun qmcKey(size: Int, seed: Int): ByteArray =
    ByteArray(size) { ((abs(tan(seed + it * 0.37)) * 251).toInt() + 1).toByte() }
