package com.cdb96.ncmconverter4a.converter

import com.cdb96.ncmconverter4a.jni.KGMDecrypt

object KGMConverter {
    const val HEADER_LENGTH = 1024

    /**
     * 从完整 KGM 文件头读取 17 字节密钥。
     * @param header 文件头数据（至少 1024 字节）
     */
    fun getOwnKeyBytes(header: ByteArray): ByteArray {
        require(header.size >= HEADER_LENGTH) { "KGM header is truncated" }
        val ownKeyBytes = ByteArray(17)
        // 16-byte magic + audio offset + crypto version.
        val keyOffset = 16 + 8 + 4
        header.copyInto(ownKeyBytes, 0, keyOffset, keyOffset + 17)
        ownKeyBytes[16] = 0
        return ownKeyBytes
    }

    /** 从已解密的第一字节检测音频格式 */
    fun detectFormat(firstByte: Byte, ownKeyBytes: ByteArray): String {
        var med8 = ownKeyBytes[0].toInt() xor firstByte.toInt()
        med8 = med8 xor ((med8 and 0xf) shl 4)
        var msk8 = 0 xor 0xB8.toInt()
        msk8 = msk8 xor ((msk8 and 0xf) shl 4)
        return when ((med8 xor msk8).toByte()) {
            0x66.toByte() -> "flac"
            0x49.toByte() -> "mp3"
            else -> ""
        }
    }

    /**
     * 解密 KGM 数据流（对应 v3.4 的 write 方法）。
     * 平台只需负责 I/O，解密循环逻辑集中在此。
     *
     * @param ownKeyBytes 文件密钥
     * @param firstChunk  已读取的第一个数据块（就地解密）
     * @param firstSize   第一块有效字节数
     * @param bufferSize  后续读取缓冲区大小
     * @param read  读取下一块: (buffer) -> bytesRead, 返回 -1 表示 EOF
     * @param write 写出已解密数据: (buffer, bytesToWrite) -> Unit
     */
    fun decrypt(
        ownKeyBytes: ByteArray,
        firstChunk: ByteArray,
        firstSize: Int,
        bufferSize: Int,
        read: (ByteArray) -> Int,
        write: (ByteArray, Int) -> Unit
    ) {
        require(ownKeyBytes.size >= 17) { "KGM key must contain 17 bytes" }
        require(bufferSize > 0) { "KGM buffer size must be positive" }
        require(firstSize in 1..firstChunk.size) {
            "invalid first KGM chunk size: $firstSize"
        }

        KGMDecrypt.init(ownKeyBytes)

        var fileOffset = 0
        fileOffset = KGMDecrypt.decrypt(firstChunk, fileOffset, firstSize)
        write(firstChunk, firstSize)

        val buf = ByteArray(bufferSize)
        while (true) {
            val bytesRead = read(buf)
            if (bytesRead < 0) break
            require(bytesRead <= buf.size) { "KGM reader returned too many bytes: $bytesRead" }
            if (bytesRead == 0) continue
            fileOffset = KGMDecrypt.decrypt(buf, fileOffset, bytesRead)
            write(buf, bytesRead)
        }
    }
}
