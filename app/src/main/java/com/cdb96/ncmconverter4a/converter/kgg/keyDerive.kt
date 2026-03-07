//ported from Unlock Music Project
package com.cdb96.ncmconverter4a.converter.kgg

import java.util.Base64
import kotlin.math.abs
import kotlin.math.tan

/**
 * 生成简单密钥
 */
fun simpleMakeKey(salt: Byte, length: Int): ByteArray {
    val keyBuf = ByteArray(length)
    for (i in 0 until length) {
        val tmp = tan(salt.toDouble() + i * 0.1)
        keyBuf[i] = (abs(tmp) * 100.0).toInt().toByte()
    }
    return keyBuf
}

private const val RAW_KEY_PREFIX_V2 = "QQMusic EncV2,Key:"

/**
 * 派生密钥
 */
fun deriveKey(rawKey: ByteArray?): ByteArray {
    val rawKeyDec = Base64.getDecoder().decode(rawKey)

    val processed = if (rawKeyDec.startsWith(RAW_KEY_PREFIX_V2.toByteArray())) {
        val trimmed = rawKeyDec.copyOfRange(RAW_KEY_PREFIX_V2.length, rawKeyDec.size)
        deriveKeyV2(trimmed)
    } else {
        rawKeyDec
    }

    return deriveKeyV1(processed)
}

/**
 * V1版本密钥派生
 */
fun deriveKeyV1(rawKeyDec: ByteArray): ByteArray {
    require(rawKeyDec.size >= 16) { "key length is too short" }

    val simpleKey = simpleMakeKey(106, 8)
    val teaKey = ByteArray(16)
    for (i in 0 until 8) {
        teaKey[i shl 1] = simpleKey[i]
        teaKey[(i shl 1) + 1] = rawKeyDec[i]
    }

    val rs = decryptTencentTea(rawKeyDec.copyOfRange(8, rawKeyDec.size), teaKey)
    return rawKeyDec.copyOfRange(0, 8) + rs
}

private val DERIVE_V2_KEY1 = byteArrayOf(
    0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
    0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28
)

private val DERIVE_V2_KEY2 = byteArrayOf(
    0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
    0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54
)

/**
 * V2版本密钥派生
 */
fun deriveKeyV2(raw: ByteArray): ByteArray {
    var buf = decryptTencentTea(raw, DERIVE_V2_KEY1)
    buf = decryptTencentTea(buf, DERIVE_V2_KEY2)
    return Base64.getDecoder().decode(buf)
}

/**
 * 腾讯TEA解密
 */
fun decryptTencentTea(inBuf: ByteArray, key: ByteArray): ByteArray {
    val saltLen = 2
    val zeroLen = 7

    require(inBuf.size % 8 == 0) { "inBuf size not a multiple of the block size" }
    require(inBuf.size >= 16) { "inBuf size too small" }

    val blk = TeaCipher(key, 32)

    val destBuf = ByteArray(8)
    blk.decrypt(destBuf, inBuf.copyOfRange(0, 8))

    val padLen = (destBuf[0].toInt() and 0x7)
    val outLen = inBuf.size - 1 - padLen - saltLen - zeroLen

    val out = ByteArray(outLen)

    var ivPrev = ByteArray(8)
    var ivCur = inBuf.copyOfRange(0, 8)

    var inBufPos = 8
    var destIdx = 1 + padLen

    fun cryptBlock() {
        ivPrev = ivCur
        ivCur = inBuf.copyOfRange(inBufPos, inBufPos + 8)

        xor8Bytes(destBuf, destBuf, inBuf.copyOfRange(inBufPos, inBufPos + 8))
        blk.decrypt(destBuf, destBuf)

        inBufPos += 8
        destIdx = 0
    }

    // Skip salt
    var i = 1
    while (i <= saltLen) {
        if (destIdx < 8) {
            destIdx++
            i++
        } else if (destIdx == 8) {
            cryptBlock()
        }
    }

    // Extract data
    var outPos = 0
    while (outPos < outLen) {
        if (destIdx < 8) {
            out[outPos] = (destBuf[destIdx].toInt() xor ivPrev[destIdx].toInt()).toByte()
            destIdx++
            outPos++
        } else if (destIdx == 8) {
            cryptBlock()
        }
    }

    // Verify zero padding
    for (j in 1..zeroLen) {
        if (destBuf[destIdx] != ivPrev[destIdx]) {
            throw IllegalStateException("zero check failed")
        }
        destIdx++
    }

    return out
}

fun xor8Bytes(dst: ByteArray, a: ByteArray, b: ByteArray) {
    for (i in 0 until 8) {
        dst[i] = (a[i].toInt() xor b[i].toInt()).toByte()
    }
}

fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (this.size < prefix.size) return false
    for (i in prefix.indices) {
        if (this[i] != prefix[i]) return false
    }
    return true
}