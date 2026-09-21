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
    require(data.size >= 60) { "truncated KGG header" }
    var pos = 0

    val magicHeader = data.copyOfRange(pos, pos + 16); pos += 16
    val expectedMagic = byteArrayOf(
        0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(),
        0x86.toByte(), 0x02, 0x7F, 0x4B,
        0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(),
        0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14
    )
    require(magicHeader.contentEquals(expectedMagic)) {
        "invalid KGG magic"
    }
    val audioOffset = readIntLEInline(data, pos).toUInt(); pos += 4
    require(audioOffset.toLong() in 1024L..(64L * 1024L * 1024L)) {
        "invalid KGG audio offset: $audioOffset"
    }
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
        require(pos + 12 <= data.size) { "truncated KGG V5 header" }
        pos += 8  // 跳过 8 字节
        val audioHashLen = readIntLEInline(data, pos).toUInt().toInt(); pos += 4
        require(audioHashLen in 1..(data.size - pos)) {
            "invalid KGG audio hash length: $audioHashLen"
        }
        require(audioHashLen.toLong() <= audioOffset.toLong() - pos.toLong()) {
            "KGG audio hash overlaps audio payload"
        }
        val audioHashBuffer = data.copyOfRange(pos, pos + audioHashLen)
        header.audioHash = audioHashBuffer.decodeToString()
    }

    return header
}
