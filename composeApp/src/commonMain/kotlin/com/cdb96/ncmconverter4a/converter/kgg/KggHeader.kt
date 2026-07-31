//ported from Unlock Music Project
package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.util.LengthUtils.readIntLEInline

data class KggHeader(
    val magicHeader: ByteArray,      // 16字节: 魔数
    val audioOffset: UInt,           // 4字节: 音频数据偏移
    val cryptoVersion: UInt,         // 4字节: 加密版本
    val cryptoSlot: UInt,            // 4字节: 密钥槽位
    val cryptoTestData: ByteArray,   // 16字节: 测试数据
    val cryptoKey: ByteArray,        // 16字节: 密钥
    var audioHash: String = ""       // V5: 音频哈希标识
)

fun parseKgmHeader(data: ByteArray): KggHeader {
    var pos = 0

    val magicHeader = data.copyOfRange(pos, pos + 16); pos += 16
    val audioOffset = readIntLEInline(data, pos).toUInt(); pos += 4
    val cryptoVersion = readIntLEInline(data, pos).toUInt(); pos += 4
    val cryptoSlot = readIntLEInline(data, pos).toUInt(); pos += 4

    val cryptoTestData = data.copyOfRange(pos, pos + 16); pos += 16
    val cryptoKey = data.copyOfRange(pos, pos + 16); pos += 16

    val header = KggHeader(
        magicHeader = magicHeader,
        audioOffset = audioOffset,
        cryptoVersion = cryptoVersion,
        cryptoSlot = cryptoSlot,
        cryptoTestData = cryptoTestData,
        cryptoKey = cryptoKey
    )

    // V5 版本额外读取 AudioHash
    if (cryptoVersion == 5u) {
        pos += 8  // 跳过 8 字节
        val audioHashLen = readIntLEInline(data, pos).toUInt().toInt(); pos += 4
        val audioHashBuffer = data.copyOfRange(pos, pos + audioHashLen)
        header.audioHash = audioHashBuffer.decodeToString()
    }

    return header
}
