//ported from Unlock Music Project
package com.cdb96.ncmconverter4a.converter.kgg

import com.cdb96.ncmconverter4a.converter.KGM_MAGIC
import com.cdb96.ncmconverter4a.converter.startsWith
import com.cdb96.ncmconverter4a.util.LengthUtils.readIntLEInline

data class KggHeader(
    val audioOffset: UInt,           // 4字节: 音频数据偏移
    val cryptoVersion: UInt,         // 4字节: 加密版本
    val audioHash: String = ""       // V5: 音频哈希标识
)

fun parseKgmHeader(data: ByteArray): KggHeader {
    require(data.size >= 60) { "truncated KGG header" }
    require(data.startsWith(KGM_MAGIC)) { "invalid KGG magic" }

    val audioOffset = readIntLEInline(data, 16).toUInt()
    require(audioOffset.toLong() in 1024L..(64L * 1024L * 1024L)) {
        "invalid KGG audio offset: $audioOffset"
    }
    val cryptoVersion = readIntLEInline(data, 20).toUInt()
    val audioHash = if (cryptoVersion == 5u) {
        val hashPos = 72
        require(hashPos <= data.size) { "truncated KGG V5 header" }
        val audioHashLen = readIntLEInline(data, 68).toUInt().toInt()
        require(audioHashLen in 1..(data.size - hashPos)) {
            "invalid KGG audio hash length: $audioHashLen"
        }
        require(audioHashLen.toLong() <= audioOffset.toLong() - hashPos.toLong()) {
            "KGG audio hash overlaps audio payload"
        }
        data.decodeToString(hashPos, hashPos + audioHashLen)
    } else ""

    return KggHeader(audioOffset = audioOffset, cryptoVersion = cryptoVersion, audioHash = audioHash)
}
