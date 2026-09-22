package com.cdb96.ncmconverter4a.converter

enum class EncryptedFormat { KGM, KGG, NCM, UNSUPPORTED }

private val NCM_MAGIC = "CTENFDAM".encodeToByteArray()

// The complete 16-byte KGM/KGG signature. Checking the full signature avoids
// routing arbitrary files to the NCM parser merely because they are not KGM.
private val KGM_MAGIC = byteArrayOf(
    0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(),
    0x86.toByte(), 0x02, 0x7F, 0x4B,
    0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(),
    0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14
)

fun detectEncryptedFormat(header: ByteArray): EncryptedFormat = when {
    header.startsWith(KGM_MAGIC) -> if (header.size >= 24 &&
        header[20] == 5.toByte() && header[21] == 0.toByte() &&
        header[22] == 0.toByte() && header[23] == 0.toByte()
    ) EncryptedFormat.KGG else EncryptedFormat.KGM
    header.startsWith(NCM_MAGIC) -> EncryptedFormat.NCM
    else -> EncryptedFormat.UNSUPPORTED
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}
